package com.hnp.backendofflinefirst.logging;

import com.hnp.backendofflinefirst.entity.AuditLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * One line per audited change, written to {@code audit.log}.
 *
 * <h2>Why this is not in {@link BusinessEventLogger} any more</h2>
 * It used to write into {@code business.log}, one line <em>per changed field</em>. Measured on
 * the live database that came to 42,498 audit lines against roughly 40 real business events —
 * 9.8 MB against 708 KB of {@code app.log}. Anyone opening {@code business.log} to find out
 * what the system had done was reading a file that was 99.8% something else. The two are
 * different questions asked by different people at different times:
 * <ul>
 *   <li><b>business.log</b> — "what did the system do?" Imports, scheduler runs, sheets
 *       created and expired. Tens of lines a day; an operator scrolls it.</li>
 *   <li><b>audit.log</b> — "who changed this row?" Tens of thousands of lines a day; nobody
 *       scrolls it, it is grepped for one entity or shipped to a log store.</li>
 * </ul>
 *
 * <h2>Why one line, and why no values</h2>
 * The authoritative record is the {@code audit_log} table, which keeps every old and new value
 * as JSONB and has its own admin page at {@code /audit-logs}. This file is a <em>trace</em>,
 * not a second copy: enough to see that a change happened and which fields moved, so a support
 * question can be answered from the filesystem alone and then followed up in the UI. Repeating
 * the values here would double the storage of data that already has a home, and would put
 * potentially sensitive field contents into a plain file with different retention.
 */
@Component
public class AuditTrailLogger {

    /**
     * A synthetic logger name — no such package exists — so {@code logback-spring.xml} can
     * route it to its own appender. Same convention as {@code …business}. It deliberately does
     * not use the real {@code …audit} package name, which would also capture anything
     * {@code AuditEntitySupport} logs.
     */
    private static final Logger AUDIT = LoggerFactory.getLogger("com.hnp.backendofflinefirst.audittrail");

    /**
     * Field names are listed, but a wide entity would otherwise produce a line hundreds of
     * characters long that no terminal shows in full. The count is always exact; the list is
     * what got truncated.
     */
    private static final int MAX_LISTED_FIELDS = 12;

    public void changeRecorded(AuditLog row) {
        if (!AUDIT.isInfoEnabled()) {
            return;
        }
        List<Map<String, String>> changes = row.getChanges();
        int fieldCount = changes == null ? 0 : changes.size();
        AUDIT.info("action={} entity={} id={} actor={} source={} fields={} changed=[{}]",
                row.getAction(),
                row.getEntityType(),
                row.getEntityId(),
                row.getActorUsername(),
                row.getSource(),
                fieldCount,
                fieldNames(changes));
    }

    private static String fieldNames(List<Map<String, String>> changes) {
        if (changes == null || changes.isEmpty()) {
            return "";
        }
        String listed = changes.stream()
                .limit(MAX_LISTED_FIELDS)
                .map(c -> String.valueOf(c.get("field")))
                .collect(Collectors.joining(","));
        int overflow = changes.size() - MAX_LISTED_FIELDS;
        return overflow > 0 ? listed + ",+" + overflow + " more" : listed;
    }
}
