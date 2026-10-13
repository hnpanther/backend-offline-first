package com.hnp.backendofflinefirst.service;

import com.hnp.backendofflinefirst.dto.AuditRetentionProgress;
import com.hnp.backendofflinefirst.logging.BusinessEventLogger;
import com.hnp.backendofflinefirst.repository.AuditLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The nightly {@code audit_log} purge.
 *
 * <p>`api_key_usage` already had one, with the comment that the table "needs a purge that nobody
 * has to remember to run"; `audit_log` — the larger table — only had the button on the settings
 * page. These cases pin the three things that make an unattended deleter safe: it can be turned
 * off, it never throws, and it refuses a retention that would delete the whole table.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AuditRetentionScheduleTest {

    @Mock AuditLogRepository auditLogRepository;
    @Mock AuditLogPurgeService auditLogPurgeService;
    @Mock AppSettingsService appSettingsService;
    @Mock BusinessEventLogger businessEventLogger;

    AuditRetentionService service;

    @BeforeEach
    void setUp() {
        service = new AuditRetentionService(
                auditLogRepository, auditLogPurgeService, appSettingsService, businessEventLogger);
        ReflectionTestUtils.setField(service, "batchSize", 5000);
        ReflectionTestUtils.setField(service, "scheduledEnabled", true);
    }

    @Test
    void theNightlyRunPurgesWithTheRetentionFromSettings() {
        when(appSettingsService.getAuditRetentionDays()).thenReturn(90);
        AtomicInteger calls = new AtomicInteger();
        when(auditLogPurgeService.deleteBatchBefore(anyLong(), anyInt()))
                .thenAnswer(inv -> calls.incrementAndGet() == 1 ? 120 : 0);

        service.scheduledPurge();

        awaitUntil(() -> service.getProgress().getStatus() == AuditRetentionProgress.Status.COMPLETED);
        assertThat(service.getProgress().getDeletedCount()).isEqualTo(120);
    }

    @Test
    void itCanBeTurnedOffEntirely() {
        ReflectionTestUtils.setField(service, "scheduledEnabled", false);

        service.scheduledPurge();

        verify(auditLogPurgeService, never()).deleteBatchBefore(anyLong(), anyInt());
        assertThat(service.isRunning()).isFalse();
    }

    /**
     * Retention is bounded at 1..3650 where it is stored, so this is defence in depth — but an
     * unattended deleter reading a zero would delete the entire audit trail, which is not a
     * failure to discover in production.
     */
    @Test
    void aRetentionThatWouldDeleteEverythingIsRefused() {
        when(appSettingsService.getAuditRetentionDays()).thenReturn(0);

        service.scheduledPurge();

        verify(auditLogPurgeService, never()).deleteBatchBefore(anyLong(), anyInt());
    }

    /** A manual purge from the settings page is in progress: the scheduled run has nothing to do. */
    @Test
    void itSkipsQuietlyWhileAPurgeIsAlreadyRunningAndNeverThrows() {
        when(appSettingsService.getAuditRetentionDays()).thenReturn(90);
        when(auditLogPurgeService.deleteBatchBefore(anyLong(), anyInt())).thenAnswer(inv -> {
            Thread.sleep(300);      // hold the first purge open
            return 0;
        });

        service.startPurge();
        awaitUntil(service::isRunning);

        service.scheduledPurge();   // must not throw

        awaitUntil(() -> !service.isRunning());
    }

    @Test
    void theManualButtonStillWorksUnchanged() {
        when(appSettingsService.getAuditRetentionDays()).thenReturn(30);
        AtomicInteger calls = new AtomicInteger();
        when(auditLogPurgeService.deleteBatchBefore(anyLong(), anyInt()))
                .thenAnswer(inv -> calls.incrementAndGet() == 1 ? 7 : 0);

        service.startPurge();

        awaitUntil(() -> service.getProgress().getStatus() == AuditRetentionProgress.Status.COMPLETED);
        assertThat(service.getProgress().getDeletedCount()).isEqualTo(7);
    }

    /**
     * The purge runs on its own single-thread executor, so every assertion here is about state
     * that becomes true shortly. Polled rather than slept on: a fixed sleep is either flaky or
     * slow, and awaitility is not a dependency of this project.
     */
    private static void awaitUntil(java.util.concurrent.Callable<Boolean> condition) {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        try {
            while (System.nanoTime() < deadline) {
                if (Boolean.TRUE.equals(condition.call())) {
                    return;
                }
                Thread.sleep(10);
            }
            throw new AssertionError("condition did not become true within 5s");
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
