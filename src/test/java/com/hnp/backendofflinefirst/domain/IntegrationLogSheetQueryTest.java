package com.hnp.backendofflinefirst.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Parsing and validating what a third-party system sends to the list endpoint.
 *
 * <p>The behaviour worth defending here is that bad input is <b>refused</b>. Every alternative
 * — clamping a bad date to now, dropping an unknown status, defaulting a malformed range —
 * answers 200 with the wrong rows, and an integration that imports the wrong month for a year
 * finds out from a discrepancy in someone else's report.
 */
class IntegrationLogSheetQueryTest {

    private static final ZoneId TEHRAN = ZoneId.of("Asia/Tehran");

    private static IntegrationLogSheetQuery parse(String from, String to) {
        return IntegrationLogSheetQuery.parse(from, to, null, null, null, null, null, TEHRAN);
    }

    // ── Date formats ─────────────────────────────────────────────────────────

    @Test
    void acceptsAnIsoInstant() {
        IntegrationLogSheetQuery query = parse("2026-08-01T00:00:00Z", "2026-09-01T00:00:00Z");

        assertThat(query.fromInstant()).isEqualTo(Instant.parse("2026-08-01T00:00:00Z"));
        assertThat(query.toInstant()).isEqualTo(Instant.parse("2026-09-01T00:00:00Z"));
    }

    @Test
    void acceptsAnExplicitOffset() {
        IntegrationLogSheetQuery query = parse("2026-08-01T03:30:00+03:30", "2026-08-02T03:30:00+03:30");

        // +03:30 is Tehran's offset, so this is the same instant as midnight UTC.
        assertThat(query.fromInstant()).isEqualTo(Instant.parse("2026-08-01T00:00:00Z"));
    }

    @Test
    void aZonelessDateTimeIsReadInTheConfiguredZoneAndNotTheJvmDefault() {
        IntegrationLogSheetQuery query = parse("2026-08-01T00:00:00", "2026-08-02T00:00:00");

        // Midnight in Tehran is 20:30 the previous day, UTC. If this ever equals
        // 2026-08-01T00:00:00Z, the parser has silently fallen back to the JVM zone.
        assertThat(query.fromInstant()).isEqualTo(Instant.parse("2026-07-31T20:30:00Z"));
    }

    @Test
    void aPlainDateMeansMidnightInTheConfiguredZone() {
        IntegrationLogSheetQuery query = parse("2026-08-01", "2026-09-01");

        assertThat(query.fromInstant()).isEqualTo(Instant.parse("2026-07-31T20:30:00Z"));
        assertThat(query.toInstant()).isEqualTo(Instant.parse("2026-08-31T20:30:00Z"));
    }

    @Test
    void rejectsAnUnparseableDateRatherThanGuessing() {
        assertThatThrownBy(() -> parse("01/08/2026", "2026-09-01"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'from'")
                .hasMessageContaining("2026-08-01");
    }

    @Test
    void bothEndsOfTheRangeAreRequired() {
        assertThatThrownBy(() -> parse(null, "2026-09-01"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'from' is required");
        assertThatThrownBy(() -> parse("2026-08-01", "  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'to' is required");
    }

    @Test
    void refusesAnInvertedOrEmptyRange() {
        assertThatThrownBy(() -> parse("2026-09-01", "2026-08-01"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("strictly before");
        // from == to is empty under a half-open range, so it can only be a mistake.
        assertThatThrownBy(() -> parse("2026-08-01", "2026-08-01"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("strictly before");
    }

    // ── Statuses ─────────────────────────────────────────────────────────────

    @Test
    void defaultsToCompletedRoundsIncludingApprovedOnes() {
        // APPROVED belongs in the default and its absence would be silent: every consumer on the
        // default would simply stop receiving rounds the day approval was switched on, with no
        // error and no way to tell it from a quiet plant. A reviewed round is more trustworthy
        // than an unreviewed one, not less.
        assertThat(parse("2026-08-01", "2026-09-01").statuses())
                .containsExactlyInAnyOrder(LogSheetStatus.SUBMITTED, LogSheetStatus.APPROVED);
    }

    @Test
    void acceptsTheFinishedStatusesTogether() {
        IntegrationLogSheetQuery query = IntegrationLogSheetQuery.parse(
                "2026-08-01", "2026-09-01", "SUBMITTED, EXPIRED", null, null, null, null, TEHRAN);

        assertThat(query.statuses())
                .containsExactlyInAnyOrder(LogSheetStatus.SUBMITTED, LogSheetStatus.EXPIRED);
    }

    @Test
    void statusNamesAreCaseInsensitive() {
        IntegrationLogSheetQuery query = IntegrationLogSheetQuery.parse(
                "2026-08-01", "2026-09-01", "submitted,expired", null, null, null, null, TEHRAN);

        assertThat(query.statuses())
                .containsExactlyInAnyOrder(LogSheetStatus.SUBMITTED, LogSheetStatus.EXPIRED);
    }

    @Test
    void refusesAnInFlightStatusRatherThanSilentlyDroppingIt() {
        // The whole point: statuses=IN_PROGRESS must not answer 200 with only the submitted
        // rows, because the caller would conclude no round is ever in progress.
        for (String open : new String[]{"PENDING", "ASSIGNED", "IN_PROGRESS"}) {
            assertThatThrownBy(() -> IntegrationLogSheetQuery.parse(
                    "2026-08-01", "2026-09-01", open, null, null, null, null, TEHRAN))
                    .as(open)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("finished log sheets only");
        }
    }

    @Test
    void refusesAnUnknownStatus() {
        assertThatThrownBy(() -> IntegrationLogSheetQuery.parse(
                "2026-08-01", "2026-09-01", "SUBMITTED,NONSENSE", null, null, null, null, TEHRAN))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("NONSENSE");
    }

    @Test
    void theExposableSetIsExactlyTheTerminalStatesMinusNone() {
        // A guard against the set quietly widening. Adding an in-flight status here would put
        // half-recorded work on a third party's feed, which is the one thing this API must not do.
        assertThat(IntegrationLogSheetQuery.EXPOSABLE_STATUSES)
                .containsExactlyInAnyOrder(
                        LogSheetStatus.SUBMITTED,
                        LogSheetStatus.APPROVED,
                        LogSheetStatus.VOIDED,
                        LogSheetStatus.EXPIRED,
                        LogSheetStatus.CANCELLED);
        assertThat(IntegrationLogSheetQuery.EXPOSABLE_STATUSES)
                .allMatch(LogSheetStatus::isTerminal);
    }

    // ── Paging ───────────────────────────────────────────────────────────────

    @Test
    void appliesTheDefaultPageSize() {
        IntegrationLogSheetQuery query = parse("2026-08-01", "2026-09-01");

        assertThat(query.page()).isZero();
        assertThat(query.size()).isEqualTo(IntegrationLogSheetQuery.DEFAULT_PAGE_SIZE);
    }

    @Test
    void clampsAnOversizedPageRatherThanRefusingIt() {
        IntegrationLogSheetQuery query = IntegrationLogSheetQuery.parse(
                "2026-08-01", "2026-09-01", null, null, null, 0, 100_000, TEHRAN);

        assertThat(query.size()).isEqualTo(IntegrationLogSheetQuery.MAX_PAGE_SIZE);
        assertThat(IntegrationLogSheetQuery.wasClamped(100_000)).isTrue();
    }

    @Test
    void normalisesNonsensicalPagingInput() {
        IntegrationLogSheetQuery query = IntegrationLogSheetQuery.parse(
                "2026-08-01", "2026-09-01", null, null, null, -5, 0, TEHRAN);

        assertThat(query.page()).isZero();
        assertThat(query.size()).isEqualTo(IntegrationLogSheetQuery.DEFAULT_PAGE_SIZE);
    }

    // ── Configurable page limits ─────────────────────────────────────────────

    @Test
    void usesTheCompiledInLimitsWhenNothingIsConfigured() {
        IntegrationLogSheetQuery.PageLimits limits = IntegrationLogSheetQuery.PageLimits.DEFAULTS;

        assertThat(limits.defaultSize()).isEqualTo(50);
        assertThat(limits.maxSize()).isEqualTo(200);
    }

    @Test
    void appliesAConfiguredMaximum() {
        IntegrationLogSheetQuery.PageLimits limits = IntegrationLogSheetQuery.PageLimits.of(25, 75);

        IntegrationLogSheetQuery query = IntegrationLogSheetQuery.parse(
                "2026-08-01", "2026-09-01", null, null, null, 0, 500, TEHRAN, limits);

        assertThat(query.size()).isEqualTo(75);
    }

    @Test
    void appliesAConfiguredDefaultWhenNoSizeIsAskedFor() {
        IntegrationLogSheetQuery.PageLimits limits = IntegrationLogSheetQuery.PageLimits.of(25, 75);

        IntegrationLogSheetQuery query = IntegrationLogSheetQuery.parse(
                "2026-08-01", "2026-09-01", null, null, null, 0, null, TEHRAN, limits);

        assertThat(query.size()).isEqualTo(25);
    }

    /**
     * A configured maximum may tune the cap; it may not remove it.
     *
     * <p>The cap exists so no single request becomes unbounded, and a properties file is exactly
     * where an extra zero gets typed. Clamped rather than refused at startup, because an
     * operator adjusting a page size should not be able to stop the application booting.
     */
    @Test
    void refusesToLetConfigurationDefeatTheCap() {
        IntegrationLogSheetQuery.PageLimits limits =
                IntegrationLogSheetQuery.PageLimits.of(50, 1_000_000);

        assertThat(limits.maxSize()).isEqualTo(IntegrationLogSheetQuery.ABSOLUTE_MAX_PAGE_SIZE);
    }

    @Test
    void fallsBackToTheCompiledDefaultsForNonsensicalConfiguration() {
        assertThat(IntegrationLogSheetQuery.PageLimits.of(0, 0))
                .isEqualTo(IntegrationLogSheetQuery.PageLimits.DEFAULTS);
        assertThat(IntegrationLogSheetQuery.PageLimits.of(-5, -5))
                .isEqualTo(IntegrationLogSheetQuery.PageLimits.DEFAULTS);
    }

    @Test
    void neverLetsTheDefaultExceedTheMaximum() {
        // Otherwise a caller who asked for nothing would receive more rows than a caller who
        // asked explicitly is allowed.
        IntegrationLogSheetQuery.PageLimits limits = IntegrationLogSheetQuery.PageLimits.of(500, 100);

        assertThat(limits.defaultSize()).isEqualTo(100);
        assertThat(limits.maxSize()).isEqualTo(100);
    }

    @Test
    void resolvesEachRequestedSizeAgainstTheLimits() {
        IntegrationLogSheetQuery.PageLimits limits = IntegrationLogSheetQuery.PageLimits.of(20, 60);

        assertThat(limits.resolve(null)).isEqualTo(20);
        assertThat(limits.resolve(0)).isEqualTo(20);
        assertThat(limits.resolve(-3)).isEqualTo(20);
        assertThat(limits.resolve(10)).isEqualTo(10);
        assertThat(limits.resolve(60)).isEqualTo(60);
        assertThat(limits.resolve(61)).isEqualTo(60);
    }

    @Test
    void carriesTheOptionalFiltersThrough() {
        IntegrationLogSheetQuery query = IntegrationLogSheetQuery.parse(
                "2026-08-01", "2026-09-01", null, 7L, 9L, 2, 25, TEHRAN);

        assertThat(query.unitIds()).containsExactly(7L);
        assertThat(query.templateIds()).containsExactly(9L);
        assertThat(query.page()).isEqualTo(2);
        assertThat(query.size()).isEqualTo(25);
    }

    // ── The shape filters ────────────────────────────────────────────────────
    //
    // Which rounds a consumer wants is not a detail of its taste: an ERP importing gauge
    // readings has no use for a shift-handover questionnaire, and a form-processing system has
    // no use for 47 pumps. These parameters are that distinction, and the rule throughout is the
    // same as for `statuses` — a value that cannot be understood is refused, because the
    // alternative is 200 with the wrong rows.

    @Test
    void withNoShapeFiltersNothingIsRestricted() {
        IntegrationLogSheetQuery query = parse("2026-08-01", "2026-09-01");

        // Null, not empty. The repository reads "no restriction" as `:param IS NULL`; an empty
        // set would mean a restriction nothing satisfies, and the two are one keystroke apart.
        assertThat(query.assets()).isEqualTo(IntegrationLogSheetQuery.AssetPresence.ANY);
        assertThat(query.requiresAssets()).isNull();
        assertThat(query.classIds()).isNull();
        assertThat(query.assetIds()).isNull();
        assertThat(query.scanModes()).isNull();
        assertThat(query.origins()).isNull();
        assertThat(query.severities()).isNull();
    }

    @Test
    void readsTheAssetPresenceFilterInEitherCase() {
        assertThat(filtered(p -> p.assets("WITH")).requiresAssets()).isTrue();
        assertThat(filtered(p -> p.assets("without")).requiresAssets()).isFalse();
        assertThat(filtered(p -> p.assets("Any")).requiresAssets()).isNull();
    }

    @Test
    void refusesAnAssetPresenceValueItCannotUnderstand() {
        assertThatThrownBy(() -> filtered(p -> p.assets("true")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'assets'")
                .hasMessageContaining("WITH");
    }

    @Test
    void acceptsOneIdOrSeveral() {
        // `unitId=3` has to keep meaning exactly what it meant before the parameter took a list.
        assertThat(filtered(p -> p.unitId("3")).unitIds()).containsExactly(3L);
        assertThat(filtered(p -> p.unitId("3, 4,5")).unitIds()).containsExactlyInAnyOrder(3L, 4L, 5L);
        // A trailing comma is a client's own join, not a request to filter by nothing.
        assertThat(filtered(p -> p.classId("8,")).classIds()).containsExactly(8L);
    }

    @Test
    void anEmptyFilterIsTheSameAsNoFilter() {
        assertThat(filtered(p -> p.unitId("")).unitIds()).isNull();
        assertThat(filtered(p -> p.unitId("  ")).unitIds()).isNull();
        assertThat(filtered(p -> p.severity("")).severities()).isNull();
    }

    @Test
    void refusesAnIdThatIsNotANumberOrIsNotPositive() {
        assertThatThrownBy(() -> filtered(p -> p.unitId("abc")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'unitId'");
        // Zero and negatives are a client bug — an unset variable, an off-by-one — and the empty
        // page they would otherwise produce looks exactly like a quiet plant.
        assertThatThrownBy(() -> filtered(p -> p.assetId("0")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive");
        assertThatThrownBy(() -> filtered(p -> p.templateId("-1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive");
    }

    @Test
    void capsHowManyValuesOneFilterMayCarry() {
        String tooMany = java.util.stream.IntStream
                .rangeClosed(1, IntegrationLogSheetQuery.MAX_FILTER_VALUES + 1)
                .mapToObj(String::valueOf)
                .collect(java.util.stream.Collectors.joining(","));

        assertThatThrownBy(() -> filtered(p -> p.assetId(tooMany)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at most");
    }

    @Test
    void readsScanModeAndOriginAsTheirEnums() {
        assertThat(filtered(p -> p.scanMode("qr,nfc_or_qr")).scanModes())
                .containsExactlyInAnyOrder(AssetScanMode.QR, AssetScanMode.NFC_OR_QR);
        assertThat(filtered(p -> p.origin("SCHEDULED")).origins())
                .containsExactly(GenerationMode.SCHEDULED);
    }

    @Test
    void refusesAnUnknownScanModeWithTheListOfWhatIsAllowed() {
        assertThatThrownBy(() -> filtered(p -> p.scanMode("BARCODE")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("BARCODE")
                .hasMessageContaining("NFC_OR_QR");
    }

    @Test
    void carriesSeverityAsTheStoredStrings() {
        // Parsed through FieldValidationSeverity so the API cannot drift from the values the
        // evaluator writes, then held as text because max_severity is a VARCHAR(10).
        assertThat(filtered(p -> p.severity("warning,danger")).severities())
                .containsExactlyInAnyOrder("WARNING", "DANGER");
    }

    @Test
    void refusesACombinationThatCouldNeverMatchAnything() {
        // assets=WITHOUT says "no equipment rows"; classId, assetId and severity each say "an
        // equipment row like this". Together they describe nothing, and a caller polling that
        // would watch a permanently empty feed and conclude the plant had gone quiet.
        for (java.util.function.Consumer<IntegrationLogSheetQuery.Params.ParamsBuilder> narrowing
                : List.<java.util.function.Consumer<IntegrationLogSheetQuery.Params.ParamsBuilder>>of(
                        p -> p.classId("1"), p -> p.assetId("1"), p -> p.severity("DANGER"))) {
            assertThatThrownBy(() -> filtered(p -> {
                p.assets("WITHOUT");
                narrowing.accept(p);
            }))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("assets=WITHOUT");
        }
    }

    @Test
    void theShapeFiltersDoNotDisturbTheDateRange() {
        IntegrationLogSheetQuery query = filtered(p -> p
                .assets("WITH")
                .classId("4")
                .scanMode("QR")
                .severity("DANGER"));

        assertThat(query.fromInstant()).isEqualTo(Instant.parse("2026-07-31T20:30:00Z"));
        assertThat(query.statuses())
                .containsExactlyInAnyOrder(LogSheetStatus.SUBMITTED, LogSheetStatus.APPROVED);
    }

    /** One month, plus whatever the caller is narrowing by. */
    private static IntegrationLogSheetQuery filtered(
            java.util.function.Consumer<IntegrationLogSheetQuery.Params.ParamsBuilder> narrowing) {
        IntegrationLogSheetQuery.Params.ParamsBuilder params = IntegrationLogSheetQuery.Params
                .builder()
                .from("2026-08-01")
                .to("2026-09-01");
        narrowing.accept(params);
        return IntegrationLogSheetQuery.parse(
                params.build(), TEHRAN, IntegrationLogSheetQuery.PageLimits.DEFAULTS);
    }
}
