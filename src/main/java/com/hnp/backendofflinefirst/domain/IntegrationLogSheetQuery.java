package com.hnp.backendofflinefirst.domain;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * A validated request to the integration list endpoint.
 *
 * <p>Everything a caller sends is parsed and checked <b>here</b>, before any of it reaches a
 * query, and every rejection is a message that says what was wrong with which parameter. The
 * alternative — coercing bad input to a default and answering 200 — is how an integration ends
 * up silently importing the wrong month for a year.
 *
 * <h2>The range is half-open: {@code [from, to)}</h2>
 *
 * <p>This is the one design decision here worth arguing about, so: a closed range forces every
 * caller to answer "does {@code to=2026-08-31} include the 31st?", and the two reasonable
 * answers differ by a day. Worse, whichever is chosen, consecutive polls either double-count
 * the boundary instant or lose it. Half-open makes {@code from=2026-08-01&to=2026-09-01}
 * exactly August, and makes yesterday's {@code to} usable verbatim as today's {@code from}
 * with no overlap and no gap — which is what a polling integration actually does.
 *
 * <h2>Accepted date formats</h2>
 *
 * <ul>
 *   <li>{@code 2026-08-01T00:00:00Z} — ISO-8601 instant. Unambiguous; prefer this.</li>
 *   <li>{@code 2026-08-01T00:00:00+03:30} — ISO-8601 with an offset. Also unambiguous.</li>
 *   <li>{@code 2026-08-01T00:00:00} — no offset, interpreted in the configured plant zone.</li>
 *   <li>{@code 2026-08-01} — a date, meaning midnight in the plant zone.</li>
 * </ul>
 *
 * <p>The last two are accepted because an integrator will send them whether or not they are
 * documented, and rejecting them buys nothing while guessing at them silently would be worse
 * than either. The zone they resolve in is configuration
 * ({@code app.integration.default-zone}), not the JVM default, so the meaning of a zoneless
 * date does not change when somebody fixes the server's clock settings.
 */
public record IntegrationLogSheetQuery(
        long fromEpochMillis,
        long toEpochMillis,
        Set<LogSheetStatus> statuses,
        Long unitId,
        Long templateId,
        int page,
        int size) {

    /**
     * The only statuses this API will ever return.
     *
     * <p>Every one of them is terminal. An in-flight sheet is half-recorded work: its values
     * are whatever the operator has typed so far, its assets may be untouched, and its status
     * will change without any event the integration can observe. Publishing that is not a
     * smaller version of publishing a finished round — it is publishing something that is not
     * true yet.
     *
     * <p>The set is enforced again in the repository query, so it holds even if a caller of
     * that method skips this class entirely.
     */
    public static final Set<LogSheetStatus> EXPOSABLE_STATUSES = Set.of(
            LogSheetStatus.SUBMITTED,
            LogSheetStatus.VOIDED,
            LogSheetStatus.EXPIRED,
            LogSheetStatus.CANCELLED);

    /**
     * What a caller gets when it does not name any status.
     *
     * <p>{@code SUBMITTED} alone, because "completed log sheets" is what the integration exists
     * to publish, and because a default that quietly included voided rounds would have an
     * external system importing readings this plant has explicitly invalidated. Anything wider
     * has to be asked for by name.
     */
    public static final Set<LogSheetStatus> DEFAULT_STATUSES = Set.of(LogSheetStatus.SUBMITTED);

    public static final int DEFAULT_PAGE_SIZE = 50;

    /**
     * Hard ceiling on {@code size}.
     *
     * <p>A request over it is clamped rather than refused, and the effective size is echoed in
     * the response so the caller can see it happened. 200 detail-free summaries is a response
     * of a few hundred kilobytes; the number exists to stop one request from turning into an
     * unbounded one, not to make callers page more than they need.
     */
    public static final int MAX_PAGE_SIZE = 200;

    /**
     * Parses and validates raw request parameters.
     *
     * @param zone the plant zone that a date with no offset is interpreted in
     * @throws IllegalArgumentException with a message naming the offending parameter
     */
    public static IntegrationLogSheetQuery parse(String from, String to, String statuses,
                                                 Long unitId, Long templateId,
                                                 Integer page, Integer size, ZoneId zone) {
        long fromMillis = parseInstant(from, "from", zone);
        long toMillis = parseInstant(to, "to", zone);
        if (fromMillis >= toMillis) {
            throw new IllegalArgumentException(
                    "'from' must be strictly before 'to'. The range is half-open: [from, to).");
        }
        return new IntegrationLogSheetQuery(
                fromMillis,
                toMillis,
                parseStatuses(statuses),
                unitId,
                templateId,
                page == null || page < 0 ? 0 : page,
                size == null || size <= 0 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE));
    }

    private static long parseInstant(String raw, String parameter, ZoneId zone) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("'" + parameter + "' is required. Use an ISO-8601 "
                    + "date-time such as 2026-08-01T00:00:00Z, or a date such as 2026-08-01.");
        }
        String value = raw.trim();
        try {
            // Widest first: an instant or an explicit offset needs no zone assumption at all.
            return OffsetDateTime.parse(value).toInstant().toEpochMilli();
        } catch (DateTimeException ignored) {
            // Not offset-qualified — fall through.
        }
        try {
            return LocalDateTime.parse(value).atZone(zone).toInstant().toEpochMilli();
        } catch (DateTimeException ignored) {
            // Not a local date-time either — fall through.
        }
        try {
            return LocalDate.parse(value).atStartOfDay(zone).toInstant().toEpochMilli();
        } catch (DateTimeException e) {
            throw new IllegalArgumentException("'" + parameter + "' is not a valid date-time: '"
                    + value + "'. Accepted: 2026-08-01T00:00:00Z, 2026-08-01T00:00:00+03:30, "
                    + "2026-08-01T00:00:00 or 2026-08-01.");
        }
    }

    /**
     * Parses a comma-separated status list.
     *
     * <p>An unknown or non-terminal status is <b>refused</b>, never dropped. Silently ignoring
     * {@code statuses=IN_PROGRESS} would answer 200 with only the submitted rows, and the
     * caller would conclude that no round is ever in progress — a wrong answer that looks
     * exactly like a right one.
     */
    private static Set<LogSheetStatus> parseStatuses(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_STATUSES;
        }
        Set<LogSheetStatus> parsed = new LinkedHashSet<>();
        List<String> rejected = new ArrayList<>();
        for (String token : raw.split(",")) {
            String candidate = token.trim();
            if (candidate.isEmpty()) {
                continue;
            }
            LogSheetStatus status = LogSheetStatus.fromNullable(candidate);
            if (status == null || !EXPOSABLE_STATUSES.contains(status)) {
                rejected.add(candidate);
            } else {
                parsed.add(status);
            }
        }
        if (!rejected.isEmpty()) {
            throw new IllegalArgumentException("Unsupported status " + rejected
                    + ". This API exposes finished log sheets only; allowed values are "
                    + EXPOSABLE_STATUSES.stream().map(Enum::name).sorted().toList() + ".");
        }
        if (parsed.isEmpty()) {
            return DEFAULT_STATUSES;
        }
        return parsed;
    }

    /** True when {@code size} was clamped — used only to explain the echoed value in the docs. */
    public static boolean wasClamped(Integer requestedSize) {
        return requestedSize != null && requestedSize > MAX_PAGE_SIZE;
    }

    public Instant fromInstant() {
        return Instant.ofEpochMilli(fromEpochMillis);
    }

    public Instant toInstant() {
        return Instant.ofEpochMilli(toEpochMillis);
    }
}
