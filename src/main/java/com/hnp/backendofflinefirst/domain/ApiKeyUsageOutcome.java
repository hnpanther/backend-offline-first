package com.hnp.backendofflinefirst.domain;

/**
 * How one integration request ended.
 *
 * <p>Deliberately finer-grained than the HTTP status. Every rejection below answers 401, so a
 * status code alone cannot tell "this integrator never sent a key" from "this key was revoked
 * last Tuesday and somebody is still polling with it" — which are completely different
 * conversations to have with the integrator.
 */
public enum ApiKeyUsageOutcome {

    /** The request was served. */
    OK,

    /** No {@code X-API-Key} header, or an empty one. */
    MISSING_KEY,

    /** Malformed key, unknown {@code key_id}, or a secret that did not match the hash. */
    INVALID_KEY,

    /** The key exists and is paused ({@code active = false}). Reversible. */
    DISABLED_KEY,

    /** The key exists and was permanently revoked. */
    REVOKED_KEY,

    /** The key exists and its {@code expires_at} has passed. */
    EXPIRED_KEY,

    /** Authenticated, but the request itself was rejected — a bad date range, a bad status. */
    BAD_REQUEST,

    /** Authenticated, but the requested log sheet does not exist or is not exposable. */
    NOT_FOUND,

    /** Authenticated and the handler failed. */
    ERROR
}
