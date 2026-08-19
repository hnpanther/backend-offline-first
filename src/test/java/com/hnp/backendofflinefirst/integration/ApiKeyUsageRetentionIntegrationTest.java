package com.hnp.backendofflinefirst.integration;

import com.hnp.backendofflinefirst.domain.ApiKeyUsageOutcome;
import com.hnp.backendofflinefirst.entity.ApiKeyUsage;
import com.hnp.backendofflinefirst.repository.ApiKeyUsageRepository;
import com.hnp.backendofflinefirst.service.ApiKeyUsageRetentionService;
import com.hnp.backendofflinefirst.service.AppSettingsService;
import com.hnp.backendofflinefirst.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The purge that keeps {@code api_key_usage} from growing without bound.
 *
 * <p>Worth an integration test rather than a unit test for one reason: the delete is a native
 * batched statement, and the loop that drives it calls a {@code @Transactional} method on a
 * <em>separate bean</em>. Had it stayed a self-invocation the proxy would not apply, the
 * {@code @Modifying} query would fail for want of a transaction, and the failure would surface
 * at 03:00 on a server nobody is watching. Only a real context catches that.
 *
 * <p>Not {@code @Transactional} — a rolled-back test cannot observe a batched delete that
 * commits per batch.
 */
class ApiKeyUsageRetentionIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired ApiKeyUsageRepository apiKeyUsageRepository;
    @Autowired ApiKeyUsageRetentionService retentionService;
    @Autowired AppSettingsService appSettingsService;

    private final List<Long> created = new ArrayList<>();

    @AfterEach
    void tearDown() {
        created.forEach(id -> {
            try {
                apiKeyUsageRepository.deleteById(id);
            } catch (RuntimeException ignored) {
                // Already purged by the test, which is the point of most of them.
            }
        });
        created.clear();
    }

    @Test
    void deletesRowsPastRetentionAndKeepsTheRest() {
        long now = System.currentTimeMillis();
        long retentionMs = Duration.ofDays(appSettingsService.getAuditRetentionDays()).toMillis();

        Long old = save(now - retentionMs - Duration.ofDays(1).toMillis());
        Long recent = save(now - Duration.ofMinutes(5).toMillis());

        retentionService.purge(now);

        assertThat(apiKeyUsageRepository.findById(old))
                .as("older than retention — must go")
                .isEmpty();
        assertThat(apiKeyUsageRepository.findById(recent))
                .as("inside retention — must stay")
                .isPresent();
    }

    @Test
    void aRowExactlyAtTheCutoffIsKept() {
        // The boundary is `requested_at < cutoff`, so the row exactly on it survives. Stated
        // as a test because an off-by-one here silently deletes a day of audit trail.
        long now = System.currentTimeMillis();
        long retentionMs = Duration.ofDays(appSettingsService.getAuditRetentionDays()).toMillis();

        Long atCutoff = save(now - retentionMs);

        retentionService.purge(now);

        assertThat(apiKeyUsageRepository.findById(atCutoff)).isPresent();
    }

    @Test
    void purgingTwiceIsHarmless() {
        long now = System.currentTimeMillis();
        long retentionMs = Duration.ofDays(appSettingsService.getAuditRetentionDays()).toMillis();
        save(now - retentionMs - Duration.ofDays(2).toMillis());

        long first = retentionService.purge(now);
        long second = retentionService.purge(now);

        assertThat(first).isPositive();
        assertThat(second).as("nothing left to delete").isZero();
    }

    @Test
    void countsWhatIsEligibleWithoutDeletingIt() {
        long now = System.currentTimeMillis();
        long retentionMs = Duration.ofDays(appSettingsService.getAuditRetentionDays()).toMillis();
        Long old = save(now - retentionMs - Duration.ofDays(3).toMillis());

        assertThat(retentionService.countRowsEligibleForPurge(now)).isPositive();
        assertThat(apiKeyUsageRepository.findById(old)).isPresent();
    }

    /**
     * A rejected request has no key to point at, and those rows must still be purgeable —
     * the nullable foreign key is what makes recording an unknown key possible at all.
     */
    @Test
    void purgesRowsThatBelongToNoKey() {
        long now = System.currentTimeMillis();
        long retentionMs = Duration.ofDays(appSettingsService.getAuditRetentionDays()).toMillis();
        Long orphan = save(now - retentionMs - Duration.ofDays(1).toMillis());

        assertThat(apiKeyUsageRepository.findById(orphan).orElseThrow().getApiKeyId()).isNull();

        retentionService.purge(now);

        assertThat(apiKeyUsageRepository.findById(orphan)).isEmpty();
    }

    private Long save(long requestedAt) {
        ApiKeyUsage usage = new ApiKeyUsage();
        usage.setMethod("GET");
        usage.setPath("/integration/v1/log-sheets");
        usage.setStatusCode(200);
        usage.setOutcome(ApiKeyUsageOutcome.OK);
        usage.setRequestedAt(requestedAt);
        Long id = apiKeyUsageRepository.save(usage).getId();
        created.add(id);
        return id;
    }
}
