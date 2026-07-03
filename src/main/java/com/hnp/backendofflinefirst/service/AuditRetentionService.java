package com.hnp.backendofflinefirst.service;

import com.hnp.backendofflinefirst.dto.AuditRetentionProgress;
import com.hnp.backendofflinefirst.logging.BusinessEventLogger;
import com.hnp.backendofflinefirst.repository.AuditLogRepository;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Purges {@code audit_log} rows older than configured retention in background batches.
 * Cancellation is cooperative: it takes effect between batches, not mid-DELETE.
 */
@Service
@RequiredArgsConstructor
public class AuditRetentionService {

    private final AuditLogRepository auditLogRepository;
    private final AuditLogPurgeService auditLogPurgeService;
    private final AppSettingsService appSettingsService;
    private final BusinessEventLogger businessEventLogger;

    @Value("${app.audit.retention.batch-size:5000}")
    private int batchSize;

    private final AtomicReference<AuditRetentionProgress> progress =
            new AtomicReference<>(AuditRetentionProgress.idle());

    private final ExecutorService purgeExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "audit-retention");
        t.setDaemon(true);
        return t;
    });

    private volatile Future<?> runningTask;

    public AuditRetentionProgress getProgress() {
        return progress.get();
    }

    public boolean isRunning() {
        return progress.get().isRunning();
    }

    public long countRowsEligibleForPurge() {
        return auditLogRepository.countByRecordedAtLessThan(cutoffMillis(appSettingsService.getAuditRetentionDays()));
    }

    public synchronized void startPurge() {
        if (isRunning()) {
            throw new IllegalStateException("Audit purge is already running.");
        }
        int retentionDays = appSettingsService.getAuditRetentionDays();
        AuditRetentionProgress running = AuditRetentionProgress.running(retentionDays);
        progress.set(running);
        runningTask = purgeExecutor.submit(() -> executePurge(retentionDays, running));
        businessEventLogger.schedulerRun("audit-retention-start", retentionDays);
    }

    public void requestCancel() {
        AuditRetentionProgress current = progress.get();
        if (!current.isRunning()) {
            throw new IllegalStateException("No audit purge is running.");
        }
        current.getCancelRequested().set(true);
    }

    private void executePurge(int retentionDays, AuditRetentionProgress running) {
        long cutoff = cutoffMillis(retentionDays);
        long deleted = 0;
        long startedAt = running.getStartedAt() != null ? running.getStartedAt() : System.currentTimeMillis();
        try {
            while (!running.getCancelRequested().get()) {
                int removed = auditLogPurgeService.deleteBatchBefore(cutoff, batchSize);
                if (removed == 0) {
                    break;
                }
                deleted += removed;
                progress.set(running.withDeletedCount(deleted));
            }

            if (running.getCancelRequested().get()) {
                progress.set(AuditRetentionProgress.cancelled(deleted, retentionDays, startedAt));
                businessEventLogger.schedulerRun("audit-retention-cancelled", (int) deleted);
            } else {
                progress.set(AuditRetentionProgress.completed(deleted, retentionDays, startedAt));
                businessEventLogger.schedulerRun("audit-retention-completed", (int) deleted);
            }
        } catch (Exception e) {
            progress.set(AuditRetentionProgress.failed(deleted, retentionDays, startedAt, e.getMessage()));
            businessEventLogger.error("AUDIT_RETENTION", e.getMessage(), e);
        } finally {
            runningTask = null;
        }
    }

    private static long cutoffMillis(int retentionDays) {
        return System.currentTimeMillis() - TimeUnit.DAYS.toMillis(retentionDays);
    }

    @PreDestroy
    void shutdown() {
        purgeExecutor.shutdownNow();
    }
}
