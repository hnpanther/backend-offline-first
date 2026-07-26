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
    private Long id;

    @Column(nullable = false, unique = true, length = 64)
    private String jti;

    @Column(name = "user_id", nullable = false)
    private Long userId;

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

    public boolean isRevoked() {
        return revokedAt != null;
    }

    public boolean isActiveAt(long now) {
        return !isRevoked() && expiresAt > now;
    }
}
