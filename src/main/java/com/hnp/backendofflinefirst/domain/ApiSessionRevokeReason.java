package com.hnp.backendofflinefirst.domain;

/** Why an {@code api_sessions} row stopped being usable. */
public enum ApiSessionRevokeReason {
    /** Revoked from the admin panel. */
    ADMIN,
    /** Automatically closed because the same user logged in on another device. */
    SUPERSEDED,
    /** Client asked to end its own session. */
    LOGOUT
}
