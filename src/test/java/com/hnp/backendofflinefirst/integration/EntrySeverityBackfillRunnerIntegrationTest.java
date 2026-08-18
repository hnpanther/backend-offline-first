package com.hnp.backendofflinefirst.integration;

import com.hnp.backendofflinefirst.config.EntrySeverityBackfillRunner;
import com.hnp.backendofflinefirst.domain.FieldValidationSupport;
import com.hnp.backendofflinefirst.domain.GenerationMode;
import com.hnp.backendofflinefirst.domain.LogSheetStatus;
import com.hnp.backendofflinefirst.entity.AssetClass;
import com.hnp.backendofflinefirst.entity.FieldDefinition;
import com.hnp.backendofflinefirst.entity.LogSheet;
import com.hnp.backendofflinefirst.repository.AssetClassRepository;
import com.hnp.backendofflinefirst.repository.FieldDefinitionRepository;
import com.hnp.backendofflinefirst.repository.LogSheetEntryRepository;
import com.hnp.backendofflinefirst.repository.LogSheetRepository;
import com.hnp.backendofflinefirst.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The startup backfill has to finish its work — permanently.
 *
 * <p>It advertises itself as idempotent and self-disabling, and it was neither. Its query asked
 * for {@code form_data IS NOT NULL}; a log sheet is raised with one entry per asset and submitted
 * whether or not every asset was reached, so the untouched ones hold an empty json object — not
 * SQL NULL. {@code EntrySeverityEvaluator} reads an empty map as "nothing to judge" and writes the
 * severity back to NULL, so those rows were selected again on the next boot, and the next. On a
 * live database that was 3,093 entries read, their sheets loaded and their definition snapshots
 * resolved on <em>every single start</em>, followed by an INFO line reporting 3,093 entries
 * stamped. Nothing had been stamped.
 *
 * <p>The load-bearing case is {@link #anUntouchedEntryIsNotWorkAndIsNeverSelectedAgain}: it fails
 * against the old predicate. The rest keep the fix from becoming a regression — a row that
 * genuinely needs stamping must still get stamped, on the first pass, with the right value.
 */
class EntrySeverityBackfillRunnerIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired EntrySeverityBackfillRunner runner;
    @Autowired LogSheetEntryRepository logSheetEntryRepository;
    @Autowired LogSheetRepository logSheetRepository;
    @Autowired AssetClassRepository assetClassRepository;
    @Autowired FieldDefinitionRepository fieldDefinitionRepository;
    @Autowired JdbcTemplate jdbc;

    private Long sheetId;
    private Long classId;
    private long now;

    @BeforeEach
    void setUp() {
        now = System.currentTimeMillis();

        AssetClass klass = new AssetClass();
        klass.setName("کلاس بک‌فیل " + System.nanoTime());
        klass.setCreatedAt(now);
        klass.setUpdatedAt(now);
        classId = assetClassRepository.save(klass).getId();

        FieldDefinition fd = new FieldDefinition();
        fd.setClassId(classId);
        fd.setKey("pressure");
        fd.setLabel("فشار");
        fd.setDataType("number");
        // The nested shape the evaluator actually reads: warning and danger each carry their
        // own min/max. A flat map of min/max keys parses to "no thresholds" and every reading
        // then comes back OK — which is how this fixture was wrong the first time.
        fd.setValidation(validation(10d, 20d, 5d, 25d));
        fd.setOrder(1);
        fd.setCreatedAt(now);
        fd.setUpdatedAt(now);
        fieldDefinitionRepository.save(fd);

        LogSheet sheet = new LogSheet();
        sheet.setTemplateName("Backfill fixture");
        sheet.setScopeSummary("fixture");
        sheet.setStatus(LogSheetStatus.SUBMITTED);
        sheet.setOrigin(GenerationMode.MANUAL);
        sheet.setCreatedAt(now);
        sheet.setUpdatedAt(now);
        sheetId = logSheetRepository.save(sheet).getId();
    }

    @Test
    void anUntouchedEntryIsNotWorkAndIsNeverSelectedAgain() {
        // The entry every raised sheet carries for an asset nobody reached: present, empty.
        Long id = insertEntry("{}");

        assertThat(pendingIds())
                .as("an empty form_data is not a value waiting to be judged")
                .doesNotContain(id);

        runner.run(null);

        // Still NULL — correctly so, because there is nothing to evaluate — and still not
        // pending. Under the old predicate this row came back on every boot forever.
        assertThat(severityOf(id)).isNull();
        assertThat(pendingIds()).doesNotContain(id);
    }

    @Test
    void theJsonNullLiteralIsNotWorkEither() {
        // Distinct from SQL NULL and from an empty object: jsonb null reaches the evaluator as a
        // null map, which it also answers with NULL. Same endless loop, different spelling.
        Long id = insertEntry("null");

        assertThat(pendingIds()).doesNotContain(id);
    }

    @Test
    void anEntryThatReallyHasValuesIsStamped() {
        Long ok = insertEntry("{\"pressure\": 15}");

        assertThat(pendingIds()).contains(ok);

        runner.run(null);

        assertThat(severityOf(ok)).isEqualTo("OK");
    }

    @Test
    void aBreachIsStampedWithItsSeverityNotJustMarkedEvaluated() {
        // Backfilling to a flat OK would be worse than leaving NULL: the exception report would
        // then confidently show nothing wrong across the whole history.
        Long danger = insertEntry("{\"pressure\": 99}");
        Long warning = insertEntry("{\"pressure\": 22}");

        runner.run(null);

        assertThat(severityOf(danger)).isEqualTo("DANGER");
        assertThat(severityOf(warning)).isEqualTo("WARNING");
    }

    @Test
    void aSecondStartFindsNothingToDo() {
        Long filled = insertEntry("{\"pressure\": 15}");
        Long empty = insertEntry("{}");

        runner.run(null);
        assertThat(pendingIds()).doesNotContain(filled, empty);

        // The whole claim in one line: whatever the first boot did, the second has no work.
        assertThat(runner.backfill())
                .as("a restart must not re-read and re-write rows it already settled")
                .isZero();
    }

    @Test
    void anAlreadyEvaluatedEntryIsLeftAlone() {
        Long id = insertEntry("{\"pressure\": 99}");
        jdbc.update("UPDATE log_sheet_entries SET max_severity = 'OK' WHERE id = ?", id);

        runner.run(null);

        // NULL means "never evaluated"; a stamped row is history and the backfill does not
        // re-judge it, even when today's ranges would disagree.
        assertThat(severityOf(id)).isEqualTo("OK");
    }

    @Test
    void theFetchIsBoundedSoALargeLegacySetCannotBeReadIntoHeapAtOnce() {
        insertEntry("{\"pressure\": 15}");
        insertEntry("{\"pressure\": 16}");
        insertEntry("{\"pressure\": 17}");

        assertThat(logSheetEntryRepository.findUnevaluatedWithValues(2))
                .as("the limit is the point — an unbounded read is what took the login page down")
                .hasSize(2);
    }

    private Long insertEntry(String formDataJson) {
        jdbc.update("""
                INSERT INTO log_sheet_entries (log_sheet_id, class_id, form_data, max_severity,
                                               created_at, updated_at)
                VALUES (?, ?, ?::jsonb, NULL, ?, ?)
                """, sheetId, classId, formDataJson, now, now);
        return jdbc.queryForObject("SELECT max(id) FROM log_sheet_entries", Long.class);
    }

    /** Ids the backfill query would pick up — the real repository method, not a copy of it. */
    private List<Long> pendingIds() {
        return logSheetEntryRepository.findUnevaluatedWithValues(Integer.MAX_VALUE).stream()
                .map(e -> e.getId())
                .toList();
    }

    private static Map<String, Object> validation(double warnMin, double warnMax,
                                                  double dangerMin, double dangerMax) {
        Map<String, Object> warning = new HashMap<>();
        warning.put(FieldValidationSupport.KEY_MIN, warnMin);
        warning.put(FieldValidationSupport.KEY_MAX, warnMax);
        Map<String, Object> danger = new HashMap<>();
        danger.put(FieldValidationSupport.KEY_MIN, dangerMin);
        danger.put(FieldValidationSupport.KEY_MAX, dangerMax);
        Map<String, Object> validation = new HashMap<>();
        validation.put(FieldValidationSupport.KEY_WARNING, warning);
        validation.put(FieldValidationSupport.KEY_DANGER, danger);
        return validation;
    }

    private String severityOf(Long id) {
        return jdbc.queryForObject(
                "SELECT max_severity FROM log_sheet_entries WHERE id = ?", String.class, id);
    }
}
