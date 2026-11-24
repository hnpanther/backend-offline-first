package com.hnp.backendofflinefirst.domain;

import com.hnp.backendofflinefirst.util.DateUtils;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reading and writing a {@code datetime} field's value — a calendar instant the operator
 * picked (Jalali on the panel and the tablet; Gregorian on the wire).
 *
 * <p>Canonical stored shape mirrors {@link LocationValues}: a small object with an explicit
 * {@code type}, so a reader can tell what a value is without consulting the field definition.
 *
 * <pre>{ "occurred_at": { "type": "datetime", "epochMs": 1732420200000 } }</pre>
 *
 * <p><b>Why an object and not a bare number or an ISO string.</b> A bare number looks like any
 * other reading; a string forces every consumer to re-parse and re-guess the timezone. Named
 * keys survive Jackson, jsonb, Excel export and the integration API without a parsing step.
 * Display is always Jalali in Asia/Tehran ({@code DateUtils}); the stored value is UTC epoch
 * milliseconds so two devices in the same plant never disagree about which instant was chosen.
 *
 * <p>Accepted on the way in (and normalised on the way out):
 * <ul>
 *   <li>the canonical object;</li>
 *   <li>a bare epoch-millis number (or digit string);</li>
 *   <li>the panel picker's hidden value {@code yyyy-MM-dd'T'HH:mm} interpreted in Asia/Tehran.</li>
 * </ul>
 */
public final class DateTimeValues {

    public static final String TYPE_KEY = "type";
    public static final String TYPE_VALUE = "datetime";
    public static final String EPOCH_KEY = "epochMs";

    /** Same pattern the Persian datetime picker's hidden input posts (zone applied via {@link DateUtils#TEHRAN}). */
    private static final DateTimeFormatter INPUT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");

    /**
     * Soft bounds so a typo or a corrupted payload is refused rather than stored as a fact.
     * Roughly 1970-01-01 … 2100-01-01 UTC.
     */
    public static final long MIN_EPOCH_MS = 0L;
    public static final long MAX_EPOCH_MS = 4_102_444_800_000L;

    private DateTimeValues() {}

    public static boolean isDateTimeField(String dataType) {
        return TYPE_VALUE.equals(dataType);
    }

    /** Canonical stored form. */
    public static Map<String, Object> toValue(long epochMs) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put(TYPE_KEY, TYPE_VALUE);
        out.put(EPOCH_KEY, epochMs);
        return out;
    }

    /**
     * Parses a stored or inbound value into epoch milliseconds.
     *
     * @return the instant, or {@code null} when the value holds no usable one
     */
    public static Long parseEpoch(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Map<?, ?> map) {
            Long epoch = toLong(map.get(EPOCH_KEY));
            return inRange(epoch) ? epoch : null;
        }
        if (value instanceof Number number) {
            long epoch = number.longValue();
            return inRange(epoch) ? epoch : null;
        }
        if (value instanceof String text) {
            String trimmed = text.trim();
            if (trimmed.isEmpty()) {
                return null;
            }
            if (trimmed.chars().allMatch(Character::isDigit)) {
                try {
                    long epoch = Long.parseLong(trimmed);
                    return inRange(epoch) ? epoch : null;
                } catch (NumberFormatException e) {
                    return null;
                }
            }
            try {
                long epoch = LocalDateTime.parse(trimmed, INPUT_FMT)
                        .atZone(DateUtils.TEHRAN)
                        .toInstant()
                        .toEpochMilli();
                return inRange(epoch) ? epoch : null;
            } catch (DateTimeParseException e) {
                return null;
            }
        }
        return null;
    }

    /** Canonical form written back, or {@code null} when unparseable. */
    public static Map<String, Object> canonical(Object value) {
        Long epoch = parseEpoch(value);
        return epoch == null ? null : toValue(epoch);
    }

    private static boolean inRange(Long epoch) {
        return epoch != null && epoch >= MIN_EPOCH_MS && epoch <= MAX_EPOCH_MS;
    }

    private static Long toLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Gregorian ISO-8601 with offset for the integration API and machine readers. */
    public static String toIso(long epochMs) {
        return Instant.ofEpochMilli(epochMs).atZone(DateUtils.TEHRAN)
                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }
}
