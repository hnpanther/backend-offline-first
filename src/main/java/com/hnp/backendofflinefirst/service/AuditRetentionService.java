package com.hnp.backendofflinefirst.service;

import com.hnp.backendofflinefirst.dto.AuditRetentionProgress;
import com.hnp.backendofflinefirst.logging.BusinessEventLogger;
import com.hnp.backendofflinefirst.repository.AuditLogRepository;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
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
@Slf4j
public class AuditRetentionService {

    private final AuditLogRepository auditLogRepository;
    private final AuditLogPurgeService auditLogPurgeService;
    private final AppSettingsService appSettingsService;
    private final BusinessEventLogger businessEventLogger;

    @Value("${app.audit.retention.batch-size:5000}")
    private int batchSize;

    /** Whether the nightly purge runs at all. See {@link #scheduledPurge()}. */
    @Value("${app.audit.retention.scheduled.enabled:true}")
    private boolean scheduledEnabled;

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

    /**
     * The nightly run, so the table does not depend on somebody remembering to press a button.
     *
     * <h2>Why this exists</h2>
     *
     * <p>{@code audit_log} is the fastest-growing table in the system and, until this method, the
     * only retention it had was {@link #startPurge()} behind an admin control. {@code
     * api_key_usage} already had a scheduled purge with the comment that it "needs a purge that
     * nobody has to remember to run" — the same argument, not applied to the larger table.
     *
     * <p>A cron rather than a fixed delay, for the reason the attachment sweep records: a fixed
     * delay pins the job to whenever the server last restarted, so a mid-shift restart moves it
     * into the middle of every following shift. 04:00 by default, an hour after the integration
     * usage purge (03:00) and two after the attachment sweep (02:00), so the three never contend.
     *
     * <p><b>It never throws.</b> A scheduled method that throws writes a stack trace on every
     * firing and has no caller to tell. A purge already running — a manual one started from the
     * panel — simply means there is nothing for this run to do.
     *
     * <p>How long is kept is <b>not</b> a property: it stays {@code audit.retention.days} in
     * {@code app_settings}, where an administrator can already see and change it, and it is
     * bounded there (1..3650, default 90). The guard below is defence in depth for an unattended
     * deleter, not a check the setting can actually fail today.
     */
    @Scheduled(cron = "${app.audit.retention.scheduled.cron:0 0 4 * * *}",
            zone = "${app.audit.retention.scheduled.zone:Asia/Tehran}")
    void scheduledPurge() {
        if (!scheduledEnabled) {
            return;
        }
        if (isRunning()) {
            log.info("Scheduled audit purge skipped: a purge is already running.");
            return;
        }
        int retentionDays = appSettingsService.getAuditRetentionDays();
        if (retentionDays < 1) {
            log.warn("Scheduled audit purge skipped: retention is {} days, which would delete "
                    + "everything. Fix audit.retention.days in settings.", retentionDays);
            return;
        }
        try {
            startPurge();
            log.info("Scheduled audit purge started (retention {} days).", retentionDays);
        } catch (RuntimeException e) {
            // Includes the race where a manual purge started between the check above and here.
            log.warn("Scheduled audit purge could not start: {}", e.getMessage());
        }
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
