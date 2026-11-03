package com.hnp.backendofflinefirst.domain;

/**
 * What the log-sheet list is currently showing: everything that narrows it, in one value.
 *
 * <h2>Why this is a type and not four more parameters</h2>
 *
 * <p>The list page and {@code GET /log-sheets/export} must narrow the rows <b>identically</b>.
 * The export used to take {@code status} and nothing else, so adding a filter to the page would
 * have left the Excel button exporting a different set of rows from the one on screen —
 * silently, with the same filename and the same columns, and somebody eventually mails that file
 * to a manager. One object, passed to both, makes that impossible to get wrong by forgetting.
 *
 * <p>It also keeps the parse-and-validate step in one place. {@link IntegrationLogSheetQuery}
 * made the same choice for the integration API and records the reason: coercing bad input to a
 * default and answering 200 is how a caller ends up reading the wrong month for a year.
 *
 * <h2>The range is half-open: {@code [from, to)}</h2>
 *
 * <p>Same rule as the integration API, for the same reason. A closed range forces every reader to
 * answer "does `to = 08:00` include 08:00:00.000?", and the two reasonable answers differ.
 * Half-open makes "from Farvardin 1 to Farvardin 2" exactly one day, and lets one filter's
 * {@code to} be reused as the next one's {@code from} with no overlap and no gap.
 *
 * @param status    the one status to show, or null for every status
 * @param term      a trimmed search term, or null when nothing was typed
 * @param dateField which timestamp {@link #fromAt} / {@link #toAt} apply to; never null
 * @param fromAt    inclusive lower bound in epoch millis, or null
 * @param toAt      exclusive upper bound in epoch millis, or null
 */
public record LogSheetListFilter(LogSheetStatus status,
                                 String term,
                                 LogSheetDateField dateField,
                                 Long fromAt,
                                 Long toAt) {

    /** Everything, unnarrowed. */
    public static final LogSheetListFilter NONE =
            new LogSheetListFilter(null, null, LogSheetDateField.CREATED_AT, null, null);

    /**
     * Builds a filter from raw request parameters, refusing the two things that cannot be
     * guessed at.
     *
     * <p>Dates arrive already parsed because the Persian picker's format is
     * {@code DateUtils.parseInput}'s business, not this type's, and that is an instance method on
     * a bean.
     *
     * @throws IllegalArgumentException when {@code dateField} names no known column, or when the
     *         range is empty by construction
     */
    public static LogSheetListFilter of(String status, String q, String dateField,
                                        Long fromAt, Long toAt) {
        LogSheetDateField field = LogSheetDateField.fromNullableOrDefault(dateField);
        if (field == null) {
            throw new IllegalArgumentException("Unknown log sheet date field: " + dateField);
        }
        if (fromAt != null && toAt != null && fromAt >= toAt) {
            throw new IllegalArgumentException("Log sheet date range is empty: from must be before to.");
        }
        String trimmed = q == null || q.trim().isEmpty() ? null : q.trim();
        return new LogSheetListFilter(LogSheetStatus.fromNullable(status), trimmed, field, fromAt, toAt);
    }

    /** Status only — the shape every caller had before the date filters existed. */
    public static LogSheetListFilter ofStatus(String status) {
        return new LogSheetListFilter(
                LogSheetStatus.fromNullable(status), null, LogSheetDateField.CREATED_AT, null, null);
    }

    public boolean hasTerm() {
        return term != null;
    }

    public boolean hasDateBound() {
        return fromAt != null || toAt != null;
    }
}
