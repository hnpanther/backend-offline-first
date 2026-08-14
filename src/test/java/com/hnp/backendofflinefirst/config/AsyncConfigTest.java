package com.hnp.backendofflinefirst.config;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The audit pool's rejection policy.
 *
 * <p>This is the single most consequential line in {@link AsyncConfig}. The audit trail is an
 * unbounded producer — a bulk import enqueues one task per saved row — against a bounded
 * queue. With the default {@code AbortPolicy}, filling that queue throws
 * {@code TaskRejectedException} <i>into the producing thread</i>, which is how a 9,942-row
 * asset import died with a message naming an executor that has nothing to do with importing.
 */
class AsyncConfigTest {

    private final AsyncConfig asyncConfig = new AsyncConfig();

    @Test
    void auditPoolMakesTheProducerAbsorbOverflowRatherThanThrowAtIt() {
        ThreadPoolTaskExecutor executor = (ThreadPoolTaskExecutor) asyncConfig.auditExecutor(2, 4, 2000);
        try {
            assertThat(executor.getThreadPoolExecutor().getRejectedExecutionHandler())
                    .isInstanceOf(ThreadPoolExecutor.CallerRunsPolicy.class);
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void aFullAuditQueueRunsTheTaskOnTheCallerInsteadOfRejectingIt() throws Exception {
        // One thread, one queue slot: the third submission has nowhere to go.
        ThreadPoolTaskExecutor executor = (ThreadPoolTaskExecutor) asyncConfig.auditExecutor(1, 1, 1);
        CountDownLatch blockWorker = new CountDownLatch(1);
        AtomicReference<String> overflowRanOn = new AtomicReference<>();
        try {
            executor.execute(() -> await(blockWorker));   // occupies the only thread
            executor.execute(() -> { });                  // fills the only queue slot

            String callerThread = Thread.currentThread().getName();
            assertThatCode(() -> executor.execute(
                    () -> overflowRanOn.set(Thread.currentThread().getName())))
                    .doesNotThrowAnyException();

            // The point: the overflowing task ran, and it ran here. The import slows to the
            // rate audit can sustain instead of dying halfway through a file.
            assertThat(overflowRanOn.get()).isEqualTo(callerThread);
        } finally {
            blockWorker.countDown();
            executor.shutdown();
        }
    }

    @Test
    void auditPoolDrainsQueuedRowsOnShutdown() {
        ThreadPoolTaskExecutor executor = (ThreadPoolTaskExecutor) asyncConfig.auditExecutor(2, 4, 2000);
        try {
            // A queued audit row is a compliance record: dropping it at shutdown would leave a
            // committed entity change with no trail and nothing to say one was ever lost.
            assertThat(executor).hasFieldOrPropertyWithValue("waitForTasksToCompleteOnShutdown", true);
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void importPoolStaysSequential() {
        // Two concurrent imports of overlapping master data would race on the uniqueness
        // checks and produce a result neither user could explain.
        Executor executor = asyncConfig.importExecutor(1, 1);
        try {
            assertThat(((ThreadPoolTaskExecutor) executor).getMaxPoolSize()).isEqualTo(1);
        } finally {
            ((ThreadPoolTaskExecutor) executor).shutdown();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
