package com.hnp.backendofflinefirst.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Persian error payload for mobile / JSON API clients.
 *
 * <p>{@code message} is what every PWA build reads and shows. {@code code} is present only for a
 * refusal a client must <em>branch on</em> rather than display — a stable English token that never
 * changes when the Persian sentence is reworded — and is omitted otherwise, so the body of every
 * other error is exactly what it always was.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiErrorResponse(String message, String code) {

    /** An error the client only shows. */
    public ApiErrorResponse(String message) {
        this(message, null);
    }
}
