package com.hnp.backendofflinefirst.service;

import com.hnp.backendofflinefirst.dto.LogSheetDto;
import com.hnp.backendofflinefirst.entity.LogSheet;
import com.hnp.backendofflinefirst.repository.LogSheetEntryRepository;
import com.hnp.backendofflinefirst.repository.LogSheetRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LogSheetServiceTest {

    @Mock LogSheetRepository logSheetRepository;
    @Mock LogSheetEntryRepository logSheetEntryRepository;
    @Mock LogSheetAccessService logSheetAccessService;

    @InjectMocks LogSheetService logSheetService;

    @Test
    void submitBatchCreatesNewLogSheet() {
        LogSheetDto dto = new LogSheetDto();
        dto.setLocalId("local-1");
        dto.setTemplateName("Round A");
        dto.setStatus("submitted");

        when(logSheetRepository.findByLocalId("local-1")).thenReturn(Optional.empty());
        when(logSheetAccessService.resolveOperationalUnitIdForSubmit(null)).thenReturn("unit-1");
        when(logSheetRepository.save(any(LogSheet.class))).thenAnswer(inv -> inv.getArgument(0));

        var results = logSheetService.submitBatch(List.of(dto));

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getLocalId()).isEqualTo("local-1");
        assertThat(results.get(0).getServerId()).isNotBlank();
        assertThat(results.get(0).getError()).isNull();
        verify(logSheetRepository).save(any(LogSheet.class));
    }

    @Test
    void submitBatchUpdatesExistingByLocalId() {
        LogSheet existing = new LogSheet();
        existing.setId("server-1");
        existing.setLocalId("local-1");
        existing.setOperationalUnitId("unit-1");

        LogSheetDto dto = new LogSheetDto();
        dto.setLocalId("local-1");
        dto.setStatus("approved");

        when(logSheetRepository.findByLocalId("local-1")).thenReturn(Optional.of(existing));
        when(logSheetEntryRepository.findByLogSheetId("server-1")).thenReturn(List.of());

        var results = logSheetService.submitBatch(List.of(dto));

        assertThat(results.get(0).getServerId()).isEqualTo("server-1");
        assertThat(existing.getStatus()).isEqualTo("approved");
    }
}
