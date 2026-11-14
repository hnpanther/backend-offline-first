package com.hnp.backendofflinefirst.repository;

import com.hnp.backendofflinefirst.domain.AlarmWebhookState;
import com.hnp.backendofflinefirst.entity.AlarmWebhookDelivery;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface AlarmWebhookDeliveryRepository extends JpaRepository<AlarmWebhookDelivery, Long> {

    /**
     * Same claim shape as the evaluation queue; see {@code AlarmEvaluationRequestRepository}.
     *
     * <p>Only deliveries whose endpoint is <b>active</b> are claimed (V21): what is queued for a
     * receiver switched off waits, untouched and unattempted, and goes out when it is switched
     * back on. A delivery whose endpoint was deleted has none, and is never claimed — the delete
     * already failed it.
     */
    @Query(value = """
            SELECT d.id FROM alarm_webhook_deliveries d
            JOIN alarm_webhook_endpoints e ON e.id = d.endpoint_id AND e.active
            WHERE d.state = 'PENDING' AND d.next_attempt_at <= :now
            ORDER BY d.id
            LIMIT :limit
            FOR UPDATE OF d SKIP LOCKED
            """, nativeQuery = true)
    List<Long> lockDue(@Param("now") long now, @Param("limit") int limit);

    /**
     * Leases the claimed rows and counts the attempt <b>before</b> the POST is made.
     *
     * <p>Counted up front so a process killed during the request still leaves the attempt
     * recorded — otherwise a receiver that crashes the sender would be retried for ever with
     * {@code attempts} stuck at zero.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE AlarmWebhookDelivery d
            SET d.nextAttemptAt = :leaseUntil, d.attempts = d.attempts + 1, d.lastAttemptAt = :now
            WHERE d.id IN :ids
            """)
    int lease(@Param("ids") Collection<Long> ids, @Param("now") long now,
              @Param("leaseUntil") long leaseUntil);

    Page<AlarmWebhookDelivery> findAllByOrderByIdDesc(Pageable pageable);

    List<AlarmWebhookDelivery> findByAlarmIdOrderByIdAsc(Long alarmId);

    long countByState(AlarmWebhookState state);

    List<AlarmWebhookDelivery> findByEndpointIdAndState(Long endpointId, AlarmWebhookState state);

    /** Rows queued before V21 that no endpoint owns — attached by the one-time import. */
    List<AlarmWebhookDelivery> findByEndpointIdIsNullAndState(AlarmWebhookState state);
}
