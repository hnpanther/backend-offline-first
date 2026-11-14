package com.hnp.backendofflinefirst.domain;

import java.util.Map;

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
 * <h2>A filter narrows; it never widens (roadmap §26)</h2>
 *
 * <p>{@code unitId} is <b>intersected</b> with the viewer's visible units by
 * {@code LogSheetAccessService}, never substituted for them. A supervisor of units 3 and 5 who
 * edits the URL to {@code unitId=9} gets an empty page, not unit 9's rounds — the same rule
 * {@code /reports/exceptions} applies to its own unit picker. Nothing in this type knows about
 * scope, on purpose: a filter is what the user asked for, scope is what they are allowed.
 *
 * @param status         the one status to show, or null for every status
 * @param term           a trimmed search term, or null when nothing was typed. Matched against the
 *                       template name, the operator name and the responsible unit (name or code) —
 *                       and, when it is a whole number, against the sheet's own id
 * @param dateField      which timestamp {@link #fromAt} / {@link #toAt} apply to; never null
 * @param fromAt         inclusive lower bound in epoch millis, or null
 * @param toAt           exclusive upper bound in epoch millis, or null
 * @param unitId         the responsible operational unit, exactly — not its descendants. That
 *                       is the dashboard's per-unit row ({@code GROUP BY operational_unit_id}),
 *                       so a row's link and the list it opens count the same rounds
 * @param templateId     rounds raised from one template; a custom sheet has none and never matches
 * @param origin         {@code MANUAL} or {@code SCHEDULED}, or null for both
 * @param assigneeUserId the person currently holding the round
 * @param overdueAsOf    when non-null, only rounds still open whose deadline is before this
 *                       instant — the dashboard's «عقب‌افتاده», judged against one «now» for the
 *                       page and its export
 * @param categoryId     rounds whose frozen category is this one (roadmap §28)
 */
public record LogSheetListFilter(LogSheetStatus status,
                                 String term,
                                 LogSheetDateField dateField,
                                 Long fromAt,
                                 Long toAt,
                                 Long unitId,
                                 Long templateId,
                                 GenerationMode origin,
                                 Long assigneeUserId,
                                 Long overdueAsOf,
                                 Long categoryId) {

    /** Everything, unnarrowed. */
    public static final LogSheetListFilter NONE =
            new LogSheetListFilter(null, null, LogSheetDateField.CREATED_AT, null, null);

    /** The shape every caller had before the unit / template / origin / assignee filters. */
    public LogSheetListFilter(LogSheetStatus status, String term, LogSheetDateField dateField,
                              Long fromAt, Long toAt) {
        this(status, term, dateField, fromAt, toAt, null, null, null, null, null, null);
    }

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

    /**
     * The whole filter from the request's parameters — the one parser behind the list page and
     * its Excel export.
     *
     * <p><b>Ids and the origin are refused, not ignored, when they cannot be read.</b> A link
     * that says {@code unitId=abc} was built by hand or truncated, and answering it with every
     * unit's rounds under a heading that implies a filter is the wrong-rows-with-a-200 failure
     * the date field already refuses. Empty means «no filter», because that is what a cleared
     * {@code <select>} posts.
     *
     * @param params   the request's parameters, first value of each
     * @param fromAt   {@code fromAt}, already parsed by the Persian picker's parser
     * @param toAt     {@code toAt}, likewise
     * @param now      the instant «overdue» is judged against
     */
    public static LogSheetListFilter fromParameters(Map<String, String> params, Long fromAt, Long toAt,
                                                    long now) {
        LogSheetListFilter base = of(params.get("status"), params.get("q"), params.get("dateField"),
                fromAt, toAt);
        return new LogSheetListFilter(base.status(), base.term(), base.dateField(),
                base.fromAt(), base.toAt(),
                parseId("unitId", params.get("unitId")),
                parseId("templateId", params.get("templateId")),
                parseOrigin(params.get("origin")),
                parseId("assigneeUserId", params.get("assigneeUserId")),
                isTrue(params.get("overdue")) ? now : null,
                parseId("categoryId", params.get("categoryId")));
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

    public boolean overdueOnly() {
        return overdueAsOf != null;
    }

    /**
     * The search term as a sheet id, when it is one — «4711» or «۴۷۱۱», since the panel is used
     * with a Persian keyboard and a supervisor reading a number off a printed round types it in
     * whichever digits that keyboard gives.
     *
     * @return the id, or null when the term is not a whole number that fits in a {@code long}
     */
    public Long termAsId() {
        if (term == null) {
            return null;
        }
        String digits = latinDigits(term);
        if (digits.isEmpty() || digits.length() > 18) {
            return null;
        }
        for (int i = 0; i < digits.length(); i++) {
            if (digits.charAt(i) < '0' || digits.charAt(i) > '9') {
                return null;
            }
        }
        return Long.parseLong(digits);
    }

    /**
     * How many of the collapsible filters are narrowing the list — NOT counting the search box,
     * which is never folded away. The page puts it on the toggle and opens the bar when it is
     * above zero, because a folded bar hiding an active filter is a short list with nothing on
     * screen to explain it.
     */
    public int activeFilterCount() {
        return (status != null ? 1 : 0)
                + (fromAt != null ? 1 : 0)
                + (toAt != null ? 1 : 0)
                + (unitId != null ? 1 : 0)
                + (templateId != null ? 1 : 0)
                + (origin != null ? 1 : 0)
                + (assigneeUserId != null ? 1 : 0)
                + (overdueAsOf != null ? 1 : 0)
                + (categoryId != null ? 1 : 0);
    }

    // ------------------------------------------------------------------ parsing

    private static Long parseId(String name, String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.length() > 18 || !trimmed.chars().allMatch(c -> c >= '0' && c <= '9')) {
            throw new IllegalArgumentException("Invalid log sheet filter: " + name);
        }
        long value = Long.parseLong(trimmed);
        if (value <= 0) {
            throw new IllegalArgumentException("Invalid log sheet filter: " + name);
        }
        return value;
    }

    private static GenerationMode parseOrigin(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return GenerationMode.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Invalid log sheet filter: origin");
        }
    }

    private static boolean isTrue(String raw) {
        return raw != null && ("true".equalsIgnoreCase(raw.trim()) || "1".equals(raw.trim())
                || "on".equalsIgnoreCase(raw.trim()));
    }

    /** Persian (U+06F0–U+06F9) and Arabic-Indic (U+0660–U+0669) digits to ASCII; trims. */
    private static String latinDigits(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '۰' && c <= '۹') {
                out.append((char) ('0' + (c - '۰')));
            } else if (c >= '٠' && c <= '٩') {
                out.append((char) ('0' + (c - '٠')));
            } else {
                out.append(c);
            }
        }
        return out.toString().trim();
    }
}
