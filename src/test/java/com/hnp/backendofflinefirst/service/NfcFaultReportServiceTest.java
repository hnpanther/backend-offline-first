package com.hnp.backendofflinefirst.service;

import com.hnp.backendofflinefirst.support.TestPrincipals;
import com.hnp.backendofflinefirst.domain.ActionSource;
import com.hnp.backendofflinefirst.domain.NfcFaultReportStatus;
import com.hnp.backendofflinefirst.dto.NfcFaultReportDto;
import com.hnp.backendofflinefirst.dto.NfcFaultReportSubmitResult;
import com.hnp.backendofflinefirst.entity.LogSheet;
import com.hnp.backendofflinefirst.entity.LogSheetEntry;
import com.hnp.backendofflinefirst.entity.NfcFaultReport;
import com.hnp.backendofflinefirst.entity.User;
import com.hnp.backendofflinefirst.repository.LogSheetEntryRepository;
import com.hnp.backendofflinefirst.repository.LogSheetRepository;
import com.hnp.backendofflinefirst.repository.NfcFaultReportRepository;
import com.hnp.backendofflinefirst.repository.UserRepository;
import com.hnp.backendofflinefirst.security.AppUserDetails;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NfcFaultReportServiceTest {

    @Mock NfcFaultReportRepository repository;
    @Mock LogSheetRepository logSheetRepository;
    @Mock LogSheetEntryRepository logSheetEntryRepository;
    @Mock UserRepository userRepository;
    @Mock OperationalUnitScopeService scopeService;
    @Mock LogSheetAccessService logSheetAccessService;

    @InjectMocks NfcFaultReportService service;

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private void authenticate(Long userId, String role) {
        User user = new User();
        user.setId(userId);
        user.setUsername("user-" + userId);
        user.setPersonnelCode("PC-" + java.util.UUID.randomUUID());
        user.setPasswordHash("x");
        AppUserDetails principal = TestPrincipals.of(user, Set.of(role), Set.of());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    private LogSheet sheet(Long id, Long unitId) {
        LogSheet s = new LogSheet();
        s.setId(id);
        s.setOperationalUnitId(unitId);
        return s;
    }

    private LogSheetEntry entryFor(Long sheetId, Long assetId) {
        LogSheetEntry e = new LogSheetEntry();
        e.setLogSheetId(sheetId);
        e.setAssetId(assetId);
        return e;
    }

    // ---------------------------------------------------------------- createFromWeb

    @Test
    void createFromWebSucceedsForSupervisorOfTheUnit() {
        authenticate(1L, "SUPERVISOR");
        LogSheet sheet = sheet(1L, 10L);
        when(logSheetRepository.findById(1L)).thenReturn(Optional.of(sheet));
        when(logSheetEntryRepository.findByLogSheetId(1L)).thenReturn(List.of(entryFor(1L, 42L)));
        when(scopeService.isSupervisorOf(1L, 10L)).thenReturn(true);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        NfcFaultReport saved = service.createFromWeb(1L, 42L, "تگ کنده شده");

        assertThat(saved.getLogSheetId()).isEqualTo(1L);
        assertThat(saved.getAssetId()).isEqualTo(42L);
        assertThat(saved.getOperationalUnitId()).isEqualTo(10L);
        assertThat(saved.getReportedByUserId()).isEqualTo(1L);
        assertThat(saved.getSource()).isEqualTo(ActionSource.WEB);
        assertThat(saved.getReason()).isEqualTo("تگ کنده شده");
    }

    @Test
    void createFromWebDeniedForSupervisorOfADifferentUnit() {
        authenticate(1L, "SUPERVISOR");
        LogSheet sheet = sheet(1L, 10L);
        when(logSheetRepository.findById(1L)).thenReturn(Optional.of(sheet));
        when(logSheetEntryRepository.findByLogSheetId(1L)).thenReturn(List.of(entryFor(1L, 42L)));
        when(scopeService.isSupervisorOf(1L, 10L)).thenReturn(false);

        assertThatThrownBy(() -> service.createFromWeb(1L, 42L, "reason"))
                .isInstanceOf(AccessDeniedException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void createFromWebAllowedForAdminRegardlessOfUnit() {
        authenticate(1L, "ADMIN");
        LogSheet sheet = sheet(1L, 10L);
        when(logSheetRepository.findById(1L)).thenReturn(Optional.of(sheet));
        when(logSheetEntryRepository.findByLogSheetId(1L)).thenReturn(List.of(entryFor(1L, 42L)));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        NfcFaultReport saved = service.createFromWeb(1L, 42L, null);

        assertThat(saved.getSource()).isEqualTo(ActionSource.WEB);
        verify(scopeService, never()).isSupervisorOf(any(), any());
    }

    @Test
    void createFromWebRejectsAssetNotOnTheSheet() {
        authenticate(1L, "ADMIN");
        LogSheet sheet = sheet(1L, 10L);
        when(logSheetRepository.findById(1L)).thenReturn(Optional.of(sheet));
        when(logSheetEntryRepository.findByLogSheetId(1L)).thenReturn(List.of(entryFor(1L, 99L)));

        assertThatThrownBy(() -> service.createFromWeb(1L, 42L, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not part of this log sheet");
        verify(repository, never()).save(any());
    }

    // ---------------------------------------------------------------- submitBatch (mobile)

    @Test
    void submitBatchCreatesReportForUnitScopedOperator() {
        authenticate(5L, "OPERATOR");
        LogSheet sheet = sheet(1L, 10L);
        when(logSheetRepository.findById(1L)).thenReturn(Optional.of(sheet));
        when(logSheetEntryRepository.findByLogSheetId(1L)).thenReturn(List.of(entryFor(1L, 42L)));
        when(logSheetAccessService.canView(sheet)).thenReturn(true);
        when(repository.save(any())).thenAnswer(inv -> {
            NfcFaultReport r = inv.getArgument(0);
            r.setId(77L);
            return r;
        });

        NfcFaultReportDto dto = new NfcFaultReportDto();
        dto.setLogSheetId(1L);
        dto.setAssetId(42L);
        dto.setReason("device NFC broken");
        dto.setLocalId("local-1");
        dto.setClientActionId("client-1");

        List<NfcFaultReportSubmitResult> results = service.submitBatch(List.of(dto));

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getOutcome()).isEqualTo("CREATED");
        assertThat(results.get(0).getServerId()).isEqualTo(77L);
        assertThat(results.get(0).getError()).isNull();

        ArgumentCaptor<NfcFaultReport> captor = ArgumentCaptor.forClass(NfcFaultReport.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getSource()).isEqualTo(ActionSource.MOBILE);
        assertThat(captor.getValue().getReportedByUserId()).isEqualTo(5L);
        assertThat(captor.getValue().getClientActionId()).isEqualTo("client-1");
    }

    @Test
    void submitBatchIsIdempotentForReplayedClientActionId() {
        when(repository.existsByClientActionId("dup-1")).thenReturn(true);

        NfcFaultReportDto dto = new NfcFaultReportDto();
        dto.setClientActionId("dup-1");
        dto.setLocalId("local-1");

        List<NfcFaultReportSubmitResult> results = service.submitBatch(List.of(dto));

        assertThat(results.get(0).getOutcome()).isEqualTo("DUPLICATE");
        verify(repository, never()).save(any());
    }

    @Test
    void submitBatchRejectsMissingLogSheetOrAssetId() {
        NfcFaultReportDto dto = new NfcFaultReportDto();
        dto.setLocalId("local-1");

        List<NfcFaultReportSubmitResult> results = service.submitBatch(List.of(dto));

        assertThat(results.get(0).getOutcome()).isEqualTo("ERROR");
        verify(repository, never()).save(any());
    }

    @Test
    void submitBatchRejectsUnknownLogSheet() {
        when(logSheetRepository.findById(1L)).thenReturn(Optional.empty());

        NfcFaultReportDto dto = new NfcFaultReportDto();
        dto.setLogSheetId(1L);
        dto.setAssetId(42L);
        dto.setLocalId("local-1");

        List<NfcFaultReportSubmitResult> results = service.submitBatch(List.of(dto));

        assertThat(results.get(0).getOutcome()).isEqualTo("ERROR");
        assertThat(results.get(0).getError()).contains("not found");
    }

    @Test
    void submitBatchRejectsAssetNotOnTheSheet() {
        LogSheet sheet = sheet(1L, 10L);
        when(logSheetRepository.findById(1L)).thenReturn(Optional.of(sheet));
        when(logSheetEntryRepository.findByLogSheetId(1L)).thenReturn(List.of(entryFor(1L, 99L)));

        NfcFaultReportDto dto = new NfcFaultReportDto();
        dto.setLogSheetId(1L);
        dto.setAssetId(42L);
        dto.setLocalId("local-1");

        List<NfcFaultReportSubmitResult> results = service.submitBatch(List.of(dto));

        assertThat(results.get(0).getOutcome()).isEqualTo("ERROR");
        assertThat(results.get(0).getError()).contains("not part of this log sheet");
        verify(repository, never()).save(any());
    }

    @Test
    void submitBatchRejectsWhenReporterIsOutsideUnitScope() {
        authenticate(5L, "OPERATOR");
        LogSheet sheet = sheet(1L, 10L);
        when(logSheetRepository.findById(1L)).thenReturn(Optional.of(sheet));
        when(logSheetEntryRepository.findByLogSheetId(1L)).thenReturn(List.of(entryFor(1L, 42L)));
        when(logSheetAccessService.canView(sheet)).thenReturn(false);

        NfcFaultReportDto dto = new NfcFaultReportDto();
        dto.setLogSheetId(1L);
        dto.setAssetId(42L);
        dto.setLocalId("local-1");

        List<NfcFaultReportSubmitResult> results = service.submitBatch(List.of(dto));

        assertThat(results.get(0).getOutcome()).isEqualTo("ERROR");
        assertThat(results.get(0).getError()).contains("outside your unit scope");
        verify(repository, never()).save(any());
    }

    @Test
    void submitBatchRejectsBatchLargerThanTheConfiguredMax() {
        List<NfcFaultReportDto> oversized = java.util.stream.IntStream.rangeClosed(1, 501)
                .mapToObj(i -> {
                    NfcFaultReportDto dto = new NfcFaultReportDto();
                    dto.setLocalId("local-" + i);
                    return dto;
                })
                .toList();

        assertThatThrownBy(() -> service.submitBatch(oversized))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maximum allowed is 500");
    }

    // ---------------------------------------------------------------- findVisible

    /** Null unitIds is the panel-wide "unrestricted" marker, and must reach the query as null. */
    @Test
    void findVisiblePassesNullScopeForAdmin() {
        authenticate(1L, "ADMIN");
        Pageable pageable = PageRequest.of(0, 25);
        when(repository.search(isNull(), isNull(), isNull(), eq(pageable)))
                .thenReturn(new PageImpl<>(List.of(new NfcFaultReport())));

        Page<NfcFaultReport> reports = service.findVisible(null, null, pageable);

        assertThat(reports.getContent()).hasSize(1);
        verify(scopeService, never()).getAccessibleUnitIds(any());
    }

    @Test
    void findVisibleIsUnitScopedForSupervisor() {
        authenticate(1L, "SUPERVISOR");
        Pageable pageable = PageRequest.of(0, 25);
        when(scopeService.getAccessibleUnitIds(1L)).thenReturn(Set.of(10L, 20L));
        when(repository.search(eq(Set.of(10L, 20L)), isNull(), isNull(), eq(pageable)))
                .thenReturn(new PageImpl<>(List.of(new NfcFaultReport())));

        Page<NfcFaultReport> reports = service.findVisible(null, null, pageable);

        assertThat(reports.getContent()).hasSize(1);
    }

    /**
     * Scoped to no unit at all. The query must not be reached: {@code IN ()} is not portable, and
     * on some dialects an empty list matches everything — which would show one supervisor every
     * other unit's reports.
     */
    @Test
    void findVisibleReturnsEmptyWhenSupervisorHasNoUnits() {
        authenticate(1L, "SUPERVISOR");
        when(scopeService.getAccessibleUnitIds(1L)).thenReturn(Set.of());

        Page<NfcFaultReport> reports = service.findVisible(null, null, PageRequest.of(0, 25));

        assertThat(reports).isEmpty();
        verify(repository, never()).search(any(), any(), any(), any());
    }

    /**
     * The search term is lower-cased and wrapped once in the service. The query compares a column
     * against a literal; doing it there would mean {@code LOWER(:q)} evaluated per row.
     */
    @Test
    void findVisibleNormalisesTheSearchTermOnce() {
        authenticate(1L, "ADMIN");
        Pageable pageable = PageRequest.of(0, 25);
        when(repository.search(isNull(), eq(NfcFaultReportStatus.OPEN), eq("%pump-12%"), eq(pageable)))
                .thenReturn(new PageImpl<>(List.of(new NfcFaultReport())));

        Page<NfcFaultReport> reports =
                service.findVisible(NfcFaultReportStatus.OPEN, "  PUMP-12 ", pageable);

        assertThat(reports.getContent()).hasSize(1);
    }

    /** A blank box is not a filter that matches nothing — it is no filter. */
    @Test
    void findVisibleTreatsABlankSearchAsNoFilter() {
        authenticate(1L, "ADMIN");
        Pageable pageable = PageRequest.of(0, 25);
        when(repository.search(isNull(), isNull(), isNull(), eq(pageable)))
                .thenReturn(new PageImpl<>(List.of()));

        service.findVisible(null, "   ", pageable);

        verify(repository).search(isNull(), isNull(), isNull(), eq(pageable));
    }

    @Test
    void countOpenVisibleIsScopedAndShortCircuitsOnAnEmptyScope() {
        authenticate(1L, "SUPERVISOR");
        when(scopeService.getAccessibleUnitIds(1L)).thenReturn(Set.of());

        assertThat(service.countOpenVisible()).isZero();
        verify(repository, never()).countOpenInScope(any());
    }

    // ---------------------------------------------------------------- delete

    @Test
    void deleteRemovesExistingReport() {
        when(repository.existsById(5L)).thenReturn(true);

        service.delete(5L);

        verify(repository).deleteById(5L);
    }

    @Test
    void deleteRejectsUnknownId() {
        when(repository.existsById(5L)).thenReturn(false);

        assertThatThrownBy(() -> service.delete(5L)).isInstanceOf(IllegalArgumentException.class);
        verify(repository, never()).deleteById(any());
    }
}
