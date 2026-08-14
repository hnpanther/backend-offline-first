package com.hnp.backendofflinefirst.config;

import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

@Configuration
@EnableAsync
public class AsyncConfig {

    /**
     * Audit writer pool.
     * <p>
     * <b>{@link ThreadPoolExecutor.CallerRunsPolicy} is load-bearing, not a tuning knob.</b>
     * This is an unbounded producer (a bulk import loop enqueues one task per saved row)
     * against a bounded queue. With the default {@code AbortPolicy} a full queue throws
     * {@code TaskRejectedException} <i>into the calling thread</i> — so a 9,942-row asset
     * import died with "ExecutorService in active state did not accept task", a message
     * naming an executor that has nothing to do with importing and carrying no row number.
     * CallerRunsPolicy makes the producer perform the INSERT itself instead: the import
     * slows down to the rate audit can sustain, which is the correct trade. Losing audit
     * rows or losing the import are both worse than running slower.
     * <p>
     * Note the consequence: the audit INSERT then runs on the caller's thread in its own
     * {@code REQUIRES_NEW} transaction, so it borrows a second pooled connection while the
     * caller still holds one. {@code spring.datasource.hikari.maximum-pool-size} must stay
     * comfortably above the number of threads that can be mid-write at once.
     */
    @Bean(name = "auditExecutor")
    public Executor auditExecutor(
            @Value("${app.audit.async.core-pool-size:2}") int corePoolSize,
            @Value("${app.audit.async.max-pool-size:4}") int maxPoolSize,
            @Value("${app.audit.async.queue-capacity:2000}") int queueCapacity) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("audit-");
        executor.setTaskDecorator(AsyncConfig::wrapWithMdc);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        // A queued audit row is a compliance record; dropping it on shutdown would leave a
        // committed entity change with no trail. Drain, with a bound so shutdown terminates.
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }

    @Bean(name = "importExecutor")
    public Executor importExecutor(
            @Value("${app.import.async.core-pool-size:1}") int corePoolSize,
            @Value("${app.import.async.max-pool-size:1}") int maxPoolSize) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(16);
        executor.setThreadNamePrefix("import-");
        executor.setTaskDecorator(AsyncConfig::wrapWithMdc);
        executor.initialize();
        return executor;
    }

    /** Copies request MDC (correlationId, user, …) onto async audit threads. */
    static Runnable wrapWithMdc(Runnable task) {
        Map<String, String> captured = MDC.getCopyOfContextMap();
        return () -> {
            Map<String, String> previous = MDC.getCopyOfContextMap();
            if (captured != null) {
                MDC.setContextMap(captured);
            } else {
                MDC.clear();
            }
            try {
                task.run();
            } finally {
                if (previous != null) {
                    MDC.setContextMap(previous);
                } else {
                    MDC.clear();
                }
            }
        };
    }
}
