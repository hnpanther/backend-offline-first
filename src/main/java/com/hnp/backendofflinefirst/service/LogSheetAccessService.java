package com.hnp.backendofflinefirst.service;

import com.hnp.backendofflinefirst.domain.LogSheetStatus;
import com.hnp.backendofflinefirst.entity.LogSheet;
import com.hnp.backendofflinefirst.repository.LogSheetRepository;
import com.hnp.backendofflinefirst.security.SecurityUtils;
import com.hnp.backendofflinefirst.ui.WebListSupport;
import com.hnp.backendofflinefirst.security.Capabilities;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Enforces operational-unit scope on log sheet list/detail for USER role. */
@Service
@RequiredArgsConstructor
public class LogSheetAccessService {

    private final LogSheetRepository logSheetRepository;
    private final OperationalUnitScopeService unitScopeService;

    public List<LogSheet> findVisibleLogSheets(String statusFilter) {
        return findVisibleLogSheets(statusFilter, null,
                WebListSupport.pageable(0, Integer.MAX_VALUE)).getContent();
    }

    public Page<LogSheet> findVisibleLogSheets(String statusFilter, String q, Pageable pageable) {
        Collection<Long> unitIds = visibleUnitIdsOrNull();
        if (unitIds != null && unitIds.isEmpty()) {
            return Page.empty(pageable);
        }
        LogSheetStatus status = statusFilter != null && !statusFilter.isBlank()
                ? LogSheetStatus.fromNullable(statusFilter) : null;
        return WebListSupport.hasSearch(q)
                ? logSheetRepository.searchVisibleWithTerm(unitIds, status, WebListSupport.searchTerm(q), pageable)
                : logSheetRepository.searchVisible(unitIds, status, pageable);
    }

    public Map<String, Long> countVisibleByStatus() {
        Collection<Long> unitIds = visibleUnitIdsOrNull();
        if (unitIds != null && unitIds.isEmpty()) {
            return Map.of();
        }
        Map<String, Long> out = new LinkedHashMap<>();
        for (Object[] row : logSheetRepository.countGroupedByStatus(unitIds)) {
            LogSheetStatus status = (LogSheetStatus) row[0];
            String key = status == null ? com.hnp.backendofflinefirst.ui.FaMessages.UNKNOWN : status.name();
            out.put(key, (Long) row[1]);
        }
        return out;
    }

    public Map<String, Long> countVisibleByTemplateName() {
        Collection<Long> unitIds = visibleUnitIdsOrNull();
        if (unitIds != null && unitIds.isEmpty()) {
            return Map.of();
        }
        Map<String, Long> out = new LinkedHashMap<>();
        for (Object[] row : logSheetRepository.countGroupedByTemplateName(unitIds)) {
            out.put((String) row[0], (Long) row[1]);
        }
        return out;
    }

    public long countVisible() {
        Collection<Long> unitIds = visibleUnitIdsOrNull();
        if (unitIds != null && unitIds.isEmpty()) {
            return 0L;
        }
        return logSheetRepository.countVisible(unitIds);
    }

    /** Sheets currently assigned to the user and still open (their inbox). */
    public List<LogSheet> findAssignedTo(Long userId) {
        return logSheetRepository.findByAssigneeUserId(userId).stream()
                .filter(s -> s.getStatus() == LogSheetStatus.ASSIGNED
                        || s.getStatus() == LogSheetStatus.IN_PROGRESS)
                .toList();
    }

    /** Pending, unassigned sheets in the user's accessible units (the pick-up pool). */
    public List<LogSheet> findAvailablePool(Long userId) {
        Set<Long> unitIds = unitScopeService.getAccessibleUnitIds(userId);
        if (unitIds.isEmpty()) return List.of();
        return logSheetRepository.findByOperationalUnitIdInAndStatus(unitIds, LogSheetStatus.PENDING);
    }

    /**
     * Open sheets in supervised units that are assigned to someone other than the
     * supervisor (for mobile release / reassign while online).
     */
    /** The statuses that make a sheet "still open" for the supervisor's team list. */
    private static final Set<LogSheetStatus> TEAM_OPEN_STATUSES =
            Set.of(LogSheetStatus.ASSIGNED, LogSheetStatus.IN_PROGRESS);

    public List<LogSheet> findTeamOpenForSupervisor(Long supervisorId) {
        Set<Long> unitIds = unitScopeService.getSupervisorScopeUnitIds(supervisorId);
        if (unitIds.isEmpty()) return List.of();
        return logSheetRepository.findOpenInUnitsAssignedToOthers(
                unitIds, TEAM_OPEN_STATUSES, supervisorId);
    }

    public LogSheet requireVisibleLogSheet(Long id) {
        LogSheet sheet = logSheetRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Log sheet not found."));
        if (!canView(sheet)) {
            throw new AccessDeniedException("Access to this log sheet is not allowed.");
        }
        return sheet;
    }

    /**
     * The sheet, refused unless this actor may <b>change</b> it — not merely see it.
     *
     * <h2>Why "visible" was the wrong question for a write</h2>
     *
     * <p>{@link #canView} is unit-scoped: an operator sees every sheet in their operational unit,
     * which is what the inbox and the lists are built on. Readings never used it as a write gate —
     * only the current assignee may complete a sheet, and a submission from anyone else is stored
     * as {@code SUPERSEDED} rather than applied. Attachments did, and the difference was a real
     * gap: one operator could add a photograph to, or delete one from, a colleague's round in the
     * same unit, on either surface.
     *
     * <p>What makes it easy to miss is that a test appeared to cover it.
     * {@code stopsAllowingItTheMomentTheSheetLeavesTheirHands} asserts an upload turns 403 the
     * moment the assignee is cleared — but it first removes the operator from every unit, so
     * {@code canView} was falling back to the assignee check for want of anything else. With unit
     * membership intact, which is the normal case, it never refused.
     *
     * <h2>The rule</h2>
     *
     * <p>Assignee, or {@link Capabilities#LOGSHEET_COMPLETE_WEB_ANY}. Deliberately <b>wider</b>
     * than {@code LogSheetWebCompletionAccess.canCompleteOnWeb}, and every case that one admits is
     * a case this one admits too — the capability outright, and its other two branches both
     * require the actor to be the assignee. So the web fill page cannot lose a path it had; the
     * controller still applies its own, narrower rule on top.
     *
     * <p>A sheet in the pool has no assignee, so only the capability opens it. That matches what
     * filling already does: the web form refuses an unassigned sheet without the capability, and
     * a tablet claims before it captures.
     */
    public LogSheet requireWritableLogSheet(Long id) {
        LogSheet sheet = requireVisibleLogSheet(id);
        if (!canWrite(sheet)) {
            throw new AccessDeniedException("This log sheet is not yours to change.");
        }
        return sheet;
    }

    /** @see #requireWritableLogSheet */
    public boolean canWrite(LogSheet sheet) {
        if (sheet == null) {
            return false;
        }
        if (SecurityUtils.hasCapability(Capabilities.LOGSHEET_COMPLETE_WEB_ANY)) {
            return true;
        }
        return isOwnWork(sheet);
    }

    public boolean canView(LogSheet sheet) {
        if (!SecurityUtils.isUnitScopedOnly()) return true;
        if (isOwnWork(sheet)) return true;
        if (sheet.getOperationalUnitId() == null) return false;
        return unitScopeService.canAccessUnit(SecurityUtils.currentUserId(), sheet.getOperationalUnitId());
    }

    /**
     * The sheet this caller is personally holding.
     *
     * <p><b>Why this branch exists.</b> One action from an operator's point of view — "deliver
     * the work I did" — used to be judged by two different rules. {@code submitOne} asks only
     * whether the caller <em>is the assignee</em>; everything else went through unit scope. So
     * moving somebody between operational units while they were offline produced a round that
     * arrived complete and silently lost half of itself: the readings were accepted, and the
     * photographs (403), the NFC fault reports (refused), the bundle refresh and even the
     * sheet's own page in the panel were not. Nothing warned either side.
     *
     * <p>Removing somebody from a unit does not touch {@code assignee_user_id} and does not
     * touch their roles, so the submit path was right and the rest was inconsistent with it.
     * This is the same rule, written once, where every object-level check already passes.
     *
     * <p><b>The blast radius is one row and one person.</b> {@code assignee_user_id} is server
     * data, never a client parameter, and {@code release} / {@code reassign} / {@code takeover}
     * revoke it the instant ownership moves — a former assignee is refused again immediately.
     *
     * <p>Deliberately <b>not</b> applied to the list queries ({@link #visibleUnitIdsOrNull()}):
     * those are unit-scoped SQL, and a sheet in a unit the user no longer belongs to still does
     * not belong in that unit's listing. Their own work reaches them through «کارتابل من»
     * ({@code findAssignedTo}, which has never had a unit filter) and through the mobile inbox.
     */
    private boolean isOwnWork(LogSheet sheet) {
        Long userId = SecurityUtils.currentUserId();
        return userId != null && userId.equals(sheet.getAssigneeUserId());
    }

    public Long resolveOperationalUnitIdForSubmit(Long dtoUnitId) {
        if (dtoUnitId != null) {
            if (SecurityUtils.isUnitScopedOnly()) {
                Long userId = SecurityUtils.currentUserId();
                if (!unitScopeService.canAccessUnit(userId, dtoUnitId)) {
                    throw new AccessDeniedException("Selected operational unit is not allowed.");
                }
            }
            return dtoUnitId;
        }
        if (SecurityUtils.isUnitScopedOnly()) {
            return unitScopeService.getPrimaryUnitId(SecurityUtils.currentUserId());
        }
        return null;
    }

    /**
     * {@code null} = unrestricted; empty = no access; otherwise unit id set.
     */
    private Collection<Long> visibleUnitIdsOrNull() {
        if (!SecurityUtils.isUnitScopedOnly()) {
            return null;
        }
        return unitScopeService.getAccessibleUnitIds(SecurityUtils.currentUserId());
    }
}
