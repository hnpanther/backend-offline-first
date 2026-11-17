package com.hnp.backendofflinefirst.entity;

import com.hnp.backendofflinefirst.domain.ApiSessionRevokeReason;
import jakarta.persistence.*;
import lombok.Data;

/**
 * Server-side record of one issued mobile-API JWT, keyed by its {@code jti} claim.
 * <p>
 * Rows outlive the token so the admin panel can show login history; a token counts
 * as usable only while {@code revokedAt} is null and {@code expiresAt} is in the future.
 */
@Entity
@Table(name = "api_sessions")
@Data
public class ApiSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "jti", nullable = false, unique = true, length = 64)
    private String jti;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "username")
    private String username;

    /** Client-supplied device name from the login payload (optional). */
    @Column(name = "device_label")
    private String deviceLabel;

    @Column(name = "user_agent", length = 512)
    private String userAgent;

    @Column(name = "ip_address", length = 64)
    private String ipAddress;

    @Column(name = "issued_at", nullable = false)
    private long issuedAt;

    @Column(name = "expires_at", nullable = false)
    private long expiresAt;

    /** Throttled touch timestamp — see {@code ApiSessionService.LAST_SEEN_THROTTLE_MS}. */
    @Column(name = "last_seen_at")
    private Long lastSeenAt;

    @Column(name = "revoked_at")
    private Long revokedAt;

    @Column(name = "revoked_by")
    private Long revokedBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "revoke_reason", length = 32)
    private ApiSessionRevokeReason revokeReason;

    /**
     * The enrolled device this session was issued on, or null.
     *
     * <p>Null for every session issued while device enrolment was off — which is every session
     * that existed before V13. Those stay valid until they expire, exactly as before: enrolment
     * is enforced at login, never by cutting a running session off.
     */
    @Column(name = "device_id")
    private Long deviceId;

    /**
     * Rounds the tablet holding this session reported it has not delivered in any form (V25) —
     * readings the server has neither applied nor kept for review. Null until the tablet reports
     * at all, which an app older than V25 never does.
     */
    @Column(name = "undelivered_count")
    private Integer undeliveredCount;

    /** When the oldest of those rounds was last worked on, by the device's clock. */
    @Column(name = "undelivered_oldest_at")
    private Long undeliveredOldestAt;

    /** Server time of the last report. */
    @Column(name = "backlog_reported_at")
    private Long backlogReportedAt;

    public boolean isRevoked() {
        return revokedAt != null;
    }

    public boolean isActiveAt(long now) {
        return !isRevoked() && expiresAt > now;
    }
}
