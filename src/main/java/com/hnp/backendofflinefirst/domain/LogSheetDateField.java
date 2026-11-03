package com.hnp.backendofflinefirst.domain;

/**
 * Which of a log sheet's three timestamps a date filter applies to.
 *
 * <h2>Why the column is named rather than assumed</h2>
 *
 * <p>{@code created_at}, {@code due_at} and {@code completed_at} answer three different
 * questions — raised when, due when, finished when — and a range that silently picks one is read
 * as whichever the reader had in mind. On a list of rounds those three give genuinely different
 * row sets: a round raised last night, due this morning and completed at noon matches a
 * «from 10:00» filter on one of them and not on the others.
 *
 * <p>So the filter carries the field, the UI shows which one is in force, and an unrecognised
 * value is <b>refused</b> rather than coerced to the default — see
 * {@link LogSheetListFilter#of}. Coercing would answer 200 with rows the caller did not ask for,
 * which is the failure {@link IntegrationLogSheetQuery} documents for its own parameters.
 *
 * <h2>{@code COMPLETED_AT} and null</h2>
 *
 * <p>{@code completed_at} is null on every round that is not finished, and a comparison against
 * null is never true — so filtering on it <em>excludes</em> open rounds rather than keeping them.
 * That is the intended reading of "completed after X" and it is worth knowing before somebody
 * reports the open rounds as missing.
 *
 * <p>Deliberately <b>not</b> {@code COALESCE(completed_at, submitted_at)}, which is what the
 * reports use. There the question is "when was this work delivered" and either column answers
 * it; here the operator picked a named column and must get that column.
 */
public enum LogSheetDateField {

    CREATED_AT("createdAt"),
    DUE_AT("dueAt"),
    COMPLETED_AT("completedAt");

    /** The {@code LogSheet} attribute name, for {@code root.get(...)} in a Specification. */
    private final String attribute;

    LogSheetDateField(String attribute) {
        this.attribute = attribute;
    }

    public String attribute() {
        return attribute;
    }

    /**
     * Parses a request parameter, tolerating absence and rejecting a wrong name.
     *
     * @return the default ({@link #CREATED_AT}) for null or blank, the matching value for a
     *         recognised name, and {@code null} for anything else — which the caller turns into
     *         a refusal rather than a default
     */
    public static LogSheetDateField fromNullableOrDefault(String value) {
        if (value == null || value.isBlank()) {
            return CREATED_AT;
        }
        try {
            return LogSheetDateField.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
