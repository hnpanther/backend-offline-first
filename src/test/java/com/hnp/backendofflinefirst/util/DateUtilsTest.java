package com.hnp.backendofflinefirst.util;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DateUtilsTest {

    private static final ZoneId TEHRAN = ZoneId.of("Asia/Tehran");
    private final DateUtils dateUtils = new DateUtils();

    @Test
    void formatInputHidden_outputsTehranWallTimeAndParsesBack() {
        long epoch = LocalDateTime.of(2025, 3, 21, 14, 30)
                .atZone(TEHRAN).toInstant().toEpochMilli();

        String hidden = dateUtils.formatInputHidden(epoch);

        assertEquals("2025-03-21T14:30", hidden);
        assertEquals(epoch, dateUtils.parseInput(hidden));
    }

    @Test
    void parseInput_legacyEpochMillis_stillWorks() {
        long epoch = LocalDateTime.of(2025, 3, 21, 14, 30)
                .atZone(TEHRAN).toInstant().toEpochMilli();

        assertEquals(epoch, dateUtils.parseInput(Long.toString(epoch)));
    }

    @Test
    void parseInput_legacyIso_interpretsAsTehranWallTime() {
        String iso = "2025-03-21T14:30";
        long expected = LocalDateTime.parse(iso).atZone(TEHRAN).toInstant().toEpochMilli();

        assertEquals(expected, dateUtils.parseInput(iso));
    }

    @Test
    void parseInput_blankOrInvalid_returnsNull() {
        assertNull(dateUtils.parseInput(null));
        assertNull(dateUtils.parseInput(""));
        assertNull(dateUtils.parseInput("   "));
        assertNull(dateUtils.parseInput("not-a-date"));
    }

    @Test
    void formatInputHidden_null_returnsEmpty() {
        assertEquals("", dateUtils.formatInputHidden(null));
    }

    @Test
    void format_jalaliDate_matchesTehranInstant() {
        long epoch = Instant.parse("2025-03-21T11:00:00Z").toEpochMilli();
        assertEquals("1404/01/01 14:30", dateUtils.format(epoch));
    }

    @Test
    void requireFutureWithinYears_null_isNoOp() {
        assertDoesNotThrow(() -> DateUtils.requireFutureWithinYears(null, System.currentTimeMillis(), "Field"));
    }

    @Test
    void requireFutureWithinYears_pastValue_rejected() {
        long now = System.currentTimeMillis();
        long past = now - 1_000L;

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> DateUtils.requireFutureWithinYears(past, now, "Field"));
        assertTrue(ex.getMessage().contains("must be in the future"));
    }

    @Test
    void requireFutureWithinYears_exactlyNow_rejected() {
        long now = System.currentTimeMillis();

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> DateUtils.requireFutureWithinYears(now, now, "Field"));
        assertTrue(ex.getMessage().contains("must be in the future"));
    }

    @Test
    void requireFutureWithinYears_slightlyInFuture_accepted() {
        long now = System.currentTimeMillis();
        long soon = now + 60_000L;

        assertDoesNotThrow(() -> DateUtils.requireFutureWithinYears(soon, now, "Field"));
    }

    @Test
    void requireFutureWithinYears_exactlyAtTwoYearBoundary_accepted() {
        long now = System.currentTimeMillis();
        long maxAllowed = Instant.ofEpochMilli(now).atZone(TEHRAN)
                .plusYears(DateUtils.MAX_FUTURE_YEARS).toInstant().toEpochMilli();

        assertDoesNotThrow(() -> DateUtils.requireFutureWithinYears(maxAllowed, now, "Field"));
    }

    @Test
    void requireFutureWithinYears_justPastTwoYearBoundary_rejected() {
        long now = System.currentTimeMillis();
        long maxAllowed = Instant.ofEpochMilli(now).atZone(TEHRAN)
                .plusYears(DateUtils.MAX_FUTURE_YEARS).toInstant().toEpochMilli();

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> DateUtils.requireFutureWithinYears(maxAllowed + 1, now, "Field"));
        assertTrue(ex.getMessage().contains("must be within 2 years from now"));
    }
}
