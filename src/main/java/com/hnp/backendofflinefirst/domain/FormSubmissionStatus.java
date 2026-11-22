package com.hnp.backendofflinefirst.domain;

/**
 * Lifecycle of an ad-hoc form submission (carrier 3).
 *
 * <p>{@code DRAFT} is mutable and syncs like log-sheet progress. {@code SUBMITTED} is
 * immutable — corrections are a new submission later, not an edit.
 */
public enum FormSubmissionStatus {
    DRAFT,
    SUBMITTED
}
