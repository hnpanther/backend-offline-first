package com.hnp.backendofflinefirst.service;

import com.hnp.backendofflinefirst.repository.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** One transactional batch delete per call (used from background purge thread). */
@Service
@RequiredArgsConstructor
public class AuditLogPurgeService {

    private final AuditLogRepository auditLogRepository;

    @Transactional
    public int deleteBatchBefore(long cutoff, int limit) {
        return auditLogRepository.deleteBatchBefore(cutoff, limit);
    }
}
