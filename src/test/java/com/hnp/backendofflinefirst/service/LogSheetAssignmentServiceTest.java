package com.hnp.backendofflinefirst.service;

import com.hnp.backendofflinefirst.domain.ActionSource;
import com.hnp.backendofflinefirst.domain.AssignmentType;
import com.hnp.backendofflinefirst.domain.LogSheetStatus;
import com.hnp.backendofflinefirst.entity.LogSheet;
import com.hnp.backendofflinefirst.entity.User;
import com.hnp.backendofflinefirst.repository.LogSheetRepository;
import com.hnp.backendofflinefirst.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import org.springframework.security.access.AccessDeniedException;

@ExtendWith(MockitoExtension.class)
class LogSheetAssignmentServiceTest {

    @Mock LogSheetRepository logSheetRepository;
    @Mock OperationalUnitScopeService scopeService;
    @Mock LogSheetActionLogger actionLogger;
    @Mock UserRepository userRepository;

    @InjectMocks LogSheetAssignmentService service;

    // Numeric id fixtures: sheet=1, unit=10, operators=100/101/199/200/201, supervisor=300
    private LogSheet sheet(LogSheetStatus status) {
        LogSheet s = new LogSheet();
        s.setId(1L);
        s.setOperationalUnitId(10L);
        s.setStatus(status);
        return s;
    }

    private void stubSheet(LogSheet s) {
        when(logSheetRepository.findById(1L)).thenReturn(Optional.of(s));
        lenient().when(logSheetRepository.save(any(LogSheet.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(userRepository.findById(any())).thenReturn(Optional.of(new User()));
    }

    // ---- claim ----

    @Test
    void operatorClaimsPendingSheet() {
        LogSheet s = sheet(LogSheetStatus.PENDING);
        stubSheet(s);
        when(scopeService.isOperatorOf(100L, 10L)).thenReturn(true);

        service.claim(1L, 100L, ActionSource.MOBILE);

        assertThat(s.getStatus()).isEqualTo(LogSheetStatus.IN_PROGRESS);
        assertThat(s.getAssignmentType()).isEqualTo(AssignmentType.SELF_CLAIMED);
        assertThat(s.getAssigneeUserId()).isEqualTo(100L);
        assertThat(s.getClaimedAt()).isNotNull();
    }

    @Test
    void claimFailsWhenNotPending() {
        LogSheet s = sheet(LogSheetStatus.IN_PROGRESS);
        stubSheet(s);

        assertThatThrownBy(() -> service.claim(1L, 100L, ActionSource.MOBILE))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void claimFailsWhenOutsideUnit() {
        LogSheet s = sheet(LogSheetStatus.PENDING);
        stubSheet(s);
        when(scopeService.isOperatorOf(199L, 10L)).thenReturn(false);
        when(scopeService.isSupervisorOf(199L, 10L)).thenReturn(false);

        assertThatThrownBy(() -> service.claim(1L, 199L, ActionSource.MOBILE))
                .isInstanceOf(AccessDeniedException.class);
    }

    // ---- release ----

    @Test
    void selfClaimedCanBeReleasedByOwner() {
        LogSheet s = sheet(LogSheetStatus.IN_PROGRESS);
        s.setAssignmentType(AssignmentType.SELF_CLAIMED);
        s.setAssigneeUserId(100L);
        stubSheet(s);

        service.release(1L, 100L, ActionSource.MOBILE);

        assertThat(s.getStatus()).isEqualTo(LogSheetStatus.PENDING);
        assertThat(s.getAssigneeUserId()).isNull();
    }

    @Test
    void selfClaimedCannotBeReleasedByOthers() {
        LogSheet s = sheet(LogSheetStatus.IN_PROGRESS);
        s.setAssignmentType(AssignmentType.SELF_CLAIMED);
        s.setAssigneeUserId(100L);
        stubSheet(s);

        assertThatThrownBy(() -> service.release(1L, 101L, ActionSource.MOBILE))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void supervisorAssignedCannotBeReleasedByOperator() {
        LogSheet s = sheet(LogSheetStatus.ASSIGNED);
        s.setAssignmentType(AssignmentType.SUPERVISOR_ASSIGNED);
        s.setAssigneeUserId(100L);
        stubSheet(s);
        when(scopeService.isSupervisorOf(100L, 10L)).thenReturn(false);

        assertThatThrownBy(() -> service.release(1L, 100L, ActionSource.MOBILE))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void supervisorAssignedCanBeReleasedBySupervisor() {
        LogSheet s = sheet(LogSheetStatus.ASSIGNED);
        s.setAssignmentType(AssignmentType.SUPERVISOR_ASSIGNED);
        s.setAssigneeUserId(100L);
        stubSheet(s);
        when(scopeService.isSupervisorOf(300L, 10L)).thenReturn(true);

        service.release(1L, 300L, ActionSource.WEB);

        assertThat(s.getStatus()).isEqualTo(LogSheetStatus.PENDING);
        assertThat(s.getAssignmentType()).isNull();
    }

    // ---- assign / reassign ----

    @Test
    void supervisorAssignsPendingToOperator() {
        LogSheet s = sheet(LogSheetStatus.PENDING);
        stubSheet(s);
        when(scopeService.isSupervisorOf(300L, 10L)).thenReturn(true);
        when(scopeService.isOperatorOf(100L, 10L)).thenReturn(true);

        service.assign(1L, 100L, 300L, ActionSource.WEB);

        assertThat(s.getStatus()).isEqualTo(LogSheetStatus.ASSIGNED);
        assertThat(s.getAssignmentType()).isEqualTo(AssignmentType.SUPERVISOR_ASSIGNED);
        assertThat(s.getAssigneeUserId()).isEqualTo(100L);
        assertThat(s.getAssignedByUserId()).isEqualTo(300L);
    }

    @Test
    void assignFailsWhenNotSupervisor() {
        LogSheet s = sheet(LogSheetStatus.PENDING);
        stubSheet(s);
        when(scopeService.isSupervisorOf(201L, 10L)).thenReturn(false);

        assertThatThrownBy(() -> service.assign(1L, 100L, 201L, ActionSource.WEB))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void assignFailsWhenTargetNotOperatorOfUnit() {
        LogSheet s = sheet(LogSheetStatus.PENDING);
        stubSheet(s);
        when(scopeService.isSupervisorOf(300L, 10L)).thenReturn(true);
        when(scopeService.isOperatorOf(200L, 10L)).thenReturn(false);

        assertThatThrownBy(() -> service.assign(1L, 200L, 300L, ActionSource.WEB))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void supervisorReassignsAssignedSheet() {
        LogSheet s = sheet(LogSheetStatus.ASSIGNED);
        s.setAssignmentType(AssignmentType.SUPERVISOR_ASSIGNED);
        s.setAssigneeUserId(100L);
        stubSheet(s);
        when(scopeService.isSupervisorOf(300L, 10L)).thenReturn(true);
        when(scopeService.isOperatorOf(101L, 10L)).thenReturn(true);

        service.reassign(1L, 101L, 300L, ActionSource.WEB);

        assertThat(s.getAssigneeUserId()).isEqualTo(101L);
        assertThat(s.getStatus()).isEqualTo(LogSheetStatus.ASSIGNED);
    }

    // ---- takeover ----

    @Test
    void supervisorTakesOverOperatorWork() {
        LogSheet s = sheet(LogSheetStatus.IN_PROGRESS);
        s.setAssignmentType(AssignmentType.SELF_CLAIMED);
        s.setAssigneeUserId(100L);
        stubSheet(s);
        when(scopeService.isSupervisorOf(300L, 10L)).thenReturn(true);

        service.takeover(1L, 300L, ActionSource.WEB);

        assertThat(s.getAssigneeUserId()).isEqualTo(300L);
        assertThat(s.getAssignmentType()).isEqualTo(AssignmentType.SUPERVISOR_ASSIGNED);
        assertThat(s.getStatus()).isEqualTo(LogSheetStatus.IN_PROGRESS);
    }

    @Test
    void takeoverFailsWhenNotSupervisor() {
        LogSheet s = sheet(LogSheetStatus.IN_PROGRESS);
        s.setAssigneeUserId(100L);
        stubSheet(s);
        when(scopeService.isSupervisorOf(101L, 10L)).thenReturn(false);

        assertThatThrownBy(() -> service.takeover(1L, 101L, ActionSource.WEB))
                .isInstanceOf(AccessDeniedException.class);
    }

    // ---- extend ----

    @Test
    void supervisorExtendsDeadline() {
        LogSheet s = sheet(LogSheetStatus.IN_PROGRESS);
        s.setAssigneeUserId(100L);
        s.setDueAt(1000L);
        stubSheet(s);
        when(scopeService.isSupervisorOf(300L, 10L)).thenReturn(true);

        long newDue = System.currentTimeMillis() + 3_600_000L;
        service.extend(1L, 300L, newDue, ActionSource.WEB);

        assertThat(s.getDueAt()).isEqualTo(newDue);
        assertThat(s.getStatus()).isEqualTo(LogSheetStatus.IN_PROGRESS);
    }

    @Test
    void extendReopensExpiredSheet() {
        LogSheet s = sheet(LogSheetStatus.EXPIRED);
        s.setAssigneeUserId(100L);
        s.setExpiredAt(1000L);
        stubSheet(s);
        when(scopeService.isSupervisorOf(300L, 10L)).thenReturn(true);

        long newDue = System.currentTimeMillis() + 3_600_000L;
        service.extend(1L, 300L, newDue, ActionSource.WEB);

        assertThat(s.getStatus()).isEqualTo(LogSheetStatus.IN_PROGRESS);
        assertThat(s.getExpiredAt()).isNull();
    }
}
