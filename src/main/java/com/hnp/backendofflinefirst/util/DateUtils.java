package com.hnp.backendofflinefirst.util;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

@Component("dateUtils")
public class DateUtils {

    private static final ZoneId TEHRAN = ZoneId.of("Asia/Tehran");
    private static final DateTimeFormatter DATETIME_FMT =
            DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm").withZone(TEHRAN);
    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("yyyy/MM/dd").withZone(TEHRAN);

    public String format(Long epochMs) {
        if (epochMs == null) return "—";
        return DATETIME_FMT.format(Instant.ofEpochMilli(epochMs));
    }

    public String formatDate(Long epochMs) {
        if (epochMs == null) return "—";
        return DATE_FMT.format(Instant.ofEpochMilli(epochMs));
    }
}
