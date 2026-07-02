package com.hnp.backendofflinefirst.domain;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * Time unit of a template's recurrence. Combined with {@code recurrence_every}
 * it expresses any period: (HOUR,24)=daily, (DAY,2)=every 2 days, (MONTH,1)=monthly.
 * Calendar-aware for WEEK/MONTH so month lengths and DST are handled correctly.
 */
public enum RecurrenceUnit {
    HOUR,
    DAY,
    WEEK,
    MONTH;

    /**
     * Advances {@code fromEpochMillis} by {@code every} units of this period.
     *
     * @param zone the zone used for calendar math (WEEK/MONTH)
     */
    public long advance(long fromEpochMillis, int every, ZoneId zone) {
        int step = Math.max(every, 1);
        ZonedDateTime from = Instant.ofEpochMilli(fromEpochMillis).atZone(zone);
        ZonedDateTime next = switch (this) {
            case HOUR -> from.plusHours(step);
            case DAY -> from.plusDays(step);
            case WEEK -> from.plusWeeks(step);
            case MONTH -> from.plusMonths(step);
        };
        return next.toInstant().toEpochMilli();
    }

    public static RecurrenceUnit fromNullable(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return RecurrenceUnit.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
