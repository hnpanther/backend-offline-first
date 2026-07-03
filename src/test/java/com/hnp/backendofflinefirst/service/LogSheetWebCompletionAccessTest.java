package com.hnp.backendofflinefirst.service;

import com.hnp.backendofflinefirst.domain.LogSheetStatus;
import com.hnp.backendofflinefirst.entity.LogSheet;
import com.hnp.backendofflinefirst.entity.User;
import com.hnp.backendofflinefirst.security.AppUserDetails;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LogSheetWebCompletionAccessTest {

    @Mock OperationalUnitScopeService scopeService;

    @InjectMocks LogSheetWebCompletionAccess access;

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private void authenticate(Long userId, String... roles) {
        User user = new User();
        user.setId(userId);
        user.setUsername("user-" + userId);
        user.setPasswordHash("x");
        AppUserDetails principal = new AppUserDetails(user, Set.of(roles), Set.of());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    private LogSheet sheet(Long assignee, Long unitId) {
        LogSheet s = new LogSheet();
        s.setId(1L);
        s.setAssigneeUserId(assignee);
        s.setOperationalUnitId(unitId);
        s.setStatus(LogSheetStatus.IN_PROGRESS);
        return s;
    }

    @Test
    void operatorAssigneeCannotCompleteOnWeb() {
        authenticate(100L, "OPERATOR");
        LogSheet s = sheet(100L, 10L);
        when(scopeService.isOperatorOf(100L, 10L)).thenReturn(true);

        assertThat(access.canCompleteOnWeb(s)).isFalse();
        assertThat(access.isMobileOnlyAssignee(s)).isTrue();
    }

    @Test
    void seniorOperatorAssigneeCanCompleteOnWeb() {
        authenticate(100L, "SENIOR_OPERATOR");
        LogSheet s = sheet(100L, 10L);

        assertThat(access.canCompleteOnWeb(s)).isTrue();
        assertThat(access.isMobileOnlyAssignee(s)).isFalse();
    }

    @Test
    void supervisorAssigneeCanCompleteOnWeb() {
        authenticate(100L, "SUPERVISOR");
        LogSheet s = sheet(100L, 10L);
        when(scopeService.isSupervisorOf(100L, 10L)).thenReturn(true);

        assertThat(access.canCompleteOnWeb(s)).isTrue();
        assertThat(access.isMobileOnlyAssignee(s)).isFalse();
    }

    @Test
    void adminCanCompleteOnWebEvenWhenNotAssignee() {
        authenticate(1L, "ADMIN");
        LogSheet s = sheet(200L, 10L);

        assertThat(access.canCompleteOnWeb(s)).isTrue();
        assertThat(access.isMobileOnlyAssignee(s)).isFalse();
    }
}
