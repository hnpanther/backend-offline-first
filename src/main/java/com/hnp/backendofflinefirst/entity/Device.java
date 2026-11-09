package com.hnp.backendofflinefirst.entity;

import com.hnp.backendofflinefirst.domain.DeviceRevokeReason;
import com.hnp.backendofflinefirst.domain.DeviceStatus;
import jakarta.persistence.*;
import lombok.Data;

/**
 * One physical handheld, identified by the public half of a key it generated and can never
 * export.
 *
 * <p>It belongs to no user. A tablet is shared between shifts by design, so what is trusted is
 * the device; who is using it is still decided by the password at every login, independently.
 * The two lifetimes are separate on purpose — a session expiring or being revoked never touches
 * this row, and revoking this row ends every session that was issued on it.
 *
 * <p>Never deleted. A device that must stop is {@link DeviceStatus#REVOKED}, so who approved it,
 * when, and who withdrew it stays on record.
 */
@Entity
@Table(name = "devices")
@Data
public class Device {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /** ECDSA P-256 public key as X.509 SubjectPublicKeyInfo DER, base64. */
    @Column(name = "public_key", nullable = false, columnDefinition = "TEXT")
    private String publicKey;

    /** SHA-256 of the SPKI DER, lowercase hex. Unique: one row per key. */
    @Column(name = "key_fingerprint", nullable = false, unique = true, length = 64)
    private String keyFingerprint;

    @Column(name = "label")
    private String label;

    @Column(name = "user_agent", length = 512)
    private String userAgent;

    @Column(name = "request_ip", length = 64)
    private String requestIp;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private DeviceStatus status;

    @Column(name = "requested_at", nullable = false)
    private long requestedAt;

    /** Until when a {@code PENDING} request may still be completed. Meaningless otherwise. */
    @Column(name = "request_expires_at", nullable = false)
    private long requestExpiresAt;

    /**
     * SHA-256 of the one-time code, never the code. Masked in the audit trail like every other
     * credential hash ({@code AuditEntitySupport.MASKED_FIELDS}).
     */
    @Column(name = "code_hash", length = 64)
    private String codeHash;

    @Column(name = "code_issued_at")
    private Long codeIssuedAt;

    @Column(name = "code_expires_at")
    private Long codeExpiresAt;

    @Column(name = "code_issued_by")
    private Long codeIssuedBy;

    @Column(name = "code_failed_attempts", nullable = false)
    private int codeFailedAttempts;

    @Column(name = "approved_at")
    private Long approvedAt;

    @Column(name = "approved_by")
    private Long approvedBy;

    @Column(name = "revoked_at")
    private Long revokedAt;

    @Column(name = "revoked_by")
    private Long revokedBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "revoke_reason", length = 16)
    private DeviceRevokeReason revokeReason;

    @Column(name = "last_seen_at")
    private Long lastSeenAt;

    /** A pending request that can still be completed. */
    public boolean isPendingAt(long now) {
        return status == DeviceStatus.PENDING && requestExpiresAt > now;
    }

    /** A code has been issued for this request and has not run out. */
    public boolean hasLiveCodeAt(long now) {
        return codeHash != null && codeExpiresAt != null && codeExpiresAt > now;
    }

    /** Drops the one-time code, whatever state it was in. */
    public void clearCode() {
        codeHash = null;
        codeIssuedAt = null;
        codeExpiresAt = null;
        codeIssuedBy = null;
        codeFailedAttempts = 0;
    }
}
