package com.hnp.backendofflinefirst.service;

import com.hnp.backendofflinefirst.repository.ApiKeyUsageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * One transactional batch delete per call.
 *
 * <p>A separate bean, exactly like {@code AuditLogPurgeService}, because {@code @Transactional}
 * works through a proxy: had {@code ApiKeyUsageRetentionService} called a {@code @Transactional}
 * method on itself, the annotation would have done nothing, the {@code @Modifying} query would
 * have failed for want of a transaction, and the purge would have been broken in a way that
 * only shows up at 03:00.
 */
@Service
@RequiredArgsConstructor
public class ApiKeyUsagePurgeService {

    private final ApiKeyUsageRepository apiKeyUsageRepository;

    @Transactional
    public int deleteBatchOlderThan(long cutoff, int batchSize) {
        return apiKeyUsageRepository.deleteBatchOlderThan(cutoff, batchSize);
    }
}
