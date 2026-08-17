package com.hnp.backendofflinefirst.service;

import com.hnp.backendofflinefirst.domain.EntrySeverityEvaluator;
import com.hnp.backendofflinefirst.domain.ActionSource;
import com.hnp.backendofflinefirst.domain.LogSheetActionType;
import com.hnp.backendofflinefirst.domain.LocationValues;
import com.hnp.backendofflinefirst.domain.LogSheetEntrySource;
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
import com.hnp.backendofflinefirst.security.Capabilities;
import com.hnp.backendofflinefirst.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
    private final AssetStatusRequestService assetStatusRequestService;
    private final OperationalUnitScopeService scopeService;
    private final BusinessEventLogger businessEventLogger;
    private final LogSheetFieldDefinitionsService fieldDefinitionsService;

    /**
     * Caps items per sync batch — the whole batch runs in one DB transaction (see below),
     * so an unbounded array could tie up a connection/thread for an unreasonable time.
     * Field-level default (not {@code final}) lets plain unit tests that build this service
     * without Spring keep a sane value; the real app overrides it via {@code @Value}.
     */
    @Value("${app.sync.batch-max-items}")
    private int batchMaxItems = 500;

    // ---------------------------------------------------------------- mobile sync

    @Transactional
    public List<LogSheetSubmitResult> submitBatch(List<LogSheetDto> dtos) {
        List<LogSheetSubmitResult> results = new ArrayList<>();
        if (dtos == null) return results;
        if (dtos.size() > batchMaxItems) {
            throw new IllegalArgumentException(
                    "Batch has " + dtos.size() + " items; maximum allowed is " + batchMaxItems + ".");
        }
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
                SecurityUtils.isUnitScopedOnly(), false)) {
            return resolveFailedCompletion(sheet, dto, currentUserId, completedAt, now);
        }
        mergeMobileEntryUpdates(sheet, dto.getEntries(), currentUserId);
        // After the entries are persisted, never before: the reading to act on is the one that
        // was actually stored, not the one in the request that validation might still have
        // altered. This raises requests for a supervisor to decide — completing a round proposes
        // an asset's new state, it does not declare it.
        assetStatusRequestService.raiseFromCompletedSheet(sheet, currentUserId);
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
        // VALIDATION_ERROR rather than a plain ERROR: this is the only submit rejection an
        // operator can actually fix, by editing the values. The app uses the distinction to
        // offer "correct and resubmit" for this case alone — a deleted sheet or an asset
        // mismatch is supervisor territory, and offering the same control there would imply a
        // power the operator does not have. Older clients fall through to their default branch
        // and still show the message, so the new value is backward compatible.
        return new LogSheetSubmitResult(
                dto.getLocalId(),
                sheet.getId(),
                String.join(" | ", errors),
                "VALIDATION_ERROR");
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
    private void mergeMobileEntryUpdates(LogSheet sheet, List<LogSheetEntryDto> entryDtos, Long actorUserId) {
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
                Map<String, Object> previousFormData = entry.getFormData();
                boolean hadData = hasEntryFormData(previousFormData);
                // A mobile submit always resends every entry currently on the device, including
                // ones the submitter never opened (e.g. another operator's already-filled asset
                // from before a reassignment) — retainKnownFormData ends up byte-for-byte the
                // same as what's already stored for those. Only attribute authorship when this
                // submit actually changes the value, so re-submitting an unchanged sheet cannot
                // silently reassign someone else's reading to the current submitter/method
                // (AGENTS.md gotcha #20).
                boolean formDataChanged = !Objects.equals(previousFormData, formData);
                entry.setFormData(formData);
                // Severity must be recomputed on every write, not only when the value changed:
                // a resubmit of the same values against a re-snapshotted sheet, or a clear,
                // both have to leave the flag consistent with what is actually stored.
                EntrySeverityEvaluator.apply(entry, fieldDefs);
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
                    if (formDataChanged) {
                        entry.setEntrySource(Boolean.TRUE.equals(dto.getManualEntry())
                                ? LogSheetEntrySource.PWA_MANUAL : LogSheetEntrySource.PWA_NFC);
                        entry.setFilledByUserId(actorUserId);
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
        return saveDraftFromWeb(sheetId, entryValues, null);
    }

    @Transactional
    public LogSheet saveDraftFromWeb(Long sheetId, Map<String, Map<String, Object>> entryValues, String notes) {
        LogSheet sheet = requireOpenSheetForWeb(sheetId);
        assertWebCompletionAccess(sheet);
        entryValues = normaliseWebEntryValues(sheet, entryValues);
        applyWebEntryValues(sheet, entryValues);
        applyWebNotes(sheet, notes);
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
        return completeFromWeb(sheetId, entryValues, null);
    }

    @Transactional
    public LogSheet completeFromWeb(Long sheetId, Map<String, Map<String, Object>> entryValues, String notes) {
        LogSheet sheet = requireOpenSheetForWeb(sheetId);
        assertWebCompletionAccess(sheet);
        // Before validation, not after: validation judges the value it is given, and the web
        // form's coordinate arrives as two raw strings that only become a coordinate once
        // paired. Normalising afterwards meant every location field — including empty ones —
        // was rejected as "not a valid lat/lng".
        entryValues = normaliseWebEntryValues(sheet, entryValues);
        validateWebFormData(sheet, entryValues);

        long now = System.currentTimeMillis();
        // Claim SUBMITTED first so a losing concurrent complete cannot flush entry formData.
        // Whoever cannot complete an unassigned sheet must still be the assignee at UPDATE time
        // (takeover/reassign race).
        if (!tryApplyCompletion(sheet, SecurityUtils.currentUserId(), now, now, now, null, null, ActionSource.WEB, null,
                !SecurityUtils.hasCapability(Capabilities.LOGSHEET_COMPLETE_WEB_ANY), false)) {
            throw new IllegalStateException("This log sheet cannot be completed.");
        }
        applyWebEntryValues(sheet, entryValues);
        assetStatusRequestService.raiseFromCompletedSheet(sheet, SecurityUtils.currentUserId());
        LogSheet fresh = require(sheetId);
        if (applyWebNotes(fresh, notes)) {
            logSheetRepository.save(fresh);
        }
        return require(sheetId);
    }

    /**
     * When the deadline passes, a saved draft is auto-submitted as the final record.
     *
     * <p>"A saved draft" means exactly one thing: {@code draft_saved_at}, which only
     * {@link #saveDraftFromWeb} ever sets. A round being filled in the mobile app has no draft on
     * the server — the device pushes completions, never drafts — so it expires normally and its
     * completion is accepted later on the strength of {@code completed_at} instead.
     *
     * <p><b>A pool sheet is auto-finalised too, with no completer.</b> Somebody with plant-wide
     * completion rights can save a draft against an unassigned sheet, and that draft is real work:
     * refusing to finalise it left the row neither submitted nor expired — the scheduler retried it
     * every minute forever, and the compliance report counted a round that had actually been
     * recorded as missed. The ownership guard still holds, in the other direction: the update
     * requires the row to be <i>still</i> unassigned, so a claim that lands first wins and this
     * simply does nothing until the next tick, when the sheet is finalised under its new assignee.
     */
    @Transactional
    public boolean finalizeDraftOnExpiry(Long sheetId, long now) {
        LogSheet sheet = logSheetRepository.findById(sheetId).orElse(null);
        if (sheet == null || sheet.getStatus() == LogSheetStatus.SUBMITTED
                || sheet.getStatus() == LogSheetStatus.VOIDED
                || sheet.getStatus() == LogSheetStatus.CANCELLED) {
            return false;
        }
        if (sheet.getDraftSavedAt() == null) {
            return false;
        }
        long completedAt = sheet.getDueAt() != null ? sheet.getDueAt() : now;
        Long assignee = sheet.getAssigneeUserId();
        // Exactly one guard applies, and either way it says the same thing: complete this row only
        // if its ownership has not moved since it was read a moment ago.
        boolean completed = tryApplyCompletion(sheet, assignee, completedAt, now, now,
                null, null, ActionSource.SERVER, null, assignee != null, assignee == null);
        if (completed) {
            // The third completion path. A draft auto-submitted at its deadline is as much a
            // completion as one an operator pressed submit on, and its status readings are just
            // as real — omitting it here would leave those changes unproposed for every
            // overdue round.
            assetStatusRequestService.raiseFromCompletedSheet(sheet, sheet.getAssigneeUserId());
        }
        return completed;
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
        if (sheet.getStatus() != null && sheet.getStatus().isTerminal()) {
            throw new IllegalStateException(
                    sheet.getStatus() == LogSheetStatus.SUBMITTED
                            ? "This log sheet is already completed."
                            : "This log sheet cannot be edited.");
        }
        long now = System.currentTimeMillis();
        if (sheet.getDueAt() != null && now > sheet.getDueAt()) {
            throw new IllegalStateException("This log sheet completion deadline has passed.");
        }
        return sheet;
    }

    private void assertWebCompletionAccess(LogSheet sheet) {
        if (SecurityUtils.hasCapability(Capabilities.LOGSHEET_COMPLETE_WEB_ANY)) {
            return;
        }
        Long userId = SecurityUtils.currentUserId();
        boolean isAssignee = userId != null && userId.equals(sheet.getAssigneeUserId());
        if (!isAssignee) {
            throw new AccessDeniedException("This log sheet is no longer assigned to you.");
        }
        if (SecurityUtils.hasCapability(Capabilities.LOGSHEET_COMPLETE_WEB_SELF)) {
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
     * @param requireUnassigned when true, UPDATE instead requires {@code assigneeUserId IS NULL}.
     *        Only the expiry scheduler's auto-finalise of a pool sheet passes this: there is no
     *        assignee to compare against, and it still must not complete a sheet that somebody
     *        claimed in the meantime. Never combine it with {@code requireCurrentAssignee}.
     * @return {@code false} if a concurrent expiry/completion/ownership change already changed the row
     */
    private boolean tryApplyCompletion(LogSheet sheet, Long actorUserId, long completedAt, long submittedAt,
                                       long syncedAt, String operatorName, String syncStatus,
                                       ActionSource source, String clientActionId,
                                       boolean requireCurrentAssignee, boolean requireUnassigned) {
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
                expectedAssigneeUserId,
                requireUnassigned);
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
        // Checked before the assignee-mismatch fallback below: cancellation doesn't change the
        // assignee, so without this a cancelled sheet would otherwise fall through to the
        // generic "deadline has passed" (EXPIRED) message, which is simply wrong. The operator's
        // work is preserved as a void submission (same as the "completed by someone else" case
        // below) rather than silently discarded — the supervisor cancelled the sheet, not the
        // operator's effort, and that effort should stay visible for review.
        if (fresh.getStatus() == LogSheetStatus.CANCELLED) {
            return voidSubmission(fresh, dto, currentUserId, completedAt, now,
                    "This log sheet was cancelled.", "CANCELLED");
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
        return voidSubmission(sheet, dto, userId, completedAt, now, reason, "SUPERSEDED");
    }

    /**
     * Records a late/void submission that must not overwrite the sheet's authoritative state,
     * preserving the operator's payload for later review. {@code outcome} lets callers report a
     * more specific reason than "superseded" to the client (e.g. {@code CANCELLED}) while still
     * going through the same audit trail (always logged as {@link LogSheetActionType#SUPERSEDE} —
     * from the sheet's perspective this offline completion attempt was voided by a state change
     * that happened while the operator was offline, regardless of which one).
     */
    private LogSheetSubmitResult voidSubmission(LogSheet sheet, LogSheetDto dto, Long userId,
                                                long completedAt, long now, String reason, String outcome) {
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
        return new LogSheetSubmitResult(dto.getLocalId(), sheet.getId(), reason, outcome);
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

    /**
     * @param notes {@code null} leaves existing notes unchanged; blank clears; otherwise trimmed text (max 4000).
     * @return true when the entity was modified
     */
    private boolean applyWebNotes(LogSheet sheet, String notes) {
        if (notes == null) {
            return false;
        }
        String trimmed = notes.trim();
        if (trimmed.length() > 4000) {
            throw new IllegalArgumentException("Log sheet notes must be at most 4000 characters.");
        }
        String normalized = trimmed.isEmpty() ? null : trimmed;
        if (Objects.equals(sheet.getNotes(), normalized)) {
            return false;
        }
        sheet.setNotes(normalized);
        return true;
    }

    /**
     * Normalises a whole web submission before anything reads it.
     *
     * <p>Runs at the entry points rather than inside {@code applyWebEntryValues} so that
     * validation and storage see the same values. The web form posts a location as two raw
     * strings; until they are paired they are neither a coordinate nor an empty field, and
     * validating them in that state rejected every location field on the sheet.
     */
    private Map<String, Map<String, Object>> normaliseWebEntryValues(
            LogSheet sheet, Map<String, Map<String, Object>> entryValues) {
        if (entryValues == null || entryValues.isEmpty()) {
            return entryValues;
        }
        List<LogSheetEntry> entries = logSheetEntryRepository.findByLogSheetId(sheet.getId());
        List<FieldDefinition> fieldDefs = resolveFieldDefinitions(sheet, entries);
        if (fieldDefs.stream().noneMatch(d -> LocationValues.isLocationField(d.getDataType()))) {
            // Nothing to do for the overwhelming majority of sheets.
            return entryValues;
        }
        Map<String, Map<String, Object>> out = new LinkedHashMap<>(entryValues);
        for (LogSheetEntry entry : entries) {
            Map<String, Object> values = out.get(String.valueOf(entry.getId()));
            if (values == null) {
                continue;
            }
            out.put(String.valueOf(entry.getId()),
                    normaliseLocationValues(values, fieldDefs, entry.getClassId()));
        }
        return out;
    }

    /**
     * Turns the web form's two same-named coordinate inputs into the stored location object.
     *
     * <p>The mobile app already sends the canonical shape, so only the web path needs this —
     * but both must end up identical in the database, or every reader (display, Excel export,
     * a future map) would have to cope with two shapes and would eventually get one wrong.
     *
     * <p>A pair that will not parse is dropped rather than stored: validation then reports the
     * field as unanswered, which is true, instead of storing half a position that looks real.
     */
    private Map<String, Object> normaliseLocationValues(Map<String, Object> values,
                                                        List<FieldDefinition> fieldDefs,
                                                        Long classId) {
        if (values == null || values.isEmpty()) {
            return values;
        }
        Map<String, Object> out = new LinkedHashMap<>(values);
        for (FieldDefinition def : fieldDefs) {
            if (def.getKey() == null || !LocationValues.isLocationField(def.getDataType())) {
                continue;
            }
            if (classId != null && def.getClassId() != null && !classId.equals(def.getClassId())) {
                continue;
            }
            if (!out.containsKey(def.getKey())) {
                continue;
            }
            Object stored = LocationValues.fromWebPair(out.get(def.getKey()));
            if (stored == null) {
                out.remove(def.getKey());
            } else {
                out.put(def.getKey(), stored);
            }
        }
        return out;
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
            Map<String, Object> previousFormData = entry.getFormData();
            boolean hadData = hasEntryFormData(previousFormData);
            // Same rationale as the mobile path: the web fill form resubmits every entry's
            // current value on every save, including ones this actor never touched — only
            // reattribute authorship when the value actually changed (AGENTS.md gotcha #20).
            boolean formDataChanged = !Objects.equals(previousFormData, values);
            entry.setFormData(values);
            EntrySeverityEvaluator.apply(entry, fieldDefs);
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
            if (formDataChanged) {
                entry.setEntrySource(LogSheetEntrySource.WEB);
                entry.setFilledByUserId(SecurityUtils.currentUserId());
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
