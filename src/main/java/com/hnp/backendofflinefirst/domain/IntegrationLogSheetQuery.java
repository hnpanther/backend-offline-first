package com.hnp.backendofflinefirst.domain;

import lombok.Builder;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

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
 *
 * <h2>Why an absent filter is {@code null} and never an empty set</h2>
 *
 * <p>The repository expresses "no restriction" as {@code :param IS NULL}, so a filter the caller
 * did not send must arrive as null. An <b>empty</b> set would mean the opposite — a restriction
 * satisfied by nothing — and the two are one keystroke apart in every mapping function. So an
 * empty list is never constructed here: {@code unitId=} with nothing after it is the same as not
 * sending it, and that is decided once, in {@link #parseIds}.
 *
 * @param fromEpochMillis inclusive lower bound of the completion window
 * @param toEpochMillis   exclusive upper bound of the completion window
 * @param statuses        the finished statuses to include; never null, never empty
 * @param unitIds         responsible operational units, or null for every unit
 * @param templateIds     templates the round was raised from, or null for every template
 * @param assets          whether the round must carry equipment, must carry none, or either
 * @param classIds        asset classes the round must have covered, or null for any
 * @param assetIds        particular assets the round must have covered, or null for any
 * @param scanModes       the round's frozen scan mode, or null for any
 * @param origins         SCHEDULED / MANUAL, or null for either
 * @param severities      severities at least one of the round's assets must have recorded, or
 *                        null for any. Held as the stored strings, because
 *                        {@code log_sheet_entries.max_severity} is a text column
 * @param categoryCodes   log-sheet category codes (V19), upper-cased, or null for any round —
 *                        uncategorised ones included. Codes rather than ids because the code is
 *                        what the feed publishes and what never changes; the service resolves
 *                        them, and a code that matches nothing matches no round
 * @param statusesNamed   whether the caller named {@code statuses}, rather than taking the
 *                        default — a key limited to some statuses answers the two differently
 *                        ({@link IntegrationKeyScope#effectiveStatuses})
 * @param page            zero-based page index
 * @param size            rows per page, already resolved against the configured limits
 */
public record IntegrationLogSheetQuery(
        long fromEpochMillis,
        long toEpochMillis,
        Set<LogSheetStatus> statuses,
        Set<Long> unitIds,
        Set<Long> templateIds,
        AssetPresence assets,
        Set<Long> classIds,
        Set<Long> assetIds,
        Set<AssetScanMode> scanModes,
        Set<GenerationMode> origins,
        Set<String> severities,
        Set<String> categoryCodes,
        boolean statusesNamed,
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
            LogSheetStatus.APPROVED,
            LogSheetStatus.VOIDED,
            LogSheetStatus.EXPIRED,
            LogSheetStatus.CANCELLED);

    /**
     * What a caller gets when it does not name any status.
     *
     * <p>Completed rounds — {@code SUBMITTED} <b>and</b> {@code APPROVED} — because "completed
     * log sheets" is what the integration exists to publish, and because a default that quietly
     * included voided rounds would have an external system importing readings this plant has
     * explicitly invalidated. Anything wider has to be asked for by name.
     *
     * <p><b>{@code APPROVED} being here is not a detail.</b> Approval is a review step laid on
     * top of completion, so a round that a supervisor has accepted is more trustworthy than one
     * they have not looked at — not less. Leaving it out would mean every consumer on the
     * default silently stopped receiving rounds the day approval was switched on, with no error
     * anywhere and no way for them to tell the difference from a quiet plant. A consumer that
     * wants only unreviewed or only approved rounds asks for the status by name.
     */
    public static final Set<LogSheetStatus> DEFAULT_STATUSES =
            Set.copyOf(LogSheetStatus.COMPLETED_STATUSES);

    /** Applied when a caller does not ask for a size. Overridable by configuration. */
    public static final int DEFAULT_PAGE_SIZE = 50;

    /**
     * Ceiling on {@code size} when configuration does not say otherwise.
     *
     * <p>A request over it is clamped rather than refused, and the effective size is echoed in
     * the response so the caller can see it happened. 200 detail-free summaries is a response
     * of a few hundred kilobytes; the number exists to stop one request from turning into an
     * unbounded one, not to make callers page more than they need.
     */
    public static final int MAX_PAGE_SIZE = 200;

    /**
     * The ceiling above which a configured value is itself refused.
     *
     * <p>The whole point of a page cap is that no single request becomes unbounded, and a cap
     * read from a properties file can be mistyped — an extra zero would quietly restore exactly
     * the behaviour the cap exists to prevent. So configuration may tune the limit, and may not
     * remove it.
     */
    public static final int ABSOLUTE_MAX_PAGE_SIZE = 1_000;

    /**
     * How many values one comma-separated filter may carry.
     *
     * <p>Every list filter becomes an {@code IN (…)} with one bind parameter per value, and a
     * caller pasting ten thousand asset ids into a URL would produce a query PostgreSQL has to
     * plan from scratch — a separate plan per distinct list length, none of them reusable.
     * Refused with a message rather than truncated: a silently shortened list answers 200 with a
     * subset, which is the failure this whole class exists to prevent.
     */
    public static final int MAX_FILTER_VALUES = 100;

    /** Same shape {@code LogSheetCategoryService} accepts when a category is created. */
    private static final Pattern CATEGORY_CODE = Pattern.compile("[A-Za-z0-9_-]{1,50}");

    /**
     * Whether a round has to carry equipment at all.
     *
     * <p>A log sheet is a round of equipment readings <em>or</em> a questionnaire with no
     * equipment in it ({@code AssetSelectionMode.NONE}, roadmap §22) — and most consumers want
     * exactly one of those two. An ERP importing gauge readings has nothing to do with a shift
     * handover form, and a form-processing system has nothing to do with 47 pumps.
     *
     * <p><b>It is judged on the sheet's own rows, not on the template's mode.</b> The mode lives
     * on {@code log_sheet_templates}; the round itself only has the entries it was raised with,
     * and a custom sheet has no template at all. "Does this round have equipment on it" is
     * therefore {@code EXISTS (SELECT 1 FROM log_sheet_entries …)} — which is also the truthful
     * question, because it describes the round that happened rather than the template's
     * intention at some later moment.
     *
     * <p>One row exists per asset the round covered <em>whether or not the operator reached
     * it</em>, so {@code WITH} means "this round was about equipment", not "something was read".
     * The {@code severity} filter is the one that means something was read.
     */
    public enum AssetPresence {
        /** No restriction — equipment rounds and questionnaires alike. The default. */
        ANY,
        /** Only rounds carrying at least one asset. */
        WITH,
        /** Only rounds carrying none: the questions-only sheets. */
        WITHOUT;

        /**
         * The value the repository takes: {@code null} for no restriction.
         *
         * <p>Three states in one nullable Boolean rather than passing the enum into JPQL,
         * because the query needs {@code EXISTS} in one branch and {@code NOT EXISTS} in the
         * other, and a null-means-skip parameter is the pattern every other filter in this
         * repository already uses.
         */
        public Boolean required() {
            return switch (this) {
                case ANY -> null;
                case WITH -> Boolean.TRUE;
                case WITHOUT -> Boolean.FALSE;
            };
        }

        static AssetPresence parse(String raw) {
            if (raw == null || raw.isBlank()) {
                return ANY;
            }
            String value = raw.trim().toUpperCase();
            for (AssetPresence candidate : values()) {
                if (candidate.name().equals(value)) {
                    return candidate;
                }
            }
            throw new IllegalArgumentException("'assets' must be one of WITH, WITHOUT or ANY — got '"
                    + raw.trim() + "'. WITH returns rounds carrying equipment, WITHOUT returns the "
                    + "questions-only rounds, ANY (the default) returns both.");
        }
    }

    /**
     * The page limits in force, after configuration and sanity-clamping.
     *
     * <p>A value rather than two loose ints so a caller cannot pass them the wrong way round,
     * and so the clamping rule lives in one place with the reason written next to it.
     */
    public record PageLimits(int defaultSize, int maxSize) {

        /** The compiled-in defaults, used when nothing is configured and by the unit tests. */
        public static final PageLimits DEFAULTS = new PageLimits(DEFAULT_PAGE_SIZE, MAX_PAGE_SIZE);

        /**
         * Reads configured limits, refusing values that would defeat the cap.
         *
         * <p>Clamped rather than rejected at startup: an operator tuning a page size should not
         * be able to stop the application booting, and a clamped value is visible in the
         * response's echoed {@code size} either way.
         */
        public static PageLimits of(int configuredDefault, int configuredMax) {
            int max = configuredMax <= 0
                    ? MAX_PAGE_SIZE
                    : Math.min(configuredMax, ABSOLUTE_MAX_PAGE_SIZE);
            int fallback = configuredDefault <= 0 ? DEFAULT_PAGE_SIZE : configuredDefault;
            // The default can never exceed the ceiling, or an unasked-for request would return
            // more rows than an explicit request is allowed to.
            return new PageLimits(Math.min(fallback, max), max);
        }

        /** The size to apply for a request that asked for {@code requested} (possibly nothing). */
        public int resolve(Integer requested) {
            return requested == null || requested <= 0 ? defaultSize : Math.min(requested, maxSize);
        }
    }

    /**
     * The raw query string, exactly as it arrived, before anything has been interpreted.
     *
     * <p><b>A builder rather than thirteen positional arguments.</b> Nine of these are strings
     * and five of those are ids; {@code parse(from, to, statuses, unitId, templateId, assets,
     * classId, assetId, …)} is a signature where swapping two arguments compiles, passes every
     * type check, and silently filters by the wrong dimension. The builder names each one at the
     * call site, which is the whole reason it is worth the annotation.
     *
     * <p>Every field is a string because that is what HTTP delivers, and because the parsing
     * belongs here rather than in Spring's converters: a {@code @RequestParam Long unitId} given
     * {@code abc} produces a framework message about type conversion, while {@link #parseIds}
     * produces one that names the parameter and says what it expected. {@code page} and
     * {@code size} stay numeric — they are plain integers with no list, no enum and no custom
     * rule, and Spring's own 400 for {@code page=abc} says everything there is to say.
     */
    @Builder
    public record Params(
            String from,
            String to,
            String statuses,
            String unitId,
            String templateId,
            String assets,
            String classId,
            String assetId,
            String scanMode,
            String origin,
            String severity,
            String category,
            Integer page,
            Integer size) {
    }

    /**
     * Parses and validates raw request parameters, using the compiled-in page limits.
     *
     * <p>Kept for tests and any caller with no configuration to hand.
     */
    public static IntegrationLogSheetQuery parse(String from, String to, String statuses,
                                                 Long unitId, Long templateId,
                                                 Integer page, Integer size, ZoneId zone) {
        return parse(from, to, statuses, unitId, templateId, page, size, zone, PageLimits.DEFAULTS);
    }

    /**
     * The date-range-and-paging shape, for callers that use none of the shape filters.
     *
     * <p>Every parameter this overload takes existed before the asset, class, scan-mode, origin
     * and severity filters did, and it is kept so that the tests which pin the date, status and
     * paging rules read as they always have — those rules did not change, and a diff that
     * rewrote forty assertions would have hidden that fact.
     */
    public static IntegrationLogSheetQuery parse(String from, String to, String statuses,
                                                 Long unitId, Long templateId,
                                                 Integer page, Integer size, ZoneId zone,
                                                 PageLimits limits) {
        return parse(Params.builder()
                .from(from)
                .to(to)
                .statuses(statuses)
                .unitId(unitId == null ? null : String.valueOf(unitId))
                .templateId(templateId == null ? null : String.valueOf(templateId))
                .page(page)
                .size(size)
                .build(), zone, limits);
    }

    /**
     * Parses and validates everything a third party sent.
     *
     * @param zone   the plant zone that a date with no offset is interpreted in
     * @param limits the page-size default and ceiling in force
     * @throws IllegalArgumentException with a message naming the offending parameter
     */
    public static IntegrationLogSheetQuery parse(Params raw, ZoneId zone, PageLimits limits) {
        long fromMillis = parseInstant(raw.from(), "from", zone);
        long toMillis = parseInstant(raw.to(), "to", zone);
        if (fromMillis >= toMillis) {
            throw new IllegalArgumentException(
                    "'from' must be strictly before 'to'. The range is half-open: [from, to).");
        }

        AssetPresence assets = AssetPresence.parse(raw.assets());
        Set<Long> classIds = parseIds(raw.classId(), "classId");
        Set<Long> assetIds = parseIds(raw.assetId(), "assetId");
        Set<String> severities = parseSeverities(raw.severity());
        // A combination that cannot match anything is refused, not answered with an empty page.
        // `assets=WITHOUT` says "no equipment rows"; each of these three says "an equipment row
        // like this". Together they describe nothing, and a caller polling that combination would
        // see a permanently empty feed and conclude the plant had gone quiet.
        refuseIfContradictory(assets, classIds, "classId");
        refuseIfContradictory(assets, assetIds, "assetId");
        refuseIfContradictory(assets, severities, "severity");

        return new IntegrationLogSheetQuery(
                fromMillis,
                toMillis,
                parseStatuses(raw.statuses()),
                parseIds(raw.unitId(), "unitId"),
                parseIds(raw.templateId(), "templateId"),
                assets,
                classIds,
                assetIds,
                parseEnums(raw.scanMode(), "scanMode", AssetScanMode.class),
                parseEnums(raw.origin(), "origin", GenerationMode.class),
                severities,
                parseCategoryCodes(raw.category()),
                raw.statuses() != null && !raw.statuses().isBlank(),
                raw.page() == null || raw.page() < 0 ? 0 : raw.page(),
                limits.resolve(raw.size()));
    }

    private static void refuseIfContradictory(AssetPresence assets, Set<?> entryFilter,
                                              String parameter) {
        if (assets == AssetPresence.WITHOUT && entryFilter != null) {
            throw new IllegalArgumentException("'assets=WITHOUT' and '" + parameter
                    + "' cannot both be used: '" + parameter + "' selects rounds by something "
                    + "recorded against an asset, and a round with no assets has nothing to match. "
                    + "Use assets=WITH, or drop '" + parameter + "'.");
        }
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
        for (String candidate : tokens(raw, "statuses")) {
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

    /**
     * A comma-separated list of row ids, or {@code null} when the caller sent none.
     *
     * <p>Accepts one value as readily as twenty, which is what keeps {@code unitId=3} meaning
     * exactly what it meant before this parameter learned to take a list.
     *
     * <p><b>An id that matches nothing is not an error.</b> {@code unitId=999999} answers an
     * empty page, deliberately: refusing an unknown id would turn every list filter into a probe
     * for which units, templates, classes and assets this plant has, answerable with one request
     * each by a caller that should only ever learn about the rows it is entitled to see.
     *
     * <p>Zero and negatives <em>are</em> refused, because no row carries them — they are a
     * client bug (an unset variable, an off-by-one), and the empty page they would otherwise
     * produce looks exactly like a quiet plant.
     */
    private static Set<Long> parseIds(String raw, String parameter) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        Set<Long> ids = new LinkedHashSet<>();
        for (String candidate : tokens(raw, parameter)) {
            long id;
            try {
                id = Long.parseLong(candidate);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("'" + parameter + "' must be a number, or "
                        + "several separated by commas — got '" + candidate + "'.");
            }
            if (id <= 0) {
                throw new IllegalArgumentException("'" + parameter
                        + "' must be a positive id — got " + id + ".");
            }
            ids.add(id);
        }
        return ids.isEmpty() ? null : Set.copyOf(ids);
    }

    /**
     * A comma-separated list of enum names, or {@code null} when the caller sent none.
     *
     * <p>Case-insensitive, and an unknown name is refused with the full list of what is allowed
     * — the one message that lets an integrator fix it without reading this source.
     */
    private static <E extends Enum<E>> Set<E> parseEnums(String raw, String parameter,
                                                         Class<E> type) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        Set<E> parsed = new LinkedHashSet<>();
        for (String candidate : tokens(raw, parameter)) {
            E value = null;
            for (E option : type.getEnumConstants()) {
                if (option.name().equalsIgnoreCase(candidate)) {
                    value = option;
                    break;
                }
            }
            if (value == null) {
                throw new IllegalArgumentException("'" + parameter + "' has an unknown value '"
                        + candidate + "'. Allowed: "
                        + List.of(type.getEnumConstants()).stream().map(Enum::name).toList() + ".");
            }
            parsed.add(value);
        }
        return parsed.isEmpty() ? null : Set.copyOf(parsed);
    }

    /**
     * The severities at least one of the round's assets must have recorded.
     *
     * <p>Parsed through {@link FieldValidationSeverity} so the API cannot drift from the values
     * the evaluator writes, then carried as strings because
     * {@code log_sheet_entries.max_severity} is a {@code VARCHAR(10)}.
     *
     * <p><b>An exact set, not a floor.</b> {@code severity=WARNING,DANGER} is "rounds with a
     * problem" spelled out, which is both shorter to explain than a rank and immune to the trap
     * underneath one: the column is text, and in text {@code 'DANGER' < 'OK' < 'WARNING'}, so a
     * {@code >=} comparison would have been quietly meaningless.
     *
     * <p>A round whose assets were never read matches none of them —
     * {@code max_severity IS NULL} is the has-no-reading state (the predicate three partial
     * indexes are built on) — which is why {@code severity=OK} means "something was read and it
     * was fine" rather than "no restriction".
     */
    private static Set<String> parseSeverities(String raw) {
        Set<FieldValidationSeverity> parsed =
                parseEnums(raw, "severity", FieldValidationSeverity.class);
        return parsed == null
                ? null
                : parsed.stream().map(Enum::name).collect(Collectors.toUnmodifiableSet());
    }

    /**
     * Log-sheet category codes, upper-cased, or {@code null} when the caller sent none.
     *
     * <p>A malformed code is refused — it can never match, and is a client bug. A well-formed one
     * that names no category is not: like an unknown id it answers an empty page, so the
     * parameter cannot be used to learn which categories exist.
     */
    private static Set<String> parseCategoryCodes(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        Set<String> codes = new LinkedHashSet<>();
        for (String candidate : tokens(raw, "category")) {
            if (!CATEGORY_CODE.matcher(candidate).matches()) {
                throw new IllegalArgumentException("'category' has an invalid code '" + candidate
                        + "'. A category code is 1 to 50 letters, digits, '-' or '_'.");
            }
            codes.add(candidate.toUpperCase(Locale.ROOT));
        }
        return codes.isEmpty() ? null : Set.copyOf(codes);
    }

    /**
     * Splits a comma-separated parameter, dropping empty tokens and capping the count.
     *
     * <p>Empty tokens are dropped rather than refused because {@code unitId=3,} is a trailing
     * comma from a client's own join, not an attempt to filter by nothing.
     */
    private static List<String> tokens(String raw, String parameter) {
        List<String> out = new ArrayList<>();
        for (String token : raw.split(",")) {
            String candidate = token.trim();
            if (!candidate.isEmpty()) {
                out.add(candidate);
            }
        }
        if (out.size() > MAX_FILTER_VALUES) {
            throw new IllegalArgumentException("'" + parameter + "' carries " + out.size()
                    + " values; at most " + MAX_FILTER_VALUES + " are accepted. Split the request, "
                    + "or widen the filter and select client-side.");
        }
        return out;
    }

    /** True when {@code size} was clamped — used only to explain the echoed value in the docs. */
    public static boolean wasClamped(Integer requestedSize) {
        return requestedSize != null && requestedSize > MAX_PAGE_SIZE;
    }

    /** {@code null} for no restriction, {@code TRUE} for equipment rounds, {@code FALSE} for none. */
    public Boolean requiresAssets() {
        return assets == null ? null : assets.required();
    }

    public Instant fromInstant() {
        return Instant.ofEpochMilli(fromEpochMillis);
    }

    public Instant toInstant() {
        return Instant.ofEpochMilli(toEpochMillis);
    }
}
