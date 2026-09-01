package com.hnp.backendofflinefirst.service;

import com.hnp.backendofflinefirst.domain.EntrySeverityEvaluator;
import com.hnp.backendofflinefirst.domain.ActionSource;
import com.hnp.backendofflinefirst.domain.LogSheetActionType;
import com.hnp.backendofflinefirst.domain.LocationValues;
import com.hnp.backendofflinefirst.domain.LogSheetEntrySource;
import com.hnp.backendofflinefirst.domain.LogSheetStatus;
import com.hnp.backendofflinefirst.dto.LogSheetDto;
import com.hnp.backendofflinefirst.dto.LogSheetEntryDto;
import com.hnp.backendofflinefirst.dto.LogSheetProgressItem;
import com.hnp.backendofflinefirst.dto.LogSheetProgressResult;
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
import lombok.extern.slf4j.Slf4j;
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
@Slf4j
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
    private final LogSheetEntryRevisionService revisionService;

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
        // `isCompleted()`, not SUBMITTED: an approved round is finished too, and a tablet coming
        // back from a week offline must be told "somebody already completed this" rather than
        // falling through to the deadline branch and being told its clock was wrong.
        if (sheet.getStatus() != null && sheet.getStatus().isCompleted()) {
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
        String message = foreignAssetMessage(dto.getEntries(), serverId);
        return message == null
                ? null
                : new LogSheetSubmitResult(dto.getLocalId(), serverId, message, "ERROR");
    }

    /**
     * "None of these assets is a stranger to this sheet", as a message or {@code null}.
     *
     * <p>Shared by the submit and progress paths because it is the same rule and the same
     * failure: a client may update the assets the server put on the round and no others. Adding
     * one would forge a reading for equipment nobody was asked to inspect, and would do it
     * against a sheet whose frozen asset list is the whole basis for trusting the round.
     */
    private String foreignAssetMessage(List<LogSheetEntryDto> submitted, Long serverId) {
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
        return "Asset(s) not part of this log sheet (ids: " + ids + ").";
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
                Map<String, Object> formData = storableFormData(dto.getFormData(), fieldDefs, entry.getClassId());
                Map<String, Object> previousFormData = entry.getFormData();
                boolean hadData = hasEntryFormData(previousFormData);

                // A device may not blank an answer it has never seen. See the method's javadoc:
                // this is the one conflict the merge refuses to resolve by last-writer-wins,
                // because the loser is destroyed rather than superseded.
                if (wouldBlankUnseenAnswer(dto, entry, formData, hadData)) {
                    log.warn("Ignored blank entry from a stale device — sheet {} asset {} keeps "
                                    + "its stored answer (device base createdAt={}/updatedAt={}, "
                                    + "server createdAt={}/updatedAt={}, actor={})",
                            sheet.getId(), assetId, dto.getCreatedAt(), dto.getUpdatedAt(),
                            entry.getCreatedAt(), entry.getUpdatedAt(), actorUserId);
                    continue;
                }
                // A mobile submit always resends every entry currently on the device, including
                // ones the submitter never opened (e.g. another operator's already-filled asset
                // from before a reassignment) — storableFormData ends up byte-for-byte the
                // same as what's already stored for those. Only attribute authorship when this
                // submit actually changes the value, so re-submitting an unchanged sheet cannot
                // silently reassign someone else's reading to the current submitter/method
                // (AGENTS.md gotcha #20).
                boolean formDataChanged = !Objects.equals(previousFormData, formData);
                // Before the mutation, while the entry still holds what is about to be lost.
                // Gated on the same `formDataChanged` that decides re-attribution below, so an
                // entry can never change hands without its previous reading being kept — and a
                // resubmit that changes nothing can never manufacture a history row.
                if (formDataChanged) {
                    revisionService.recordSupersededValue(
                            entry, sheet, actorUserId, ActionSource.MOBILE, now);
                }
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

    // ---------------------------------------------------------------- mobile progress

    /**
     * Statuses a progress push may be accepted against.
     *
     * <p>Narrower than {@link #COMPLETABLE_STATUSES} on both ends, and each omission is a
     * decision. {@code PENDING} is out because a pool sheet has no assignee and progress
     * requires one — {@code release} clears both together, so "PENDING and assigned to me" does
     * not exist. {@code EXPIRED} is out because expiry is exactly the signal that the round's
     * window has closed; a completion may still arrive afterwards and be judged on its device
     * time, but a live report about work still in progress cannot be about a window that has
     * already shut.
     */
    static final List<LogSheetStatus> OPEN_FOR_PROGRESS_STATUSES = List.of(
            LogSheetStatus.ASSIGNED,
            LogSheetStatus.IN_PROGRESS);

    /** {@code log_sheets.draft_source} for a push from a tablet. */
    private static final String DRAFT_SOURCE_MOBILE = "MOBILE";

    /** {@code log_sheets.draft_source} for the panel's «ذخیره پیش‌نویس». */
    private static final String DRAFT_SOURCE_WEB = "WEB";

    /**
     * Partial values from rounds still being walked.
     *
     * <p><b>What this is for.</b> A tablet used to push completions and nothing else, so a round
     * was invisible to the server for its whole duration: twenty assets filled in the first hour,
     * the device online the entire time, and a supervisor looking at the sheet saw no data at all.
     * If the sheet then changed hands, the next operator started from an empty form and re-walked
     * ground already covered. This endpoint is what makes progress visible while it is being made,
     * and what makes a handover carry the first operator's work with it.
     *
     * <p><b>What it deliberately is not.</b> Not a completion, and nothing here may become one.
     * It writes no {@code COMPLETE}/{@code SUBMIT} action row, raises no asset status request —
     * completing a round <em>proposes</em> an asset's new state, and a round in progress proposes
     * nothing — and records no void submission when it is refused. A rejected progress push loses
     * nothing: the operator's work is still on the device, still theirs, and still deliverable
     * through the ordinary submit path.
     *
     * <p><b>Idempotency is by value, not by key.</b> There is no {@code clientActionId}: progress
     * is meant to be re-sent, and a unique action key would answer the second push
     * {@code DUPLICATE} and quietly stop the supervisor's view from advancing. Re-sending values
     * the server already holds simply changes nothing — {@code formDataChanged} is false, so no
     * authorship moves and no revision row is written.
     *
     * <p>Runs in one transaction like the submit batch, and is capped by the same
     * {@code app.sync.batch-max-items}.
     */
    @Transactional
    public List<LogSheetProgressResult> saveProgressBatch(List<LogSheetProgressItem> items) {
        List<LogSheetProgressResult> results = new ArrayList<>();
        if (items == null) return results;
        if (items.size() > batchMaxItems) {
            throw new IllegalArgumentException(
                    "Batch has " + items.size() + " items; maximum allowed is " + batchMaxItems + ".");
        }
        for (LogSheetProgressItem item : items) {
            results.add(saveProgressOne(item));
        }
        return results;
    }

    private LogSheetProgressResult saveProgressOne(LogSheetProgressItem item) {
        Long serverId = item.getServerId() != null ? item.getServerId() : item.getId();
        if (serverId == null) {
            return progressResult(item, null, "Log sheet server id was not provided.", "ERROR", null);
        }
        LogSheet sheet = logSheetRepository.findById(serverId).orElse(null);
        if (sheet == null) {
            return progressResult(item, serverId, "Log sheet not found on server.", "ERROR", null);
        }

        // Nothing to report is not a failure. The device should not have pushed, but answering
        // ERROR would park the row, and answering SAVED would move a "last seen" stamp on the
        // strength of an empty payload — which is worse, because the supervisor would read it as
        // fresh work having arrived.
        if (item.getEntries() == null || item.getEntries().isEmpty()) {
            return progressResult(item, serverId, null, "NO_CHANGE", null);
        }

        Long currentUserId = SecurityUtils.currentUserId();
        long now = System.currentTimeMillis();

        LogSheetProgressResult refusal = refuseProgressIfNotOpenForThisActor(item, sheet, currentUserId, now);
        if (refusal != null) {
            return refusal;
        }

        String foreign = foreignAssetMessage(item.getEntries(), serverId);
        if (foreign != null) {
            return progressResult(item, serverId, foreign, "ERROR", null);
        }

        LogSheetProgressResult invalid = validateProgressFormData(item, sheet, serverId);
        if (invalid != null) {
            return invalid;
        }

        // Claim the progress stamp first, exactly as the completion path claims SUBMITTED first:
        // a takeover, reassign, release or cancel can land in the middle of a push that runs on a
        // timer, and values written after ownership moved would put a departed operator's
        // readings onto somebody else's round with no trace.
        boolean firstReport = sheet.getStartedAt() == null;
        int updated = logSheetRepository.markProgressIfStillOwned(
                serverId, currentUserId, now, DRAFT_SOURCE_MOBILE, item.getOperatorName(),
                LogSheetStatus.IN_PROGRESS, OPEN_FOR_PROGRESS_STATUSES);
        if (updated == 0) {
            return resolveFailedProgress(item, serverId, currentUserId, now);
        }

        // One START row, the first time a round reports anything, and never again.
        //
        // `LogSheetActionType.START` has existed since the beginning and nothing wrote it: the
        // documented state machine says "start (first draft save)", and until now a tablet made
        // no draft save the server could see. So a sheet's history jumped from CLAIM straight to
        // COMPLETE, with hours in between and nothing to say when the operator actually began.
        //
        // Emphatically not one row per push. A round reports on a timer for as long as somebody
        // is walking it; a row per report would bury the eleven transitions that matter under
        // hundreds that do not. `started_at` was null before the update above, so this is
        // precisely the first one — and the update is what makes that read atomic.
        if (firstReport) {
            actionLogger.record(serverId, LogSheetActionType.START, ActionSource.MOBILE,
                    currentUserId, null, null, now, null);
        }

        // Re-read: the update above is a bulk JPQL modification with clearAutomatically, so the
        // instance loaded before it is detached and still says ASSIGNED. The merge stamps the
        // sheet's status onto any revision row it writes, and that has to be the status the sheet
        // is actually in.
        LogSheet fresh = require(serverId);
        mergeMobileEntryUpdates(fresh, item.getEntries(), currentUserId);
        businessEventLogger.logSheetProgressSaved(serverId, currentUserId, item.getEntries().size());
        return progressResult(item, serverId, null, "SAVED", now);
    }

    /**
     * Why this push cannot be accepted, or {@code null}.
     *
     * <p>Ordered most-specific first, because the operator is holding the tablet and the
     * difference between "the supervisor cancelled this round" and "somebody else has it now"
     * decides what they do next. The atomic update re-checks all of it; this exists to name the
     * reason rather than to decide it.
     */
    private LogSheetProgressResult refuseProgressIfNotOpenForThisActor(
            LogSheetProgressItem item, LogSheet sheet, Long currentUserId, long now) {
        Long serverId = sheet.getId();
        if (sheet.getStatus() == LogSheetStatus.CANCELLED) {
            return progressResult(item, serverId, "This log sheet was cancelled.", "CANCELLED", null);
        }
        if ((sheet.getStatus() != null && sheet.getStatus().isCompleted())
                || sheet.getStatus() == LogSheetStatus.VOIDED) {
            return progressResult(item, serverId,
                    "This log sheet was already completed by someone else.", "SUPERSEDED", null);
        }
        // Stricter than the submit path, which lets a plant-wide actor complete a sheet they do
        // not hold. Progress publishes unfinished work under the assignee's name and stamps
        // draft_saved_by_user_id with it, so "I am the person walking this round" is the whole
        // precondition. Anyone who wants the round has an action for taking it over, and taking
        // it over is the honest thing to record.
        if (currentUserId == null || !currentUserId.equals(sheet.getAssigneeUserId())) {
            return progressResult(item, serverId,
                    "This log sheet is no longer assigned to you.", "SUPERSEDED", null);
        }
        if (sheet.getStatus() == LogSheetStatus.EXPIRED
                || (sheet.getDueAt() != null && sheet.getDueAt() < now)) {
            // Neither a void submission nor a data loss: the round can still be completed
            // afterwards on the strength of its device completion time. Only the live reporting
            // stops.
            return progressResult(item, serverId,
                    "This log sheet completion deadline has passed.", "EXPIRED", null);
        }
        if (!OPEN_FOR_PROGRESS_STATUSES.contains(sheet.getStatus())) {
            return progressResult(item, serverId,
                    "This log sheet is not open for progress updates.", "SUPERSEDED", null);
        }
        return null;
    }

    /**
     * Validates the answers a progress push carries, and nothing about the ones it does not.
     *
     * <p>{@code validatePartialEntry} rather than {@code validateFilledEntry}: a round in
     * progress is incomplete by definition, so judging it against "every required field is
     * present" would refuse every push until the last one. What is still checked is the shape of
     * the answers that are there — a number that is not a number, a select value outside its
     * options — because those would be refused at submit anyway, and letting them into
     * {@code form_data} early would show the supervisor a value the final submission then throws
     * out.
     *
     * <p>Judged against the sheet's own frozen snapshot, like every other validation here.
     */
    private LogSheetProgressResult validateProgressFormData(LogSheetProgressItem item,
                                                            LogSheet sheet,
                                                            Long serverId) {
        List<LogSheetEntry> serverEntries = logSheetEntryRepository.findByLogSheetId(serverId);
        List<FieldDefinition> fieldDefs = resolveFieldDefinitions(sheet, serverEntries);
        if (fieldDefs.isEmpty()) {
            return null;
        }
        Map<Long, LogSheetEntry> byAssetId = serverEntries.stream()
                .filter(e -> e.getAssetId() != null)
                .collect(Collectors.toMap(LogSheetEntry::getAssetId, e -> e, (left, right) -> left));
        Map<Long, String> assetCodes = assetCodesById(serverEntries);

        List<String> errors = new ArrayList<>();
        for (LogSheetEntryDto dto : item.getEntries()) {
            LogSheetEntry entry = dto.getAssetId() == null ? null : byAssetId.get(dto.getAssetId());
            if (entry == null || dto.getFormData() == null) {
                continue;
            }
            List<FieldDefinition> entryDefs = defsForClass(fieldDefs, entry.getClassId());
            List<ValidationIssue> issues =
                    FormDataValidationSupport.validatePartialEntry(dto.getFormData(), entryDefs);
            String message = FormDataValidationSupport.formatIssues(
                    entry.getAssetId(), entry.getAssetName(), assetCodes.get(entry.getAssetId()), issues);
            if (message != null) {
                errors.add(message);
            }
        }
        if (errors.isEmpty()) {
            return null;
        }
        return progressResult(item, serverId, String.join(" | ", errors), "VALIDATION_ERROR", null);
    }

    /** Names what won the race after {@code markProgressIfStillOwned} matched no row. */
    private LogSheetProgressResult resolveFailedProgress(LogSheetProgressItem item, Long serverId,
                                                         Long currentUserId, long now) {
        LogSheet fresh = logSheetRepository.findById(serverId).orElse(null);
        if (fresh == null) {
            return progressResult(item, serverId, "Log sheet not found on server.", "ERROR", null);
        }
        LogSheetProgressResult refusal =
                refuseProgressIfNotOpenForThisActor(item, fresh, currentUserId, now);
        if (refusal != null) {
            return refusal;
        }
        // Nothing in the WHERE clause is left to have failed except the deadline moving under us.
        return progressResult(item, serverId,
                "This log sheet completion deadline has passed.", "EXPIRED", null);
    }

    private LogSheetProgressResult progressResult(LogSheetProgressItem item, Long serverId,
                                                  String error, String outcome, Long savedAt) {
        return new LogSheetProgressResult(item.getLocalId(), serverId, error, outcome, savedAt);
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
        // Who saved it and from where. `draft_saved_at` has had two writers since V5 — this and
        // a tablet's progress push — so the sheet's page can no longer say "a draft was saved"
        // and leave the reader to guess which surface it came from.
        sheet.setDraftSavedByUserId(SecurityUtils.currentUserId());
        sheet.setDraftSource(DRAFT_SOURCE_WEB);
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
                !SecurityUtils.hasCapability(Capabilities.LOGSHEET_COMPLETE_WEB_ANY))) {
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
                    sheet.getStatus().isCompleted()
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
        if (fresh.getStatus() != null && fresh.getStatus().isCompleted()) {
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
            values = storableFormData(values, fieldDefs, entry.getClassId());
            Map<String, Object> previousFormData = entry.getFormData();
            boolean hadData = hasEntryFormData(previousFormData);
            // Only reattribute authorship when the value actually changed (AGENTS.md gotcha #20).
            // The web fill page used to make this essential by resubmitting every entry on every
            // save, touched or not; it now posts one asset at a time, so the case is rarer —
            // confirming a dialog without editing anything. The guard stays because the mobile
            // path still resends the whole device state, and because "saved without changing"
            // must not reassign somebody else's reading either way.
            boolean formDataChanged = !Objects.equals(previousFormData, values);
            // Same rule as the mobile path: keep what this save replaces, and only when it
            // really replaces something. This is the case the table was built for — a supervisor
            // reopening a delivered round and correcting an operator's reading in the browser.
            if (formDataChanged) {
                revisionService.recordSupersededValue(
                        entry, sheet, SecurityUtils.currentUserId(), ActionSource.WEB, now);
            }
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

    /**
     * What may actually be written to {@code log_sheet_entries.form_data}.
     *
     * <p>Two filters, and the second one is newer than this project's first live bug in this
     * area. {@code retainKnownKeys} drops keys the sheet's frozen schema does not define and
     * canonicalises attachment and location values. {@code answeredOnly} then drops the keys
     * that carry no answer, so an asset nobody filled stores {@code {}}.
     *
     * <p>Both callers need the second filter and for the same reason: <b>each of them submits
     * every entry of the sheet, not just the ones that changed.</b> The web fill form posts all
     * of them on every save, and a mobile submit resends everything on the device. Without this,
     * one save writes {@code {"Bar": "", "Status": ""}} onto every asset in the sheet — which is
     * precisely the damage V4 exists to repair, and which then defeated the PWA's merge.
     */
    private static Map<String, Object> storableFormData(Map<String, Object> formData,
                                                        List<FieldDefinition> fieldDefs,
                                                        Long classId) {
        List<FieldDefinition> defs = fieldDefs == null ? List.of() : fieldDefs;
        return FormDataValidationSupport.answeredOnly(
                FormDataValidationSupport.retainKnownKeys(formData, defsForClass(defs, classId)));
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

    /**
     * Whether this incoming entry would destroy a stored answer that its device never saw.
     *
     * <h2>The failure it prevents</h2>
     *
     * <p>A mobile submit resends <b>every</b> entry on the device, including assets the operator
     * never opened. So the payload for a reopened sheet carries blanks for everything somebody
     * else filled in the meantime, and {@code setFormData} used to write them: operator fills
     * three assets and syncs, supervisor reopens the sheet and fills two more in the browser,
     * supervisor reassigns it back, the operator's next submit blanks the supervisor's two. Seen
     * on log sheet 85, where the wiped rows still carry {@code entry_source = WEB} and a
     * {@code filled_by_user_id} — attribution left standing over values that are gone.
     *
     * <h2>How "never saw it" is decided</h2>
     *
     * <p>{@code created_at} and {@code updated_at} are only ever written when the answers change,
     * so together they are the entry's version. The device echoes back whatever the last bundle
     * gave it for an entry it did not touch. Therefore:
     *
     * <ul>
     *   <li><b>pair matches</b> — the device is up to date with this entry. It may blank it; that
     *       is an operator deliberately clearing a reading, and clearing has to keep working.</li>
     *   <li><b>pair differs</b> — the stored answer arrived after the device's last sync, so the
     *       blank is stale echo rather than intent. Refuse it.</li>
     * </ul>
     *
     * <p>Equality, not ordering: for an untouched entry the device is echoing the server's own
     * numbers, so no comparison of two clocks is involved and device clock skew cannot flip the
     * decision.
     *
     * <h2>What this deliberately does not cover</h2>
     *
     * <p>Only the destructive direction. When both sides hold answers the merge stays
     * last-writer-wins at <b>entry</b> level — a decision taken knowingly: field-level merging
     * would resolve that case too, and is a much larger change for a much rarer conflict.
     */
    private static boolean wouldBlankUnseenAnswer(LogSheetEntryDto dto,
                                                  LogSheetEntry entry,
                                                  Map<String, Object> incomingFormData,
                                                  boolean storedHasAnswers) {
        if (!storedHasAnswers || hasEntryFormData(incomingFormData)) {
            return false;
        }
        return !Objects.equals(dto.getCreatedAt(), entry.getCreatedAt())
                || !Objects.equals(dto.getUpdatedAt(), entry.getUpdatedAt());
    }

    /**
     * Whether an entry holds any answer.
     *
     * <p>Delegates rather than reimplementing: this was a second copy of the rule, and a second
     * copy is how the rule drifts. {@link FormDataValidationSupport#isAnswered} is the only
     * definition, and it is the one the storage paths and the PWA now agree on.
     */
    private static boolean hasEntryFormData(Map<String, Object> formData) {
        return FormDataValidationSupport.hasMeaningfulFormData(formData);
    }

    @SafeVarargs
    private static <T> T firstNonNull(T... values) {
        for (T v : values) {
            if (v != null) return v;
        }
        return null;
    }
}
