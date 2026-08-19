package com.hnp.backendofflinefirst.service;

import com.hnp.backendofflinefirst.logging.BusinessEventLogger;
import com.hnp.backendofflinefirst.repository.ApiKeyUsageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Purges {@code api_key_usage} rows older than the configured audit retention.
 *
 * <p><b>Why this is scheduled when the {@code audit_log} purge is a button.</b> An
 * administrator triggers the audit purge because they know when it is convenient to walk a
 * large table. Nobody triggers this one, because nobody thinks about it — and that is the
 * problem it solves: an integration polling once a minute writes about 1,400 rows a day
 * whether or not anyone is watching, and a table that only ever grows is a disk that
 * eventually fills. Left to a button it would be pressed once, in the incident that the full
 * disk caused.
 *
 * <p>It reuses {@code audit.retention.days} rather than adding a setting. "How long do we keep
 * a record of who did what" is one policy question, and answering it twice invites the two
 * answers to drift — with the surprising half being the one nobody remembers configuring.
 *
 * <p>Deleted in batches for the same reason {@code AuditRetentionService} batches: one
 * {@code DELETE} over a year of rows is a long transaction and a great deal of WAL, on a table
 * whose only purpose is to be read occasionally.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ApiKeyUsageRetentionService {

    /**
     * Bounds one pass. The loop provably terminates — every deleted row leaves the candidate
     * set — but the proof is a property of the predicate, not of the loop, and a scheduled task
     * that cannot finish is worse than one that stops early and says so. Same reasoning as
     * {@code EntrySeverityBackfillRunner.MAX_PASSES} (gotcha 9c-2).
     */
    private static final int MAX_PASSES = 1_000;

    private final ApiKeyUsageRepository apiKeyUsageRepository;
    private final ApiKeyUsagePurgeService apiKeyUsagePurgeService;
    private final AppSettingsService appSettingsService;
    private final BusinessEventLogger businessEventLogger;

    @Value("${app.integration.usage-retention.batch-size:5000}")
    private int batchSize;

    @Value("${app.integration.usage-retention.enabled:true}")
    private boolean enabled;

    /**
     * A cron, not a fixed delay — the same choice, and the same reason, as the attachment
     * sweep: a fixed delay pins the run to whenever the server last restarted, so a Tuesday
     * afternoon restart means every future purge runs mid-shift, forever. 03:00 local, an hour
     * after the sweep so the two do not contend.
     */
    @Scheduled(cron = "${app.integration.usage-retention.cron:0 0 3 * * *}",
            zone = "${app.integration.usage-retention.zone:Asia/Tehran}")
    public void scheduledPurge() {
        if (!enabled) {
            return;
        }
        purge(System.currentTimeMillis());
    }

    /**
     * @return rows deleted
     */
    public long purge(long now) {
        int retentionDays = appSettingsService.getAuditRetentionDays();
        long cutoff = now - Duration.ofDays(retentionDays).toMillis();
        long deleted = 0;
        for (int pass = 0; pass < MAX_PASSES; pass++) {
            int batch = apiKeyUsagePurgeService.deleteBatchOlderThan(cutoff, batchSize);
            deleted += batch;
            if (batch == 0) {
                break;
            }
            if (pass == MAX_PASSES - 1) {
                log.warn("Integration usage purge stopped at the {}-pass limit with rows still "
                        + "older than {} days; the next run will continue.", MAX_PASSES, retentionDays);
            }
        }
        if (deleted > 0) {
            log.info("Integration API usage purge: deleted {} row(s) older than {} days", deleted, retentionDays);
            businessEventLogger.schedulerRun("api-key-usage-purge", (int) Math.min(deleted, Integer.MAX_VALUE));
        }
        return deleted;
    }

    public long countRowsEligibleForPurge(long now) {
        int retentionDays = appSettingsService.getAuditRetentionDays();
        return apiKeyUsageRepository.countByRequestedAtLessThan(
                now - Duration.ofDays(retentionDays).toMillis());
    }
}
