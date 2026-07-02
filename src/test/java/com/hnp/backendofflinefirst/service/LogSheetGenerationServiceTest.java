package com.hnp.backendofflinefirst.service;

import com.hnp.backendofflinefirst.domain.LogSheetStatus;
import com.hnp.backendofflinefirst.domain.RecurrenceUnit;
import com.hnp.backendofflinefirst.entity.LogSheet;
import com.hnp.backendofflinefirst.entity.LogSheetTemplate;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class LogSheetGenerationServiceTest {

    @Mock LogSheetRepository logSheetRepository;
    @Mock LogSheetEntryRepository logSheetEntryRepository;
    @Mock LogSheetTemplateRepository templateRepository;
    @Mock SubFunctionRepository subFunctionRepository;
    @Mock AssetEntryRepository assetEntryRepository;
    @Mock AssetHierarchyService hierarchyService;
    @Mock LogSheetActionLogger actionLogger;

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
}
