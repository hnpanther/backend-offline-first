package com.hnp.backendofflinefirst.service;

import com.hnp.backendofflinefirst.support.TestPrincipals;
import com.hnp.backendofflinefirst.domain.ActionSource;
import com.hnp.backendofflinefirst.domain.AssignmentType;
import com.hnp.backendofflinefirst.domain.LogSheetActionType;
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
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LogSheetAssignmentServiceTest {

    @Mock LogSheetRepository logSheetRepository;
    @Mock OperationalUnitScopeService scopeService;
    @Mock LogSheetActionLogger actionLogger;
    // Asset status propagation. Lenient because these cases assert log-sheet lifecycle
    // behaviour, not what happens to the assets afterwards — that has its own integration
    // test where the real service runs against a real database.
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
        user.setPersonnelCode("PC-" + java.util.UUID.randomUUID());
        user.setActive(true);
        AppUserDetails principal = TestPrincipals.of(user, Set.of("ADMIN"), Set.of());
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

    @Test
    void extendRejectsPastDueDate() {
        LogSheet s = sheet(LogSheetStatus.IN_PROGRESS);
        s.setAssigneeUserId(100L);
        s.setDueAt(1000L);
        stubSheet(s);
        when(scopeService.isSupervisorOf(300L, 10L)).thenReturn(true);

        assertThatThrownBy(() -> service.extend(1L, 300L, System.currentTimeMillis() - 1L, ActionSource.WEB))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("future");
    }

    @Test
    void extendRejectsDeadlineMoreThanTwoYearsAhead() {
        LogSheet s = sheet(LogSheetStatus.IN_PROGRESS);
        s.setAssigneeUserId(100L);
        s.setDueAt(1000L);
        stubSheet(s);
        when(scopeService.isSupervisorOf(300L, 10L)).thenReturn(true);

        long tooFar = System.currentTimeMillis() + 3L * 365 * 24 * 3_600_000L;
        assertThatThrownBy(() -> service.extend(1L, 300L, tooFar, ActionSource.WEB))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("within 2 years");
    }

    // ---- reopen submitted (admin or unit supervisor) ----

    @Test
    void reopenSubmittedSheetWithAssignee() {
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
        service.reopenSubmittedWithExtend(1L, 1L, newDue, ActionSource.WEB);

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
    void reopenSubmittedSheetWithoutAssigneeAsPending() {
        authenticateAsAdmin(1L);
        LogSheet s = sheet(LogSheetStatus.SUBMITTED);
        s.setSubmittedAt(2_000L);
        s.setCompletedAt(2_000L);
        stubSheet(s);

        long newDue = System.currentTimeMillis() + 3_600_000L;
        service.reopenSubmittedWithExtend(1L, 1L, newDue, ActionSource.WEB);

        assertThat(s.getStatus()).isEqualTo(LogSheetStatus.PENDING);
        assertThat(s.getAssigneeUserId()).isNull();
    }

    @Test
    void reopenFailsWhenSheetExpired() {
        authenticateAsAdmin(1L);
        LogSheet s = sheet(LogSheetStatus.EXPIRED);
        stubSheet(s);

        long newDue = System.currentTimeMillis() + 3_600_000L;
        assertThatThrownBy(() -> service.reopenSubmittedWithExtend(1L, 1L, newDue, ActionSource.WEB))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Only submitted");
    }

    @Test
    void reopenFailsWhenVoided() {
        authenticateAsAdmin(1L);
        LogSheet s = sheet(LogSheetStatus.VOIDED);
        stubSheet(s);

        long newDue = System.currentTimeMillis() + 3_600_000L;
        assertThatThrownBy(() -> service.reopenSubmittedWithExtend(1L, 1L, newDue, ActionSource.WEB))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Only submitted");
    }

    @Test
    void unitSupervisorCanReopenSubmittedSheet() {
        LogSheet s = sheet(LogSheetStatus.SUBMITTED);
        s.setAssigneeUserId(100L);
        stubSheet(s);
        when(scopeService.isSupervisorOf(300L, 10L)).thenReturn(true);

        long newDue = System.currentTimeMillis() + 3_600_000L;
        service.reopenSubmittedWithExtend(1L, 300L, newDue, ActionSource.WEB);

        assertThat(s.getStatus()).isEqualTo(LogSheetStatus.IN_PROGRESS);
    }

    @Test
    void reopenFailsWhenNotAdminOrUnitSupervisor() {
        LogSheet s = sheet(LogSheetStatus.SUBMITTED);
        stubSheet(s);
        when(scopeService.isSupervisorOf(99L, 10L)).thenReturn(false);

        long newDue = System.currentTimeMillis() + 3_600_000L;
        assertThatThrownBy(() -> service.reopenSubmittedWithExtend(1L, 99L, newDue, ActionSource.WEB))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void reopenFailsWhenNewDeadlineNotInFuture() {
        authenticateAsAdmin(1L);
        LogSheet s = sheet(LogSheetStatus.SUBMITTED);
        stubSheet(s);

        assertThatThrownBy(() -> service.reopenSubmittedWithExtend(1L, 1L, System.currentTimeMillis() - 1L, ActionSource.WEB))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("future");
    }

    @Test
    void reopenFailsWhenNewDeadlineMoreThanTwoYearsAhead() {
        authenticateAsAdmin(1L);
        LogSheet s = sheet(LogSheetStatus.SUBMITTED);
        stubSheet(s);

        long tooFar = System.currentTimeMillis() + 3L * 365 * 24 * 3_600_000L;
        assertThatThrownBy(() -> service.reopenSubmittedWithExtend(1L, 1L, tooFar, ActionSource.WEB))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("within 2 years");
    }

    // ---- void / unvoid ----

    @Test
    void voidSubmittedPreservesCompletionData() {
        authenticateAsAdmin(1L);
        LogSheet s = sheet(LogSheetStatus.SUBMITTED);
        s.setCompletedAt(2_000L);
        s.setSubmittedAt(2_000L);
        s.setCompletedByUserId(100L);
        s.setNotes("kept");
        stubSheet(s);

        service.voidSubmitted(1L, 1L, ActionSource.WEB);

        assertThat(s.getStatus()).isEqualTo(LogSheetStatus.VOIDED);
        assertThat(s.getCompletedAt()).isEqualTo(2_000L);
        assertThat(s.getSubmittedAt()).isEqualTo(2_000L);
        assertThat(s.getCompletedByUserId()).isEqualTo(100L);
        assertThat(s.getNotes()).isEqualTo("kept");
        verify(actionLogger).record(eq(1L), eq(LogSheetActionType.VOID),
                eq(ActionSource.WEB), eq(1L), isNull(), isNull(), anyLong(), isNull(), isNull());
    }

    @Test
    void unitSupervisorCanVoidSubmitted() {
        LogSheet s = sheet(LogSheetStatus.SUBMITTED);
        stubSheet(s);
        when(scopeService.isSupervisorOf(300L, 10L)).thenReturn(true);

        service.voidSubmitted(1L, 300L, ActionSource.WEB);
        assertThat(s.getStatus()).isEqualTo(LogSheetStatus.VOIDED);
    }

    @Test
    void voidFailsWhenNotSubmitted() {
        authenticateAsAdmin(1L);
        LogSheet s = sheet(LogSheetStatus.IN_PROGRESS);
        stubSheet(s);

        assertThatThrownBy(() -> service.voidSubmitted(1L, 1L, ActionSource.WEB))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Only submitted");
    }

    @Test
    void voidFailsOutsideUnit() {
        LogSheet s = sheet(LogSheetStatus.SUBMITTED);
        stubSheet(s);
        when(scopeService.isSupervisorOf(99L, 10L)).thenReturn(false);

        assertThatThrownBy(() -> service.voidSubmitted(1L, 99L, ActionSource.WEB))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void restoreVoidedReturnsToSubmitted() {
        authenticateAsAdmin(1L);
        LogSheet s = sheet(LogSheetStatus.VOIDED);
        s.setCompletedAt(2_000L);
        s.setSubmittedAt(2_000L);
        stubSheet(s);

        service.restoreVoided(1L, 1L, ActionSource.WEB);

        assertThat(s.getStatus()).isEqualTo(LogSheetStatus.SUBMITTED);
        assertThat(s.getCompletedAt()).isEqualTo(2_000L);
        verify(actionLogger).record(eq(1L), eq(LogSheetActionType.UNVOID),
                eq(ActionSource.WEB), eq(1L), isNull(), isNull(), anyLong(), isNull(), isNull());
    }

    @Test
    void restoreFailsWhenNotVoided() {
        authenticateAsAdmin(1L);
        LogSheet s = sheet(LogSheetStatus.SUBMITTED);
        stubSheet(s);

        assertThatThrownBy(() -> service.restoreVoided(1L, 1L, ActionSource.WEB))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Only voided");
    }

    @Test
    void extendRejectsVoidedSheet() {
        authenticateAsAdmin(1L);
        LogSheet s = sheet(LogSheetStatus.VOIDED);
        stubSheet(s);

        assertThatThrownBy(() -> service.extend(1L, 1L, System.currentTimeMillis() + 1000L, ActionSource.WEB))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cannot be extended");
    }

    @Test
    void extendReopensCancelledSheetWithAssignee() {
        LogSheet s = sheet(LogSheetStatus.CANCELLED);
        s.setAssigneeUserId(100L);
        s.setCancelledAt(1000L);
        stubSheet(s);
        when(scopeService.isSupervisorOf(300L, 10L)).thenReturn(true);

        long newDue = System.currentTimeMillis() + 3_600_000L;
        service.extend(1L, 300L, newDue, ActionSource.WEB);

        assertThat(s.getStatus()).isEqualTo(LogSheetStatus.IN_PROGRESS);
        assertThat(s.getCancelledAt()).isNull();
        assertThat(s.getDueAt()).isEqualTo(newDue);
    }

    @Test
    void extendReopensCancelledSheetWithoutAssigneeAsPending() {
        LogSheet s = sheet(LogSheetStatus.CANCELLED);
        s.setCancelledAt(1000L);
        stubSheet(s);
        when(scopeService.isSupervisorOf(300L, 10L)).thenReturn(true);

        long newDue = System.currentTimeMillis() + 3_600_000L;
        service.extend(1L, 300L, newDue, ActionSource.WEB);

        assertThat(s.getStatus()).isEqualTo(LogSheetStatus.PENDING);
        assertThat(s.getCancelledAt()).isNull();
    }

    // ---- cancel ----

    @Test
    void supervisorCancelsPendingSheet() {
        LogSheet s = sheet(LogSheetStatus.PENDING);
        stubSheet(s);
        when(scopeService.isSupervisorOf(300L, 10L)).thenReturn(true);

        service.cancel(1L, 300L, ActionSource.WEB);

        assertThat(s.getStatus()).isEqualTo(LogSheetStatus.CANCELLED);
        assertThat(s.getCancelledAt()).isNotNull();
        verify(actionLogger).record(eq(1L), eq(LogSheetActionType.CANCEL),
                eq(ActionSource.WEB), eq(300L), isNull(), isNull(), anyLong(), isNull(), isNull());
    }

    @Test
    void supervisorCancelsAssignedSheet() {
        LogSheet s = sheet(LogSheetStatus.ASSIGNED);
        s.setAssigneeUserId(100L);
        stubSheet(s);
        when(scopeService.isSupervisorOf(300L, 10L)).thenReturn(true);

        service.cancel(1L, 300L, ActionSource.WEB);

        assertThat(s.getStatus()).isEqualTo(LogSheetStatus.CANCELLED);
    }

    @Test
    void adminCancelsInProgressSheetWithoutUnitCheck() {
        authenticateAsAdmin(1L);
        LogSheet s = sheet(LogSheetStatus.IN_PROGRESS);
        s.setAssigneeUserId(100L);
        stubSheet(s);

        service.cancel(1L, 1L, ActionSource.WEB);

        assertThat(s.getStatus()).isEqualTo(LogSheetStatus.CANCELLED);
    }

    @Test
    void cancelFailsWhenSubmitted() {
        authenticateAsAdmin(1L);
        LogSheet s = sheet(LogSheetStatus.SUBMITTED);
        stubSheet(s);

        assertThatThrownBy(() -> service.cancel(1L, 1L, ActionSource.WEB))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Only pending, assigned, or in-progress");
    }

    @Test
    void cancelFailsWhenExpired() {
        authenticateAsAdmin(1L);
        LogSheet s = sheet(LogSheetStatus.EXPIRED);
        stubSheet(s);

        assertThatThrownBy(() -> service.cancel(1L, 1L, ActionSource.WEB))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Only pending, assigned, or in-progress");
    }

    @Test
    void cancelFailsWhenAlreadyCancelled() {
        authenticateAsAdmin(1L);
        LogSheet s = sheet(LogSheetStatus.CANCELLED);
        stubSheet(s);

        assertThatThrownBy(() -> service.cancel(1L, 1L, ActionSource.WEB))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Only pending, assigned, or in-progress");
    }

    @Test
    void cancelFailsOutsideUnit() {
        LogSheet s = sheet(LogSheetStatus.PENDING);
        stubSheet(s);
        when(scopeService.isSupervisorOf(99L, 10L)).thenReturn(false);

        assertThatThrownBy(() -> service.cancel(1L, 99L, ActionSource.WEB))
                .isInstanceOf(AccessDeniedException.class);
    }

    // ---- optional action comment (extend / cancel / void) ----------------------------------
    // The comment explains *why* an action was taken. It is always optional: an empty one must
    // never block the action, and must land as null rather than "" so history rendering can
    // simply test for presence.

    @Test
    void cancelRecordsTheSuppliedComment() {
        LogSheet s = sheet(LogSheetStatus.PENDING);
        stubSheet(s);
        when(scopeService.isSupervisorOf(300L, 10L)).thenReturn(true);

        service.cancel(1L, 300L, ActionSource.WEB, "واحد در تعمیرات اساسی است");

        assertThat(s.getStatus()).isEqualTo(LogSheetStatus.CANCELLED);
        verify(actionLogger).record(eq(1L), eq(LogSheetActionType.CANCEL), eq(ActionSource.WEB),
                eq(300L), isNull(), isNull(), anyLong(), isNull(), eq("واحد در تعمیرات اساسی است"));
    }

    @Test
    void voidRecordsTheSuppliedComment() {
        LogSheet s = sheet(LogSheetStatus.SUBMITTED);
        stubSheet(s);
        when(scopeService.isSupervisorOf(300L, 10L)).thenReturn(true);

        service.voidSubmitted(1L, 300L, ActionSource.WEB, "مقادیر اشتباه ثبت شده بود");

        assertThat(s.getStatus()).isEqualTo(LogSheetStatus.VOIDED);
        verify(actionLogger).record(eq(1L), eq(LogSheetActionType.VOID), eq(ActionSource.WEB),
                eq(300L), isNull(), isNull(), anyLong(), isNull(), eq("مقادیر اشتباه ثبت شده بود"));
    }

    @Test
    void extendRecordsTheSuppliedComment() {
        LogSheet s = sheet(LogSheetStatus.IN_PROGRESS);
        s.setAssigneeUserId(100L);
        stubSheet(s);
        when(scopeService.isSupervisorOf(300L, 10L)).thenReturn(true);

        long newDue = System.currentTimeMillis() + 3_600_000L;
        service.extend(1L, 300L, newDue, ActionSource.WEB, "به دلیل توقف واحد");

        assertThat(s.getDueAt()).isEqualTo(newDue);
        verify(actionLogger).record(eq(1L), eq(LogSheetActionType.EXTEND), eq(ActionSource.WEB),
                eq(300L), isNull(), isNull(), anyLong(), isNull(), eq("به دلیل توقف واحد"));
    }

    @Test
    void aBlankCommentIsStoredAsNullAndStillPerformsTheAction() {
        LogSheet s = sheet(LogSheetStatus.PENDING);
        stubSheet(s);
        when(scopeService.isSupervisorOf(300L, 10L)).thenReturn(true);

        service.cancel(1L, 300L, ActionSource.WEB, "   ");

        assertThat(s.getStatus()).as("the action must not depend on a comment").isEqualTo(LogSheetStatus.CANCELLED);
        verify(actionLogger).record(eq(1L), eq(LogSheetActionType.CANCEL), eq(ActionSource.WEB),
                eq(300L), isNull(), isNull(), anyLong(), isNull(), isNull());
    }

    @Test
    void aCommentIsTrimmedBeforeItIsRecorded() {
        LogSheet s = sheet(LogSheetStatus.PENDING);
        stubSheet(s);
        when(scopeService.isSupervisorOf(300L, 10L)).thenReturn(true);

        service.cancel(1L, 300L, ActionSource.WEB, "  دلیل واقعی  ");

        verify(actionLogger).record(eq(1L), eq(LogSheetActionType.CANCEL), eq(ActionSource.WEB),
                eq(300L), isNull(), isNull(), anyLong(), isNull(), eq("دلیل واقعی"));
    }

    /**
     * Rejected rather than truncated: half a reason reads as a whole one and misleads.
     * No repository or scope stubbing here on purpose — the comment is validated before any
     * lookup happens, so the sheet is never even loaded.
     */
    @Test
    void anOverlongCommentIsRejectedAndTheActionDoesNotHappen() {
        LogSheet s = sheet(LogSheetStatus.PENDING);

        String tooLong = "x".repeat(1001);

        assertThatThrownBy(() -> service.cancel(1L, 300L, ActionSource.WEB, tooLong))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Action comment is too long.");

        assertThat(s.getStatus()).isEqualTo(LogSheetStatus.PENDING);
        verify(actionLogger, never()).record(anyLong(), any(), any(), any(), any(), any(),
                anyLong(), any(), any());
    }

    @Test
    void unvoidRecordsTheSuppliedComment() {
        LogSheet s = sheet(LogSheetStatus.VOIDED);
        stubSheet(s);
        when(scopeService.isSupervisorOf(300L, 10L)).thenReturn(true);

        service.restoreVoided(1L, 300L, ActionSource.WEB, "بررسی مجدد نشان داد مقادیر درست بوده‌اند");

        assertThat(s.getStatus()).isEqualTo(LogSheetStatus.SUBMITTED);
        verify(actionLogger).record(eq(1L), eq(LogSheetActionType.UNVOID), eq(ActionSource.WEB),
                eq(300L), isNull(), isNull(), anyLong(), isNull(),
                eq("بررسی مجدد نشان داد مقادیر درست بوده‌اند"));
    }

    @Test
    void reopenRecordsTheSuppliedComment() {
        LogSheet s = sheet(LogSheetStatus.SUBMITTED);
        s.setAssigneeUserId(100L);
        stubSheet(s);
        when(scopeService.isSupervisorOf(300L, 10L)).thenReturn(true);

        long newDue = System.currentTimeMillis() + 3_600_000L;
        service.reopenSubmittedWithExtend(1L, 300L, newDue, ActionSource.WEB, "چند پارامتر جا افتاده بود");

        assertThat(s.getStatus()).isEqualTo(LogSheetStatus.IN_PROGRESS);
        assertThat(s.getDueAt()).isEqualTo(newDue);
        verify(actionLogger).record(eq(1L), eq(LogSheetActionType.ADMIN_REOPEN), eq(ActionSource.WEB),
                eq(300L), isNull(), isNull(), anyLong(), isNull(), eq("چند پارامتر جا افتاده بود"));
    }

    @Test
    void unvoidWithABlankCommentStillRestoresTheSheet() {
        LogSheet s = sheet(LogSheetStatus.VOIDED);
        stubSheet(s);
        when(scopeService.isSupervisorOf(300L, 10L)).thenReturn(true);

        service.restoreVoided(1L, 300L, ActionSource.WEB, "  ");

        assertThat(s.getStatus()).isEqualTo(LogSheetStatus.SUBMITTED);
        verify(actionLogger).record(eq(1L), eq(LogSheetActionType.UNVOID), eq(ActionSource.WEB),
                eq(300L), isNull(), isNull(), anyLong(), isNull(), isNull());
    }

    @Test
    void reopenWithABlankCommentStillReopensTheSheet() {
        LogSheet s = sheet(LogSheetStatus.SUBMITTED);
        stubSheet(s);
        when(scopeService.isSupervisorOf(300L, 10L)).thenReturn(true);

        service.reopenSubmittedWithExtend(1L, 300L, System.currentTimeMillis() + 3_600_000L,
                ActionSource.WEB, null);

        assertThat(s.getStatus()).isEqualTo(LogSheetStatus.PENDING);
        verify(actionLogger).record(eq(1L), eq(LogSheetActionType.ADMIN_REOPEN), eq(ActionSource.WEB),
                eq(300L), isNull(), isNull(), anyLong(), isNull(), isNull());
    }

    /** The length guard is shared, so it must bite on the two newest actions too. */
    @Test
    void anOverlongCommentIsRejectedOnUnvoidAndReopenAsWell() {
        String tooLong = "x".repeat(1001);

        assertThatThrownBy(() -> service.restoreVoided(1L, 300L, ActionSource.WEB, tooLong))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Action comment is too long.");

        assertThatThrownBy(() -> service.reopenSubmittedWithExtend(
                1L, 300L, System.currentTimeMillis() + 3_600_000L, ActionSource.WEB, tooLong))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Action comment is too long.");

        verify(actionLogger, never()).record(anyLong(), any(), any(), any(), any(), any(),
                anyLong(), any(), any());
    }

    @Test
    void aCommentAtExactlyTheLimitIsAccepted() {
        LogSheet s = sheet(LogSheetStatus.PENDING);
        stubSheet(s);
        when(scopeService.isSupervisorOf(300L, 10L)).thenReturn(true);

        String atLimit = "x".repeat(1000);
        service.cancel(1L, 300L, ActionSource.WEB, atLimit);

        assertThat(s.getStatus()).isEqualTo(LogSheetStatus.CANCELLED);
        verify(actionLogger).record(eq(1L), eq(LogSheetActionType.CANCEL), eq(ActionSource.WEB),
                eq(300L), isNull(), isNull(), anyLong(), isNull(), eq(atLimit));
    }
}
