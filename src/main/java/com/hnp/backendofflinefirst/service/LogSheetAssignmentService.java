package com.hnp.backendofflinefirst.service;

import com.hnp.backendofflinefirst.domain.ActionSource;
import com.hnp.backendofflinefirst.domain.AssignmentType;
import com.hnp.backendofflinefirst.domain.LogSheetActionType;
import com.hnp.backendofflinefirst.domain.LogSheetStatus;
import com.hnp.backendofflinefirst.entity.LogSheet;
import com.hnp.backendofflinefirst.entity.User;
import com.hnp.backendofflinefirst.repository.LogSheetRepository;
import com.hnp.backendofflinefirst.repository.UserRepository;
import com.hnp.backendofflinefirst.security.SecurityUtils;
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
        int updated = logSheetRepository.releaseIfStillOpen(
                sheetId, LogSheetStatus.PENDING, OPEN_ASSIGNED_WORK, now);
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
        int updated = logSheetRepository.takeoverIfStillOpen(
                sheetId,
                supervisorId,
                AssignmentType.SUPERVISOR_ASSIGNED,
                LogSheetStatus.IN_PROGRESS,
                OPEN_FOR_OWNERSHIP_CHANGE,
                now,
                fullName(supervisorId));
        if (updated == 0) {
            throw new IllegalStateException("This log sheet cannot be taken over.");
        }
        actionLogger.record(sheetId, LogSheetActionType.TAKEOVER, source, supervisorId, from, supervisorId, now, null);
        return require(sheetId);
    }

    /**
     * Supervisor extends the completion deadline. If the sheet had already expired,
     * a future deadline reopens it (to in-progress if it has an assignee, else pending).
     */
    @Transactional
    public LogSheet extend(Long sheetId, Long actorUserId, long newDueAt, ActionSource source) {
        LogSheet sheet = require(sheetId);
        requireSupervisorOrAdmin(actorUserId, sheet);
        if (sheet.getStatus() == LogSheetStatus.SUBMITTED || sheet.getStatus() == LogSheetStatus.CANCELLED) {
            throw new IllegalStateException("This log sheet cannot be extended.");
        }
        long now = System.currentTimeMillis();
        sheet.setDueAt(newDueAt);
        if (sheet.getStatus() == LogSheetStatus.EXPIRED && newDueAt > now) {
            sheet.setStatus(sheet.getAssigneeUserId() != null ? LogSheetStatus.IN_PROGRESS : LogSheetStatus.PENDING);
            sheet.setExpiredAt(null);
        }
        sheet.setUpdatedAt(now);
        logSheetRepository.save(sheet);
        actionLogger.record(sheetId, LogSheetActionType.EXTEND, source, actorUserId, null, null, now, null);
        return sheet;
    }

    /**
     * Admin-only: reopen a submitted log sheet. A new future {@code dueAt} is required.
     * Preserves entry form data; clears final submission timestamps so the sheet can be edited again.
     * Expired sheets use {@link #extend} instead.
     */
    @Transactional
    public LogSheet adminReopenAndExtend(Long sheetId, Long adminUserId, long newDueAt, ActionSource source) {
        if (!SecurityUtils.isAdmin()) {
            throw new AccessDeniedException("Only system administrators can reopen submitted log sheets.");
        }
        LogSheet sheet = require(sheetId);
        if (sheet.getStatus() != LogSheetStatus.SUBMITTED) {
            throw new IllegalStateException("Only submitted log sheets can be reopened.");
        }
        long now = System.currentTimeMillis();
        if (newDueAt <= now) {
            throw new IllegalArgumentException("New deadline must be in the future.");
        }
        sheet.setDueAt(newDueAt);
        sheet.setStatus(sheet.getAssigneeUserId() != null ? LogSheetStatus.IN_PROGRESS : LogSheetStatus.PENDING);
        sheet.setSubmittedAt(null);
        sheet.setCompletedAt(null);
        sheet.setCompletedByUserId(null);
        sheet.setSyncedAt(null);
        sheet.setExpiredAt(null);
        sheet.setDraftSavedAt(null);
        sheet.setUpdatedAt(now);
        logSheetRepository.save(sheet);
        actionLogger.record(sheetId, LogSheetActionType.ADMIN_REOPEN, source, adminUserId, null, null, now, null);
        return sheet;
    }

    private void requireSupervisorOrAdmin(Long actorUserId, LogSheet sheet) {
        if (SecurityUtils.isAdmin()) {
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
