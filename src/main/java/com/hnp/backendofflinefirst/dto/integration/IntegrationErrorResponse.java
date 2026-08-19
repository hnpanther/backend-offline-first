package com.hnp.backendofflinefirst.dto.integration;

/**
 * The error shape of the integration API.
 *
 * <p><b>English, and machine-readable — unlike every other error surface in this application.</b>
 * The rest of the system answers in Persian because a person reads it; this API is consumed by
 * software written by somebody who may not read Persian and, more to the point, whose code has
 * to branch on <em>what</em> went wrong. A localised sentence cannot be branched on, so
 * {@link #error} carries a stable {@code snake_case} code that is part of the contract and the
 * message is only there for whoever is reading the integrator's logs.
 *
 * <p>The codes are deliberately coarse on the authentication side: every rejected key answers
 * {@code unauthorized}, whatever was actually wrong with it. Telling a caller that a key exists
 * but is disabled — or that it expired last week — is telling somebody holding a key they
 * should not have exactly how to get a working one. The real reason is recorded in
 * {@code api_key_usage}, where an administrator can see it and the caller cannot.
 */
public record IntegrationErrorResponse(String error, String message) {

    /** No key, a malformed key, an unknown key, a wrong secret, disabled, revoked or expired. */
    public static final String UNAUTHORIZED = "unauthorized";

    /** The key is fine; the request is not — a bad date range, an unsupported status. */
    public static final String INVALID_REQUEST = "invalid_request";

    /** No log sheet with that id is exposable through this API. */
    public static final String NOT_FOUND = "not_found";

    /** Anything unhandled. Never carries the exception text. */
    public static final String INTERNAL_ERROR = "internal_error";

    public static IntegrationErrorResponse unauthorized() {
        return new IntegrationErrorResponse(UNAUTHORIZED,
                "A valid, active API key is required. Send it in the X-API-Key header.");
    }

    public static IntegrationErrorResponse invalidRequest(String message) {
        return new IntegrationErrorResponse(INVALID_REQUEST, message);
    }

    public static IntegrationErrorResponse notFound(String message) {
        return new IntegrationErrorResponse(NOT_FOUND, message);
    }

    public static IntegrationErrorResponse internalError() {
        return new IntegrationErrorResponse(INTERNAL_ERROR,
                "The request could not be completed. Contact the system administrator.");
    }
}
