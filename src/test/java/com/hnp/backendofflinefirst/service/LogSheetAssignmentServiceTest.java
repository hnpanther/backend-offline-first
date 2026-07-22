package com.hnp.backendofflinefirst.service;

import com.hnp.backendofflinefirst.domain.ActionSource;
import com.hnp.backendofflinefirst.domain.AssignmentType;
import com.hnp.backendofflinefirst.domain.LogSheetStatus;
import com.hnp.backendofflinefirst.entity.LogSheet;
import com.hnp.backendofflinefirst.entity.User;
import com.hnp.backendofflinefirst.repository.LogSheetRepository;
import com.hnp.backendofflinefirst.repository.UserRepository;
import com.hnp.backendofflinefirst.security.AppUserDetails;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.access.AccessDeniedException;

import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LogSheetAssignmentServiceTest {

    @Mock LogSheetRepository logSheetRepository;
    @Mock OperationalUnitScopeService scopeService;
    @Mock LogSheetActionLogger actionLogger;
    @Mock UserRepository userRepository;

    @InjectMocks LogSheetAssignmentService service;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateAsAdmin(Long userId) {
        User user = new User();
        user.setId(userId);
        user.setUsername("admin");
        user.setActive(true);
        AppUserDetails principal = new AppUserDetails(user, Set.of("ADMIN"), Set.of());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

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
        when(logSheetRepository.claimIfPending(
                eq(1L), eq(100L), eq(AssignmentType.SELF_CLAIMED),
                eq(LogSheetStatus.IN_PROGRESS), eq(LogSheetStatus.PENDING),
                anyLong(), any()))
                .thenAnswer(inv -> {
                    s.setAssigneeUserId(100L);
                    s.setAssignmentType(AssignmentType.SELF_CLAIMED);
                    s.setStatus(LogSheetStatus.IN_PROGRESS);
                    s.setClaimedAt(inv.getArgument(5));
                    s.setStartedAt(inv.getArgument(5));
                    return 1;
                });

        LogSheet claimed = service.claim(1L, 100L, ActionSource.MOBILE);

        assertThat(claimed.getStatus()).isEqualTo(LogSheetStatus.IN_PROGRESS);
        assertThat(claimed.getAssignmentType()).isEqualTo(AssignmentType.SELF_CLAIMED);
        assertThat(claimed.getAssigneeUserId()).isEqualTo(100L);
        assertThat(claimed.getClaimedAt()).isNotNull();
    }

    @Test
    void claimFailsWhenNotPending() {
        LogSheet s = sheet(LogSheetStatus.IN_PROGRESS);
        stubSheet(s);
        when(scopeService.isOperatorOf(100L, 10L)).thenReturn(true);
        when(logSheetRepository.claimIfPending(
                eq(1L), eq(100L), eq(AssignmentType.SELF_CLAIMED),
                eq(LogSheetStatus.IN_PROGRESS), eq(LogSheetStatus.PENDING),
                anyLong(), any()))
                .thenReturn(0);

        assertThatThrownBy(() -> service.claim(1L, 100L, ActionSource.MOBILE))
                .isInstanceOf(IllegalStateException.class);
        verify(actionLogger, never()).record(any(), any(), any(), any(), any(), any(), anyLong(), any());
    }

    @Test
    void claimFailsWhenConcurrentClaimAlreadyWon() {
        LogSheet s = sheet(LogSheetStatus.PENDING);
        stubSheet(s);
        when(scopeService.isOperatorOf(100L, 10L)).thenReturn(true);
        when(logSheetRepository.claimIfPending(
                eq(1L), eq(100L), eq(AssignmentType.SELF_CLAIMED),
                eq(LogSheetStatus.IN_PROGRESS), eq(LogSheetStatus.PENDING),
                anyLong(), any()))
                .thenReturn(0);

        assertThatThrownBy(() -> service.claim(1L, 100L, ActionSource.MOBILE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cannot be claimed");
    }

    @Test
    void claimFailsWhenOutsideUnit() {
        LogSheet s = sheet(LogSheetStatus.PENDING);
        stubSheet(s);
        when(scopeService.isOperatorOf(199L, 10L)).thenReturn(false);
        when(scopeService.isSupervisorOf(199L, 10L)).thenReturn(false);

        assertThatThrownBy(() -> service.claim(1L, 199L, ActionSource.MOBILE))
                .isInstanceOf(AccessDeniedException.class);
        verify(logSheetRepository, never()).claimIfPending(
                any(), any(), any(), any(), any(), anyLong(), any());
    }

    // ---- release ----

    @Test
    void selfClaimedCanBeReleasedByOwner() {
        LogSheet open = sheet(LogSheetStatus.IN_PROGRESS);
        open.setAssignmentType(AssignmentType.SELF_CLAIMED);
        open.setAssigneeUserId(100L);
        LogSheet released = sheet(LogSheetStatus.PENDING);
        when(logSheetRepository.findById(1L)).thenReturn(Optional.of(open), Optional.of(released));
        when(logSheetRepository.releaseIfStillOpen(
                eq(1L), eq(LogSheetStatus.PENDING), anyCollection(), eq(100L), eq(AssignmentType.SELF_CLAIMED), anyLong()))
                .thenReturn(1);

        LogSheet result = service.release(1L, 100L, ActionSource.MOBILE);

        assertThat(result.getStatus()).isEqualTo(LogSheetStatus.PENDING);
        assertThat(result.getAssigneeUserId()).isNull();
        verify(logSheetRepository, never()).save(any());
    }

    @Test
    void selfClaimedCannotBeReleasedByOthers() {
        LogSheet s = sheet(LogSheetStatus.IN_PROGRESS);
        s.setAssignmentType(AssignmentType.SELF_CLAIMED);
        s.setAssigneeUserId(100L);
        stubSheet(s);
        when(scopeService.isSupervisorOf(101L, 10L)).thenReturn(false);

        assertThatThrownBy(() -> service.release(1L, 101L, ActionSource.MOBILE))
                .isInstanceOf(AccessDeniedException.class);
        verify(logSheetRepository, never()).releaseIfStillOpen(
                any(), any(), anyCollection(), any(), any(), anyLong());
    }

    @Test
    void supervisorCanReleaseSelfClaimedOperatorWork() {
        LogSheet open = sheet(LogSheetStatus.IN_PROGRESS);
        open.setAssignmentType(AssignmentType.SELF_CLAIMED);
        open.setAssigneeUserId(100L);
        LogSheet released = sheet(LogSheetStatus.PENDING);
        when(logSheetRepository.findById(1L)).thenReturn(Optional.of(open), Optional.of(released));
        when(scopeService.isSupervisorOf(300L, 10L)).thenReturn(true);
        when(logSheetRepository.releaseIfStillOpen(
                eq(1L), eq(LogSheetStatus.PENDING), anyCollection(), eq(100L), eq(AssignmentType.SELF_CLAIMED), anyLong()))
                .thenReturn(1);

        LogSheet result = service.release(1L, 300L, ActionSource.MOBILE);

        assertThat(result.getStatus()).isEqualTo(LogSheetStatus.PENDING);
        assertThat(result.getAssigneeUserId()).isNull();
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
        verify(logSheetRepository, never()).releaseIfStillOpen(
                any(), any(), anyCollection(), any(), any(), anyLong());
    }

    @Test
    void supervisorAssignedCanBeReleasedBySupervisor() {
        LogSheet open = sheet(LogSheetStatus.ASSIGNED);
        open.setAssignmentType(AssignmentType.SUPERVISOR_ASSIGNED);
        open.setAssigneeUserId(100L);
        LogSheet released = sheet(LogSheetStatus.PENDING);
        when(logSheetRepository.findById(1L)).thenReturn(Optional.of(open), Optional.of(released));
        when(scopeService.isSupervisorOf(300L, 10L)).thenReturn(true);
        when(logSheetRepository.releaseIfStillOpen(
                eq(1L), eq(LogSheetStatus.PENDING), anyCollection(), eq(100L), eq(AssignmentType.SUPERVISOR_ASSIGNED), anyLong()))
                .thenReturn(1);

        LogSheet result = service.release(1L, 300L, ActionSource.WEB);

        assertThat(result.getStatus()).isEqualTo(LogSheetStatus.PENDING);
        assertThat(result.getAssignmentType()).isNull();
    }

    @Test
    void releaseFailsAtomicallyWhenSheetAlreadySubmitted() {
        LogSheet open = sheet(LogSheetStatus.IN_PROGRESS);
        open.setAssignmentType(AssignmentType.SELF_CLAIMED);
        open.setAssigneeUserId(100L);
        stubSheet(open);
        when(logSheetRepository.releaseIfStillOpen(
                eq(1L), eq(LogSheetStatus.PENDING), anyCollection(), eq(100L), eq(AssignmentType.SELF_CLAIMED), anyLong()))
                .thenReturn(0);

        assertThatThrownBy(() -> service.release(1L, 100L, ActionSource.MOBILE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cannot be released");
        verify(actionLogger, never()).record(any(), any(), any(), any(), any(), any(), anyLong(), any());
    }

    @Test
    void releaseFailsAtomicallyWhenOwnershipChangedByTakeover() {
        LogSheet open = sheet(LogSheetStatus.IN_PROGRESS);
        open.setAssignmentType(AssignmentType.SELF_CLAIMED);
        open.setAssigneeUserId(100L);
        stubSheet(open);
        when(logSheetRepository.releaseIfStillOpen(
                eq(1L), eq(LogSheetStatus.PENDING), anyCollection(),
                eq(100L), eq(AssignmentType.SELF_CLAIMED), anyLong()))
                .thenReturn(0);

        assertThatThrownBy(() -> service.release(1L, 100L, ActionSource.MOBILE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cannot be released");
        verify(actionLogger, never()).record(any(), any(), any(), any(), any(), any(), anyLong(), any());
    }

    // ---- assign / reassign ----

    @Test
    void supervisorAssignsPendingToOperator() {
        LogSheet s = sheet(LogSheetStatus.PENDING);
        stubSheet(s);
        when(scopeService.isSupervisorOf(300L, 10L)).thenReturn(true);
        when(scopeService.isOperatorOf(100L, 10L)).thenReturn(true);
        when(logSheetRepository.assignIfPending(
                eq(1L), eq(100L), eq(300L), eq(AssignmentType.SUPERVISOR_ASSIGNED),
                eq(LogSheetStatus.ASSIGNED), eq(LogSheetStatus.PENDING),
                anyLong(), any()))
                .thenAnswer(inv -> {
                    s.setAssigneeUserId(100L);
                    s.setAssignmentType(AssignmentType.SUPERVISOR_ASSIGNED);
                    s.setAssignedByUserId(300L);
                    s.setStatus(LogSheetStatus.ASSIGNED);
                    s.setAssignedAt(inv.getArgument(6));
                    return 1;
                });

        LogSheet assigned = service.assign(1L, 100L, 300L, ActionSource.WEB);

        assertThat(assigned.getStatus()).isEqualTo(LogSheetStatus.ASSIGNED);
        assertThat(assigned.getAssignmentType()).isEqualTo(AssignmentType.SUPERVISOR_ASSIGNED);
        assertThat(assigned.getAssigneeUserId()).isEqualTo(100L);
        assertThat(assigned.getAssignedByUserId()).isEqualTo(300L);
    }

    @Test
    void assignFailsWhenConcurrentClaimAlreadyWon() {
        LogSheet s = sheet(LogSheetStatus.PENDING);
        stubSheet(s);
        when(scopeService.isSupervisorOf(300L, 10L)).thenReturn(true);
        when(scopeService.isOperatorOf(100L, 10L)).thenReturn(true);
        when(logSheetRepository.assignIfPending(
                eq(1L), eq(100L), eq(300L), eq(AssignmentType.SUPERVISOR_ASSIGNED),
                eq(LogSheetStatus.ASSIGNED), eq(LogSheetStatus.PENDING),
                anyLong(), any()))
                .thenReturn(0);

        assertThatThrownBy(() -> service.assign(1L, 100L, 300L, ActionSource.WEB))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("pending");
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
        LogSheet open = sheet(LogSheetStatus.ASSIGNED);
        open.setAssignmentType(AssignmentType.SUPERVISOR_ASSIGNED);
        open.setAssigneeUserId(100L);
        LogSheet reassigned = sheet(LogSheetStatus.ASSIGNED);
        reassigned.setAssignmentType(AssignmentType.SUPERVISOR_ASSIGNED);
        reassigned.setAssigneeUserId(101L);
        when(logSheetRepository.findById(1L)).thenReturn(Optional.of(open), Optional.of(reassigned));
        when(scopeService.isSupervisorOf(300L, 10L)).thenReturn(true);
        when(scopeService.isOperatorOf(101L, 10L)).thenReturn(true);
        when(logSheetRepository.reassignIfStillOpen(
                eq(1L), eq(101L), eq(300L),
                eq(AssignmentType.SUPERVISOR_ASSIGNED), eq(AssignmentType.SUPERVISOR_ASSIGNED),
                eq(100L), eq(LogSheetStatus.ASSIGNED), anyCollection(), anyLong(), any()))
                .thenReturn(1);

        LogSheet result = service.reassign(1L, 101L, 300L, ActionSource.WEB);

        assertThat(result.getAssigneeUserId()).isEqualTo(101L);
        assertThat(result.getStatus()).isEqualTo(LogSheetStatus.ASSIGNED);
        verify(logSheetRepository, never()).save(any());
    }

    @Test
    void reassignFailsAtomicallyWhenSheetAlreadySubmitted() {
        LogSheet open = sheet(LogSheetStatus.ASSIGNED);
        open.setAssignmentType(AssignmentType.SUPERVISOR_ASSIGNED);
        open.setAssigneeUserId(100L);
        stubSheet(open);
        when(scopeService.isSupervisorOf(300L, 10L)).thenReturn(true);
        when(scopeService.isOperatorOf(101L, 10L)).thenReturn(true);
        when(logSheetRepository.reassignIfStillOpen(
                eq(1L), eq(101L), eq(300L),
                eq(AssignmentType.SUPERVISOR_ASSIGNED), eq(AssignmentType.SUPERVISOR_ASSIGNED),
                eq(100L), eq(LogSheetStatus.ASSIGNED), anyCollection(), anyLong(), any()))
                .thenReturn(0);

        assertThatThrownBy(() -> service.reassign(1L, 101L, 300L, ActionSource.WEB))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("reassigned");
        verify(actionLogger, never()).record(any(), any(), any(), any(), any(), any(), anyLong(), any());
    }

    // ---- takeover ----

    @Test
    void supervisorTakesOverOperatorWork() {
        LogSheet open = sheet(LogSheetStatus.IN_PROGRESS);
        open.setAssignmentType(AssignmentType.SELF_CLAIMED);
        open.setAssigneeUserId(100L);
        LogSheet taken = sheet(LogSheetStatus.IN_PROGRESS);
        taken.setAssignmentType(AssignmentType.SUPERVISOR_ASSIGNED);
        taken.setAssigneeUserId(300L);
        when(logSheetRepository.findById(1L)).thenReturn(Optional.of(open), Optional.of(taken));
        when(scopeService.isSupervisorOf(300L, 10L)).thenReturn(true);
        when(logSheetRepository.takeoverIfStillOpen(
                eq(1L), eq(300L), eq(AssignmentType.SUPERVISOR_ASSIGNED),
                eq(LogSheetStatus.IN_PROGRESS), anyCollection(),
                eq(100L), eq(AssignmentType.SELF_CLAIMED), anyLong(), any()))
                .thenReturn(1);

        LogSheet result = service.takeover(1L, 300L, ActionSource.WEB);

        assertThat(result.getAssigneeUserId()).isEqualTo(300L);
        assertThat(result.getAssignmentType()).isEqualTo(AssignmentType.SUPERVISOR_ASSIGNED);
        assertThat(result.getStatus()).isEqualTo(LogSheetStatus.IN_PROGRESS);
        verify(logSheetRepository, never()).save(any());
    }

    @Test
    void takeoverFailsWhenNotSupervisor() {
        LogSheet s = sheet(LogSheetStatus.IN_PROGRESS);
        s.setAssigneeUserId(100L);
        stubSheet(s);
        when(scopeService.isSupervisorOf(101L, 10L)).thenReturn(false);

        assertThatThrownBy(() -> service.takeover(1L, 101L, ActionSource.WEB))
                .isInstanceOf(AccessDeniedException.class);
        verify(logSheetRepository, never()).takeoverIfStillOpen(
                any(), any(), any(), any(), anyCollection(), any(), any(), anyLong(), any());
    }

    @Test
    void takeoverFailsAtomicallyWhenSheetAlreadySubmitted() {
        LogSheet open = sheet(LogSheetStatus.IN_PROGRESS);
        open.setAssigneeUserId(100L);
        open.setAssignmentType(AssignmentType.SELF_CLAIMED);
        stubSheet(open);
        when(scopeService.isSupervisorOf(300L, 10L)).thenReturn(true);
        when(logSheetRepository.takeoverIfStillOpen(
                eq(1L), eq(300L), eq(AssignmentType.SUPERVISOR_ASSIGNED),
                eq(LogSheetStatus.IN_PROGRESS), anyCollection(),
                eq(100L), eq(AssignmentType.SELF_CLAIMED), anyLong(), any()))
                .thenReturn(0);

        assertThatThrownBy(() -> service.takeover(1L, 300L, ActionSource.WEB))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cannot be taken over");
        verify(actionLogger, never()).record(any(), any(), any(), any(), any(), any(), anyLong(), any());
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

    // ---- admin reopen (submitted only) ----

    @Test
    void adminReopensSubmittedSheetWithAssignee() {
        authenticateAsAdmin(1L);
        LogSheet s = sheet(LogSheetStatus.SUBMITTED);
        s.setAssigneeUserId(100L);
        s.setDueAt(1_000L);
        s.setSubmittedAt(2_000L);
        s.setCompletedAt(2_000L);
        s.setCompletedByUserId(100L);
        s.setSyncedAt(3_000L);
        s.setDraftSavedAt(1_500L);
        stubSheet(s);

        long newDue = System.currentTimeMillis() + 3_600_000L;
        service.adminReopenAndExtend(1L, 1L, newDue, ActionSource.WEB);

        assertThat(s.getStatus()).isEqualTo(LogSheetStatus.IN_PROGRESS);
        assertThat(s.getDueAt()).isEqualTo(newDue);
        assertThat(s.getSubmittedAt()).isNull();
        assertThat(s.getCompletedAt()).isNull();
        assertThat(s.getCompletedByUserId()).isNull();
        assertThat(s.getSyncedAt()).isNull();
        assertThat(s.getDraftSavedAt()).isNull();
        assertThat(s.getAssigneeUserId()).isEqualTo(100L);
    }

    @Test
    void adminReopensSubmittedSheetWithoutAssigneeAsPending() {
        authenticateAsAdmin(1L);
        LogSheet s = sheet(LogSheetStatus.SUBMITTED);
        s.setSubmittedAt(2_000L);
        s.setCompletedAt(2_000L);
        stubSheet(s);

        long newDue = System.currentTimeMillis() + 3_600_000L;
        service.adminReopenAndExtend(1L, 1L, newDue, ActionSource.WEB);

        assertThat(s.getStatus()).isEqualTo(LogSheetStatus.PENDING);
        assertThat(s.getAssigneeUserId()).isNull();
    }

    @Test
    void adminReopenFailsWhenSheetExpired() {
        authenticateAsAdmin(1L);
        LogSheet s = sheet(LogSheetStatus.EXPIRED);
        stubSheet(s);

        long newDue = System.currentTimeMillis() + 3_600_000L;
        assertThatThrownBy(() -> service.adminReopenAndExtend(1L, 1L, newDue, ActionSource.WEB))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Only submitted");
    }

    @Test
    void adminReopenFailsWhenNotAdmin() {
        long newDue = System.currentTimeMillis() + 3_600_000L;
        assertThatThrownBy(() -> service.adminReopenAndExtend(1L, 99L, newDue, ActionSource.WEB))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void adminReopenFailsWhenNewDeadlineNotInFuture() {
        authenticateAsAdmin(1L);
        LogSheet s = sheet(LogSheetStatus.SUBMITTED);
        stubSheet(s);

        assertThatThrownBy(() -> service.adminReopenAndExtend(1L, 1L, System.currentTimeMillis() - 1L, ActionSource.WEB))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("future");
    }
}
