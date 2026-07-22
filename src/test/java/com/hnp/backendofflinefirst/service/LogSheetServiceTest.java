package com.hnp.backendofflinefirst.service;

import com.hnp.backendofflinefirst.domain.FieldDefinitionSnapshot;
import com.hnp.backendofflinefirst.domain.FieldValidationSupport;
import com.hnp.backendofflinefirst.entity.FieldDefinition;
import com.hnp.backendofflinefirst.domain.LogSheetStatus;
import com.hnp.backendofflinefirst.dto.LogSheetEntryDto;
import com.hnp.backendofflinefirst.dto.LogSheetDto;
import com.hnp.backendofflinefirst.dto.LogSheetSubmitResult;
import com.hnp.backendofflinefirst.entity.LogSheet;
import com.hnp.backendofflinefirst.entity.LogSheetEntry;
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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LogSheetServiceTest {

    @Mock LogSheetRepository logSheetRepository;
    @Mock LogSheetEntryRepository logSheetEntryRepository;
    @Mock LogSheetVoidSubmissionRepository voidSubmissionRepository;
    @Mock LogSheetActionLogger actionLogger;
    @Mock OperationalUnitScopeService scopeService;
    @Mock BusinessEventLogger businessEventLogger;
    @Mock LogSheetFieldDefinitionsService fieldDefinitionsService;

    @InjectMocks LogSheetService logSheetService;

    @org.junit.jupiter.api.BeforeEach
    void defaultFieldDefinitions() {
        lenient().when(fieldDefinitionsService.resolveForEntries(any(), any())).thenReturn(List.of());
        lenient().when(logSheetRepository.submitIfStillCompletable(
                any(), any(), anyLong(), anyLong(), anyLong(), any(), any(), any(), anyCollection()))
                .thenReturn(1);
        lenient().when(logSheetRepository.expireIfStillOpenAndOverdue(any(), anyLong(), any(), anyCollection()))
                .thenReturn(1);
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private LogSheetEntry sheetEntry(Long logSheetId, Long assetId) {
        LogSheetEntry entry = new LogSheetEntry();
        entry.setId(assetId);
        entry.setLogSheetId(logSheetId);
        entry.setAssetId(assetId);
        entry.setFormData(new HashMap<>());
        return entry;
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
        assertThat(results.get(0).getOutcome()).isEqualTo("SUBMITTED");
        verify(logSheetRepository).submitIfStillCompletable(
                eq(1L), eq(100L), anyLong(), anyLong(), anyLong(), any(), any(),
                eq(LogSheetStatus.SUBMITTED), anyCollection());
    }

    @Test
    void submitRejectedWhenCompletedAfterDue() {
        authenticateOperator(100L);
        long due = System.currentTimeMillis() - 3_600_000L; // already past
        LogSheet s = assignedSheet(100L, due);
        when(logSheetRepository.findById(1L)).thenReturn(Optional.of(s));

        LogSheetDto dto = new LogSheetDto();
        dto.setServerId(1L);
        dto.setLocalId("local-1");
        dto.setCompletedAt(System.currentTimeMillis()); // after due

        List<LogSheetSubmitResult> results = logSheetService.submitBatch(List.of(dto));

        assertThat(results.get(0).getOutcome()).isEqualTo("EXPIRED");
        verify(logSheetRepository).expireIfStillOpenAndOverdue(
                eq(1L), anyLong(), eq(LogSheetStatus.EXPIRED), anyCollection());
        verify(logSheetRepository, never()).submitIfStillCompletable(
                any(), any(), anyLong(), anyLong(), anyLong(), any(), any(), any(), anyCollection());
    }

    @Test
    void submitFailsAtomicallyWhenConcurrentExpiryWinsAfterDue() {
        authenticateOperator(100L);
        long due = System.currentTimeMillis() + 3_600_000L;
        LogSheet s = assignedSheet(100L, due);
        when(logSheetRepository.findById(1L)).thenReturn(Optional.of(s));
        when(logSheetRepository.submitIfStillCompletable(
                any(), any(), anyLong(), anyLong(), anyLong(), any(), any(), any(), anyCollection()))
                .thenReturn(0);
        LogSheet expired = assignedSheet(100L, due);
        expired.setStatus(LogSheetStatus.EXPIRED);
        when(logSheetRepository.findById(1L)).thenReturn(Optional.of(s), Optional.of(expired));

        LogSheetDto dto = new LogSheetDto();
        dto.setServerId(1L);
        dto.setLocalId("local-1");
        dto.setCompletedAt(System.currentTimeMillis());

        List<LogSheetSubmitResult> results = logSheetService.submitBatch(List.of(dto));

        assertThat(results.get(0).getOutcome()).isEqualTo("EXPIRED");
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
        verify(logSheetRepository).submitIfStillCompletable(
                eq(1L), eq(100L), eq(due - 60_000L), anyLong(), anyLong(), any(), any(),
                eq(LogSheetStatus.SUBMITTED), anyCollection());
    }

    @Test
    void completedBeforeDueAcceptedEvenWhenServerMarkedExpired() {
        authenticateOperator(100L);
        long due = System.currentTimeMillis() - 86_400_000L;
        LogSheet s = assignedSheet(100L, due);
        s.setStatus(LogSheetStatus.EXPIRED);
        when(logSheetRepository.findById(1L)).thenReturn(Optional.of(s));

        LogSheetDto dto = new LogSheetDto();
        dto.setServerId(1L);
        dto.setLocalId("local-1");
        dto.setCompletedAt(due - 60_000L);

        List<LogSheetSubmitResult> results = logSheetService.submitBatch(List.of(dto));

        assertThat(results.get(0).getOutcome()).isEqualTo("SUBMITTED");
        verify(logSheetRepository).submitIfStillCompletable(
                eq(1L), eq(100L), eq(due - 60_000L), anyLong(), anyLong(), any(), any(),
                eq(LogSheetStatus.SUBMITTED), anyCollection());
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

    @Test
    void submitRejectedWhenSubmittedAssetNotOnLogSheet() {
        authenticateOperator(100L);
        LogSheet s = assignedSheet(100L, System.currentTimeMillis() + 3_600_000L);
        when(logSheetRepository.findById(1L)).thenReturn(Optional.of(s));
        when(logSheetEntryRepository.findByLogSheetId(1L)).thenReturn(List.of(sheetEntry(1L, 1L), sheetEntry(1L, 2L)));

        LogSheetDto dto = new LogSheetDto();
        dto.setServerId(1L);
        dto.setLocalId("local-1");
        dto.setCompletedAt(System.currentTimeMillis());
        LogSheetEntryDto entry = new LogSheetEntryDto();
        entry.setAssetId(48L);
        entry.setAssetName("Foreign pump");
        dto.setEntries(List.of(entry));

        List<LogSheetSubmitResult> results = logSheetService.submitBatch(List.of(dto));

        assertThat(results.get(0).getOutcome()).isEqualTo("ERROR");
        assertThat(results.get(0).getError()).contains("48");
        assertThat(s.getStatus()).isEqualTo(LogSheetStatus.IN_PROGRESS);
        verify(logSheetEntryRepository, never()).save(any());
        verify(logSheetEntryRepository, never()).deleteAll(anyCollection());
    }

    @Test
    void submitRejectsMixedValidAndForeignAssetsWithoutMutatingEntries() {
        authenticateOperator(100L);
        LogSheet s = assignedSheet(100L, System.currentTimeMillis() + 3_600_000L);
        when(logSheetRepository.findById(1L)).thenReturn(Optional.of(s));

        LogSheetEntry asset1 = sheetEntry(1L, 1L);
        asset1.setFormData(new HashMap<>(Map.of("temp", 5)));
        when(logSheetEntryRepository.findByLogSheetId(1L)).thenReturn(List.of(asset1, sheetEntry(1L, 2L)));

        LogSheetDto dto = new LogSheetDto();
        dto.setServerId(1L);
        dto.setLocalId("local-1");
        dto.setCompletedAt(System.currentTimeMillis());

        LogSheetEntryDto valid = new LogSheetEntryDto();
        valid.setAssetId(1L);
        valid.setFormData(Map.of("temp", 99));
        LogSheetEntryDto foreign = new LogSheetEntryDto();
        foreign.setAssetId(15L);
        foreign.setFormData(Map.of("temp", 88));
        dto.setEntries(List.of(valid, foreign));

        List<LogSheetSubmitResult> results = logSheetService.submitBatch(List.of(dto));

        assertThat(results.get(0).getOutcome()).isEqualTo("ERROR");
        assertThat(results.get(0).getError()).contains("15");
        assertThat(asset1.getFormData()).containsEntry("temp", 5);
        verify(logSheetEntryRepository, never()).save(any());
        verify(logSheetEntryRepository, never()).deleteAll(anyCollection());
    }

    @Test
    void submitWithNullEntriesCompletesWithoutTouchingEntries() {
        authenticateOperator(100L);
        LogSheet s = assignedSheet(100L, System.currentTimeMillis() + 3_600_000L);
        when(logSheetRepository.findById(1L)).thenReturn(Optional.of(s));
        lenient().when(logSheetRepository.save(any(LogSheet.class))).thenAnswer(inv -> inv.getArgument(0));

        LogSheetDto dto = new LogSheetDto();
        dto.setServerId(1L);
        dto.setLocalId("local-1");
        dto.setCompletedAt(System.currentTimeMillis());
        dto.setEntries(null);

        List<LogSheetSubmitResult> results = logSheetService.submitBatch(List.of(dto));

        assertThat(results.get(0).getOutcome()).isEqualTo("SUBMITTED");
        verify(logSheetEntryRepository, never()).save(any());
        verify(logSheetEntryRepository, never()).deleteAll(anyCollection());
    }

    @Test
    void submitWithEmptyEntriesCompletesWithoutTouchingEntries() {
        authenticateOperator(100L);
        LogSheet s = assignedSheet(100L, System.currentTimeMillis() + 3_600_000L);
        when(logSheetRepository.findById(1L)).thenReturn(Optional.of(s));
        lenient().when(logSheetRepository.save(any(LogSheet.class))).thenAnswer(inv -> inv.getArgument(0));

        LogSheetDto dto = new LogSheetDto();
        dto.setServerId(1L);
        dto.setLocalId("local-1");
        dto.setCompletedAt(System.currentTimeMillis());
        dto.setEntries(List.of());

        List<LogSheetSubmitResult> results = logSheetService.submitBatch(List.of(dto));

        assertThat(results.get(0).getOutcome()).isEqualTo("SUBMITTED");
        verify(logSheetEntryRepository, never()).save(any());
        verify(logSheetEntryRepository, never()).deleteAll(anyCollection());
    }

    @Test
    void submitUpdatesOnlyMatchingAssetFormData() {
        authenticateOperator(100L);
        LogSheet s = assignedSheet(100L, System.currentTimeMillis() + 3_600_000L);
        when(logSheetRepository.findById(1L)).thenReturn(Optional.of(s));
        lenient().when(logSheetRepository.save(any(LogSheet.class))).thenAnswer(inv -> inv.getArgument(0));

        LogSheetEntry asset1 = sheetEntry(1L, 1L);
        LogSheetEntry asset2 = sheetEntry(1L, 2L);
        asset2.setFormData(new HashMap<>(Map.of("pressure", 3)));
        when(logSheetEntryRepository.findByLogSheetId(1L)).thenReturn(List.of(asset1, asset2));

        LogSheetDto dto = new LogSheetDto();
        dto.setServerId(1L);
        dto.setLocalId("local-1");
        dto.setCompletedAt(System.currentTimeMillis());

        LogSheetEntryDto submitted = new LogSheetEntryDto();
        submitted.setAssetId(1L);
        submitted.setFormData(Map.of("temp", 31));
        dto.setEntries(List.of(submitted));

        logSheetService.submitBatch(List.of(dto));

        assertThat(asset1.getFormData()).containsEntry("temp", 31);
        assertThat(asset2.getFormData()).containsEntry("pressure", 3);
        verify(logSheetEntryRepository, times(1)).save(any(LogSheetEntry.class));
    }

    @Test
    void submitOmitsAssetWithoutRemovingServerEntry() {
        authenticateOperator(100L);
        LogSheet s = assignedSheet(100L, System.currentTimeMillis() + 3_600_000L);
        when(logSheetRepository.findById(1L)).thenReturn(Optional.of(s));
        lenient().when(logSheetRepository.save(any(LogSheet.class))).thenAnswer(inv -> inv.getArgument(0));

        LogSheetEntry asset1 = sheetEntry(1L, 1L);
        LogSheetEntry asset2 = sheetEntry(1L, 2L);
        LogSheetEntry asset3 = sheetEntry(1L, 3L);
        when(logSheetEntryRepository.findByLogSheetId(1L)).thenReturn(List.of(asset1, asset2, asset3));

        LogSheetDto dto = new LogSheetDto();
        dto.setServerId(1L);
        dto.setLocalId("local-1");
        dto.setCompletedAt(System.currentTimeMillis());

        LogSheetEntryDto submitted1 = new LogSheetEntryDto();
        submitted1.setAssetId(1L);
        submitted1.setFormData(Map.of("temp", 10));
        LogSheetEntryDto submitted2 = new LogSheetEntryDto();
        submitted2.setAssetId(2L);
        submitted2.setFormData(Map.of("temp", 20));
        dto.setEntries(List.of(submitted1, submitted2));

        List<LogSheetSubmitResult> results = logSheetService.submitBatch(List.of(dto));

        assertThat(results.get(0).getOutcome()).isEqualTo("SUBMITTED");
        assertThat(asset1.getFormData()).containsEntry("temp", 10);
        assertThat(asset2.getFormData()).containsEntry("temp", 20);
        assertThat(asset3.getFormData()).isEmpty();
        verify(logSheetEntryRepository, times(2)).save(any(LogSheetEntry.class));
        verify(logSheetEntryRepository, never()).deleteAll(any());
    }

    @Test
    void submitIgnoresTamperedEntryMetadata() {
        authenticateOperator(100L);
        LogSheet s = assignedSheet(100L, System.currentTimeMillis() + 3_600_000L);
        when(logSheetRepository.findById(1L)).thenReturn(Optional.of(s));
        lenient().when(logSheetRepository.save(any(LogSheet.class))).thenAnswer(inv -> inv.getArgument(0));

        LogSheetEntry existing = sheetEntry(1L, 101L);
        existing.setAssetName("Pump A");
        existing.setClassId(7L);
        existing.setNfcTagId("NFC-REAL");
        existing.setSubFunctionCode("SF-01");
        existing.setSubFunctionTag("TAG-01");
        when(logSheetEntryRepository.findByLogSheetId(1L)).thenReturn(List.of(existing));

        LogSheetDto dto = new LogSheetDto();
        dto.setServerId(1L);
        dto.setLocalId("local-1");
        dto.setCompletedAt(System.currentTimeMillis());

        LogSheetEntryDto tampered = new LogSheetEntryDto();
        tampered.setAssetId(101L);
        tampered.setAssetName("Fake name");
        tampered.setClassId(999L);
        tampered.setNfcTagId("NFC-FAKE");
        tampered.setSubFunctionCode("SF-FAKE");
        tampered.setSubFunctionTag("TAG-FAKE");
        tampered.setFormData(Map.of("temp", 42));
        dto.setEntries(List.of(tampered));

        List<LogSheetSubmitResult> results = logSheetService.submitBatch(List.of(dto));

        assertThat(results.get(0).getOutcome()).isEqualTo("SUBMITTED");
        assertThat(existing.getAssetName()).isEqualTo("Pump A");
        assertThat(existing.getClassId()).isEqualTo(7L);
        assertThat(existing.getNfcTagId()).isEqualTo("NFC-REAL");
        assertThat(existing.getSubFunctionCode()).isEqualTo("SF-01");
        assertThat(existing.getSubFunctionTag()).isEqualTo("TAG-01");
        assertThat(existing.getFormData()).containsEntry("temp", 42);
    }

    @Test
    void submitStoresEntryTimestampsFromClient() {
        authenticateOperator(100L);
        LogSheet s = assignedSheet(100L, System.currentTimeMillis() + 3_600_000L);
        when(logSheetRepository.findById(1L)).thenReturn(Optional.of(s));
        LogSheetEntry existing = sheetEntry(1L, 48L);
        when(logSheetEntryRepository.findByLogSheetId(1L)).thenReturn(List.of(existing));
        lenient().when(logSheetRepository.save(any(LogSheet.class))).thenAnswer(inv -> inv.getArgument(0));

        LogSheetDto dto = new LogSheetDto();
        dto.setServerId(1L);
        dto.setLocalId("local-1");
        dto.setCompletedAt(System.currentTimeMillis());
        LogSheetEntryDto entry = new LogSheetEntryDto();
        entry.setAssetId(48L);
        entry.setAssetName("Pump");
        entry.setCreatedAt(1_700_000_000_000L);
        entry.setUpdatedAt(1_700_000_100_000L);
        dto.setEntries(List.of(entry));

        logSheetService.submitBatch(List.of(dto));

        assertThat(existing.getCreatedAt()).isEqualTo(1_700_000_000_000L);
        assertThat(existing.getUpdatedAt()).isEqualTo(1_700_000_100_000L);
        verify(logSheetEntryRepository).save(existing);
    }

    @Test
    void webDraftSaveSetsCreatedAtOnFirstData() {
        authenticate(100L, "SENIOR_OPERATOR");
        LogSheet s = assignedSheet(100L, System.currentTimeMillis() + 3_600_000L);
        when(logSheetRepository.findById(1L)).thenReturn(Optional.of(s));
        lenient().when(logSheetRepository.save(any(LogSheet.class))).thenAnswer(inv -> inv.getArgument(0));

        LogSheetEntry entry = new LogSheetEntry();
        entry.setId(10L);
        entry.setLogSheetId(1L);
        entry.setFormData(new HashMap<>());
        when(logSheetEntryRepository.findByLogSheetId(1L)).thenReturn(List.of(entry));

        logSheetService.saveDraftFromWeb(1L, Map.of("10", Map.of("temp", 22)));

        assertThat(entry.getCreatedAt()).isNotNull();
        assertThat(entry.getUpdatedAt()).isNull();
    }

    @Test
    void webDraftSaveSetsUpdatedAtOnEdit() {
        authenticate(100L, "SENIOR_OPERATOR");
        LogSheet s = assignedSheet(100L, System.currentTimeMillis() + 3_600_000L);
        when(logSheetRepository.findById(1L)).thenReturn(Optional.of(s));
        lenient().when(logSheetRepository.save(any(LogSheet.class))).thenAnswer(inv -> inv.getArgument(0));

        LogSheetEntry entry = new LogSheetEntry();
        entry.setId(10L);
        entry.setLogSheetId(1L);
        entry.setFormData(Map.of("temp", 20));
        entry.setCreatedAt(1_700_000_000_000L);
        when(logSheetEntryRepository.findByLogSheetId(1L)).thenReturn(List.of(entry));

        logSheetService.saveDraftFromWeb(1L, Map.of("10", Map.of("temp", 25)));

        assertThat(entry.getCreatedAt()).isEqualTo(1_700_000_000_000L);
        assertThat(entry.getUpdatedAt()).isNotNull();
    }

    @Test
    void submitRejectedWhenRequiredFieldMissingOnFilledEntry() {
        authenticateOperator(100L);
        LogSheet s = assignedSheet(100L, System.currentTimeMillis() + 3_600_000L);
        s.setFieldDefinitionsSnapshot(List.of(snapshotField("temp", true)));
        when(logSheetRepository.findById(1L)).thenReturn(Optional.of(s));

        LogSheetEntry existing = sheetEntry(1L, 48L);
        existing.setClassId(7L);
        when(logSheetEntryRepository.findByLogSheetId(1L)).thenReturn(List.of(existing));
        FieldDefinition temp = toField("temp", true);
        FieldDefinition note = toField("note", false);
        note.setDataType("text");
        when(fieldDefinitionsService.resolveForEntries(eq(s), any())).thenReturn(List.of(temp, note));

        LogSheetDto dto = new LogSheetDto();
        dto.setServerId(1L);
        dto.setLocalId("local-1");
        dto.setCompletedAt(System.currentTimeMillis());
        LogSheetEntryDto entry = new LogSheetEntryDto();
        entry.setAssetId(48L);
        entry.setFormData(Map.of("note", "started"));
        dto.setEntries(List.of(entry));

        List<LogSheetSubmitResult> results = logSheetService.submitBatch(List.of(dto));

        assertThat(results.get(0).getOutcome()).isEqualTo("ERROR");
        assertThat(results.get(0).getError()).contains("temp");
        verify(logSheetEntryRepository, never()).save(any());
    }

    @Test
    void submitAllowsBlankAssetsWhenOnlySomeAreFilled() {
        authenticateOperator(100L);
        LogSheet s = assignedSheet(100L, System.currentTimeMillis() + 3_600_000L);
        when(logSheetRepository.findById(1L)).thenReturn(Optional.of(s));
        lenient().when(logSheetRepository.save(any(LogSheet.class))).thenAnswer(inv -> inv.getArgument(0));

        LogSheetEntry filled = sheetEntry(1L, 1L);
        filled.setClassId(7L);
        LogSheetEntry blank = sheetEntry(1L, 2L);
        blank.setClassId(7L);
        when(logSheetEntryRepository.findByLogSheetId(1L)).thenReturn(List.of(filled, blank));
        when(fieldDefinitionsService.resolveForEntries(eq(s), any())).thenReturn(List.of(toField("temp", true)));

        LogSheetDto dto = new LogSheetDto();
        dto.setServerId(1L);
        dto.setLocalId("local-1");
        dto.setCompletedAt(System.currentTimeMillis());
        LogSheetEntryDto filledDto = new LogSheetEntryDto();
        filledDto.setAssetId(1L);
        filledDto.setFormData(Map.of("temp", 42));
        LogSheetEntryDto blankDto = new LogSheetEntryDto();
        blankDto.setAssetId(2L);
        blankDto.setFormData(Map.of());
        dto.setEntries(List.of(filledDto, blankDto));

        List<LogSheetSubmitResult> results = logSheetService.submitBatch(List.of(dto));

        assertThat(results.get(0).getOutcome()).isEqualTo("SUBMITTED");
    }

    @Test
    void submitAcceptedWhenValueMatchesSnapshotRules() {
        authenticateOperator(100L);
        LogSheet s = assignedSheet(100L, System.currentTimeMillis() + 3_600_000L);
        s.setFieldDefinitionsSnapshot(List.of(snapshotField("temp", true)));
        when(logSheetRepository.findById(1L)).thenReturn(Optional.of(s));
        lenient().when(logSheetRepository.save(any(LogSheet.class))).thenAnswer(inv -> inv.getArgument(0));

        LogSheetEntry existing = sheetEntry(1L, 48L);
        existing.setClassId(7L);
        when(logSheetEntryRepository.findByLogSheetId(1L)).thenReturn(List.of(existing));
        when(fieldDefinitionsService.resolveForEntries(eq(s), any())).thenReturn(List.of(toField("temp", true)));

        LogSheetDto dto = new LogSheetDto();
        dto.setServerId(1L);
        dto.setLocalId("local-1");
        dto.setCompletedAt(System.currentTimeMillis());
        LogSheetEntryDto entry = new LogSheetEntryDto();
        entry.setAssetId(48L);
        entry.setFormData(Map.of("temp", 42));
        dto.setEntries(List.of(entry));

        List<LogSheetSubmitResult> results = logSheetService.submitBatch(List.of(dto));

        assertThat(results.get(0).getOutcome()).isEqualTo("SUBMITTED");
    }

    @Test
    void submitAllowsValuesOutsideDangerRange() {
        authenticateOperator(100L);
        LogSheet s = assignedSheet(100L, System.currentTimeMillis() + 3_600_000L);
        when(logSheetRepository.findById(1L)).thenReturn(Optional.of(s));
        lenient().when(logSheetRepository.save(any(LogSheet.class))).thenAnswer(inv -> inv.getArgument(0));
        when(logSheetRepository.submitIfStillCompletable(
                any(), any(), anyLong(), anyLong(), anyLong(), any(), any(), any(), anyCollection()))
                .thenReturn(1);

        FieldDefinition temp = toField("temp", false);
        temp.setValidation(FieldValidationSupport.build("number", null, 20.0, 80.0, 10.0, 90.0));
        LogSheetEntry existing = sheetEntry(1L, 48L);
        existing.setClassId(7L);
        when(logSheetEntryRepository.findByLogSheetId(1L)).thenReturn(List.of(existing));
        when(fieldDefinitionsService.resolveForEntries(eq(s), any())).thenReturn(List.of(temp));

        LogSheetDto dto = new LogSheetDto();
        dto.setServerId(1L);
        dto.setLocalId("local-1");
        dto.setCompletedAt(System.currentTimeMillis());
        LogSheetEntryDto entry = new LogSheetEntryDto();
        entry.setAssetId(48L);
        entry.setFormData(Map.of("temp", 95));
        dto.setEntries(List.of(entry));

        List<LogSheetSubmitResult> results = logSheetService.submitBatch(List.of(dto));

        assertThat(results.get(0).getOutcome()).isEqualTo("SUBMITTED");
    }

    @Test
    void webCompleteRejectedWhenRequiredFieldMissingOnFilledEntry() {
        authenticate(100L, "SENIOR_OPERATOR");
        LogSheet s = assignedSheet(100L, System.currentTimeMillis() + 3_600_000L);
        when(logSheetRepository.findById(1L)).thenReturn(Optional.of(s));

        LogSheetEntry entry = new LogSheetEntry();
        entry.setId(10L);
        entry.setLogSheetId(1L);
        entry.setAssetId(48L);
        entry.setClassId(7L);
        entry.setFormData(new HashMap<>());
        when(logSheetEntryRepository.findByLogSheetId(1L)).thenReturn(List.of(entry));
        FieldDefinition temp = toField("temp", true);
        FieldDefinition note = toField("note", false);
        note.setDataType("text");
        when(fieldDefinitionsService.resolveForEntries(eq(s), any())).thenReturn(List.of(temp, note));

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        logSheetService.completeFromWeb(1L, Map.of("10", Map.of("note", "started"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("temp");
    }

    @Test
    void webCompleteAllowsBlankAssetsAmongFilledOnes() {
        authenticate(100L, "SENIOR_OPERATOR");
        LogSheet s = assignedSheet(100L, System.currentTimeMillis() + 3_600_000L);
        when(logSheetRepository.findById(1L)).thenReturn(Optional.of(s));
        lenient().when(logSheetRepository.save(any(LogSheet.class))).thenAnswer(inv -> inv.getArgument(0));
        when(logSheetRepository.submitIfStillCompletable(
                any(), any(), anyLong(), anyLong(), anyLong(), any(), any(), any(), anyCollection()))
                .thenReturn(1);

        LogSheetEntry filled = new LogSheetEntry();
        filled.setId(10L);
        filled.setLogSheetId(1L);
        filled.setAssetId(1L);
        filled.setClassId(7L);
        filled.setFormData(new HashMap<>());
        LogSheetEntry blank = new LogSheetEntry();
        blank.setId(11L);
        blank.setLogSheetId(1L);
        blank.setAssetId(2L);
        blank.setClassId(7L);
        blank.setFormData(new HashMap<>());
        when(logSheetEntryRepository.findByLogSheetId(1L)).thenReturn(List.of(filled, blank));
        when(fieldDefinitionsService.resolveForEntries(eq(s), any())).thenReturn(List.of(toField("temp", true)));

        LogSheet result = logSheetService.completeFromWeb(1L, Map.of(
                "10", Map.of("temp", 22),
                "11", Map.of()));

        assertThat(result).isNotNull();
        verify(logSheetRepository).submitIfStillCompletable(
                eq(1L), any(), anyLong(), anyLong(), anyLong(), any(), any(),
                eq(LogSheetStatus.SUBMITTED), anyCollection());
    }

    private FieldDefinitionSnapshot snapshotField(String key, boolean required) {
        FieldDefinitionSnapshot snap = new FieldDefinitionSnapshot();
        snap.setClassId(7L);
        snap.setKey(key);
        snap.setDataType("number");
        snap.setRequired(required);
        return snap;
    }

    private FieldDefinition toField(String key, boolean required) {
        FieldDefinition fd = new FieldDefinition();
        fd.setClassId(7L);
        fd.setKey(key);
        fd.setDataType("number");
        fd.setRequired(required);
        return fd;
    }

    private void authenticate(Long userId, String role) {
        User user = new User();
        user.setId(userId);
        user.setUsername("user-" + userId);
        user.setPasswordHash("x");
        AppUserDetails principal = new AppUserDetails(user, Set.of(role), Set.of());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }
}
