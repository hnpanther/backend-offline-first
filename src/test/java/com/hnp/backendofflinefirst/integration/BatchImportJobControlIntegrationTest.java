package com.hnp.backendofflinefirst.integration;

import com.hnp.backendofflinefirst.domain.ImportJobStatus;
import com.hnp.backendofflinefirst.entity.ImportJob;
import com.hnp.backendofflinefirst.repository.ImportJobRepository;
import com.hnp.backendofflinefirst.service.importjob.ImportJobService;
import com.hnp.backendofflinefirst.support.AbstractPostgresIntegrationTest;
import com.hnp.backendofflinefirst.support.WithAppUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The controls on the batch-import page: Stop, Delete, Abandon, and the submit gate.
 *
 * <p>Three separate defects met here, and all three were invisible from the browser.
 *
 * <p><b>CSRF.</b> The page drove Stop and Delete with a bare
 * {@code fetch(url, {method:'POST'})}. The web filter chain has CSRF enabled — only
 * {@code /api/**} disables it — so every one of those calls was rejected. Because
 * {@code WebAccessDeniedHandler} answers with a redirect rather than a 403, {@code fetch}
 * followed it and returned the HTML page with status 200, and the caller's {@code res.json()}
 * threw inside an unawaited async function. The buttons did nothing, silently, forever. These
 * tests pin the server side of that: the endpoints must reject a token-less POST and accept
 * one that carries the token.
 *
 * <p><b>The stuck job.</b> Cancellation is cooperative, so a job whose worker thread died sat
 * at RUNNING with Stop unable to touch it and Delete refusing it — and
 * {@code assertNoActiveImport()} is system-wide, so it blocked every user's next import until
 * somebody restarted the application.
 *
 * <p><b>The submit gate.</b> {@code busy} is system-wide while the job list is per-user, so
 * the page cannot infer one from the other; without it on the wire the form stayed disabled
 * until a full reload.
 */
@Transactional
class BatchImportJobControlIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String ADMIN_AUTHORITIES_GET = "GET:/batch-import";
    private static final String ADMIN_AUTHORITIES_POST = "POST:/batch-import";
    private static final String ADMIN_AUTHORITIES_JOBS = "GET:/batch-import/jobs";

    /**
     * {@code WithAppUser} builds its principal with id 1 and does not create a row, while
     * {@code import_jobs.submitted_by_user_id} carries an FK and the job list filters on that
     * id — so the seeded jobs must be owned by user 1 and user 1 must exist. On a fresh
     * container {@code AdminBootstrapRunner} makes that true; this pins it rather than
     * relying on identity numbering.
     */
    private static final long OWNER_ID = 1L;

    @Autowired WebApplicationContext context;
    @Autowired ImportJobRepository importJobRepository;
    @Autowired ImportJobService importJobService;
    @Autowired JdbcTemplate jdbcTemplate;

    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        ensureOwnerExists();
    }

    private void ensureOwnerExists() {
        Integer present = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM users WHERE id = ?", Integer.class, OWNER_ID);
        if (present != null && present > 0) {
            return;
        }
        jdbcTemplate.update("""
                INSERT INTO users (id, username, password_hash, auth_type, full_name,
                                   personnel_code, active, created_at, updated_at)
                VALUES (?, ?, 'x', 'LOCAL', 'Import Test Owner', ?, TRUE, ?, ?)
                """, OWNER_ID, "import-test-owner", "PC-IMPORT-TEST",
                System.currentTimeMillis(), System.currentTimeMillis());
    }

    // ── CSRF: the reason the buttons did nothing ──────────────────────────────

    @Test
    @WithAppUser(username = "imp-admin", roles = "ADMIN",
            authorities = {ADMIN_AUTHORITIES_GET, ADMIN_AUTHORITIES_POST})
    void aPostWithoutACsrfTokenIsRejectedAndNeverReturnsJson() throws Exception {
        ImportJob job = persistJob(ImportJobStatus.FAILED, System.currentTimeMillis());

        // This is exactly what the old page sent. It must not look like success, and it must
        // not be JSON — the page treating the redirect body as JSON is what hid the failure.
        mockMvc.perform(post("/batch-import/jobs/{uuid}/delete", job.getJobUuid()))
                .andExpect(status().is3xxRedirection());

        assertThat(importJobRepository.findByJobUuid(job.getJobUuid())).isPresent();
    }

    @Test
    @WithAppUser(username = "imp-admin", roles = "ADMIN",
            authorities = {ADMIN_AUTHORITIES_GET, ADMIN_AUTHORITIES_POST})
    void deleteSucceedsWhenTheCsrfTokenIsSent() throws Exception {
        ImportJob job = persistJob(ImportJobStatus.FAILED, System.currentTimeMillis());

        mockMvc.perform(post("/batch-import/jobs/{uuid}/delete", job.getJobUuid()).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        assertThat(importJobRepository.findByJobUuid(job.getJobUuid())).isEmpty();
    }

    @Test
    @WithAppUser(username = "imp-admin", roles = "ADMIN",
            authorities = {ADMIN_AUTHORITIES_GET, ADMIN_AUTHORITIES_POST})
    void cancelSucceedsWhenTheCsrfTokenIsSent() throws Exception {
        ImportJob job = persistJob(ImportJobStatus.PENDING, System.currentTimeMillis());

        mockMvc.perform(post("/batch-import/jobs/{uuid}/cancel", job.getJobUuid()).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        assertThat(importJobRepository.findByJobUuid(job.getJobUuid()).orElseThrow().getStatus())
                .isEqualTo(ImportJobStatus.CANCELLED);
    }

    // ── Getting out of a wedged job without a restart ─────────────────────────

    @Test
    @WithAppUser(username = "imp-admin", roles = "ADMIN",
            authorities = {ADMIN_AUTHORITIES_GET, ADMIN_AUTHORITIES_POST})
    void abandoningAWedgedJobClearsItAndReleasesTheSubmitGate() throws Exception {
        ImportJob job = persistJob(ImportJobStatus.RUNNING, staleHeartbeat());
        assertThat(importJobService.hasActiveImport()).isTrue();

        mockMvc.perform(post("/batch-import/jobs/{uuid}/abandon", job.getJobUuid()).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        ImportJob reloaded = importJobRepository.findByJobUuid(job.getJobUuid()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(ImportJobStatus.FAILED);
        assertThat(reloaded.getCompletedAt()).isNotNull();
        // The whole point: the next import is no longer blocked for everyone.
        assertThat(importJobService.hasActiveImport()).isFalse();
    }

    @Test
    @WithAppUser(username = "imp-admin", roles = "ADMIN",
            authorities = {ADMIN_AUTHORITIES_GET, ADMIN_AUTHORITIES_POST})
    void anAbandonedJobCanThenBeDeleted() throws Exception {
        ImportJob job = persistJob(ImportJobStatus.RUNNING, staleHeartbeat());

        mockMvc.perform(post("/batch-import/jobs/{uuid}/abandon", job.getJobUuid()).with(csrf()))
                .andExpect(status().isOk());
        mockMvc.perform(post("/batch-import/jobs/{uuid}/delete", job.getJobUuid()).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        assertThat(importJobRepository.findByJobUuid(job.getJobUuid())).isEmpty();
    }

    @Test
    @WithAppUser(username = "imp-admin", roles = "ADMIN",
            authorities = {ADMIN_AUTHORITIES_GET, ADMIN_AUTHORITIES_POST})
    void abandonIsRefusedForAJobThatAlreadyFinished() throws Exception {
        ImportJob job = persistJob(ImportJobStatus.COMPLETED, System.currentTimeMillis());

        mockMvc.perform(post("/batch-import/jobs/{uuid}/abandon", job.getJobUuid()).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @WithAppUser(username = "imp-admin", roles = "ADMIN",
            authorities = {ADMIN_AUTHORITIES_GET, ADMIN_AUTHORITIES_POST})
    void deleteStillRefusesALiveJob() throws Exception {
        // Abandon exists precisely so this guard does not have to be relaxed: deleting a job
        // whose thread is genuinely running would leave that thread writing to a missing row.
        ImportJob job = persistJob(ImportJobStatus.RUNNING, System.currentTimeMillis());

        mockMvc.perform(post("/batch-import/jobs/{uuid}/delete", job.getJobUuid()).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false));

        assertThat(importJobRepository.findByJobUuid(job.getJobUuid())).isPresent();
    }

    // ── The watchdog ──────────────────────────────────────────────────────────

    @Test
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void theWatchdogFailsAJobThatStoppedReportingProgress() {
        ImportJob stale = persistJob(ImportJobStatus.RUNNING, staleHeartbeat());
        ImportJob live = persistJob(ImportJobStatus.RUNNING, System.currentTimeMillis());
        try {
            int cleared = importJobService.failStaleRunningJobs(
                    System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(15));

            assertThat(cleared).isEqualTo(1);
            assertThat(importJobRepository.findById(stale.getId()).orElseThrow().getStatus())
                    .isEqualTo(ImportJobStatus.FAILED);
            // A slow import is not a dead one; only silence past the timeout counts.
            assertThat(importJobRepository.findById(live.getId()).orElseThrow().getStatus())
                    .isEqualTo(ImportJobStatus.RUNNING);
        } finally {
            importJobRepository.deleteAll(java.util.List.of(stale, live));
        }
    }

    @Test
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void forceFailWritesTheStatusWithoutTouchingTheEntity() {
        ImportJob job = persistJob(ImportJobStatus.RUNNING, staleHeartbeat());
        try {
            importJobService.forceFail(job.getId(), "worker gone");

            ImportJob reloaded = importJobRepository.findById(job.getId()).orElseThrow();
            assertThat(reloaded.getStatus()).isEqualTo(ImportJobStatus.FAILED);
            assertThat(reloaded.getErrorMessage()).isEqualTo("worker gone");
        } finally {
            importJobRepository.deleteById(job.getId());
        }
    }

    @Test
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void forceFailLeavesAnAlreadyTerminalJobAlone() {
        ImportJob job = persistJob(ImportJobStatus.COMPLETED, System.currentTimeMillis());
        try {
            // A late worker must not overwrite a decision somebody already acted on.
            importJobService.forceFail(job.getId(), "too late");

            assertThat(importJobRepository.findById(job.getId()).orElseThrow().getStatus())
                    .isEqualTo(ImportJobStatus.COMPLETED);
        } finally {
            importJobRepository.deleteById(job.getId());
        }
    }

    // ── The submit gate on the wire ───────────────────────────────────────────

    @Test
    @WithAppUser(username = "imp-admin", roles = "ADMIN",
            authorities = {ADMIN_AUTHORITIES_GET, ADMIN_AUTHORITIES_JOBS})
    void theJobsEndpointReportsBusySoThePageCanReEnableTheForm() throws Exception {
        persistJob(ImportJobStatus.RUNNING, System.currentTimeMillis());

        mockMvc.perform(get("/batch-import/jobs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.busy").value(true))
                .andExpect(jsonPath("$.jobs").isArray());
    }

    @Test
    @WithAppUser(username = "imp-admin", roles = "ADMIN",
            authorities = {ADMIN_AUTHORITIES_GET, ADMIN_AUTHORITIES_JOBS})
    void busyIsFalseOnceNothingIsQueuedOrRunning() throws Exception {
        persistJob(ImportJobStatus.COMPLETED, System.currentTimeMillis());

        mockMvc.perform(get("/batch-import/jobs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.busy").value(false));
    }

    @Test
    @WithAppUser(username = "imp-admin", roles = "ADMIN",
            authorities = {ADMIN_AUTHORITIES_GET, ADMIN_AUTHORITIES_JOBS})
    void aQuietRunningJobIsFlaggedStalledSoTheAbandonButtonAppears() throws Exception {
        persistJob(ImportJobStatus.RUNNING, staleHeartbeat());

        mockMvc.perform(get("/batch-import/jobs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobs[0].stalled").value(true));
    }

    @Test
    @WithAppUser(username = "imp-admin", roles = "ADMIN",
            authorities = {ADMIN_AUTHORITIES_GET, ADMIN_AUTHORITIES_JOBS})
    void aHealthyRunningJobIsNotFlaggedStalled() throws Exception {
        persistJob(ImportJobStatus.RUNNING, System.currentTimeMillis());

        mockMvc.perform(get("/batch-import/jobs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobs[0].stalled").value(false));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Older than any plausible tick, so both the UI hint and the watchdog treat it as dead. */
    private static long staleHeartbeat() {
        return System.currentTimeMillis() - TimeUnit.HOURS.toMillis(2);
    }

    private ImportJob persistJob(ImportJobStatus status, long heartbeatAt) {
        long now = System.currentTimeMillis();
        ImportJob job = new ImportJob();
        job.setJobUuid(java.util.UUID.randomUUID().toString());
        job.setEntityType("asset-entries");
        job.setStatus(status);
        job.setFileName("assets.xlsx");
        job.setFilePath("./data/imports/does-not-exist.xlsx");
        job.setFileSize(1024L);
        job.setTotalRows(100);
        job.setProcessedRows(25);
        job.setSubmittedByUserId(OWNER_ID);
        job.setCreatedAt(now);
        job.setStartedAt(heartbeatAt);
        job.setHeartbeatAt(heartbeatAt);
        return importJobRepository.saveAndFlush(job);
    }
}
