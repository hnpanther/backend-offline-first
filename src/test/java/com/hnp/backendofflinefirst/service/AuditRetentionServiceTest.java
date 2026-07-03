package com.hnp.backendofflinefirst.service;

import com.hnp.backendofflinefirst.dto.AuditRetentionProgress;
import com.hnp.backendofflinefirst.logging.BusinessEventLogger;
import com.hnp.backendofflinefirst.repository.AuditLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuditRetentionServiceTest {

    @Mock AuditLogRepository auditLogRepository;
    @Mock AuditLogPurgeService auditLogPurgeService;
    @Mock AppSettingsService appSettingsService;
    @Mock BusinessEventLogger businessEventLogger;

    @InjectMocks AuditRetentionService auditRetentionService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(auditRetentionService, "batchSize", 2);
        when(appSettingsService.getAuditRetentionDays()).thenReturn(90);
    }

    @Test
    void startPurgeDeletesInBatchesUntilEmpty() throws Exception {
        when(auditLogPurgeService.deleteBatchBefore(anyLong(), anyInt()))
                .thenReturn(2, 2, 0);

        auditRetentionService.startPurge();
        TimeUnit.SECONDS.sleep(2);

        AuditRetentionProgress progress = auditRetentionService.getProgress();
        assertThat(progress.getStatus()).isEqualTo(AuditRetentionProgress.Status.COMPLETED);
        assertThat(progress.getDeletedCount()).isEqualTo(4);
        verify(auditLogPurgeService, atLeastOnce()).deleteBatchBefore(anyLong(), anyInt());
    }

    @Test
    void cannotStartSecondPurgeWhileRunning() throws Exception {
        lenient().when(auditLogPurgeService.deleteBatchBefore(anyLong(), anyInt())).thenAnswer(inv -> {
            Thread.sleep(500);
            return 1;
        });

        auditRetentionService.startPurge();
        Thread.sleep(100);
        assertThatThrownBy(() -> auditRetentionService.startPurge())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void cancelStopsBetweenBatches() throws Exception {
        when(auditLogPurgeService.deleteBatchBefore(anyLong(), anyInt())).thenAnswer(inv -> {
            Thread.sleep(150);
            return 2;
        });

        auditRetentionService.startPurge();
        Thread.sleep(200);
        auditRetentionService.requestCancel();
        TimeUnit.SECONDS.sleep(2);

        AuditRetentionProgress progress = auditRetentionService.getProgress();
        assertThat(progress.getStatus()).isEqualTo(AuditRetentionProgress.Status.CANCELLED);
        assertThat(progress.getDeletedCount()).isGreaterThan(0);
    }
}
