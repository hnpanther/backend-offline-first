package com.hnp.backendofflinefirst.service;

import com.hnp.backendofflinefirst.domain.ActionSource;
import com.hnp.backendofflinefirst.domain.LogSheetActionType;
import com.hnp.backendofflinefirst.domain.LogSheetStatus;
import com.hnp.backendofflinefirst.dto.LogSheetDto;
import com.hnp.backendofflinefirst.dto.LogSheetEntryDto;
import com.hnp.backendofflinefirst.dto.LogSheetSubmitResult;
import com.hnp.backendofflinefirst.entity.LogSheet;
import com.hnp.backendofflinefirst.entity.LogSheetEntry;
import com.hnp.backendofflinefirst.entity.LogSheetVoidSubmission;
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
 *   <li>mobile submits may only update entries for assets already on the sheet;
 *       foreign asset ids are rejected and omitted assets are never deleted.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class LogSheetService {

    private final LogSheetRepository logSheetRepository;
    private final LogSheetEntryRepository logSheetEntryRepository;
    private final LogSheetVoidSubmissionRepository voidSubmissionRepository;
    private final LogSheetActionLogger actionLogger;
    private final OperationalUnitScopeService scopeService;
    private final BusinessEventLogger businessEventLogger;

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
            sheet.setStatus(LogSheetStatus.EXPIRED);
            sheet.setExpiredAt(now);
            sheet.setUpdatedAt(now);
            logSheetRepository.save(sheet);
            actionLogger.record(serverId, LogSheetActionType.EXPIRE, ActionSource.MOBILE,
                    currentUserId, sheet.getAssigneeUserId(), null, completedAt, null);
            return new LogSheetSubmitResult(dto.getLocalId(), serverId,
                    "This log sheet completion deadline has passed.", "EXPIRED");
        }

        LogSheetSubmitResult entryValidation = validateSubmittedEntries(dto, serverId);
        if (entryValidation != null) {
            return entryValidation;
        }

        mergeMobileEntryUpdates(serverId, dto.getEntries());
        applyCompletion(sheet, currentUserId, completedAt, firstNonNull(dto.getSubmittedAt(), completedAt),
                now, dto.getOperatorName(), dto.getSyncStatus(), ActionSource.MOBILE, dto.getClientActionId());
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

    /** Updates form data for matching assets only; never adds or removes log-sheet rows.
     *  Asset metadata (name, class, NFC, sub-function) is server-authoritative and ignored from the client. */
    private void mergeMobileEntryUpdates(Long logSheetId, List<LogSheetEntryDto> entryDtos) {
        if (entryDtos == null || entryDtos.isEmpty()) {
            return;
        }

        Map<Long, LogSheetEntry> byAssetId = logSheetEntryRepository.findByLogSheetId(logSheetId).stream()
                .filter(entry -> entry.getAssetId() != null)
                .collect(Collectors.toMap(LogSheetEntry::getAssetId, entry -> entry, (left, right) -> left));

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
                boolean hadData = hasEntryFormData(entry.getFormData());
                entry.setFormData(dto.getFormData());
                if (hasEntryFormData(dto.getFormData())) {
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
        applyWebEntryValues(sheetId, entryValues);
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

        long now = System.currentTimeMillis();
        applyWebEntryValues(sheetId, entryValues);
        applyCompletion(sheet, SecurityUtils.currentUserId(), now, now, now, null, null, ActionSource.WEB, null);
        sheet.setDraftSavedAt(null);
        return sheet;
    }

    /** When the deadline passes, a saved draft is auto-submitted as the final record. */
    @Transactional
    public void finalizeDraftOnExpiry(LogSheet sheet, long now) {
        if (sheet.getStatus() == LogSheetStatus.SUBMITTED) return;
        long completedAt = sheet.getDueAt() != null ? sheet.getDueAt() : now;
        applyCompletion(sheet, sheet.getAssigneeUserId(), completedAt, now, now, null, null, ActionSource.SERVER, null);
        sheet.setDraftSavedAt(null);
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

    private void applyCompletion(LogSheet sheet, Long actorUserId, long completedAt, long submittedAt,
                                 long syncedAt, String operatorName, String syncStatus,
                                 ActionSource source, String clientActionId) {
        sheet.setStatus(LogSheetStatus.SUBMITTED);
        sheet.setCompletedByUserId(actorUserId);
        sheet.setCompletedAt(completedAt);
        sheet.setSubmittedAt(submittedAt);
        sheet.setSyncedAt(syncedAt);
        if (syncStatus != null) sheet.setSyncStatus(syncStatus);
        if (operatorName != null) sheet.setOperatorName(operatorName);
        sheet.setUpdatedAt(syncedAt);
        logSheetRepository.save(sheet);

        actionLogger.record(sheet.getId(), LogSheetActionType.COMPLETE, source,
                actorUserId, null, null, completedAt, clientActionId);
        actionLogger.record(sheet.getId(), LogSheetActionType.SUBMIT, source,
                actorUserId, null, null, syncedAt, null);
        businessEventLogger.logSheetCompleted(sheet.getId(), actorUserId,
                source != null ? source.name() : null);
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

    private void applyWebEntryValues(Long logSheetId, Map<String, Map<String, Object>> entryValues) {
        if (entryValues == null || entryValues.isEmpty()) return;
        long now = System.currentTimeMillis();
        List<LogSheetEntry> entries = logSheetEntryRepository.findByLogSheetId(logSheetId);
        for (LogSheetEntry entry : entries) {
            Map<String, Object> values = entryValues.get(String.valueOf(entry.getId()));
            if (values == null) continue;
            boolean hadData = hasEntryFormData(entry.getFormData());
            entry.setFormData(values);
            if (!hasEntryFormData(values)) continue;
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
