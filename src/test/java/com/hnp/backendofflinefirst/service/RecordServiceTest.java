package com.hnp.backendofflinefirst.service;

import com.hnp.backendofflinefirst.dto.DataRecordDto;
import com.hnp.backendofflinefirst.dto.RecordSubmitResult;
import com.hnp.backendofflinefirst.repository.DataRecordRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RecordServiceTest {

    @Mock DataRecordRepository dataRecordRepository;

    @InjectMocks RecordService recordService;

    @Test
    void submitBatchUpsertsByLocalId() {
        when(dataRecordRepository.findByLocalId("local-1")).thenReturn(Optional.empty());

        DataRecordDto dto = new DataRecordDto();
        dto.setLocalId("local-1");

        List<RecordSubmitResult> results = recordService.submitBatch(List.of(dto));

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getError()).isNull();
    }

    @Test
    void submitBatchRejectsBatchLargerThanTheConfiguredMax() {
        List<DataRecordDto> oversized = java.util.stream.IntStream.rangeClosed(1, 501)
                .mapToObj(i -> {
                    DataRecordDto dto = new DataRecordDto();
                    dto.setLocalId("local-" + i);
                    return dto;
                })
                .toList();

        assertThatThrownBy(() -> recordService.submitBatch(oversized))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maximum allowed is 500");

        verifyNoInteractions(dataRecordRepository);
    }

    @Test
    void submitBatchWithNullListReturnsEmptyResults() {
        assertThat(recordService.submitBatch(null)).isEmpty();
    }
}
