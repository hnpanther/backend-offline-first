package com.hnp.backendofflinefirst.service;

import com.hnp.backendofflinefirst.domain.LogSheetStatus;
import com.hnp.backendofflinefirst.entity.LogSheet;
import com.hnp.backendofflinefirst.repository.LogSheetRepository;
import com.hnp.backendofflinefirst.security.SecurityUtils;
import com.hnp.backendofflinefirst.ui.WebListSupport;
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
    public List<LogSheet> findTeamOpenForSupervisor(Long supervisorId) {
        Set<Long> unitIds = unitScopeService.getSupervisorScopeUnitIds(supervisorId);
        if (unitIds.isEmpty()) return List.of();
        return logSheetRepository.findByOperationalUnitIdIn(unitIds).stream()
                .filter(s -> s.getStatus() == LogSheetStatus.ASSIGNED
                        || s.getStatus() == LogSheetStatus.IN_PROGRESS)
                .filter(s -> s.getAssigneeUserId() != null
                        && !supervisorId.equals(s.getAssigneeUserId()))
                .toList();
    }

    public LogSheet requireVisibleLogSheet(Long id) {
        LogSheet sheet = logSheetRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Log sheet not found."));
        if (!canView(sheet)) {
            throw new AccessDeniedException("Access to this log sheet is not allowed.");
        }
        return sheet;
    }

    public boolean canView(LogSheet sheet) {
        if (!SecurityUtils.isUnitScopedOnly()) return true;
        if (sheet.getOperationalUnitId() == null) return false;
        return unitScopeService.canAccessUnit(SecurityUtils.currentUserId(), sheet.getOperationalUnitId());
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
