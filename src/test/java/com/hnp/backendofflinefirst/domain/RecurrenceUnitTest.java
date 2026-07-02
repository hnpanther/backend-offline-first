package com.hnp.backendofflinefirst.domain;

import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class RecurrenceUnitTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Tehran");

    private long millis(int year, int month, int day, int hour) {
        return ZonedDateTime.of(year, month, day, hour, 0, 0, 0, ZONE).toInstant().toEpochMilli();
    }

    @Test
    void everyTwoDaysAdvancesByTwoDays() {
        long from = millis(2026, 1, 1, 8);
        long next = RecurrenceUnit.DAY.advance(from, 2, ZONE);
        assertThat(next).isEqualTo(millis(2026, 1, 3, 8));
    }

    @Test
    void every24HoursAdvancesOneDay() {
        long from = millis(2026, 1, 1, 8);
        long next = RecurrenceUnit.HOUR.advance(from, 24, ZONE);
        assertThat(next).isEqualTo(millis(2026, 1, 2, 8));
    }

    @Test
    void monthlyAdvancesCalendarMonth() {
        long from = millis(2026, 1, 31, 8);
        long next = RecurrenceUnit.MONTH.advance(from, 1, ZONE);
        // Jan 31 + 1 month clamps to Feb 28 (2026 is not a leap year)
        assertThat(next).isEqualTo(millis(2026, 2, 28, 8));
    }

    @Test
    void zeroOrNegativeEveryTreatedAsOne() {
        long from = millis(2026, 1, 1, 8);
        assertThat(RecurrenceUnit.DAY.advance(from, 0, ZONE)).isEqualTo(millis(2026, 1, 2, 8));
    }
}
