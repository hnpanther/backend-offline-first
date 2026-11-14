package com.hnp.backendofflinefirst.entity;

import com.hnp.backendofflinefirst.domain.AlarmWebhookRuleScope;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.ToString;

/**
 * One receiver of alarm webhooks, registered in the panel (V21).
 *
 * <p>Both credentials are stored as written — the server presents them on every request — and so
 * both are kept out of {@code toString} (log lines), masked in {@code audit_log}
 * ({@code AuditEntitySupport.MASKED_FIELDS}) and never rendered after they are saved. Read them
 * only to send a request.
 */
@Entity
@Table(name = "alarm_webhook_endpoints")
@Data
public class AlarmWebhookEndpoint {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /** Unique, case-insensitively — it is how the delivery log names the receiver. */
    @Column(name = "name", nullable = false, length = 100)
    private String name;

    /** Absolute http(s). Checked by {@code AlarmWebhookTargetPolicy} at save and before every request. */
    @Column(name = "url", nullable = false, length = 2000)
    private String url;

    /** Sent as {@code X-API-Key} — the V17 contract — or no header when null. */
    @ToString.Exclude
    @Column(name = "api_key", length = 500)
    private String apiKey;

    /** The HMAC key behind {@code X-Webhook-Signature}. Generated, never typed, never sent. */
    @ToString.Exclude
    @Column(name = "signing_secret", nullable = false, length = 100)
    private String signingSecret;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    /** Whether supervisors' phone numbers are in this receiver's payloads — personal data leaving. */
    @Column(name = "include_contact_details", nullable = false)
    private boolean includeContactDetails = true;

    /** Comma-separated subscribed events, or null for every alarm event. Read through the service. */
    @Column(name = "event_types", length = 100)
    private String eventTypes;

    /** Every alarm, or only those of the rules listed in {@code alarm_webhook_endpoint_rules}. */
    @Enumerated(EnumType.STRING)
    @Column(name = "rule_scope", nullable = false, length = 20)
    private AlarmWebhookRuleScope ruleScope = AlarmWebhookRuleScope.ALL;

    @Column(name = "created_at", nullable = false)
    private Long createdAt;

    @Column(name = "created_by_user_id")
    private Long createdByUserId;

    @Column(name = "updated_at", nullable = false)
    private Long updatedAt;
}
