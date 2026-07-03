package com.hnp.backendofflinefirst.service;

import com.hnp.backendofflinefirst.entity.AuditLog;
import com.hnp.backendofflinefirst.repository.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Separate bean so {@link Async} proxy applies on audit INSERT. */
@Service
@RequiredArgsConstructor
public class AuditWriteService {

    private final AuditLogRepository auditLogRepository;

    @Async("auditExecutor")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void save(AuditLog row) {
        auditLogRepository.save(row);
    }
}
