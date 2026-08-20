package com.hnp.backendofflinefirst.service;

import com.hnp.backendofflinefirst.entity.AuditLog;
import com.hnp.backendofflinefirst.repository.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The transactional half of an audit write, in its own bean so the failure can be caught.
 *
 * <p><b>Why this is separate from {@link AuditWriteService}.</b> Catching the exception
 * <em>inside</em> a {@code @Transactional} method does not contain the failure: the constraint
 * violation marks the transaction rollback-only, and the commit that follows the method throws
 * {@code UnexpectedRollbackException} — outside the try block, on the async thread, where it
 * reaches only {@code SimpleAsyncUncaughtExceptionHandler} and is reported as a mystifying
 * "Transaction silently rolled back" with no clue as to which audit row was lost.
 *
 * <p>Splitting the transaction into its own bean puts the commit inside the caller's try block,
 * which is the only place it can be caught and explained. Same reasoning, and the same shape, as
 * {@code AuditLogPurgeService} and {@code ApiKeyUsagePurgeService}.
 */
@Service
@RequiredArgsConstructor
public class AuditRowWriter {

    private final AuditLogRepository auditLogRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void write(AuditLog row) {
        auditLogRepository.save(row);
    }
}
