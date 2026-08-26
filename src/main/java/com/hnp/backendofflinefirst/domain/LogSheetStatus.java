package com.hnp.backendofflinefirst.domain;

import java.util.List;

/**
 * Server-authoritative lifecycle state of a log sheet.
 * <pre>
 * PENDING     — generated, sitting in the unit pool, no assignee
 * ASSIGNED    — supervisor assigned it to an operator (in their inbox), not started
 * IN_PROGRESS — an operator claimed/started it
 * SUBMITTED   — completed and submitted
 * APPROVED    — a supervisor reviewed the completed round and accepted it
 * VOIDED      — submitted sheet soft-invalidated (excluded from parameter reports); restorable to SUBMITTED
 * EXPIRED     — due_at passed before completion; completion is locked (terminal)
 * CANCELLED   — manually cancelled before/without completion (reserved; not used for post-submit void)
 * </pre>
 */
public enum LogSheetStatus {
    PENDING,
    ASSIGNED,
    IN_PROGRESS,
    SUBMITTED,
    APPROVED,
    VOIDED,
    EXPIRED,
    CANCELLED;

    /**
     * The statuses that mean <b>this round was completed and its readings count</b>.
     *
     * <p><b>Every condition about completed work must use this, never {@code SUBMITTED} alone.</b>
     * Approval is a review step laid on top of completion, not a different kind of completion: an
     * approved round's readings are exactly as real as an unapproved one's, and every report,
     * every export and every external feed has to treat the two identically. Only a handful of
     * places may name {@code SUBMITTED} on its own, and all of them are about the *transition*
     * rather than about the data — approve/unapprove, void, reopen.
     *
     * <p>{@code CompletedStatusConditionTest} fails the build if a status comparison names
     * {@code SUBMITTED} outside that allow-list. That guard exists because the failure it prevents
     * is silent: a missed condition produces no error and no log line, just a smaller number in a
     * report about a plant.
     */
    public static final List<LogSheetStatus> COMPLETED_STATUSES = List.of(SUBMITTED, APPROVED);

    /** Whether this round was completed — see {@link #COMPLETED_STATUSES}. */
    public boolean isCompleted() {
        return this == SUBMITTED || this == APPROVED;
    }

    public boolean isTerminal() {
        return isCompleted() || this == VOIDED || this == EXPIRED || this == CANCELLED;
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
