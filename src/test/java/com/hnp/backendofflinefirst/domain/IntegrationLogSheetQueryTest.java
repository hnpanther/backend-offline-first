package com.hnp.backendofflinefirst.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;

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
    void defaultsToSubmittedOnly() {
        assertThat(parse("2026-08-01", "2026-09-01").statuses())
                .containsExactly(LogSheetStatus.SUBMITTED);
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

    @Test
    void carriesTheOptionalFiltersThrough() {
        IntegrationLogSheetQuery query = IntegrationLogSheetQuery.parse(
                "2026-08-01", "2026-09-01", null, 7L, 9L, 2, 25, TEHRAN);

        assertThat(query.unitId()).isEqualTo(7L);
        assertThat(query.templateId()).isEqualTo(9L);
        assertThat(query.page()).isEqualTo(2);
        assertThat(query.size()).isEqualTo(25);
    }
}
