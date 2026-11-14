package com.hnp.backendofflinefirst.entity;

import com.hnp.backendofflinefirst.domain.AlarmWebhookState;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

/**
 * One webhook POST to make, or that was made — the outbox row.
 *
 * <p>The payload is built when the event happens and stored verbatim, so the receiver is told
 * what was true at that moment (the supervisors of the unit <em>then</em>, the reading
 * <em>then</em>), and a retry an hour later sends the same bytes rather than a new description.
 */
@Entity
@Table(name = "alarm_webhook_deliveries")
@Data
public class AlarmWebhookDelivery {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /** Sent as {@code X-Event-Id}; the receiver deduplicates on it. */
    @Column(name = "event_id", nullable = false, length = 36)
    private String eventId;

    @Column(name = "event_type", nullable = false, length = 40)
    private String eventType;

    @Column(name = "alarm_id")
    private Long alarmId;

    /**
     * The receiver (V21). Null once that endpoint is deleted — the row stays, and
     * {@link #endpointName} still says where it was meant to go — and on the rare row queued
     * before V21 that no configured address was imported for.
     */
    @Column(name = "endpoint_id")
    private Long endpointId;

    /** The receiver's name when the event was queued, frozen so a deleted endpoint is still named. */
    @Column(name = "endpoint_name", length = 100)
    private String endpointName;

    @Column(name = "payload", nullable = false, columnDefinition = "text")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 20)
    private AlarmWebhookState state;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at", nullable = false)
    private Long nextAttemptAt;

    @Column(name = "last_attempt_at")
    private Long lastAttemptAt;

    @Column(name = "last_status_code")
    private Integer lastStatusCode;

    @Column(name = "last_error", length = 1000)
    private String lastError;

    @Column(name = "delivered_at")
    private Long deliveredAt;

    @Column(name = "created_at", nullable = false)
    private Long createdAt;
}
