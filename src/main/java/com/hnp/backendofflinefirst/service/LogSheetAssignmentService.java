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

/**
 * The kartabl (work-inbox) engine: claim / release / assign / reassign, with the
 * authorization rules that distinguish self-claimed from supervisor-assigned work.
 * <ul>
 *   <li>claim — online only; first request to reach the server wins.</li>
 *   <li>release — self-claimed: only the assignee; supervisor-assigned: only a
 *       supervisor of the unit.</li>
 *   <li>assign / reassign — supervisor of the unit only, from the server app.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class LogSheetAssignmentService {

    private final LogSheetRepository logSheetRepository;
    private final OperationalUnitScopeService scopeService;
    private final LogSheetActionLogger actionLogger;
    private final UserRepository userRepository;

    /** Operator picks up a pending sheet themselves. Online, atomic, first-wins. */
    @Transactional
    public LogSheet claim(Long sheetId, Long actorUserId, ActionSource source) {
        LogSheet sheet = require(sheetId);
        if (sheet.getStatus() != LogSheetStatus.PENDING) {
            throw new IllegalStateException("This log sheet cannot be claimed.");
        }
        if (!canOperateUnit(actorUserId, sheet.getOperationalUnitId())) {
            throw new AccessDeniedException("This log sheet is outside your unit scope.");
        }
        long now = System.currentTimeMillis();
        sheet.setAssigneeUserId(actorUserId);
        sheet.setAssignmentType(AssignmentType.SELF_CLAIMED);
        sheet.setAssignedByUserId(null);
        sheet.setStatus(LogSheetStatus.IN_PROGRESS);
        sheet.setClaimedAt(now);
        sheet.setStartedAt(now);
        sheet.setOperatorName(fullName(actorUserId));
        sheet.setUpdatedAt(now);
        logSheetRepository.save(sheet);
        actionLogger.record(sheetId, LogSheetActionType.CLAIM, source, actorUserId, null, actorUserId, now, null);
        return sheet;
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
            if (!actorUserId.equals(sheet.getAssigneeUserId())) {
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
        clearAssignment(sheet, now);
        logSheetRepository.save(sheet);
        actionLogger.record(sheetId, LogSheetActionType.RELEASE, source, actorUserId, from, null, now, null);
        return sheet;
    }

    /** Supervisor pushes a pending sheet into a unit operator's inbox. */
    @Transactional
    public LogSheet assign(Long sheetId, Long targetOperatorId, Long supervisorId, ActionSource source) {
        LogSheet sheet = require(sheetId);
        if (sheet.getStatus() != LogSheetStatus.PENDING) {
            throw new IllegalStateException("Only unassigned pending sheets can be assigned.");
        }
        requireSupervisorAndTarget(sheet, targetOperatorId, supervisorId);
        applyAssignment(sheet, targetOperatorId, supervisorId, LogSheetActionType.ASSIGN, source, null);
        return sheet;
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
        applyAssignment(sheet, targetOperatorId, supervisorId, LogSheetActionType.REASSIGN, source, from);
        return sheet;
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
        sheet.setAssigneeUserId(supervisorId);
        sheet.setAssignmentType(AssignmentType.SUPERVISOR_ASSIGNED);
        sheet.setAssignedByUserId(supervisorId);
        sheet.setStatus(LogSheetStatus.IN_PROGRESS);
        sheet.setClaimedAt(now);
        sheet.setStartedAt(now);
        sheet.setOperatorName(fullName(supervisorId));
        sheet.setUpdatedAt(now);
        logSheetRepository.save(sheet);
        actionLogger.record(sheetId, LogSheetActionType.TAKEOVER, source, supervisorId, from, supervisorId, now, null);
        return sheet;
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
     * Admin-only: reopen a finalized or expired log sheet with a new completion deadline.
     * Preserves entry form data; clears final submission timestamps so the sheet can be completed again.
     */
    @Transactional
    public LogSheet adminReopenAndExtend(Long sheetId, Long adminUserId, long newDueAt, ActionSource source) {
        if (!SecurityUtils.isAdmin()) {
            throw new AccessDeniedException("Only system administrators can reopen finalized log sheets.");
        }
        LogSheet sheet = require(sheetId);
        if (sheet.getStatus() != LogSheetStatus.SUBMITTED && sheet.getStatus() != LogSheetStatus.EXPIRED) {
            throw new IllegalStateException("Only finalized or expired log sheets can be reopened.");
        }
        long now = System.currentTimeMillis();
        if (newDueAt <= now) {
            throw new IllegalArgumentException("New deadline must be in the future.");
        }
        sheet.setDueAt(newDueAt);
        sheet.setStatus(sheet.getAssigneeUserId() != null ? LogSheetStatus.IN_PROGRESS : LogSheetStatus.PENDING);
        sheet.setSubmittedAt(null);
        sheet.setCompletedAt(null);
        sheet.setSyncedAt(null);
        sheet.setExpiredAt(null);
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

    private void applyAssignment(LogSheet sheet, Long targetOperatorId, Long supervisorId,
                                 LogSheetActionType action, ActionSource source, Long fromUserId) {
        long now = System.currentTimeMillis();
        sheet.setAssigneeUserId(targetOperatorId);
        sheet.setAssignmentType(AssignmentType.SUPERVISOR_ASSIGNED);
        sheet.setAssignedByUserId(supervisorId);
        sheet.setStatus(LogSheetStatus.ASSIGNED);
        sheet.setAssignedAt(now);
        sheet.setClaimedAt(null);
        sheet.setStartedAt(null);
        sheet.setOperatorName(fullName(targetOperatorId));
        sheet.setUpdatedAt(now);
        logSheetRepository.save(sheet);
        actionLogger.record(sheet.getId(), action, source, supervisorId, fromUserId, targetOperatorId, now, null);
    }

    private void clearAssignment(LogSheet sheet, long now) {
        sheet.setAssigneeUserId(null);
        sheet.setAssignmentType(null);
        sheet.setAssignedByUserId(null);
        sheet.setStatus(LogSheetStatus.PENDING);
        sheet.setAssignedAt(null);
        sheet.setClaimedAt(null);
        sheet.setStartedAt(null);
        sheet.setOperatorName(null);
        sheet.setUpdatedAt(now);
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
