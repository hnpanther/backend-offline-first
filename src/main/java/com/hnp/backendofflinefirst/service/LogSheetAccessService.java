package com.hnp.backendofflinefirst.service;

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
            String userId = SecurityUtils.currentUserId();
            Set<String> unitIds = unitScopeService.getAccessibleUnitIds(userId);
            if (unitIds.isEmpty()) return List.of();
            sheets = logSheetRepository.findByOperationalUnitIdIn(unitIds);
        } else {
            sheets = logSheetRepository.findAll();
        }
        if (statusFilter != null && !statusFilter.isBlank()) {
            return sheets.stream().filter(s -> statusFilter.equals(s.getStatus())).toList();
        }
        return sheets;
    }

    public LogSheet requireVisibleLogSheet(String id) {
        LogSheet sheet = logSheetRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("لاگ شیت یافت نشد."));
        if (!canView(sheet)) {
            throw new AccessDeniedException("دسترسی به این لاگ شیت مجاز نیست.");
        }
        return sheet;
    }

    public boolean canView(LogSheet sheet) {
        if (!SecurityUtils.isUnitScopedOnly()) return true;
        if (sheet.getOperationalUnitId() == null) return false;
        return unitScopeService.canAccessUnit(SecurityUtils.currentUserId(), sheet.getOperationalUnitId());
    }

    public String resolveOperationalUnitIdForSubmit(String dtoUnitId) {
        if (dtoUnitId != null && !dtoUnitId.isBlank()) {
            if (SecurityUtils.isUnitScopedOnly()) {
                String userId = SecurityUtils.currentUserId();
                if (!unitScopeService.canAccessUnit(userId, dtoUnitId)) {
                    throw new AccessDeniedException("واحد عملیاتی انتخاب‌شده مجاز نیست.");
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
