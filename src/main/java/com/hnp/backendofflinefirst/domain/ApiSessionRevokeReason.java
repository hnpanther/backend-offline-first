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

    /**
     * The account was deleted.
     *
     * <p><b>Rarely observable, and deliberately kept anyway.</b> {@code api_sessions.user_id} is
     * {@code ON DELETE CASCADE}, so the rows stamped with this are removed by the same
     * transaction that stamps them — the token dies because its row no longer exists, which is
     * the stronger outcome. The explicit revoke remains so that deleting a user does not depend
     * on a foreign-key rule in another file staying what it is; the web-session half of the same
     * call is in-memory and has no cascade to rely on at all.
     */
    USER_DELETED
}
