package com.hnp.backendofflinefirst.service;

import com.hnp.backendofflinefirst.entity.AuditLog;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/** Separate bean so the {@link Async} proxy applies on audit INSERT. */
@Service
@RequiredArgsConstructor
@Slf4j
public class AuditWriteService {

    private final AuditRowWriter auditRowWriter;

    /**
     * Writes one audit row, off the caller's thread.
     *
     * <p><b>Failures are logged rather than thrown, and the transaction is deliberately in
     * another bean.</b> This runs on {@code auditExecutor} long after the request that caused it
     * has returned, so anything thrown here reaches nobody but the executor's default handler —
     * which is how a lost audit row used to disappear leaving only
     * "Transaction silently rolled back", naming neither the row nor the reason.
     *
     * <p>Catching inside a {@code @Transactional} method does not achieve this: the failure marks
     * the transaction rollback-only and the <em>commit</em> throws afterwards, outside the try.
     * {@link AuditRowWriter} holds the transaction so that commit happens inside the block below.
     *
     * <p>The row's own content is logged because the point of the trail is knowing what changed,
     * and a warning saying only "an audit write failed" leaves nothing to act on.
     */
    @Async("auditExecutor")
    public void save(AuditLog row) {
        try {
            auditRowWriter.write(row);
        } catch (RuntimeException e) {
            log.warn("Audit row lost — {} {} #{} by {} ({}): {}",
                    row.getAction(), row.getEntityType(), row.getEntityId(),
                    row.getActorUsername(), row.getActorUserId(), e.toString());
        }
    }
}
