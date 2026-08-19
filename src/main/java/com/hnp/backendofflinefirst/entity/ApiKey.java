package com.hnp.backendofflinefirst.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

/**
 * One credential belonging to a third-party system that reads the integration API.
 *
 * <p><b>This is not a user.</b> It has no role, no unit assignment and no session; the
 * {@code /integration/**} filter chain authenticates it and nothing else in the application
 * can. See the {@code api_keys} table comment in {@code V4__integration_api.sql} for why a
 * service account was rejected.
 *
 * <p>The presented key is {@code lsk_<keyId>_<secret>} and exists only once, at creation.
 * Only {@link #secretHash} survives, so a lost key is re-issued, never recovered.
 *
 * <p>Three states, and they are not the same question:
 * <ul>
 *   <li>{@code active = false} — reversible pause.</li>
 *   <li>{@code revokedAt != null} — permanent; the row stays so past usage rows remain
 *       attributable.</li>
 *   <li>{@code expiresAt} in the past — the key aged out on its own.</li>
 * </ul>
 * {@link #isUsableAt(long)} is the single place all three are combined, so no caller can
 * check two of the three and believe it has checked the key.
 */
@Entity
@Table(name = "api_keys")
@Data
public class ApiKey {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /** The system this key identifies — what appears next to every usage row. */
    @Column(name = "client_name", nullable = false)
    private String clientName;

    @Column(name = "description", length = 1000)
    private String description;

    /** Public half of the presented key; the per-request lookup column. */
    @Column(name = "key_id", nullable = false, length = 64)
    private String keyId;

    /** SHA-256 (hex) of the secret half. The secret itself was never stored. */
    @Column(name = "secret_hash", nullable = false, length = 64)
    private String secretHash;

    /** Leading characters of the presented key, so a human can match it without seeing it. */
    @Column(name = "prefix", nullable = false, length = 32)
    private String prefix;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false)
    private long createdAt;

    @Column(name = "created_by_user_id")
    private Long createdByUserId;

    /** Optional hard expiry (epoch millis); null means the key does not expire. */
    @Column(name = "expires_at")
    private Long expiresAt;

    @Column(name = "revoked_at")
    private Long revokedAt;

    @Column(name = "revoked_by")
    private Long revokedBy;

    @Column(name = "revoke_reason", length = 500)
    private String revokeReason;

    /**
     * Throttled — see {@code ApiKeyService.LAST_USED_THROTTLE_MS}. Written by a
     * {@code @Modifying} query rather than {@code save()}, which keeps a polling integration
     * from producing an {@code audit_log} row per request.
     */
    @Column(name = "last_used_at")
    private Long lastUsedAt;

    public boolean isRevoked() {
        return revokedAt != null;
    }

    public boolean isExpiredAt(long now) {
        return expiresAt != null && expiresAt <= now;
    }

    /**
     * The one combined check. Every rejection reason is folded in here so that a caller
     * cannot verify the signature, check {@code active}, and forget expiry.
     */
    public boolean isUsableAt(long now) {
        return active && !isRevoked() && !isExpiredAt(now);
    }
}
