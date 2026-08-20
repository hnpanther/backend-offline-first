package com.hnp.backendofflinefirst.service;

import com.hnp.backendofflinefirst.entity.ApiKeyUsage;
import com.hnp.backendofflinefirst.repository.ApiKeyUsageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The transactional half of a usage write, in its own bean so the failure can be caught.
 *
 * <p>Same reasoning as {@link AuditRowWriter}: a {@code try/catch} inside a
 * {@code @Transactional} method cannot contain a constraint violation, because the commit that
 * follows the method throws {@code UnexpectedRollbackException} outside the block — on the async
 * thread, where nothing useful reports it.
 */
@Service
@RequiredArgsConstructor
public class ApiKeyUsageRowWriter {

    private final ApiKeyUsageRepository apiKeyUsageRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void write(ApiKeyUsage usage) {
        apiKeyUsageRepository.save(usage);
    }
}
