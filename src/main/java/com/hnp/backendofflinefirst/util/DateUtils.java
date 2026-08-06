package com.hnp.backendofflinefirst.util;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

@Component("dateUtils")
public class DateUtils {

    /** Max how far into the future a user-submitted deadline/schedule date may be. */
    public static final int MAX_FUTURE_YEARS = 2;

    private static final ZoneId TEHRAN = ZoneId.of("Asia/Tehran");
    private static final DateTimeFormatter INPUT_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm").withZone(TEHRAN);

    private static final String[] JALALI_MONTHS = {
            "", "فروردین", "اردیبهشت", "خرداد", "تیر", "مرداد", "شهریور",
            "مهر", "آبان", "آذر", "دی", "بهمن", "اسفند"
    };

    /** Jalali date + time in Asia/Tehran, e.g. ۱۴۰۴/۰۴/۱۳ ۰۹:۱۶ */
    public String format(Long epochMs) {
        if (epochMs == null) return "—";
        ZonedDateTime zdt = Instant.ofEpochMilli(epochMs).atZone(TEHRAN);
        JalaliConverter.JalaliDate j = JalaliConverter.fromGregorian(
                zdt.getYear(), zdt.getMonthValue(), zdt.getDayOfMonth());
        return String.format("%04d/%02d/%02d %02d:%02d",
                j.year(), j.month(), j.day(), zdt.getHour(), zdt.getMinute());
    }

    /** Jalali date only. */
    public String formatDate(Long epochMs) {
        if (epochMs == null) return "—";
        ZonedDateTime zdt = Instant.ofEpochMilli(epochMs).atZone(TEHRAN);
        JalaliConverter.JalaliDate j = JalaliConverter.fromGregorian(
                zdt.getYear(), zdt.getMonthValue(), zdt.getDayOfMonth());
        return String.format("%04d/%02d/%02d", j.year(), j.month(), j.day());
    }

    /** Jalali date with month name, e.g. ۱۳ تیر ۱۴۰۴ — ۰۹:۱۶ */
    public String formatLong(Long epochMs) {
        if (epochMs == null) return "—";
        ZonedDateTime zdt = Instant.ofEpochMilli(epochMs).atZone(TEHRAN);
        JalaliConverter.JalaliDate j = JalaliConverter.fromGregorian(
                zdt.getYear(), zdt.getMonthValue(), zdt.getDayOfMonth());
        String month = j.month() >= 1 && j.month() <= 12 ? JALALI_MONTHS[j.month()] : String.valueOf(j.month());
        return String.format("%d %s %d — %02d:%02d",
                j.day(), month, j.year(), zdt.getHour(), zdt.getMinute());
    }

    /** Value for an HTML {@code <input type="datetime-local">} (Gregorian, for browser input). */
    public String formatInput(Long epochMs) {
        if (epochMs == null) return "";
        return INPUT_FMT.format(Instant.ofEpochMilli(epochMs));
    }

    /** Hidden field value for the Persian datetime picker (Gregorian wall time in Asia/Tehran). */
    /**
     * Human-readable duration in Persian, for report columns like "median lateness".
     *
     * <p>Deliberately coarse — one or two units, never a precise breakdown. A manager
     * reading "۲ روز و ۴ ساعت" acts on it; "۲ روز، ۴ ساعت، ۱۷ دقیقه و ۹ ثانیه" is noise.
     * Negative or null input returns an em dash rather than a bogus zero.
     */
    public String formatDuration(Long millis) {
        if (millis == null || millis < 0) {
            return "—";
        }
        long totalMinutes = millis / 60_000L;
        if (totalMinutes < 1) {
            return "کمتر از یک دقیقه";
        }
        long days = totalMinutes / (60 * 24);
        long hours = (totalMinutes % (60 * 24)) / 60;
        long minutes = totalMinutes % 60;
        if (days > 0) {
            return hours > 0 ? days + " روز و " + hours + " ساعت" : days + " روز";
        }
        if (hours > 0) {
            return minutes > 0 ? hours + " ساعت و " + minutes + " دقیقه" : hours + " ساعت";
        }
        return minutes + " دقیقه";
    }

    public String formatInputHidden(Long epochMs) {
        if (epochMs == null) return "";
        return INPUT_FMT.format(Instant.ofEpochMilli(epochMs));
    }

    /**
     * Parses a submitted datetime from the Persian picker hidden field
     * ({@code yyyy-MM-dd'T'HH:mm} interpreted in Asia/Tehran) or legacy epoch millis.
     */
    public Long parseInput(String value) {
        if (value == null || value.isBlank()) return null;
        String trimmed = value.trim();
        if (trimmed.chars().allMatch(Character::isDigit)) {
            try {
                return Long.parseLong(trimmed);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        try {
            return java.time.LocalDateTime.parse(trimmed, INPUT_FMT)
                    .atZone(TEHRAN).toInstant().toEpochMilli();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Validates a user-submitted deadline/schedule date: must be strictly after {@code now}
     * and at most {@link #MAX_FUTURE_YEARS} years after {@code now}. {@code label} identifies
     * the field in the (English, later Persian-translated via ErrorTranslator) exception
     * message. No-op when {@code epochMs} is null — whether the field is required at all is
     * the caller's own concern.
     */
    public static void requireFutureWithinYears(Long epochMs, long now, String label) {
        if (epochMs == null) {
            return;
        }
        if (epochMs <= now) {
            throw new IllegalArgumentException(label + " must be in the future.");
        }
        long maxAllowed = Instant.ofEpochMilli(now).atZone(TEHRAN)
                .plusYears(MAX_FUTURE_YEARS).toInstant().toEpochMilli();
        if (epochMs > maxAllowed) {
            throw new IllegalArgumentException(label + " must be within " + MAX_FUTURE_YEARS + " years from now.");
        }
    }
}
