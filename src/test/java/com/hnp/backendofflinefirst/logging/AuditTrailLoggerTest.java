package com.hnp.backendofflinefirst.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.hnp.backendofflinefirst.domain.AuditAction;
import com.hnp.backendofflinefirst.entity.AuditLog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The audit trail writes <em>one</em> line per change, to its own logger.
 *
 * <p>Both halves of that sentence were defects. It used to emit one line per changed
 * <em>field</em>, into {@code business.log}: measured on the live database, 42,498 audit lines
 * against roughly 40 real business events, so the file that answers "what did the system do"
 * was 99.8% something else and nobody could read it.
 */
class AuditTrailLoggerTest {

    private static final String AUDIT_LOGGER = "com.hnp.backendofflinefirst.audittrail";

    private final AuditTrailLogger auditTrailLogger = new AuditTrailLogger();
    private Logger logger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void attachAppender() {
        logger = (Logger) LoggerFactory.getLogger(AUDIT_LOGGER);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.INFO);
    }

    @AfterEach
    void detachAppender() {
        logger.detachAppender(appender);
    }

    @Test
    void writesExactlyOneLinePerChangeRegardlessOfFieldCount() {
        auditTrailLogger.changeRecorded(auditRow(AuditAction.UPDATE, "nfcTagId", "status", "assetName"));

        assertThat(appender.list).hasSize(1);
    }

    @Test
    void theLineNamesTheActorTheEntityAndTheFieldsThatMoved() {
        auditTrailLogger.changeRecorded(auditRow(AuditAction.UPDATE, "nfcTagId", "status"));

        String line = appender.list.getFirst().getFormattedMessage();
        assertThat(line)
                .contains("action=UPDATE")
                .contains("entity=asset_entries")
                .contains("id=42")
                .contains("actor=admin")
                .contains("fields=2")
                .contains("changed=[nfcTagId,status]");
    }

    @Test
    void valuesAreDeliberatelyNotLogged() {
        AuditLog row = auditRow(AuditAction.UPDATE, "nfcTagId");
        row.getChanges().getFirst().put("oldValue", "NFC-111");
        row.getChanges().getFirst().put("newValue", "NFC-999");

        auditTrailLogger.changeRecorded(row);

        // The authoritative copy with old/new values is the audit_log table, which has its own
        // retention policy and an admin page. Repeating field contents in a plain file with
        // different retention is a second copy of data that already has a home.
        String line = appender.list.getFirst().getFormattedMessage();
        assertThat(line).doesNotContain("NFC-111").doesNotContain("NFC-999");
    }

    @Test
    void aVeryWideEntityIsTruncatedButTheCountStaysExact() {
        String[] fields = IntStream.range(0, 20).mapToObj(i -> "field" + i).toArray(String[]::new);

        auditTrailLogger.changeRecorded(auditRow(AuditAction.UPDATE, fields));

        String line = appender.list.getFirst().getFormattedMessage();
        // The count is the number you can trust; the list is what got shortened.
        assertThat(line).contains("fields=20").contains("+8 more");
    }

    @Test
    void aCreateWithNoDiffStillProducesALine() {
        AuditLog row = auditRow(AuditAction.CREATE);
        row.setChanges(null);

        auditTrailLogger.changeRecorded(row);

        assertThat(appender.list).hasSize(1);
        assertThat(appender.list.getFirst().getFormattedMessage()).contains("fields=0");
    }

    @Test
    void nothingIsWrittenToTheBusinessLogger() {
        Logger business = (Logger) LoggerFactory.getLogger("com.hnp.backendofflinefirst.business");
        ListAppender<ILoggingEvent> businessAppender = new ListAppender<>();
        businessAppender.start();
        business.addAppender(businessAppender);
        try {
            auditTrailLogger.changeRecorded(auditRow(AuditAction.UPDATE, "status"));

            // The whole point of the split: business.log answers a different question.
            assertThat(businessAppender.list).isEmpty();
        } finally {
            business.detachAppender(businessAppender);
        }
    }

    private static AuditLog auditRow(AuditAction action, String... fields) {
        AuditLog row = new AuditLog();
        row.setAction(action);
        row.setEntityType("asset_entries");
        row.setEntityId("42");
        row.setActorUsername("admin");
        row.setSource("WEB");
        List<Map<String, String>> changes = new ArrayList<>();
        for (String field : fields) {
            Map<String, String> change = new LinkedHashMap<>();
            change.put("field", field);
            changes.add(change);
        }
        row.setChanges(changes);
        return row;
    }
}
