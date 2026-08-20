package com.hnp.backendofflinefirst.service;

import com.hnp.backendofflinefirst.entity.ApiKeyUsage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * Writes one {@code api_key_usage} row, off the request thread.
 *
 * <p>A separate bean for the same reason {@code AuditWriteService} is one: {@code @Async} works
 * through a proxy, so a self-invocation from the filter's own class would run inline and the
 * annotation would be a no-op that nobody notices.
 *
 * <p>It shares {@code auditExecutor} rather than adding a pool. That executor's
 * {@code CallerRunsPolicy} is the property that matters here (see {@code AsyncConfig}): under a
 * burst the recording degrades to a synchronous insert on the request thread instead of being
 * dropped. For an audit trail, "slower" is the right failure and "missing" is not.
 *
 * <p>{@code REQUIRES_NEW} because there is no ambient transaction to join — the filter runs
 * outside the handler's — and because a failure to record usage must never roll back anything.
 * Recording is best-effort by design: a broken audit insert is a problem to fix, not a reason
 * to fail a request that already succeeded.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ApiKeyUsageWriteService {

    private final ApiKeyUsageRowWriter apiKeyUsageRowWriter;

    @Async("auditExecutor")
    public void save(ApiKeyUsage usage) {
        try {
            apiKeyUsageRowWriter.write(usage);
        } catch (RuntimeException e) {
            // Swallowed on purpose. This runs after the response has been written; rethrowing
            // reaches nobody but the executor's uncaught-exception handler, and an integration
            // request that was served must not be reported as failed because its audit row
            // was not. The WARN is the signal that something needs looking at.
            log.warn("Could not record integration API usage for client '{}' ({} {}): {}",
                    usage.getClientName(), usage.getMethod(), usage.getPath(), e.toString());
        }
    }
}
