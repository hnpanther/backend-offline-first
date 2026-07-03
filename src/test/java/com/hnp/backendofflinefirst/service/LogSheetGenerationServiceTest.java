package com.hnp.backendofflinefirst.service;

import com.hnp.backendofflinefirst.domain.LogSheetStatus;
import com.hnp.backendofflinefirst.domain.RecurrenceUnit;
import com.hnp.backendofflinefirst.entity.LogSheet;
import com.hnp.backendofflinefirst.entity.LogSheetTemplate;
import com.hnp.backendofflinefirst.entity.AssetEntry;
import com.hnp.backendofflinefirst.entity.SubFunction;
import com.hnp.backendofflinefirst.logging.BusinessEventLogger;
import com.hnp.backendofflinefirst.repository.AssetEntryRepository;
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
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LogSheetGenerationServiceTest {

    @Mock LogSheetRepository logSheetRepository;
    @Mock LogSheetEntryRepository logSheetEntryRepository;
    @Mock LogSheetTemplateRepository templateRepository;
    @Mock SubFunctionRepository subFunctionRepository;
    @Mock AssetEntryRepository assetEntryRepository;
    @Mock AssetHierarchyService hierarchyService;
    @Mock LogSheetActionLogger actionLogger;
    @Mock BusinessEventLogger businessEventLogger;
    @Mock com.hnp.backendofflinefirst.util.ReferenceLabelService referenceLabelService;

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
        lenient().when(hierarchyService.subFunctionIdsInScope(any(), any())).thenReturn(java.util.Set.of());

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
    void listAssetsInScopeReturnsMatchingAssets() {
        LogSheetTemplate t = new LogSheetTemplate();
        t.setScopeType("location");
        t.setScopeId(1L);

        SubFunction sf = new SubFunction();
        sf.setId(100L);
        sf.setCode("SF-1");
        sf.setTag("TAG-1");

        AssetEntry asset = new AssetEntry();
        asset.setId(50L);
        asset.setAssetCode("AST-1");
        asset.setAssetName("پمپ");
        asset.setNfcTagId("NFC-1");
        asset.setSubFunctionId(100L);

        when(hierarchyService.subFunctionIdsInScope("location", 1L)).thenReturn(Set.of(100L));
        when(subFunctionRepository.findAllById(Set.of(100L))).thenReturn(List.of(sf));
        when(assetEntryRepository.findAll()).thenReturn(List.of(asset));

        assertThat(service.listAssetsInScope(t)).hasSize(1);
        assertThat(service.listAssetsInScope(t).get(0).getAssetCode()).isEqualTo("AST-1");
        assertThat(service.listAssetsInScope(t).get(0).getSubFunctionCode()).isEqualTo("SF-1");
    }

    @Test
    void listAssetsInScopeReturnsEmptyWhenNoSubFunctions() {
        LogSheetTemplate t = new LogSheetTemplate();
        t.setScopeType("location");
        t.setScopeId(1L);
        when(hierarchyService.subFunctionIdsInScope("location", 1L)).thenReturn(Set.of());

        assertThat(service.listAssetsInScope(t)).isEmpty();
    }

    @Test
    void buildScopeDisplaySummaryDelegatesToLabels() {
        LogSheetTemplate t = new LogSheetTemplate();
        t.setScopeType("location");
        t.setScopeId(5L);
        when(referenceLabelService.scopeDisplayLabel("location", 5L)).thenReturn("مکان: LOC-A");

        assertThat(service.buildScopeDisplaySummary(t)).isEqualTo("مکان: LOC-A");
    }
}
