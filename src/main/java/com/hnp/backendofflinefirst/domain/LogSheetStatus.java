package com.hnp.backendofflinefirst.domain;

/**
 * Server-authoritative lifecycle state of a log sheet.
 * <pre>
 * PENDING     — generated, sitting in the unit pool, no assignee
 * ASSIGNED    — supervisor assigned it to an operator (in their inbox), not started
 * IN_PROGRESS — an operator claimed/started it
 * SUBMITTED   — completed and submitted (terminal)
 * EXPIRED     — due_at passed before completion; completion is locked (terminal)
 * CANCELLED   — manually cancelled (terminal)
 * </pre>
 */
public enum LogSheetStatus {
    PENDING,
    ASSIGNED,
    IN_PROGRESS,
    SUBMITTED,
    EXPIRED,
    CANCELLED;

    public boolean isTerminal() {
        return this == SUBMITTED || this == EXPIRED || this == CANCELLED;
    }

    public static LogSheetStatus fromNullable(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return LogSheetStatus.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
