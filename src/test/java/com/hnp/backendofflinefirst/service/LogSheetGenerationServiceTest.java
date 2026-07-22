package com.hnp.backendofflinefirst.service;

import com.hnp.backendofflinefirst.domain.FieldDefinitionSnapshot;
import com.hnp.backendofflinefirst.domain.GenerationMode;
import com.hnp.backendofflinefirst.domain.LogSheetStatus;
import com.hnp.backendofflinefirst.domain.RecurrenceUnit;
import com.hnp.backendofflinefirst.entity.LogSheet;
import com.hnp.backendofflinefirst.entity.LogSheetEntry;
import com.hnp.backendofflinefirst.entity.LogSheetTemplate;
import com.hnp.backendofflinefirst.entity.AssetEntry;
import com.hnp.backendofflinefirst.entity.FieldDefinition;
import com.hnp.backendofflinefirst.entity.SubFunction;
import com.hnp.backendofflinefirst.logging.BusinessEventLogger;
import com.hnp.backendofflinefirst.repository.LogSheetEntryRepository;
import com.hnp.backendofflinefirst.repository.LogSheetRepository;
import com.hnp.backendofflinefirst.repository.LogSheetTemplateRepository;
import com.hnp.backendofflinefirst.repository.SubFunctionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LogSheetGenerationServiceTest {

    @Mock LogSheetRepository logSheetRepository;
    @Mock LogSheetEntryRepository logSheetEntryRepository;
    @Mock LogSheetTemplateRepository templateRepository;
    @Mock SubFunctionRepository subFunctionRepository;
    @Mock AssetHierarchyService hierarchyService;
    @Mock LogSheetActionLogger actionLogger;
    @Mock BusinessEventLogger businessEventLogger;
    @Mock com.hnp.backendofflinefirst.util.ReferenceLabelService referenceLabelService;
    @Mock LogSheetFieldDefinitionsService fieldDefinitionsService;

    @InjectMocks LogSheetGenerationService service;

    private LogSheetTemplate hourlyTemplate(long nextRunAt) {
        LogSheetTemplate t = new LogSheetTemplate();
        t.setId(1L);
        t.setName("hourly");
        t.setRecurrenceUnit(RecurrenceUnit.HOUR);
        t.setRecurrenceEvery(1);
        t.setCompletionWindowMinutes(60);
        t.setNextRunAt(nextRunAt);
        return t; // no scope -> no entries to pre-populate
    }

    @Test
    void backfillsMissedRunsAsExpiredAndLeavesCurrentPending() {
        long now = System.currentTimeMillis();
        long threeHoursAgo = now - 3 * 3_600_000L;
        LogSheetTemplate t = hourlyTemplate(threeHoursAgo);
        lenient().when(hierarchyService.findAssetsInScope(any(), any(), any())).thenReturn(List.of());

        service.runScheduled(t, now, 500);

        ArgumentCaptor<LogSheet> captor = ArgumentCaptor.forClass(LogSheet.class);
        verify(logSheetRepository, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        List<LogSheet> saved = captor.getAllValues();

        // 3 missed hourly runs (all overdue) + the current live one.
        assertThat(saved).hasSize(4);
        assertThat(saved.subList(0, 3)).allMatch(s -> s.getStatus() == LogSheetStatus.EXPIRED);
        assertThat(saved.get(3).getStatus()).isEqualTo(LogSheetStatus.PENDING);
        // nextRunAt advanced into the future.
        assertThat(t.getNextRunAt()).isGreaterThan(now);
    }

    @Test
    void zeroBackfillSkipsOutageBacklogAndParksOnNextFutureOccurrence() {
        long hour = 3_600_000L;
        long now = 1_700_000_000_000L; // fixed instant
        long yesterdayCursor = now - 27 * hour;
        LogSheetTemplate t = hourlyTemplate(yesterdayCursor);
        lenient().when(hierarchyService.findAssetsInScope(any(), any(), any())).thenReturn(List.of());

        service.runScheduled(t, now, 0);

        verify(logSheetRepository, org.mockito.Mockito.never()).save(any(LogSheet.class));
        assertThat(t.getNextRunAt()).isEqualTo(now + hour);
        assertThat(t.getLastRunAt()).isEqualTo(now);
    }

    @Test
    void zeroBackfillStillGeneratesSingleLiveDueOccurrence() {
        long hour = 3_600_000L;
        long now = 1_700_000_000_000L;
        long dueAt = now; // exactly due, no backlog behind it
        LogSheetTemplate t = hourlyTemplate(dueAt);
        lenient().when(hierarchyService.findAssetsInScope(any(), any(), any())).thenReturn(List.of());

        service.runScheduled(t, now, 0);

        ArgumentCaptor<LogSheet> captor = ArgumentCaptor.forClass(LogSheet.class);
        verify(logSheetRepository, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        assertThat(captor.getAllValues()).hasSize(1);
        assertThat(captor.getValue().getStatus()).isEqualTo(LogSheetStatus.PENDING);
        assertThat(t.getNextRunAt()).isEqualTo(now + hour);
    }

    @Test
    void zeroBackfillAfterResumeGeneratesWhenFutureOccurrenceBecomesDue() {
        long hour = 3_600_000L;
        long resumeAt = 1_700_000_000_000L; // 10:45 equivalent
        long cursorFromOutage = resumeAt - 27 * hour;
        LogSheetTemplate t = hourlyTemplate(cursorFromOutage);
        lenient().when(hierarchyService.findAssetsInScope(any(), any(), any())).thenReturn(List.of());

        service.runScheduled(t, resumeAt, 0);
        long parked = t.getNextRunAt();
        assertThat(parked).isEqualTo(resumeAt + hour); // 10:55 equivalent
        verify(logSheetRepository, org.mockito.Mockito.never()).save(any(LogSheet.class));

        // Later: that parked occurrence becomes due
        service.runScheduled(t, parked, 0);

        ArgumentCaptor<LogSheet> captor = ArgumentCaptor.forClass(LogSheet.class);
        verify(logSheetRepository, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        assertThat(captor.getAllValues()).hasSize(1);
        assertThat(t.getNextRunAt()).isEqualTo(parked + hour);
    }

    @Test
    void zeroBackfillParksOnOccurrenceTwoMinutesAheadAndCreatesItWhenDue() {
        long hour = 3_600_000L;
        long twoMinutes = 2 * 60_000L;
        // Grid: ... , nextSlot-1h, nextSlot, nextSlot+1h
        long nextSlot = 1_700_001_200_000L; // upcoming occurrence (e.g. 10:55)
        long now = nextSlot - twoMinutes;   // resume 2 minutes before that slot (e.g. 10:53)
        long cursorFromOutage = nextSlot - 27 * hour;

        LogSheetTemplate t = hourlyTemplate(cursorFromOutage);
        lenient().when(hierarchyService.findAssetsInScope(any(), any(), any())).thenReturn(List.of());

        service.runScheduled(t, now, 0);

        verify(logSheetRepository, org.mockito.Mockito.never()).save(any(LogSheet.class));
        assertThat(t.getNextRunAt())
                .as("cursor must park on the next future slot even when it is only 2 minutes away")
                .isEqualTo(nextSlot);

        // Scheduler tick when that slot becomes due
        org.mockito.Mockito.clearInvocations(logSheetRepository);
        service.runScheduled(t, nextSlot, 0);

        ArgumentCaptor<LogSheet> captor = ArgumentCaptor.forClass(LogSheet.class);
        verify(logSheetRepository, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        assertThat(captor.getAllValues()).hasSize(1);
        assertThat(captor.getValue().getCreatedAt()).isEqualTo(nextSlot);
        assertThat(captor.getValue().getStatus()).isEqualTo(LogSheetStatus.PENDING);
        assertThat(t.getNextRunAt()).isEqualTo(nextSlot + hour);
    }

    @Test
    void positiveBackfillCapGeneratesOldestFirstThenSkipsRemainder() {
        long hour = 3_600_000L;
        long now = 1_700_000_000_000L;
        long fourHoursAgo = now - 4 * hour;
        LogSheetTemplate t = hourlyTemplate(fourHoursAgo);
        lenient().when(hierarchyService.findAssetsInScope(any(), any(), any())).thenReturn(List.of());

        service.runScheduled(t, now, 2);

        ArgumentCaptor<LogSheet> captor = ArgumentCaptor.forClass(LogSheet.class);
        verify(logSheetRepository, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        assertThat(captor.getAllValues()).hasSize(2);
        assertThat(t.getNextRunAt()).isGreaterThan(now);
    }

    @Test
    void listAssetsInScopeUsesEffectiveNfcFromSubFunctionWhenAssetNfcEmpty() {
        LogSheetTemplate t = new LogSheetTemplate();
        t.setScopeType("location");
        t.setScopeId(1L);
        t.setClassId(5L);

        SubFunction sf = new SubFunction();
        sf.setId(100L);
        sf.setCode("SF-1");
        sf.setTag("TAG-1");

        AssetEntry asset = new AssetEntry();
        asset.setId(50L);
        asset.setAssetCode("AST-1");
        asset.setAssetName("پمپ");
        asset.setClassId(5L);
        asset.setSubFunctionId(100L);

        when(hierarchyService.findAssetsInScope("location", 1L, 5L)).thenReturn(List.of(asset));
        when(subFunctionRepository.findAllById(Set.of(100L))).thenReturn(List.of(sf));

        assertThat(service.listAssetsInScope(t).get(0).getNfcTagId()).isEqualTo("TAG-1");
    }

    @Test
    void listAssetsInScopeReturnsMatchingAssets() {
        LogSheetTemplate t = new LogSheetTemplate();
        t.setScopeType("location");
        t.setScopeId(1L);
        t.setClassId(5L);

        SubFunction sf = new SubFunction();
        sf.setId(100L);
        sf.setCode("SF-1");
        sf.setTag("TAG-1");

        AssetEntry asset = new AssetEntry();
        asset.setId(50L);
        asset.setAssetCode("AST-1");
        asset.setAssetName("پمپ");
        asset.setNfcTagId("NFC-1");
        asset.setClassId(5L);
        asset.setSubFunctionId(100L);

        when(hierarchyService.findAssetsInScope("location", 1L, 5L)).thenReturn(List.of(asset));
        when(subFunctionRepository.findAllById(Set.of(100L))).thenReturn(List.of(sf));

        assertThat(service.listAssetsInScope(t)).hasSize(1);
        assertThat(service.listAssetsInScope(t).get(0).getAssetCode()).isEqualTo("AST-1");
        assertThat(service.listAssetsInScope(t).get(0).getSubFunctionCode()).isEqualTo("SF-1");
    }

    @Test
    void listAssetsInScopeReturnsEmptyWhenNoAssets() {
        LogSheetTemplate t = new LogSheetTemplate();
        t.setScopeType("location");
        t.setScopeId(1L);
        t.setClassId(5L);
        when(hierarchyService.findAssetsInScope("location", 1L, 5L)).thenReturn(List.of());

        assertThat(service.listAssetsInScope(t)).isEmpty();
    }

    @Test
    void listAssetsInScopeFiltersByHierarchyAndAssetClass() {
        LogSheetTemplate t = new LogSheetTemplate();
        t.setScopeType("location");
        t.setScopeId(1L);
        t.setClassId(7L);

        SubFunction sf = new SubFunction();
        sf.setId(100L);
        sf.setCode("SF-1");
        sf.setTag("TAG-1");

        AssetEntry inScope = new AssetEntry();
        inScope.setId(50L);
        inScope.setAssetCode("AST-1");
        inScope.setAssetName("پمپ");
        inScope.setClassId(7L);
        inScope.setSubFunctionId(100L);

        when(hierarchyService.findAssetsInScope("location", 1L, 7L)).thenReturn(List.of(inScope));
        when(subFunctionRepository.findAllById(Set.of(100L))).thenReturn(List.of(sf));

        assertThat(service.listAssetsInScope(t)).hasSize(1);
        assertThat(service.listAssetsInScope(t).get(0).getAssetCode()).isEqualTo("AST-1");
    }

    @Test
    void listAssetsInScopeReturnsEmptyWhenClassIdMissing() {
        LogSheetTemplate t = new LogSheetTemplate();
        t.setScopeType("location");
        t.setScopeId(1L);

        assertThat(service.listAssetsInScope(t)).isEmpty();
    }

    @Test
    void buildScopeDisplaySummaryDelegatesToLabels() {
        LogSheetTemplate t = new LogSheetTemplate();
        t.setScopeType("location");
        t.setScopeId(5L);
        t.setClassId(3L);
        when(referenceLabelService.templateScopeDisplayLabel("location", 5L, 3L))
                .thenReturn("مکان: LOC-A · کلاس: پمپ");

        assertThat(service.buildScopeDisplaySummary(t)).isEqualTo("مکان: LOC-A · کلاس: پمپ");
    }

    @Test
    void prepopulatedEntriesHaveNoTimestampsUntilDataSaved() {
        long now = System.currentTimeMillis();
        LogSheetTemplate t = new LogSheetTemplate();
        t.setId(1L);
        t.setName("hourly");
        t.setScopeType("location");
        t.setScopeId(1L);
        t.setClassId(5L);
        t.setOperationalUnitId(10L);
        t.setCompletionWindowMinutes(60);
        t.setActive(true);

        SubFunction sf = new SubFunction();
        sf.setId(100L);
        sf.setCode("SF-1");
        sf.setTag("TAG-1");

        AssetEntry asset = new AssetEntry();
        asset.setId(50L);
        asset.setAssetCode("AST-1");
        asset.setAssetName("پمپ");
        asset.setClassId(5L);
        asset.setSubFunctionId(100L);

        when(hierarchyService.findAssetsInScope("location", 1L, 5L)).thenReturn(List.of(asset));
        when(subFunctionRepository.findAllById(Set.of(100L))).thenReturn(List.of(sf));
        when(logSheetRepository.save(any(LogSheet.class))).thenAnswer(inv -> {
            LogSheet sheet = inv.getArgument(0);
            sheet.setId(99L);
            return sheet;
        });

        service.generateAt(t, GenerationMode.MANUAL, 1L, now, now);

        ArgumentCaptor<java.util.List<LogSheetEntry>> captor = ArgumentCaptor.forClass(java.util.List.class);
        verify(logSheetEntryRepository).saveAll(captor.capture());
        assertThat(captor.getValue()).hasSize(1);
        assertThat(captor.getValue().get(0).getCreatedAt()).isNull();
        assertThat(captor.getValue().get(0).getUpdatedAt()).isNull();
        assertThat(captor.getValue().get(0).getAssetId()).isEqualTo(50L);
        assertThat(captor.getValue().get(0).getNfcTagId()).isEqualTo("TAG-1");
    }

    @Test
    void generateFromTemplateAndPreviewResolveSameScopedAssets() {
        long now = System.currentTimeMillis();
        LogSheetTemplate t = new LogSheetTemplate();
        t.setId(1L);
        t.setName("pumps");
        t.setScopeType("system");
        t.setScopeId(20L);
        t.setClassId(5L);
        t.setOperationalUnitId(10L);
        t.setActive(true);

        SubFunction sf = new SubFunction();
        sf.setId(100L);
        sf.setCode("SF-1");
        sf.setTag("TAG-1");

        AssetEntry a1 = new AssetEntry();
        a1.setId(50L);
        a1.setAssetCode("AST-1");
        a1.setAssetName("پمپ ۱");
        a1.setClassId(5L);
        a1.setSubFunctionId(100L);

        AssetEntry a2 = new AssetEntry();
        a2.setId(51L);
        a2.setAssetCode("AST-2");
        a2.setAssetName("پمپ ۲");
        a2.setClassId(5L);
        a2.setSubFunctionId(100L);

        when(hierarchyService.findAssetsInScope("system", 20L, 5L)).thenReturn(List.of(a1, a2));
        when(subFunctionRepository.findAllById(Set.of(100L))).thenReturn(List.of(sf));
        when(logSheetRepository.save(any(LogSheet.class))).thenAnswer(inv -> {
            LogSheet sheet = inv.getArgument(0);
            sheet.setId(99L);
            return sheet;
        });

        var preview = service.listAssetsInScope(t);
        service.generateFromTemplate(t, GenerationMode.MANUAL, 1L, now);

        ArgumentCaptor<java.util.List<LogSheetEntry>> captor = ArgumentCaptor.forClass(java.util.List.class);
        verify(logSheetEntryRepository).saveAll(captor.capture());

        assertThat(preview).extracting(r -> r.getAssetCode()).containsExactly("AST-1", "AST-2");
        assertThat(captor.getValue()).extracting(LogSheetEntry::getAssetId).containsExactly(50L, 51L);
        verify(hierarchyService, org.mockito.Mockito.times(2)).findAssetsInScope("system", 20L, 5L);
    }

    @Test
    void generateAtCapturesFieldDefinitionSnapshot() {
        long now = System.currentTimeMillis();
        LogSheetTemplate t = new LogSheetTemplate();
        t.setId(1L);
        t.setName("pumps");
        t.setScopeType("location");
        t.setScopeId(1L);
        t.setClassId(5L);
        t.setOperationalUnitId(10L);
        t.setCompletionWindowMinutes(60);
        t.setActive(true);

        FieldDefinitionSnapshot snap = new FieldDefinitionSnapshot();
        snap.setClassId(5L);
        snap.setKey("temp");
        when(fieldDefinitionsService.captureSnapshot(5L)).thenReturn(List.of(snap));
        lenient().when(hierarchyService.findAssetsInScope(any(), any(), any())).thenReturn(List.of());
        when(logSheetRepository.save(any(LogSheet.class))).thenAnswer(inv -> {
            LogSheet sheet = inv.getArgument(0);
            sheet.setId(99L);
            return sheet;
        });

        LogSheet created = service.generateAt(t, GenerationMode.MANUAL, 1L, now, now);

        assertThat(created.getFieldDefinitionsSnapshot()).containsExactly(snap);
        verify(fieldDefinitionsService).captureSnapshot(eq(5L));
    }
}
