package com.hnp.backendofflinefirst.domain;

/**
 * Review state of an {@code NfcFaultReport}. Only {@link #OPEN} is used today — the
 * column exists so a future review workflow (e.g. under review / resolved) can be
 * added without a new migration, matching the {@code RecurrenceUnit} precedent
 * (AGENTS.md gotcha #21).
 */
public enum NfcFaultReportStatus {
    OPEN
}
