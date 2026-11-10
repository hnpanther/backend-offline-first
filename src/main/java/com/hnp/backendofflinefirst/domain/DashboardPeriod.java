package com.hnp.backendofflinefirst.domain;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;

/**
 * How far back the landing dashboard counts: one day, one week or one month.
 *
 * <h2>The window is half-open and anchored on plant midnight</h2>
 *
 * <p>Same rule as {@link LogSheetListFilter}, for the same reason. A closed range forces every
 * reader to answer "does {@code to = 00:00} include 00:00:00.000?", and the two reasonable
 * answers differ. Half-open makes «یک روز» exactly one day, and lets one period's {@code to} be
 * reused as the next one's {@code from} with no overlap and no gap — which is what makes
 * yesterday's dashboard and today's add up to the two-day figure instead of double-counting one
 * millisecond's worth of rounds.
 *
 * <p>{@code to} is the <b>next</b> midnight, not «now», so «یک روز» is the whole of today
 * rather than the part of it that has elapsed. The upper bound therefore sits slightly in the
 * future and nothing is counted there yet; that is the price of a boundary a person can name.
 * Measuring back from «now» instead would make every reload a different window, so two people
 * comparing the same screen would be comparing different numbers.
 *
 * <h2>The zone comes from the caller</h2>
 *
 * <p>A «روز» is a property of the plant, not of the machine the service runs on, and a container
 * defaults to UTC. The zone is therefore a parameter here and
 * {@code DateUtils.TEHRAN} is what the controller passes — the same shape as
 * {@code ManagementReportService.monthlyTrend(ZoneId)}, and for the same reason: that trend once
 * read {@code ZoneId.systemDefault()} and silently put every round raised between midnight and
 * 03:30 Tehran into the previous month. {@code PlantZoneIsPinnedTest} is what stops it coming
 * back, and this enum deliberately names no zone of its own.
 *
 * <h2>Weeks and months are counted in days, deliberately</h2>
 *
 * <p>{@code WEEK} is seven days and {@code MONTH} is thirty, both back from the same midnight —
 * <b>not</b> «since the start of the current Jalali week / month». Two reasons. A calendar month
 * would need Jalali→Gregorian conversion, which this codebase does not have
 * ({@code JalaliConverter} only goes the other way), and a window that resets on the first of
 * the month shows a nearly-empty dashboard on that day — the one day a manager is most likely to
 * be looking at it. A rolling thirty days answers «how are we doing» on every day of the month.
 */
public enum DashboardPeriod {

    DAY(1),
    WEEK(7),
    MONTH(30);

    private final int days;

    DashboardPeriod(int days) {
        this.days = days;
    }

    public int days() {
        return days;
    }

    /**
     * The half-open window {@code [fromAt, toAt)} this period covers, in epoch millis.
     *
     * @param zone the plant's zone — what decides where a day starts
     * @param now  the instant the request was served, in epoch millis
     */
    public Window window(ZoneId zone, long now) {
        ZonedDateTime nextMidnight = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(now), zone)
                .truncatedTo(ChronoUnit.DAYS)
                .plusDays(1);
        long toAt = nextMidnight.toInstant().toEpochMilli();
        long fromAt = nextMidnight.minusDays(days).toInstant().toEpochMilli();
        return new Window(fromAt, toAt);
    }

    /**
     * A period's bounds: {@code fromAt} inclusive, {@code toAt} exclusive.
     *
     * @param lastIncludedAt the last instant actually inside the window — {@code toAt - 1}. It
     *                       exists so a page can print the range without printing a date nothing
     *                       in the window belongs to: «تا ۱۴۰۴/۰۸/۲۰» is what a reader expects,
     *                       and the exclusive bound is the 21st.
     */
    public record Window(long fromAt, long toAt) {

        public long lastIncludedAt() {
            return toAt - 1;
        }
    }

    /**
     * Parses a request parameter, tolerating absence and rejecting a wrong name.
     *
     * <p>{@code WEEK} is the default because the dashboard is the page every login lands on and a
     * single day is often empty — a plant that raises its rounds on a weekly schedule would greet
     * its supervisors with zeros.
     *
     * @return the default ({@link #WEEK}) for null or blank, the matching value for a recognised
     *         name, and {@code null} for anything else — which the caller turns into a refusal
     *         rather than a default. Coercing would answer 200 with a period nobody asked for,
     *         which is the failure {@link LogSheetDateField} and
     *         {@link IntegrationLogSheetQuery} both record for their own parameters.
     */
    public static DashboardPeriod fromNullableOrDefault(String value) {
        if (value == null || value.isBlank()) {
            return WEEK;
        }
        try {
            return DashboardPeriod.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
