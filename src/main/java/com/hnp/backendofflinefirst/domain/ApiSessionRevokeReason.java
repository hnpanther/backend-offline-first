package com.hnp.backendofflinefirst.domain;

/** Why an {@code api_sessions} row stopped being usable. */
public enum ApiSessionRevokeReason {
    /** Revoked from the admin panel. */
    ADMIN,
    /** Automatically closed because the same user logged in on another device. */
    SUPERSEDED,
    /** Client asked to end its own session. */
    LOGOUT,

    /**
     * The account was deactivated, so every live token of theirs was closed with it.
     *
     * <p>Without this, a deactivated user kept working for the remaining life of their token —
     * up to {@code auth.jwt.expiry_minutes} (8 hours by default), because the per-request check
     * only asks whether the {@code jti} still has a live row, and the principal it rebuilds from
     * the token's claims is hard-coded to {@code active = true}. "Deactivate" has to mean now.
     */
    USER_DEACTIVATED,

    /** The account was deleted. Same reasoning as {@link #USER_DEACTIVATED}. */
    USER_DELETED
}
