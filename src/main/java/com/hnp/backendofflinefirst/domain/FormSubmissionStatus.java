package com.hnp.backendofflinefirst.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * Lifecycle of an ad-hoc form submission (carrier 3).
 *
 * <p>{@code DRAFT} is mutable. {@code SUBMITTED} freezes answers. {@code APPROVED} is a review
 * laid on top of submit (answers unchanged). {@code VOIDED} soft-invalidates a submitted filing.
 * Corrections are never silent edits of a final row.
 */
public enum FormSubmissionStatus {
    DRAFT,
    SUBMITTED,
    APPROVED,
    VOIDED;

    /** Finished filings — answers frozen; review may still move between these. */
    public static final Set<FormSubmissionStatus> FINAL_STATUSES =
            EnumSet.of(SUBMITTED, APPROVED, VOIDED);

    public boolean isFinal() {
        return FINAL_STATUSES.contains(this);
    }
}
