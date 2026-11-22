package com.hnp.backendofflinefirst.service;

import com.hnp.backendofflinefirst.dto.AttachmentSweepProgress;
import com.hnp.backendofflinefirst.logging.BusinessEventLogger;
import com.hnp.backendofflinefirst.repository.ApiKeyUsageRepository;
import com.hnp.backendofflinefirst.repository.AttachmentRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Issue B-14 (option 2): the scheduler keeps its one thread, and the two long nightly jobs only
 * start their work there — the attachment sweep on {@code sweepExecutor}, the integration usage
 * purge on its own executor. Each walk is held open on a latch, so "the scheduled call returned
 * while the work was still running" is a fact the test observes rather than a timing guess.
 */
class ScheduledLongJobsOffSchedulerThreadTest {

    private final CountDownLatch release = new CountDownLatch(1);
    private AttachmentSweepService sweep;
    private ApiKeyUsageRetentionService purge;

    @AfterEach
    void tearDown() {
        release.countDown();
        if (sweep != null) sweep.shutdown();
        if (purge != null) purge.shutdown();
    }

    // ── attachment sweep ──────────────────────────────────────────────────

    /** A sweep whose walk counts its starts and blocks until {@link #release} opens. */
    private AttachmentSweepService blockingSweep(AtomicInteger walks, AtomicReference<String> thread)
            throws Exception {
        AttachmentRepository repository = mock(AttachmentRepository.class);
        when(repository.findAllStorageKeys(any(Pageable.class))).thenReturn(Page.empty());
        AttachmentStorageService storage = mock(AttachmentStorageService.class);
        doAnswer(inv -> {
            walks.incrementAndGet();
            thread.set(Thread.currentThread().getName());
            release.await(10, TimeUnit.SECONDS);
            return null;
        }).when(storage).forEachStoredFile(any());
        AttachmentSweepService service =
                new AttachmentSweepService(repository, storage, mock(BusinessEventLogger.class));
        ReflectionTestUtils.setField(service, "scheduledSweepEnabled", true);
        ReflectionTestUtils.setField(service, "graceHours", 24);
        return service;
    }

    private static void awaitTrue(java.util.function.BooleanSupplier condition) throws InterruptedException {
        for (int i = 0; i < 200 && !condition.getAsBoolean(); i++) {
            Thread.sleep(25);
        }
        assertThat(condition.getAsBoolean()).isTrue();
    }

    @Test
    void theScheduledSweepReturnsAtOnceAndWalksOnTheSweepThread() throws Exception {
        AtomicInteger walks = new AtomicInteger();
        AtomicReference<String> thread = new AtomicReference<>();
        sweep = blockingSweep(walks, thread);

        // Inline, this call would not return until the latch opened (10 s).
        long started = System.nanoTime();
        sweep.scheduledSweep();
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).isLessThan(2_000);

        awaitTrue(() -> walks.get() == 1);
        assertThat(thread.get()).isEqualTo("attachment-sweep");
        assertThat(sweep.isRunning()).isTrue();

        release.countDown();
        awaitTrue(() -> !sweep.isRunning());
        assertThat(sweep.getProgress().getStatus()).isEqualTo(AttachmentSweepProgress.Status.COMPLETED);
    }

    @Test
    void aManualStartDuringTheScheduledSweepIsRefusedAndASecondTickIsSkipped() throws Exception {
        AtomicInteger walks = new AtomicInteger();
        sweep = blockingSweep(walks, new AtomicReference<>());

        sweep.scheduledSweep();
        awaitTrue(() -> walks.get() == 1);

        assertThatThrownBy(sweep::startSweep).isInstanceOf(IllegalStateException.class);
        sweep.scheduledSweep();   // skipped, not queued

        release.countDown();
        awaitTrue(() -> !sweep.isRunning());
        Thread.sleep(100);
        assertThat(walks.get()).isEqualTo(1);
    }

    /**
     * The race the issue found: {@code scheduledSweep} used to check {@code isRunning()} outside
     * the lock {@code startSweep} held, so both could see «not running» and two sweeps walked the
     * same root. Started together, repeatedly, exactly one may win each round.
     */
    @Test
    void aManualAndAScheduledStartAtTheSameMomentRunOneSweep() throws Exception {
        AtomicInteger walks = new AtomicInteger();
        sweep = blockingSweep(walks, new AtomicReference<>());
        ExecutorService callers = Executors.newFixedThreadPool(2);
        try {
            CyclicBarrier together = new CyclicBarrier(2);
            AtomicInteger manualStarted = new AtomicInteger();
            var manual = callers.submit(() -> {
                together.await();
                try {
                    sweep.startSweep();
                    manualStarted.incrementAndGet();
                } catch (IllegalStateException alreadyRunning) {
                    // the scheduled one won
                }
                return null;
            });
            var scheduled = callers.submit(() -> {
                together.await();
                sweep.scheduledSweep();
                return null;
            });
            manual.get(5, TimeUnit.SECONDS);
            scheduled.get(5, TimeUnit.SECONDS);

            awaitTrue(() -> walks.get() >= 1);
            Thread.sleep(200);
            assertThat(walks.get()).isEqualTo(1);
        } finally {
            release.countDown();
            callers.shutdownNow();
        }
        awaitTrue(() -> !sweep.isRunning());
        assertThat(walks.get()).isEqualTo(1);
    }

    // ── integration usage purge ───────────────────────────────────────────

    private ApiKeyUsageRetentionService purgeWith(ApiKeyUsagePurgeService batches) {
        AppSettingsService settings = mock(AppSettingsService.class);
        when(settings.getAuditRetentionDays()).thenReturn(90);
        ApiKeyUsageRetentionService service = new ApiKeyUsageRetentionService(
                mock(ApiKeyUsageRepository.class), batches, settings, mock(BusinessEventLogger.class));
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "batchSize", 10);
        return service;
    }

    @Test
    void theScheduledPurgeRunsOnItsOwnThreadAndAnOverlappingTickIsSkipped() throws Exception {
        AtomicInteger batches = new AtomicInteger();
        AtomicReference<String> thread = new AtomicReference<>();
        ApiKeyUsagePurgeService deleter = mock(ApiKeyUsagePurgeService.class);
        when(deleter.deleteBatchOlderThan(anyLong(), anyInt())).thenAnswer(inv -> {
            thread.set(Thread.currentThread().getName());
            release.await(10, TimeUnit.SECONDS);
            return batches.incrementAndGet() == 1 ? 10 : 0;
        });
        purge = purgeWith(deleter);

        long started = System.nanoTime();
        purge.scheduledPurge();
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).isLessThan(2_000);
        awaitTrue(() -> thread.get() != null);
        assertThat(thread.get()).isEqualTo("api-key-usage-purge");
        assertThat(purge.isRunning()).isTrue();

        purge.scheduledPurge();   // skipped, not queued behind the first

        release.countDown();
        awaitTrue(() -> !purge.isRunning());
        Thread.sleep(100);
        assertThat(batches.get()).isEqualTo(2);   // one run: a batch of 10, then the empty one
    }

    @Test
    void aFailingPurgeIsLoggedAndDoesNotBlockTheNextRun() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        ApiKeyUsagePurgeService deleter = mock(ApiKeyUsagePurgeService.class);
        when(deleter.deleteBatchOlderThan(anyLong(), anyInt())).thenAnswer(inv -> {
            if (calls.incrementAndGet() == 1) {
                throw new IllegalStateException("database went away");
            }
            return 0;
        });
        purge = purgeWith(deleter);

        purge.scheduledPurge();
        awaitTrue(() -> calls.get() == 1 && !purge.isRunning());

        purge.scheduledPurge();
        awaitTrue(() -> calls.get() == 2 && !purge.isRunning());
        assertThat(calls.get()).isEqualTo(2);
    }
}
