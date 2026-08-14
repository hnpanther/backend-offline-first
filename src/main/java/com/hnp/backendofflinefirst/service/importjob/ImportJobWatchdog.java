package com.hnp.backendofflinefirst.service.importjob;

import com.hnp.backendofflinefirst.config.ImportStorageProperties;
import com.hnp.backendofflinefirst.logging.BusinessEventLogger;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * Clears import jobs whose worker thread is gone.
 * <p>
 * Cancellation in this subsystem is cooperative — {@code ImportJobCancellationRegistry}
 * raises a flag that the <i>running thread</i> reads at its next progress tick. That is the
 * correct design while the thread is alive and does nothing at all once it is not, and a
 * thread can disappear without the job ever learning about it: an {@code OutOfMemoryError}
 * on a large workbook is an {@code Error}, not an {@code Exception}, and used to sail past
 * the runner's catch clause entirely.
 * <p>
 * What made that a system-wide problem rather than one bad row is
 * {@code assertNoActiveImport()}: a single job stranded at RUNNING blocks the next import
 * for <i>every</i> user, and {@code ImportJobRecoveryRunner} only runs at boot. So the
 * documented remedy for one wedged import was restarting the application. This job is the
 * reason that is no longer true; {@code ImportJobRecoveryRunner} still handles the restart
 * case, which this one cannot see (its own process died with the job).
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class ImportJobWatchdog {

    private final ImportJobService importJobService;
    private final ImportStorageProperties properties;
    private final BusinessEventLogger businessEventLogger;

    /**
     * {@code fixedDelay}, not {@code fixedRate}: the delay is measured from the end of the
     * previous scan, so a slow pass cannot let runs pile up on each other — the same reason
     * {@code LogSheetScheduler} uses it.
     */
    @Scheduled(fixedDelayString = "${app.import.watchdog-ms:60000}")
    public void failStaleJobs() {
        int timeoutMinutes = properties.getStaleTimeoutMinutes();
        if (timeoutMinutes <= 0) {
            return;
        }
        try {
            long cutoff = System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(timeoutMinutes);
            int cleared = importJobService.failStaleRunningJobs(cutoff);
            if (cleared > 0) {
                log.warn("[IMPORT_JOB] watchdog cleared {} stale job(s) after {} minutes without progress",
                        cleared, timeoutMinutes);
                businessEventLogger.error("IMPORT_WATCHDOG", "clearedStaleJobs=" + cleared);
            }
        } catch (Exception e) {
            // A watchdog that dies on one bad pass is worse than no watchdog: it stops
            // running silently and the stuck-import problem comes back without explanation.
            log.error("[IMPORT_JOB] watchdog pass failed: {}", e.getMessage(), e);
        }
    }
}
