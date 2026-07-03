package com.hnp.backendofflinefirst.service;

import com.hnp.backendofflinefirst.entity.LogSheet;
import com.hnp.backendofflinefirst.security.SecurityUtils;
import org.springframework.stereotype.Component;

/** Who may open the web fill form / complete a log sheet in the browser. */
@Component("logSheetWeb")
public class LogSheetWebCompletionAccess {

    public static final String ROLE_SENIOR_OPERATOR = "SENIOR_OPERATOR";

    private final OperationalUnitScopeService scopeService;

    public LogSheetWebCompletionAccess(OperationalUnitScopeService scopeService) {
        this.scopeService = scopeService;
    }

    public boolean canCompleteOnWeb(LogSheet sheet) {
        if (sheet == null) {
            return false;
        }
        if (SecurityUtils.isAdmin()) {
            return true;
        }
        Long userId = SecurityUtils.currentUserId();
        if (userId == null || !userId.equals(sheet.getAssigneeUserId())) {
            return false;
        }
        if (SecurityUtils.hasRole(ROLE_SENIOR_OPERATOR)) {
            return true;
        }
        return scopeService.isSupervisorOf(userId, sheet.getOperationalUnitId());
    }

    /** Assignee operator without web completion (mobile app only). */
    public boolean isMobileOnlyAssignee(LogSheet sheet) {
        if (sheet == null || canCompleteOnWeb(sheet)) {
            return false;
        }
        Long userId = SecurityUtils.currentUserId();
        return userId != null
                && userId.equals(sheet.getAssigneeUserId())
                && scopeService.isOperatorOf(userId, sheet.getOperationalUnitId());
    }
}
