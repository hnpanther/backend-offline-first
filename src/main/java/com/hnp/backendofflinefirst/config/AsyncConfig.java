package com.hnp.backendofflinefirst.config;

import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.Map;
import java.util.concurrent.Executor;

@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean(name = "auditExecutor")
    public Executor auditExecutor(
            @Value("${app.audit.async.core-pool-size:2}") int corePoolSize,
            @Value("${app.audit.async.max-pool-size:4}") int maxPoolSize) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(256);
        executor.setThreadNamePrefix("audit-");
        executor.setTaskDecorator(AsyncConfig::wrapWithMdc);
        executor.initialize();
        return executor;
    }

    @Bean(name = "importExecutor")
    public Executor importExecutor(
            @Value("${app.import.async.core-pool-size:1}") int corePoolSize,
            @Value("${app.import.async.max-pool-size:2}") int maxPoolSize) {
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
