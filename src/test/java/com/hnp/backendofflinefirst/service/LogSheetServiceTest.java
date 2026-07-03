package com.hnp.backendofflinefirst.service;

import com.hnp.backendofflinefirst.domain.LogSheetStatus;
import com.hnp.backendofflinefirst.dto.LogSheetDto;
import com.hnp.backendofflinefirst.dto.LogSheetSubmitResult;
import com.hnp.backendofflinefirst.entity.LogSheet;
import com.hnp.backendofflinefirst.entity.User;
import com.hnp.backendofflinefirst.logging.BusinessEventLogger;
import com.hnp.backendofflinefirst.repository.LogSheetEntryRepository;
import com.hnp.backendofflinefirst.repository.LogSheetRepository;
import com.hnp.backendofflinefirst.repository.LogSheetVoidSubmissionRepository;
import com.hnp.backendofflinefirst.security.AppUserDetails;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LogSheetServiceTest {

    @Mock LogSheetRepository logSheetRepository;
    @Mock LogSheetEntryRepository logSheetEntryRepository;
    @Mock LogSheetVoidSubmissionRepository voidSubmissionRepository;
    @Mock LogSheetActionLogger actionLogger;
    @Mock OperationalUnitScopeService scopeService;
    @Mock BusinessEventLogger businessEventLogger;

    @InjectMocks LogSheetService logSheetService;

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateOperator(Long userId) {
        User user = new User();
        user.setId(userId);
        user.setUsername("user-" + userId);
        user.setPasswordHash("x");
        AppUserDetails principal = new AppUserDetails(user, Set.of("OPERATOR"), Set.of());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    private LogSheet assignedSheet(Long assignee, Long dueAt) {
        LogSheet s = new LogSheet();
        s.setId(1L);
        s.setOperationalUnitId(10L);
        s.setStatus(LogSheetStatus.IN_PROGRESS);
        s.setAssigneeUserId(assignee);
        s.setDueAt(dueAt);
        return s;
    }

    @Test
    void submitCompletesSheetForAssignee() {
        authenticateOperator(100L);
        LogSheet s = assignedSheet(100L, System.currentTimeMillis() + 3_600_000L);
        when(logSheetRepository.findById(1L)).thenReturn(Optional.of(s));
        lenient().when(logSheetRepository.save(any(LogSheet.class))).thenAnswer(inv -> inv.getArgument(0));

        LogSheetDto dto = new LogSheetDto();
        dto.setServerId(1L);
        dto.setLocalId("local-1");
        dto.setCompletedAt(System.currentTimeMillis());

        List<LogSheetSubmitResult> results = logSheetService.submitBatch(List.of(dto));

        assertThat(results.get(0).getError()).isNull();
        assertThat(s.getStatus()).isEqualTo(LogSheetStatus.SUBMITTED);
        assertThat(s.getCompletedAt()).isNotNull();
        assertThat(s.getSyncedAt()).isNotNull();
    }

    @Test
    void submitRejectedWhenCompletedAfterDue() {
        authenticateOperator(100L);
        long due = System.currentTimeMillis() - 3_600_000L; // already past
        LogSheet s = assignedSheet(100L, due);
        when(logSheetRepository.findById(1L)).thenReturn(Optional.of(s));
        lenient().when(logSheetRepository.save(any(LogSheet.class))).thenAnswer(inv -> inv.getArgument(0));

        LogSheetDto dto = new LogSheetDto();
        dto.setServerId(1L);
        dto.setLocalId("local-1");
        dto.setCompletedAt(System.currentTimeMillis()); // after due

        List<LogSheetSubmitResult> results = logSheetService.submitBatch(List.of(dto));

        assertThat(results.get(0).getError()).isNotNull();
        assertThat(s.getStatus()).isEqualTo(LogSheetStatus.EXPIRED);
    }

    @Test
    void submitBySomeoneOtherThanAssigneeIsSuperseded() {
        authenticateOperator(999L);
        LogSheet s = assignedSheet(100L, System.currentTimeMillis() + 3_600_000L);
        when(logSheetRepository.findById(1L)).thenReturn(Optional.of(s));

        LogSheetDto dto = new LogSheetDto();
        dto.setServerId(1L);
        dto.setLocalId("local-1");
        dto.setCompletedAt(System.currentTimeMillis());

        List<LogSheetSubmitResult> results = logSheetService.submitBatch(List.of(dto));

        assertThat(results.get(0).getOutcome()).isEqualTo("SUPERSEDED");
        assertThat(s.getStatus()).isEqualTo(LogSheetStatus.IN_PROGRESS); // authoritative sheet untouched
    }

    @Test
    void lateOperatorSyncAfterSupervisorCompletionIsSuperseded() {
        // Supervisor already completed via takeover: sheet is SUBMITTED by supervisor (300).
        authenticateOperator(100L);
        LogSheet s = assignedSheet(300L, System.currentTimeMillis());
        s.setStatus(LogSheetStatus.SUBMITTED);
        s.setCompletedByUserId(300L);
        when(logSheetRepository.findById(1L)).thenReturn(Optional.of(s));

        LogSheetDto dto = new LogSheetDto();
        dto.setServerId(1L);
        dto.setLocalId("local-1");
        dto.setCompletedAt(System.currentTimeMillis() - 10_000L);

        List<LogSheetSubmitResult> results = logSheetService.submitBatch(List.of(dto));

        assertThat(results.get(0).getOutcome()).isEqualTo("SUPERSEDED");
        assertThat(s.getCompletedByUserId()).isEqualTo(300L); // not overwritten
    }

    @Test
    void completedWithinWindowButSyncedLateIsAccepted() {
        authenticateOperator(100L);
        long due = System.currentTimeMillis() - 86_400_000L;   // deadline was yesterday
        LogSheet s = assignedSheet(100L, due);
        when(logSheetRepository.findById(1L)).thenReturn(Optional.of(s));
        lenient().when(logSheetRepository.save(any(LogSheet.class))).thenAnswer(inv -> inv.getArgument(0));

        LogSheetDto dto = new LogSheetDto();
        dto.setServerId(1L);
        dto.setLocalId("local-1");
        dto.setCompletedAt(due - 60_000L); // finished offline before the deadline, synced now

        List<LogSheetSubmitResult> results = logSheetService.submitBatch(List.of(dto));

        assertThat(results.get(0).getOutcome()).isEqualTo("SUBMITTED");
        assertThat(s.getStatus()).isEqualTo(LogSheetStatus.SUBMITTED);
    }

    @Test
    void replayedOfflineSubmitIsIdempotent() {
        authenticateOperator(100L);
        when(actionLogger.isReplay("client-action-1")).thenReturn(true);

        LogSheetDto dto = new LogSheetDto();
        dto.setServerId(1L);
        dto.setLocalId("local-1");
        dto.setClientActionId("client-action-1");

        List<LogSheetSubmitResult> results = logSheetService.submitBatch(List.of(dto));

        assertThat(results.get(0).getError()).isNull();
        assertThat(results.get(0).getServerId()).isEqualTo(1L);
    }
}
