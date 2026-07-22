package com.hnp.backendofflinefirst.service;

import com.hnp.backendofflinefirst.domain.ActionSource;
import com.hnp.backendofflinefirst.domain.LogSheetActionType;
import com.hnp.backendofflinefirst.domain.LogSheetStatus;
import com.hnp.backendofflinefirst.dto.LogSheetDto;
import com.hnp.backendofflinefirst.dto.LogSheetEntryDto;
import com.hnp.backendofflinefirst.dto.LogSheetSubmitResult;
import com.hnp.backendofflinefirst.domain.FormDataValidationSupport;
import com.hnp.backendofflinefirst.domain.FormDataValidationSupport.ValidationIssue;
import com.hnp.backendofflinefirst.entity.FieldDefinition;
import com.hnp.backendofflinefirst.entity.LogSheet;
import com.hnp.backendofflinefirst.entity.LogSheetEntry;
import com.hnp.backendofflinefirst.entity.LogSheetVoidSubmission;
import com.hnp.backendofflinefirst.entity.AssetEntry;
import com.hnp.backendofflinefirst.repository.AssetEntryRepository;
import com.hnp.backendofflinefirst.repository.LogSheetEntryRepository;
import com.hnp.backendofflinefirst.repository.LogSheetRepository;
import com.hnp.backendofflinefirst.logging.BusinessEventLogger;
import com.hnp.backendofflinefirst.repository.LogSheetVoidSubmissionRepository;
import com.hnp.backendofflinefirst.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Completion of server-generated log sheets, from either the offline mobile app
 * (batch sync) or the server web UI. Core rules:
 * <ul>
 *   <li>only the current assignee may complete a sheet;</li>
 *   <li>the deadline is judged against the device completion time ({@code completedAt}),
 *       never the sync time — so work finished within the window offline is always
 *       accepted even if synced much later;</li>
 *   <li>a submission from anyone who is no longer the assignee (e.g. after a
 *       supervisor takeover) is stored but flagged {@code SUPERSEDED} and does not
 *       overwrite the authoritative sheet;</li>
 *   <li>replayed offline submits are idempotent via {@code clientActionId};</li>
 *   <li>completion and expiry race via atomic conditional updates so SUBMITTED cannot be
 *       overwritten by EXPIRED, and on-time offline completion can still win after EXPIRED;</li>
 *   <li>mobile submits may only update entries for assets already on the sheet;
 *       foreign asset ids are rejected and omitted assets are never deleted.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class LogSheetService {

    /** Statuses from which a sheet may still become SUBMITTED (including scheduler EXPIRED). */
    static final List<LogSheetStatus> COMPLETABLE_STATUSES = List.of(
            LogSheetStatus.PENDING,
            LogSheetStatus.ASSIGNED,
            LogSheetStatus.IN_PROGRESS,
            LogSheetStatus.EXPIRED);

    /** Statuses the expiry scheduler may still mark EXPIRED. */
    static final List<LogSheetStatus> OPEN_FOR_EXPIRY_STATUSES = List.of(
            LogSheetStatus.PENDING,
            LogSheetStatus.ASSIGNED,
            LogSheetStatus.IN_PROGRESS);

    private final LogSheetRepository logSheetRepository;
    private final LogSheetEntryRepository logSheetEntryRepository;
    private final AssetEntryRepository assetEntryRepository;
    private final LogSheetVoidSubmissionRepository voidSubmissionRepository;
    private final LogSheetActionLogger actionLogger;
    private final OperationalUnitScopeService scopeService;
    private final BusinessEventLogger businessEventLogger;
    private final LogSheetFieldDefinitionsService fieldDefinitionsService;

    // ---------------------------------------------------------------- mobile sync

    @Transactional
    public List<LogSheetSubmitResult> submitBatch(List<LogSheetDto> dtos) {
        List<LogSheetSubmitResult> results = new ArrayList<>();
        if (dtos == null) return results;
        for (LogSheetDto dto : dtos) {
            results.add(submitOne(dto));
        }
        return results;
    }

    private LogSheetSubmitResult submitOne(LogSheetDto dto) {
        if (actionLogger.isReplay(dto.getClientActionId())) {
            return new LogSheetSubmitResult(dto.getLocalId(), dto.getServerId(), null, "DUPLICATE");
        }

        Long serverId = dto.getServerId() != null ? dto.getServerId() : dto.getId();
        if (serverId == null) {
            return new LogSheetSubmitResult(dto.getLocalId(), null,
                    "Log sheet server id was not provided.", "ERROR");
        }
        LogSheet sheet = logSheetRepository.findById(serverId).orElse(null);
        if (sheet == null) {
            return new LogSheetSubmitResult(dto.getLocalId(), serverId,
                    "Log sheet not found on server.", "ERROR");
        }

        Long currentUserId = SecurityUtils.currentUserId();
        long now = System.currentTimeMillis();
        long completedAt = firstNonNull(dto.getCompletedAt(), dto.getSubmittedAt(), now);

        // Already completed: idempotent for the completer, otherwise a superseded late sync.
        if (sheet.getStatus() == LogSheetStatus.SUBMITTED) {
            if (currentUserId != null && currentUserId.equals(sheet.getCompletedByUserId())) {
                return new LogSheetSubmitResult(dto.getLocalId(), serverId, null, "DUPLICATE");
            }
            return voidSubmission(sheet, dto, currentUserId, completedAt, now,
                    "This log sheet was already completed by someone else.");
        }

        // A submission from someone who is not the current assignee is voided
        // (covers supervisor takeover while the operator was offline).
        if (SecurityUtils.isUnitScopedOnly() && !currentUserId.equals(sheet.getAssigneeUserId())) {
            return voidSubmission(sheet, dto, currentUserId, completedAt, now,
                    "This log sheet is no longer assigned to you.");
        }

        if (sheet.getStatus() == LogSheetStatus.EXPIRED) {
            if (sheet.getDueAt() == null || completedAt > sheet.getDueAt()) {
                return new LogSheetSubmitResult(dto.getLocalId(), serverId,
                        "This log sheet completion deadline has passed.", "EXPIRED");
            }
            // Scheduler marked EXPIRED while device was offline; accept if completed before dueAt.
        }
        // Deadline judged on device completion time, not the (possibly late) sync time.
        if (sheet.getDueAt() != null && completedAt > sheet.getDueAt()) {
            int expired = logSheetRepository.expireIfStillOpenAndOverdue(
                    serverId, now, LogSheetStatus.EXPIRED, OPEN_FOR_EXPIRY_STATUSES);
            if (expired == 1) {
                actionLogger.record(serverId, LogSheetActionType.EXPIRE, ActionSource.MOBILE,
                        currentUserId, sheet.getAssigneeUserId(), null, completedAt, null);
            }
            return new LogSheetSubmitResult(dto.getLocalId(), serverId,
                    "This log sheet completion deadline has passed.", "EXPIRED");
        }

        LogSheetSubmitResult entryValidation = validateSubmittedEntries(dto, serverId);
        if (entryValidation != null) {
            return entryValidation;
        }

        LogSheetSubmitResult formValidation = validateSubmittedFormData(sheet, dto);
        if (formValidation != null) {
            return formValidation;
        }

        // Claim SUBMITTED first so a losing concurrent submit cannot flush entry formData.
        // Assignee is re-checked atomically so takeover/reassign/release cannot race past the
        // earlier ownership guard above.
        if (!tryApplyCompletion(sheet, currentUserId, completedAt,
                firstNonNull(dto.getSubmittedAt(), completedAt),
                now, dto.getOperatorName(), dto.getSyncStatus(), ActionSource.MOBILE, dto.getClientActionId(),
                SecurityUtils.isUnitScopedOnly())) {
            return resolveFailedCompletion(sheet, dto, currentUserId, completedAt, now);
        }
        mergeMobileEntryUpdates(sheet, dto.getEntries());
        return new LogSheetSubmitResult(dto.getLocalId(), serverId, null, "SUBMITTED");
    }

    /**
     * Rejects assets that are not already on the server-generated log sheet.
     * The mobile client may only update entries for assets assigned at sheet creation.
     */
    private LogSheetSubmitResult validateSubmittedEntries(LogSheetDto dto, Long serverId) {
        List<LogSheetEntryDto> submitted = dto.getEntries();
        if (submitted == null || submitted.isEmpty()) {
            return null;
        }

        Set<Long> allowedAssetIds = logSheetEntryRepository.findByLogSheetId(serverId).stream()
                .map(LogSheetEntry::getAssetId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        List<Long> foreign = new ArrayList<>();
        for (LogSheetEntryDto entry : submitted) {
            Long assetId = entry.getAssetId();
            if (assetId == null) {
                continue;
            }
            if (!allowedAssetIds.contains(assetId)) {
                foreign.add(assetId);
            }
        }
        if (foreign.isEmpty()) {
            return null;
        }

        String ids = foreign.stream().distinct().map(String::valueOf).collect(Collectors.joining(", "));
        return new LogSheetSubmitResult(
                dto.getLocalId(),
                serverId,
                "Asset(s) not part of this log sheet (ids: " + ids + ").",
                "ERROR");
    }

    /**
     * Validates all sheet entries against the frozen field-definition snapshot.
     * Submitted mobile values are merged with existing server state before validation.
     */
    private LogSheetSubmitResult validateSubmittedFormData(LogSheet sheet, LogSheetDto dto) {
        List<FieldDefinition> fieldDefs;
        List<LogSheetEntry> serverEntries;
        if (sheet.getFieldDefinitionsSnapshot() != null) {
            fieldDefs = fieldDefinitionsService.resolveForEntries(sheet, List.of());
            if (fieldDefs.isEmpty()) {
                return null;
            }
            serverEntries = logSheetEntryRepository.findByLogSheetId(sheet.getId());
        } else {
            serverEntries = logSheetEntryRepository.findByLogSheetId(sheet.getId());
            fieldDefs = fieldDefinitionsService.resolveForEntries(sheet, serverEntries);
            if (fieldDefs.isEmpty()) {
                return null;
            }
        }

        Map<Long, Map<String, Object>> submittedByAsset = new HashMap<>();
        if (dto.getEntries() != null) {
            for (LogSheetEntryDto entryDto : dto.getEntries()) {
                if (entryDto.getAssetId() != null && entryDto.getFormData() != null) {
                    submittedByAsset.put(entryDto.getAssetId(), entryDto.getFormData());
                }
            }
        }

        List<String> errors = new ArrayList<>();
        Map<Long, String> assetCodes = assetCodesById(serverEntries);
        for (LogSheetEntry entry : serverEntries) {
            Map<String, Object> formData = entry.getFormData();
            Map<String, Object> submitted = submittedByAsset.get(entry.getAssetId());
            if (submitted != null) {
                formData = submitted;
            }
            List<FieldDefinition> entryDefs = defsForClass(fieldDefs, entry.getClassId());
            List<ValidationIssue> issues = FormDataValidationSupport.validateFilledEntry(formData, entryDefs);
            String message = FormDataValidationSupport.formatIssues(
                    entry.getAssetId(),
                    entry.getAssetName(),
                    assetCodes.get(entry.getAssetId()),
                    issues);
            if (message != null) {
                errors.add(message);
            }
        }
        if (errors.isEmpty()) {
            return null;
        }
        return new LogSheetSubmitResult(
                dto.getLocalId(),
                sheet.getId(),
                String.join(" | ", errors),
                "ERROR");
    }

    private static List<FieldDefinition> defsForClass(List<FieldDefinition> fieldDefs, Long classId) {
        if (classId == null) {
            return List.of();
        }
        return fieldDefs.stream()
                .filter(def -> classId.equals(def.getClassId()))
                .toList();
    }

    /** Updates form data for matching assets only; never adds or removes log-sheet rows.
     *  Asset metadata (name, class, NFC, sub-function) is server-authoritative and ignored from the client.
     *  Unknown formData keys (not in the sheet field-definition schema) are stripped before save. */
    private void mergeMobileEntryUpdates(LogSheet sheet, List<LogSheetEntryDto> entryDtos) {
        if (entryDtos == null || entryDtos.isEmpty()) {
            return;
        }

        List<LogSheetEntry> serverEntries = logSheetEntryRepository.findByLogSheetId(sheet.getId());
        Map<Long, LogSheetEntry> byAssetId = serverEntries.stream()
                .filter(entry -> entry.getAssetId() != null)
                .collect(Collectors.toMap(LogSheetEntry::getAssetId, entry -> entry, (left, right) -> left));
        List<FieldDefinition> fieldDefs = resolveFieldDefinitions(sheet, serverEntries);

        long now = System.currentTimeMillis();
        for (LogSheetEntryDto dto : entryDtos) {
            Long assetId = dto.getAssetId();
            if (assetId == null) {
                continue;
            }
            LogSheetEntry entry = byAssetId.get(assetId);
            if (entry == null) {
                continue;
            }

            if (dto.getFormData() != null) {
                Map<String, Object> formData = retainKnownFormData(dto.getFormData(), fieldDefs, entry.getClassId());
                boolean hadData = hasEntryFormData(entry.getFormData());
                entry.setFormData(formData);
                if (hasEntryFormData(formData)) {
                    if (dto.getCreatedAt() != null) {
                        if (entry.getCreatedAt() == null) {
                            entry.setCreatedAt(dto.getCreatedAt());
                        }
                    } else if (!hadData && entry.getCreatedAt() == null) {
                        entry.setCreatedAt(now);
                    }
                    if (dto.getUpdatedAt() != null) {
                        entry.setUpdatedAt(dto.getUpdatedAt());
                    } else if (hadData || entry.getCreatedAt() != null) {
                        entry.setUpdatedAt(now);
                    }
                }
            } else {
                if (dto.getCreatedAt() != null && entry.getCreatedAt() == null) {
                    entry.setCreatedAt(dto.getCreatedAt());
                }
                if (dto.getUpdatedAt() != null) {
                    entry.setUpdatedAt(dto.getUpdatedAt());
                }
            }
            logSheetEntryRepository.save(entry);
        }
    }

    // ---------------------------------------------------------------- web completion

    /** Saves entry values as draft without final submission. */
    @Transactional
    public LogSheet saveDraftFromWeb(Long sheetId, Map<String, Map<String, Object>> entryValues) {
        LogSheet sheet = requireOpenSheetForWeb(sheetId);
        assertWebCompletionAccess(sheet);
        applyWebEntryValues(sheet, entryValues);
        long now = System.currentTimeMillis();
        sheet.setDraftSavedAt(now);
        sheet.setUpdatedAt(now);
        return logSheetRepository.save(sheet);
    }

    /**
     * Final submission from the server web UI (supervisor who claimed the sheet, or admin).
     */
    @Transactional
    public LogSheet completeFromWeb(Long sheetId, Map<String, Map<String, Object>> entryValues) {
        LogSheet sheet = requireOpenSheetForWeb(sheetId);
        assertWebCompletionAccess(sheet);
        validateWebFormData(sheet, entryValues);

        long now = System.currentTimeMillis();
        // Claim SUBMITTED first so a losing concurrent complete cannot flush entry formData.
        // Non-admin actors must still be the assignee at UPDATE time (takeover/reassign race).
        if (!tryApplyCompletion(sheet, SecurityUtils.currentUserId(), now, now, now, null, null, ActionSource.WEB, null,
                !SecurityUtils.isAdmin())) {
            throw new IllegalStateException("This log sheet cannot be completed.");
        }
        applyWebEntryValues(sheet, entryValues);
        return require(sheetId);
    }

    /** When the deadline passes, a saved draft is auto-submitted as the final record. */
    @Transactional
    public boolean finalizeDraftOnExpiry(Long sheetId, long now) {
        LogSheet sheet = logSheetRepository.findById(sheetId).orElse(null);
        if (sheet == null || sheet.getStatus() == LogSheetStatus.SUBMITTED) {
            return false;
        }
        if (sheet.getDraftSavedAt() == null) {
            return false;
        }
        long completedAt = sheet.getDueAt() != null ? sheet.getDueAt() : now;
        return tryApplyCompletion(sheet, sheet.getAssigneeUserId(), completedAt, now, now,
                null, null, ActionSource.SERVER, null, true);
    }

    /**
     * Marks a sheet EXPIRED only if it is still open and overdue.
     * @return {@code true} when this call won the expiry update
     */
    @Transactional
    public boolean tryExpireOverdue(Long sheetId, long now) {
        int updated = logSheetRepository.expireIfStillOpenAndOverdue(
                sheetId, now, LogSheetStatus.EXPIRED, OPEN_FOR_EXPIRY_STATUSES);
        if (updated == 0) {
            return false;
        }
        LogSheet sheet = logSheetRepository.findById(sheetId).orElse(null);
        Long assignee = sheet != null ? sheet.getAssigneeUserId() : null;
        actionLogger.record(sheetId, LogSheetActionType.EXPIRE, ActionSource.SERVER,
                null, assignee, null, now, null);
        businessEventLogger.logSheetExpired(sheetId);
        return true;
    }

    private LogSheet requireOpenSheetForWeb(Long sheetId) {
        LogSheet sheet = logSheetRepository.findById(sheetId)
                .orElseThrow(() -> new IllegalArgumentException("Log sheet not found."));
        if (sheet.getStatus() == LogSheetStatus.SUBMITTED) {
            throw new IllegalStateException("This log sheet is already completed.");
        }
        long now = System.currentTimeMillis();
        if (sheet.getDueAt() != null && now > sheet.getDueAt()) {
            throw new IllegalStateException("This log sheet completion deadline has passed.");
        }
        return sheet;
    }

    private void assertWebCompletionAccess(LogSheet sheet) {
        if (SecurityUtils.isAdmin()) {
            return;
        }
        Long userId = SecurityUtils.currentUserId();
        boolean isAssignee = userId != null && userId.equals(sheet.getAssigneeUserId());
        if (!isAssignee) {
            throw new AccessDeniedException("This log sheet is no longer assigned to you.");
        }
        if (SecurityUtils.hasRole(LogSheetWebCompletionAccess.ROLE_SENIOR_OPERATOR)) {
            return;
        }
        if (scopeService.isSupervisorOf(userId, sheet.getOperationalUnitId())) {
            return;
        }
        throw new AccessDeniedException("Log sheets can only be completed in the mobile app.");
    }

    // ---------------------------------------------------------------- shared helpers

    /**
     * Atomically transitions the sheet to SUBMITTED when still completable and within due.
     * Callers must persist entry formData only after this returns {@code true}.
     * @param requireCurrentAssignee when true, UPDATE also requires {@code assigneeUserId = actorUserId}
     * @return {@code false} if a concurrent expiry/completion/ownership change already changed the row
     */
    private boolean tryApplyCompletion(LogSheet sheet, Long actorUserId, long completedAt, long submittedAt,
                                       long syncedAt, String operatorName, String syncStatus,
                                       ActionSource source, String clientActionId,
                                       boolean requireCurrentAssignee) {
        Long expectedAssigneeUserId = null;
        if (requireCurrentAssignee) {
            if (actorUserId == null) {
                return false;
            }
            expectedAssigneeUserId = actorUserId;
        }
        int updated = logSheetRepository.submitIfStillCompletable(
                sheet.getId(),
                actorUserId,
                completedAt,
                submittedAt,
                syncedAt,
                syncStatus,
                operatorName,
                LogSheetStatus.SUBMITTED,
                COMPLETABLE_STATUSES,
                expectedAssigneeUserId);
        if (updated == 0) {
            return false;
        }
        actionLogger.record(sheet.getId(), LogSheetActionType.COMPLETE, source,
                actorUserId, null, null, completedAt, clientActionId);
        actionLogger.record(sheet.getId(), LogSheetActionType.SUBMIT, source,
                actorUserId, null, null, syncedAt, null);
        businessEventLogger.logSheetCompleted(sheet.getId(), actorUserId,
                source != null ? source.name() : null);
        return true;
    }

    private LogSheetSubmitResult resolveFailedCompletion(LogSheet sheet, LogSheetDto dto,
                                                         Long currentUserId, long completedAt, long now) {
        LogSheet fresh = logSheetRepository.findById(sheet.getId()).orElse(sheet);
        if (fresh.getStatus() == LogSheetStatus.SUBMITTED) {
            if (currentUserId != null && currentUserId.equals(fresh.getCompletedByUserId())) {
                return new LogSheetSubmitResult(dto.getLocalId(), sheet.getId(), null, "DUPLICATE");
            }
            return voidSubmission(fresh, dto, currentUserId, completedAt, now,
                    "This log sheet was already completed by someone else.");
        }
        // Takeover / reassign / release won the ownership race while this submit was in flight.
        if (currentUserId == null || !currentUserId.equals(fresh.getAssigneeUserId())) {
            return voidSubmission(fresh, dto, currentUserId, completedAt, now,
                    "This log sheet is no longer assigned to you.");
        }
        return new LogSheetSubmitResult(dto.getLocalId(), sheet.getId(),
                "This log sheet completion deadline has passed.", "EXPIRED");
    }

    private LogSheet require(Long sheetId) {
        return logSheetRepository.findById(sheetId)
                .orElseThrow(() -> new IllegalArgumentException("Log sheet not found."));
    }

    /** Records a late/void submission that must not overwrite the completed sheet. */
    private LogSheetSubmitResult voidSubmission(LogSheet sheet, LogSheetDto dto, Long userId,
                                                long completedAt, long now, String reason) {
        LogSheetVoidSubmission v = new LogSheetVoidSubmission();
        v.setLogSheetId(sheet.getId());
        v.setSubmittedByUserId(userId);
        v.setCompletedAt(completedAt);
        v.setSyncedAt(now);
        v.setReason(reason);
        v.setPayload(entriesToPayload(dto.getEntries()));
        voidSubmissionRepository.save(v);

        actionLogger.record(sheet.getId(), LogSheetActionType.SUPERSEDE, ActionSource.MOBILE,
                userId, null, null, completedAt, dto.getClientActionId());
        return new LogSheetSubmitResult(dto.getLocalId(), sheet.getId(), reason, "SUPERSEDED");
    }

    private void validateWebFormData(LogSheet sheet, Map<String, Map<String, Object>> entryValues) {
        List<LogSheetEntry> entries = logSheetEntryRepository.findByLogSheetId(sheet.getId());
        List<FieldDefinition> fieldDefs = fieldDefinitionsService.resolveForEntries(sheet, entries);
        if (fieldDefs.isEmpty()) {
            return;
        }

        List<String> errors = new ArrayList<>();
        Map<Long, String> assetCodes = assetCodesById(entries);
        for (LogSheetEntry entry : entries) {
            Map<String, Object> formData = entry.getFormData();
            if (entryValues != null) {
                Map<String, Object> submitted = entryValues.get(String.valueOf(entry.getId()));
                if (submitted != null) {
                    formData = submitted;
                }
            }
            List<FieldDefinition> entryDefs = defsForClass(fieldDefs, entry.getClassId());
            List<ValidationIssue> issues = FormDataValidationSupport.validateFilledEntry(formData, entryDefs);
            String message = FormDataValidationSupport.formatIssues(
                    entry.getAssetId(),
                    entry.getAssetName(),
                    assetCodes.get(entry.getAssetId()),
                    issues);
            if (message != null) {
                errors.add(message);
            }
        }
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException(String.join(" | ", errors));
        }
    }

    private void applyWebEntryValues(LogSheet sheet, Map<String, Map<String, Object>> entryValues) {
        if (entryValues == null || entryValues.isEmpty()) return;
        long now = System.currentTimeMillis();
        List<LogSheetEntry> entries = logSheetEntryRepository.findByLogSheetId(sheet.getId());
        List<FieldDefinition> fieldDefs = resolveFieldDefinitions(sheet, entries);
        for (LogSheetEntry entry : entries) {
            Map<String, Object> values = entryValues.get(String.valueOf(entry.getId()));
            if (values == null) continue;
            values = retainKnownFormData(values, fieldDefs, entry.getClassId());
            boolean hadData = hasEntryFormData(entry.getFormData());
            entry.setFormData(values);
            if (!hasEntryFormData(values)) {
                logSheetEntryRepository.save(entry);
                continue;
            }
            if (!hadData && entry.getCreatedAt() == null) {
                entry.setCreatedAt(now);
            } else {
                if (entry.getCreatedAt() == null) {
                    entry.setCreatedAt(now);
                }
                entry.setUpdatedAt(now);
            }
            logSheetEntryRepository.save(entry);
        }
    }

    private List<FieldDefinition> resolveFieldDefinitions(LogSheet sheet, List<LogSheetEntry> entries) {
        if (sheet.getFieldDefinitionsSnapshot() != null) {
            return fieldDefinitionsService.resolveForEntries(sheet, List.of());
        }
        return fieldDefinitionsService.resolveForEntries(sheet, entries);
    }

    private Map<Long, String> assetCodesById(List<LogSheetEntry> entries) {
        Set<Long> assetIds = entries.stream()
                .map(LogSheetEntry::getAssetId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (assetIds.isEmpty()) {
            return Map.of();
        }
        return assetEntryRepository.findAllById(assetIds).stream()
                .filter(asset -> asset.getAssetCode() != null && !asset.getAssetCode().isBlank())
                .collect(Collectors.toMap(AssetEntry::getId, AssetEntry::getAssetCode, (left, right) -> left));
    }

    private static Map<String, Object> retainKnownFormData(Map<String, Object> formData,
                                                           List<FieldDefinition> fieldDefs,
                                                           Long classId) {
        List<FieldDefinition> defs = fieldDefs == null ? List.of() : fieldDefs;
        return FormDataValidationSupport.retainKnownKeys(formData, defsForClass(defs, classId));
    }

    private List<Map<String, Object>> entriesToPayload(List<LogSheetEntryDto> entries) {
        List<Map<String, Object>> payload = new ArrayList<>();
        if (entries == null) return payload;
        for (LogSheetEntryDto e : entries) {
            Map<String, Object> m = new HashMap<>();
            m.put("assetId", e.getAssetId());
            m.put("assetName", e.getAssetName());
            m.put("formData", e.getFormData());
            m.put("createdAt", e.getCreatedAt());
            m.put("updatedAt", e.getUpdatedAt());
            payload.add(m);
        }
        return payload;
    }

    private static boolean hasEntryFormData(Map<String, Object> formData) {
        if (formData == null || formData.isEmpty()) return false;
        for (Object value : formData.values()) {
            if (value == null) continue;
            if (value instanceof String s) {
                if (!s.isBlank()) return true;
            } else if (value instanceof Collection<?> c) {
                if (!c.isEmpty()) return true;
            } else {
                return true;
            }
        }
        return false;
    }

    @SafeVarargs
    private static <T> T firstNonNull(T... values) {
        for (T v : values) {
            if (v != null) return v;
        }
        return null;
    }
}
