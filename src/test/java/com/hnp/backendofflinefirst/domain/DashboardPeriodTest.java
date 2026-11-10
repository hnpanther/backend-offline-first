package com.hnp.backendofflinefirst.domain;

import com.hnp.backendofflinefirst.util.DateUtils;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The dashboard's window: half-open, anchored on plant midnight, refused when it is not a period.
 *
 * <p>Worth a test rather than a careful read because every one of these is silent when wrong. A
 * closed upper bound double-counts one millisecond's worth of rounds between two adjacent
 * periods; an anchor taken from the host's clock moves every boundary by the UTC offset, which is
 * the defect {@code ManagementReportTrendZoneTest} exists for; and a coerced period answers 200
 * with a window nobody asked for.
 */
class DashboardPeriodTest {

    /** A moment in the middle of a Tehran day, so a UTC-anchored window would differ visibly. */
    private static final long NOON_TEHRAN = ZonedDateTime
            .of(2026, 11, 10, 12, 30, 0, 0, DateUtils.TEHRAN)
            .toInstant().toEpochMilli();

    @Test
    void theUpperBoundIsTheNextPlantMidnightSoTodayIsWhollyIncluded() {
        DashboardPeriod.Window day = DashboardPeriod.DAY.window(DateUtils.TEHRAN, NOON_TEHRAN);

        assertThat(ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(day.toAt()), DateUtils.TEHRAN))
                .isEqualTo(ZonedDateTime.of(2026, 11, 11, 0, 0, 0, 0, DateUtils.TEHRAN));
        assertThat(ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(day.fromAt()), DateUtils.TEHRAN))
                .isEqualTo(ZonedDateTime.of(2026, 11, 10, 0, 0, 0, 0, DateUtils.TEHRAN));
    }

    @Test
    void oneDayIsExactlyOneDayAndOneWeekIsExactlySeven() {
        assertThat(span(DashboardPeriod.DAY)).isEqualTo(Duration.ofDays(1));
        assertThat(span(DashboardPeriod.WEEK)).isEqualTo(Duration.ofDays(7));
        assertThat(span(DashboardPeriod.MONTH)).isEqualTo(Duration.ofDays(30));
    }

    @Test
    void adjacentPeriodsShareTheirBoundaryWithNoOverlapAndNoGap() {
        // The whole point of a half-open range: today's `from` is yesterday's `to`, so the two
        // windows partition the rounds instead of both claiming the one raised at midnight.
        DashboardPeriod.Window today = DashboardPeriod.DAY.window(DateUtils.TEHRAN, NOON_TEHRAN);
        long yesterdayNoon = NOON_TEHRAN - Duration.ofDays(1).toMillis();
        DashboardPeriod.Window yesterday = DashboardPeriod.DAY.window(DateUtils.TEHRAN, yesterdayNoon);

        assertThat(yesterday.toAt()).isEqualTo(today.fromAt());
    }

    @Test
    void theLastIncludedInstantIsInsideTheWindowAndTheUpperBoundIsNot() {
        DashboardPeriod.Window day = DashboardPeriod.DAY.window(DateUtils.TEHRAN, NOON_TEHRAN);

        assertThat(day.lastIncludedAt()).isLessThan(day.toAt());
        assertThat(day.toAt() - day.lastIncludedAt())
                .as("it is the bound minus one millisecond, so a page can print a real date")
                .isEqualTo(1);
    }

    @Test
    void theAnchorFollowsTheZoneItIsGivenRatherThanTheHost() {
        // Tehran is +03:30, so a UTC-anchored midnight falls three and a half hours later in
        // absolute time. Reading the host's zone instead is how the twelve-month trend put a
        // round raised at 02:00 Tehran into the previous month.
        DashboardPeriod.Window tehran = DashboardPeriod.DAY.window(DateUtils.TEHRAN, NOON_TEHRAN);
        DashboardPeriod.Window utc = DashboardPeriod.DAY.window(ZoneId.of("UTC"), NOON_TEHRAN);

        assertThat(tehran.fromAt()).isNotEqualTo(utc.fromAt());
        assertThat(utc.fromAt() - tehran.fromAt()).isEqualTo(Duration.ofMinutes(210).toMillis());
    }

    @Test
    void anAbsentPeriodIsTheDefaultAndAnUnknownOneIsRefused() {
        assertThat(DashboardPeriod.fromNullableOrDefault(null)).isEqualTo(DashboardPeriod.WEEK);
        assertThat(DashboardPeriod.fromNullableOrDefault("   ")).isEqualTo(DashboardPeriod.WEEK);
        assertThat(DashboardPeriod.fromNullableOrDefault("month")).isEqualTo(DashboardPeriod.MONTH);
        assertThat(DashboardPeriod.fromNullableOrDefault(" Day "))
                .as("a hand-typed value with whitespace and a stray case is still that period")
                .isEqualTo(DashboardPeriod.DAY);

        assertThat(DashboardPeriod.fromNullableOrDefault("QUARTER"))
                .as("null tells the caller to refuse; coercing would answer 200 with another window")
                .isNull();
        assertThat(DashboardPeriod.fromNullableOrDefault("7")).isNull();
    }

    private static Duration span(DashboardPeriod period) {
        DashboardPeriod.Window window = period.window(DateUtils.TEHRAN, NOON_TEHRAN);
        return Duration.ofMillis(window.toAt() - window.fromAt());
    }
}
