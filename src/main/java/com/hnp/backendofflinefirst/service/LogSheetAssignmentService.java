package com.hnp.backendofflinefirst.service;

import com.hnp.backendofflinefirst.domain.ActionSource;
import com.hnp.backendofflinefirst.domain.AssignmentType;
import com.hnp.backendofflinefirst.domain.LogSheetActionType;
import com.hnp.backendofflinefirst.domain.LogSheetStatus;
import com.hnp.backendofflinefirst.entity.LogSheet;
import com.hnp.backendofflinefirst.entity.LogSheetActionLog;
import com.hnp.backendofflinefirst.entity.User;
import com.hnp.backendofflinefirst.repository.LogSheetRepository;
import com.hnp.backendofflinefirst.repository.UserRepository;
import com.hnp.backendofflinefirst.security.Capabilities;
import com.hnp.backendofflinefirst.security.SecurityUtils;
import com.hnp.backendofflinefirst.util.DateUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * The kartabl (work-inbox) engine: claim / release / assign / reassign, with the
 * authorization rules that distinguish self-claimed from supervisor-assigned work.
 * <ul>
 *   <li>claim — online only; atomic {@code UPDATE ... WHERE status = PENDING} (first request that
 *       updates a row wins).</li>
 *   <li>release / reassign / takeover — atomic conditional updates so a concurrent SUBMITTED
 *       completion cannot be overwritten by a stale in-memory save.</li>
 *   <li>assign — supervisor of the unit only, from the server app.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class LogSheetAssignmentService {

    /** Statuses that may still change ownership (not submitted/expired/cancelled). */
    static final List<LogSheetStatus> OPEN_FOR_OWNERSHIP_CHANGE = List.of(
            LogSheetStatus.PENDING,
            LogSheetStatus.ASSIGNED,
            LogSheetStatus.IN_PROGRESS);

    /** Assigned work that can be released or reassigned (not still sitting in the free pool). */
    static final List<LogSheetStatus> OPEN_ASSIGNED_WORK = List.of(
            LogSheetStatus.ASSIGNED,
            LogSheetStatus.IN_PROGRESS);

    private final LogSheetRepository logSheetRepository;
    private final OperationalUnitScopeService scopeService;
    private final LogSheetActionLogger actionLogger;
    private final UserRepository userRepository;
    private final DateUtils dateUtils;

    /**
     * Operator picks up a pending sheet themselves. Online, atomic, first-wins:
     * uses {@code UPDATE ... WHERE status = PENDING} so concurrent claims cannot
     * both succeed under READ_COMMITTED.
     */
    @Transactional
    public LogSheet claim(Long sheetId, Long actorUserId, ActionSource source) {
        LogSheet sheet = require(sheetId);
        if (!canOperateUnit(actorUserId, sheet.getOperationalUnitId())) {
            throw new AccessDeniedException("This log sheet is outside your unit scope.");
        }
        long now = System.currentTimeMillis();
        int updated = logSheetRepository.claimIfPending(
                sheetId,
                actorUserId,
                AssignmentType.SELF_CLAIMED,
                LogSheetStatus.IN_PROGRESS,
                LogSheetStatus.PENDING,
                now,
                fullName(actorUserId));
        if (updated == 0) {
            throw new IllegalStateException("This log sheet cannot be claimed.");
        }
        actionLogger.record(sheetId, LogSheetActionType.CLAIM, source, actorUserId, null, actorUserId, now, null);
        return require(sheetId);
    }

    /** Returns a sheet to the pool. Rules depend on how it was assigned. */
    @Transactional
    public LogSheet release(Long sheetId, Long actorUserId, ActionSource source) {
        LogSheet sheet = require(sheetId);
        if (sheet.getStatus() == null || sheet.getStatus().isTerminal()) {
            throw new IllegalStateException("This log sheet cannot be released.");
        }
        AssignmentType type = sheet.getAssignmentType();
        if (type == AssignmentType.SELF_CLAIMED) {
            boolean isOwner = actorUserId.equals(sheet.getAssigneeUserId());
            boolean isUnitSupervisor = scopeService.isSupervisorOf(actorUserId, sheet.getOperationalUnitId());
            if (!isOwner && !isUnitSupervisor) {
                throw new AccessDeniedException("Only the claimer can release this sheet.");
            }
        } else if (type == AssignmentType.SUPERVISOR_ASSIGNED) {
            if (!scopeService.isSupervisorOf(actorUserId, sheet.getOperationalUnitId())) {
                throw new AccessDeniedException("Only the unit supervisor can release an assigned sheet.");
            }
        } else {
            throw new IllegalStateException("This log sheet has no assignee to release.");
        }
        long now = System.currentTimeMillis();
        Long from = sheet.getAssigneeUserId();
        AssignmentType expectedType = sheet.getAssignmentType();
        int updated = logSheetRepository.releaseIfStillOpen(
                sheetId,
                LogSheetStatus.PENDING,
                OPEN_ASSIGNED_WORK,
                from,
                expectedType,
                now);
        if (updated == 0) {
            throw new IllegalStateException("This log sheet cannot be released.");
        }
        actionLogger.record(sheetId, LogSheetActionType.RELEASE, source, actorUserId, from, null, now, null);
        return require(sheetId);
    }

    /**
     * Supervisor pushes a pending sheet into a unit operator's inbox.
     * Atomic first-wins via {@code UPDATE ... WHERE status = PENDING}.
     */
    @Transactional
    public LogSheet assign(Long sheetId, Long targetOperatorId, Long supervisorId, ActionSource source) {
        LogSheet sheet = require(sheetId);
        requireSupervisorAndTarget(sheet, targetOperatorId, supervisorId);
        long now = System.currentTimeMillis();
        int updated = logSheetRepository.assignIfPending(
                sheetId,
                targetOperatorId,
                supervisorId,
                AssignmentType.SUPERVISOR_ASSIGNED,
                LogSheetStatus.ASSIGNED,
                LogSheetStatus.PENDING,
                now,
                fullName(targetOperatorId));
        if (updated == 0) {
            throw new IllegalStateException("Only unassigned pending sheets can be assigned.");
        }
        actionLogger.record(sheetId, LogSheetActionType.ASSIGN, source, supervisorId, null, targetOperatorId, now, null);
        return require(sheetId);
    }

    /** Supervisor moves an already supervisor-assigned sheet to another operator. */
    @Transactional
    public LogSheet reassign(Long sheetId, Long targetOperatorId, Long supervisorId, ActionSource source) {
        LogSheet sheet = require(sheetId);
        if (sheet.getAssignmentType() != AssignmentType.SUPERVISOR_ASSIGNED
                || sheet.getStatus() == null || sheet.getStatus().isTerminal()) {
            throw new IllegalStateException("Only supervisor-assigned in-progress sheets can be reassigned.");
        }
        requireSupervisorAndTarget(sheet, targetOperatorId, supervisorId);
        Long from = sheet.getAssigneeUserId();
        long now = System.currentTimeMillis();
        int updated = logSheetRepository.reassignIfStillOpen(
                sheetId,
                targetOperatorId,
                supervisorId,
                AssignmentType.SUPERVISOR_ASSIGNED,
                AssignmentType.SUPERVISOR_ASSIGNED,
                from,
                LogSheetStatus.ASSIGNED,
                OPEN_ASSIGNED_WORK,
                now,
                fullName(targetOperatorId));
        if (updated == 0) {
            throw new IllegalStateException("Only supervisor-assigned in-progress sheets can be reassigned.");
        }
        actionLogger.record(sheetId, LogSheetActionType.REASSIGN, source, supervisorId, from, targetOperatorId, now, null);
        return require(sheetId);
    }

    /**
     * Supervisor takes an in-progress/assigned sheet away from its operator (e.g.
     * the operator is offline and unavailable) so the supervisor can finish it. The
     * operator's later offline sync will be recorded but voided as superseded.
     */
    @Transactional
    public LogSheet takeover(Long sheetId, Long supervisorId, ActionSource source) {
        LogSheet sheet = require(sheetId);
        if (sheet.getStatus() == null || sheet.getStatus().isTerminal()) {
            throw new IllegalStateException("This log sheet cannot be taken over.");
        }
        if (!scopeService.isSupervisorOf(supervisorId, sheet.getOperationalUnitId())) {
            throw new AccessDeniedException("You are not the supervisor of this unit.");
        }
        long now = System.currentTimeMillis();
        Long from = sheet.getAssigneeUserId();
        AssignmentType expectedType = sheet.getAssignmentType();
        int updated = logSheetRepository.takeoverIfStillOpen(
                sheetId,
                supervisorId,
                AssignmentType.SUPERVISOR_ASSIGNED,
                LogSheetStatus.IN_PROGRESS,
                OPEN_FOR_OWNERSHIP_CHANGE,
                from,
                expectedType,
                now,
                fullName(supervisorId));
        if (updated == 0) {
            throw new IllegalStateException("This log sheet cannot be taken over.");
        }
        actionLogger.record(sheetId, LogSheetActionType.TAKEOVER, source, supervisorId, from, supervisorId, now, null);
        return require(sheetId);
    }

    /**
     * Supervisor extends the completion deadline. If the sheet had already expired
     * or been cancelled, a future deadline reopens it (to in-progress if it has an
     * assignee, else pending) — the same lever used to bring back an EXPIRED sheet
     * now also un-cancels one.
     */
    @Transactional
    public LogSheet extend(Long sheetId, Long actorUserId, long newDueAt, ActionSource source) {
        return extend(sheetId, actorUserId, newDueAt, source, null);
    }

    /** @param comment optional reason recorded in the action history; the action succeeds without it. */
    @Transactional
    public LogSheet extend(Long sheetId, Long actorUserId, long newDueAt, ActionSource source, String comment) {
        String reason = normalizeComment(comment);
        LogSheet sheet = require(sheetId);
        requireSupervisorOrAdmin(actorUserId, sheet);
        // `isCompleted()` covers APPROVED as well. Extending a finished round has never been the
        // lever — `reopen` is — and an approved one is finished twice over.
        if ((sheet.getStatus() != null && sheet.getStatus().isCompleted())
                || sheet.getStatus() == LogSheetStatus.VOIDED) {
            throw new IllegalStateException("This log sheet cannot be extended.");
        }
        long now = System.currentTimeMillis();
        DateUtils.requireFutureWithinYears(newDueAt, now, "New deadline");
        Long previousDueAt = sheet.getDueAt();
        sheet.setDueAt(newDueAt);
        if (sheet.getStatus() == LogSheetStatus.EXPIRED && newDueAt > now) {
            sheet.setStatus(sheet.getAssigneeUserId() != null ? LogSheetStatus.IN_PROGRESS : LogSheetStatus.PENDING);
            sheet.setExpiredAt(null);
        } else if (sheet.getStatus() == LogSheetStatus.CANCELLED && newDueAt > now) {
            sheet.setStatus(sheet.getAssigneeUserId() != null ? LogSheetStatus.IN_PROGRESS : LogSheetStatus.PENDING);
            sheet.setCancelledAt(null);
        }
        sheet.setUpdatedAt(now);
        logSheetRepository.save(sheet);
        actionLogger.record(sheetId, LogSheetActionType.EXTEND, source, actorUserId, null, null, now, null,
                withDeadlineChange(previousDueAt, newDueAt, reason));
        return sheet;
    }

    /**
     * Supervisor cancels a sheet that is still open — before it has been completed
     * or has expired. Reopen later via {@link #extend} with a new future due date.
     */
    @Transactional
    public LogSheet cancel(Long sheetId, Long actorUserId, ActionSource source) {
        return cancel(sheetId, actorUserId, source, null);
    }

    /** @param comment optional reason recorded in the action history; the action succeeds without it. */
    @Transactional
    public LogSheet cancel(Long sheetId, Long actorUserId, ActionSource source, String comment) {
        String reason = normalizeComment(comment);
        LogSheet sheet = require(sheetId);
        requireSupervisorOrAdmin(actorUserId, sheet);
        if (sheet.getStatus() == null || !OPEN_FOR_OWNERSHIP_CHANGE.contains(sheet.getStatus())) {
            throw new IllegalStateException("Only pending, assigned, or in-progress log sheets can be cancelled.");
        }
        long now = System.currentTimeMillis();
        sheet.setStatus(LogSheetStatus.CANCELLED);
        sheet.setCancelledAt(now);
        sheet.setUpdatedAt(now);
        logSheetRepository.save(sheet);
        actionLogger.record(sheetId, LogSheetActionType.CANCEL, source, actorUserId, null, null, now, null, reason);
        return sheet;
    }

    /**
     * Soft-void a submitted sheet so its readings are excluded from parameter reports.
     * Entry data and completion timestamps are preserved; only status changes to {@link LogSheetStatus#VOIDED}.
     * Allowed for system admin or the supervisor of the sheet's operational unit.
     */
    @Transactional
    public LogSheet voidSubmitted(Long sheetId, Long actorUserId, ActionSource source) {
        return voidSubmitted(sheetId, actorUserId, source, null);
    }

    /** @param comment optional reason recorded in the action history; the action succeeds without it. */
    @Transactional
    public LogSheet voidSubmitted(Long sheetId, Long actorUserId, ActionSource source, String comment) {
        String reason = normalizeComment(comment);
        LogSheet sheet = require(sheetId);
        requireSupervisorOrAdmin(actorUserId, sheet);
        if (sheet.getStatus() != LogSheetStatus.SUBMITTED) {
            throw new IllegalStateException("Only submitted log sheets can be voided.");
        }
        long now = System.currentTimeMillis();
        sheet.setStatus(LogSheetStatus.VOIDED);
        sheet.setUpdatedAt(now);
        logSheetRepository.save(sheet);
        // Deliberately does NOT touch asset status. Since the request workflow arrived, an
        // asset's status only ever moves when a supervisor approves or undoes a request; the
        // requests this sheet raised stay in the queue showing that their sheet was voided, and
        // the supervisor decides with that in front of them. Reverting here as well would give
        // two mechanisms for one column and would break the "only the newest request may be
        // undone" rule from behind — see AssetStatusRequestService.
        actionLogger.record(sheetId, LogSheetActionType.VOID, source, actorUserId, null, null, now, null, reason);
        return sheet;
    }

    /**
     * Restore a voided sheet back to {@link LogSheetStatus#SUBMITTED} (reportable again).
     * Does not reopen for editing — that is {@link #reopenSubmittedWithExtend}.
     */
    @Transactional
    public LogSheet restoreVoided(Long sheetId, Long actorUserId, ActionSource source) {
        return restoreVoided(sheetId, actorUserId, source, null);
    }

    /** @param comment optional reason recorded in the action history; the action succeeds without it. */
    @Transactional
    public LogSheet restoreVoided(Long sheetId, Long actorUserId, ActionSource source, String comment) {
        String reason = normalizeComment(comment);
        LogSheet sheet = require(sheetId);
        requireSupervisorOrAdmin(actorUserId, sheet);
        if (sheet.getStatus() != LogSheetStatus.VOIDED) {
            throw new IllegalStateException("Only voided log sheets can be restored to submitted.");
        }
        long now = System.currentTimeMillis();
        sheet.setStatus(LogSheetStatus.SUBMITTED);
        sheet.setUpdatedAt(now);
        logSheetRepository.save(sheet);
        // No asset status change here either. The requests this sheet raised were never
        // withdrawn when it was voided, so there is nothing to re-raise; if the supervisor
        // already decided them, that decision stands until they change it themselves.
        actionLogger.record(sheetId, LogSheetActionType.UNVOID, source, actorUserId, null, null, now, null, reason);
        return sheet;
    }

    /**
     * A supervisor has read the completed round and accepts it.
     *
     * <p><b>Approval is a review laid on top of completion, not a different kind of completion.</b>
     * Nothing about the readings changes, no asset status moves, and every report, export and
     * external feed counts an approved round exactly as it counts an unapproved one — which is
     * why conditions about completed work ask {@link LogSheetStatus#COMPLETED_STATUSES} rather
     * than naming a status. The distinction exists so a plant can tell reviewed work from
     * unreviewed work, and so that later some reports can separate them; it is not a claim that
     * unapproved readings are less real.
     *
     * <p><b>The approver may be the person who completed the round.</b> A deliberate decision by
     * the plant: on a small shift the supervisor often walks the round themselves, and refusing
     * would leave those sheets permanently unapprovable. If segregation of duties is ever wanted
     * it belongs here, as an explicit rule with its own reason.
     *
     * <p>Reachable only from {@code SUBMITTED}, and leaving only back to it. A voided or expired
     * round is not a completed one; a round still being walked has nothing to review yet.
     *
     * @param comment optional reason recorded in the action history; the action succeeds without it
     */
    @Transactional
    public LogSheet approve(Long sheetId, Long actorUserId, ActionSource source, String comment) {
        String reason = normalizeComment(comment);
        LogSheet sheet = require(sheetId);
        requireSupervisorOrAdmin(actorUserId, sheet);
        if (sheet.getStatus() != LogSheetStatus.SUBMITTED) {
            throw new IllegalStateException("Only submitted log sheets can be approved.");
        }
        long now = System.currentTimeMillis();
        sheet.setStatus(LogSheetStatus.APPROVED);
        sheet.setApprovedAt(now);
        sheet.setApprovedByUserId(actorUserId);
        sheet.setUpdatedAt(now);
        logSheetRepository.save(sheet);
        actionLogger.record(sheetId, LogSheetActionType.APPROVE, source, actorUserId, null, null, now, null, reason);
        return sheet;
    }

    /**
     * Withdraws an approval, returning the round to {@code SUBMITTED}.
     *
     * <p>The only way out of {@code APPROVED}. Voiding and reopening both deliberately refuse an
     * approved round: a supervisor who wants to invalidate or correct one has to withdraw their
     * approval first, which makes the sequence visible in the action log instead of letting a
     * reviewed round quietly become an unreviewed one. Same shape as
     * {@link #restoreVoided} — one door in, one door out.
     *
     * <p>{@code approved_at} and {@code approved_by_user_id} are cleared with the status, so the
     * column and the status can never disagree about whether a review stands. The action log
     * keeps the history of who approved and who withdrew.
     *
     * @param comment optional reason recorded in the action history; the action succeeds without it
     */
    @Transactional
    public LogSheet unapprove(Long sheetId, Long actorUserId, ActionSource source, String comment) {
        String reason = normalizeComment(comment);
        LogSheet sheet = require(sheetId);
        requireSupervisorOrAdmin(actorUserId, sheet);
        if (sheet.getStatus() != LogSheetStatus.APPROVED) {
            throw new IllegalStateException("Only approved log sheets can have their approval withdrawn.");
        }
        long now = System.currentTimeMillis();
        sheet.setStatus(LogSheetStatus.SUBMITTED);
        sheet.setApprovedAt(null);
        sheet.setApprovedByUserId(null);
        sheet.setUpdatedAt(now);
        logSheetRepository.save(sheet);
        actionLogger.record(sheetId, LogSheetActionType.UNAPPROVE, source, actorUserId, null, null, now, null, reason);
        return sheet;
    }

    /**
     * Reopen a submitted log sheet for further editing (returns to IN_PROGRESS or PENDING)
     * with a new future deadline. Preserves entry form data; clears final submission timestamps.
     * Allowed for system admin or the supervisor of the sheet's unit.
     * Voided sheets must be restored to SUBMITTED first via {@link #restoreVoided}.
     * Expired sheets use {@link #extend} instead.
     */
    @Transactional
    public LogSheet reopenSubmittedWithExtend(Long sheetId, Long actorUserId, long newDueAt, ActionSource source) {
        return reopenSubmittedWithExtend(sheetId, actorUserId, newDueAt, source, null);
    }

    /** @param comment optional reason recorded in the action history; the action succeeds without it. */
    @Transactional
    public LogSheet reopenSubmittedWithExtend(Long sheetId, Long actorUserId, long newDueAt,
                                              ActionSource source, String comment) {
        String reason = normalizeComment(comment);
        LogSheet sheet = require(sheetId);
        requireSupervisorOrAdmin(actorUserId, sheet);
        if (sheet.getStatus() != LogSheetStatus.SUBMITTED) {
            throw new IllegalStateException("Only submitted log sheets can be reopened.");
        }
        long now = System.currentTimeMillis();
        DateUtils.requireFutureWithinYears(newDueAt, now, "New deadline");
        Long previousDueAt = sheet.getDueAt();
        sheet.setDueAt(newDueAt);
        sheet.setStatus(sheet.getAssigneeUserId() != null ? LogSheetStatus.IN_PROGRESS : LogSheetStatus.PENDING);
        sheet.setSubmittedAt(null);
        sheet.setCompletedAt(null);
        sheet.setCompletedByUserId(null);
        sheet.setSyncedAt(null);
        sheet.setExpiredAt(null);
        sheet.setDraftSavedAt(null);
        // The attribution goes with the timestamp. Leaving "saved by X from the web panel"
        // behind a cleared draft would have the sheet's page name somebody for a save that no
        // longer exists — the same defect as attribution standing over a wiped reading.
        sheet.setDraftSavedByUserId(null);
        sheet.setDraftSource(null);
        sheet.setUpdatedAt(now);
        logSheetRepository.save(sheet);
        // Again, no asset status change. Re-completing the sheet will raise a fresh request if
        // the corrected reading differs from what the asset holds by then.
        actionLogger.record(sheetId, LogSheetActionType.ADMIN_REOPEN, source, actorUserId, null, null, now, null,
                withDeadlineChange(previousDueAt, newDueAt, reason));
        return sheet;
    }

    /**
     * Trims an optional action comment to null and rejects one longer than the column allows.
     *
     * <p>Blank is a first-class answer: the user chose not to explain, which must never block the
     * action. Rejecting an over-long comment (rather than silently truncating) keeps the actor's
     * words intact — a truncated reason reads as a complete one and quietly misleads the next reader.
     * The textarea carries the same {@code maxlength}, so this is the server-side backstop.
     */
    /**
     * Puts the deadline change itself on the first line of the action's comment.
     *
     * <p><b>Why the comment and not a column.</b> The action log already answers "who extended
     * this and why"; what it could not answer is "from when to when". Two new columns would carry
     * that for the two actions that move a deadline and be null on the other fourteen, and every
     * reader — the history panel, the «دلایل اقدامات» report, an export — would need teaching
     * about them. One line of text at the top of a field that already exists, already renders and
     * is already read costs nothing and is legible everywhere without a change.
     *
     * <p><b>The supervisor's own words start on the next line</b>, so the two never run together
     * and the generated part is visually a header rather than part of what they wrote.
     *
     * <p>Jalali, in Persian, formatted exactly as every date in the panel is — the line is stored
     * data read by people, not a machine field, and a Gregorian epoch in the middle of a Persian
     * history would be unreadable. Built here rather than in the controller so the text is
     * identical whichever surface triggered the action.
     *
     * <p>A sheet with no previous deadline says so rather than printing a dash and leaving the
     * reader to guess whether the date was missing or the formatting failed.
     */
    private String withDeadlineChange(Long previousDueAt, long newDueAt, String comment) {
        String header = previousDueAt == null
                ? "مهلت تکمیل تعیین شد: " + dateUtils.format(newDueAt)
                : "مهلت تکمیل از " + dateUtils.format(previousDueAt)
                        + " به " + dateUtils.format(newDueAt) + " تغییر کرد.";
        String combined = comment == null || comment.isBlank()
                ? header
                : header + System.lineSeparator() + comment;
        // The column is VARCHAR(1000) and the supervisor's part was already checked against that
        // ceiling, so the header can push a maximum-length comment over it. Trimming the header
        // would lose the fact the line exists to record; trimming the tail of what somebody wrote
        // is visible to them and recoverable. Neither happens in practice — the header is about
        // seventy characters — but a 500 error on a save is not an acceptable way to find out.
        if (combined.length() > LogSheetActionLog.MAX_COMMENT_LENGTH) {
            combined = combined.substring(0, LogSheetActionLog.MAX_COMMENT_LENGTH - 1) + "…";
        }
        return combined;
    }

    private static String normalizeComment(String comment) {
        if (comment == null || comment.isBlank()) {
            return null;
        }
        String trimmed = comment.trim();
        if (trimmed.length() > LogSheetActionLog.MAX_COMMENT_LENGTH) {
            throw new IllegalArgumentException("Action comment is too long.");
        }
        return trimmed;
    }

    private void requireSupervisorOrAdmin(Long actorUserId, LogSheet sheet) {
        if (SecurityUtils.hasCapability(Capabilities.SUPERVISE_ANY_UNIT)) {
            return;
        }
        if (!scopeService.isSupervisorOf(actorUserId, sheet.getOperationalUnitId())) {
            throw new AccessDeniedException("You are not the supervisor of this unit.");
        }
    }

    private void requireSupervisorAndTarget(LogSheet sheet, Long targetOperatorId, Long supervisorId) {
        Long unitId = sheet.getOperationalUnitId();
        if (!scopeService.isSupervisorOf(supervisorId, unitId)) {
            throw new AccessDeniedException("You are not the supervisor of this unit.");
        }
        if (!scopeService.isOperatorOf(targetOperatorId, unitId)) {
            throw new IllegalArgumentException("Target user is not an operator of this unit.");
        }
    }

    private boolean canOperateUnit(Long userId, Long unitId) {
        return scopeService.isOperatorOf(userId, unitId) || scopeService.isSupervisorOf(userId, unitId);
    }

    private String fullName(Long userId) {
        return userRepository.findById(userId).map(User::getFullName).orElse(null);
    }

    private LogSheet require(Long sheetId) {
        return logSheetRepository.findById(sheetId)
                .orElseThrow(() -> new IllegalArgumentException("Log sheet not found."));
    }
}
