package com.hnp.backendofflinefirst.util;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

@Component("dateUtils")
public class DateUtils {

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
}
