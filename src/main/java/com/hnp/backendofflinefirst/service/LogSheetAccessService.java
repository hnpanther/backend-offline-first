package com.hnp.backendofflinefirst.service;

import com.hnp.backendofflinefirst.domain.LogSheetStatus;
import com.hnp.backendofflinefirst.entity.LogSheet;
import com.hnp.backendofflinefirst.repository.LogSheetRepository;
import com.hnp.backendofflinefirst.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

/** Enforces operational-unit scope on log sheet list/detail for USER role. */
@Service
@RequiredArgsConstructor
public class LogSheetAccessService {

    private final LogSheetRepository logSheetRepository;
    private final OperationalUnitScopeService unitScopeService;

    public List<LogSheet> findVisibleLogSheets(String statusFilter) {
        List<LogSheet> sheets;
        if (SecurityUtils.isUnitScopedOnly()) {
            Long userId = SecurityUtils.currentUserId();
            Set<Long> unitIds = unitScopeService.getAccessibleUnitIds(userId);
            if (unitIds.isEmpty()) return List.of();
            sheets = logSheetRepository.findByOperationalUnitIdInOrderByIdDesc(unitIds);
        } else {
            sheets = logSheetRepository.findAllByOrderByIdDesc();
        }
        if (statusFilter != null && !statusFilter.isBlank()) {
            LogSheetStatus want = LogSheetStatus.fromNullable(statusFilter);
            return sheets.stream().filter(s -> s.getStatus() == want).toList();
        }
        return sheets;
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
}
