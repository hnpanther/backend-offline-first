package com.hnp.backendofflinefirst.domain;

import com.hnp.backendofflinefirst.entity.FieldDefinition;
import com.hnp.backendofflinefirst.entity.LogSheetEntry;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The stamping rules for {@code max_severity} / {@code breached_fields}.
 *
 * <p>The dangerous failure mode for a denormalised flag is <em>staleness</em>, so most of
 * these cases are about re-evaluation clearing or downgrading a previous verdict, not about
 * detecting a breach in the first place.
 */
class EntrySeverityEvaluatorTest {

    private static FieldDefinition numericField(String key, Long classId,
                                                Double warnMin, Double warnMax,
                                                Double dangerMin, Double dangerMax) {
        FieldDefinition fd = new FieldDefinition();
        fd.setKey(key);
        fd.setClassId(classId);
        fd.setDataType("number");
        Map<String, Object> warning = new HashMap<>();
        warning.put(FieldValidationSupport.KEY_MIN, warnMin);
        warning.put(FieldValidationSupport.KEY_MAX, warnMax);
        Map<String, Object> danger = new HashMap<>();
        danger.put(FieldValidationSupport.KEY_MIN, dangerMin);
        danger.put(FieldValidationSupport.KEY_MAX, dangerMax);
        Map<String, Object> validation = new HashMap<>();
        validation.put(FieldValidationSupport.KEY_WARNING, warning);
        validation.put(FieldValidationSupport.KEY_DANGER, danger);
        fd.setValidation(validation);
        return fd;
    }

    /** warning 10–20, danger 5–25: 12 is fine, 22 warns, 40 is danger. */
    private static List<FieldDefinition> pressureDefs() {
        return List.of(numericField("pressure", 7L, 10d, 20d, 5d, 25d));
    }

    private static LogSheetEntry entryWith(Map<String, Object> formData) {
        LogSheetEntry entry = new LogSheetEntry();
        entry.setClassId(7L);
        entry.setFormData(formData);
        return entry;
    }

    @Test
    void aValueInsideEveryRangeIsStampedOkWithNoBreachList() {
        LogSheetEntry entry = entryWith(Map.of("pressure", 12));

        EntrySeverityEvaluator.apply(entry, pressureDefs());

        assertThat(entry.getMaxSeverity()).isEqualTo("OK");
        assertThat(entry.getBreachedFields())
                .as("OK means evaluated-and-clean; there is nothing to list")
                .isNull();
    }

    @Test
    void warningAndDangerAreRecordedWithTheOffendingKey() {
        LogSheetEntry warned = entryWith(Map.of("pressure", 22));
        EntrySeverityEvaluator.apply(warned, pressureDefs());
        assertThat(warned.getMaxSeverity()).isEqualTo("WARNING");
        assertThat(warned.getBreachedFields()).containsExactly("pressure");

        LogSheetEntry endangered = entryWith(Map.of("pressure", 40));
        EntrySeverityEvaluator.apply(endangered, pressureDefs());
        assertThat(endangered.getMaxSeverity()).isEqualTo("DANGER");
        assertThat(endangered.getBreachedFields()).containsExactly("pressure");
    }

    @Test
    void theWorstSeverityWinsAndDangerKeysAreListedFirst() {
        List<FieldDefinition> defs = List.of(
                numericField("pressure", 7L, 10d, 20d, 5d, 25d),
                numericField("temp", 7L, 0d, 50d, -10d, 60d));
        LogSheetEntry entry = entryWith(Map.of("pressure", 22, "temp", 99));

        EntrySeverityEvaluator.apply(entry, defs);

        assertThat(entry.getMaxSeverity()).isEqualTo("DANGER");
        assertThat(entry.getBreachedFields())
                .as("danger before warning so the reader does not have to cross-check")
                .containsExactly("temp", "pressure");
    }

    // ── Re-evaluation: the whole reason this is a computed column ─────────────

    @Test
    void correctingAValueClearsAPreviousBreach() {
        LogSheetEntry entry = entryWith(new HashMap<>(Map.of("pressure", 40)));
        EntrySeverityEvaluator.apply(entry, pressureDefs());
        assertThat(entry.getMaxSeverity()).isEqualTo("DANGER");

        entry.setFormData(new HashMap<>(Map.of("pressure", 12)));
        EntrySeverityEvaluator.apply(entry, pressureDefs());

        assertThat(entry.getMaxSeverity()).isEqualTo("OK");
        assertThat(entry.getBreachedFields()).isNull();
    }

    @Test
    void aDangerCanBeDowngradedToAWarning() {
        LogSheetEntry entry = entryWith(new HashMap<>(Map.of("pressure", 40)));
        EntrySeverityEvaluator.apply(entry, pressureDefs());

        entry.setFormData(new HashMap<>(Map.of("pressure", 22)));
        EntrySeverityEvaluator.apply(entry, pressureDefs());

        assertThat(entry.getMaxSeverity()).isEqualTo("WARNING");
        assertThat(entry.getBreachedFields()).containsExactly("pressure");
    }

    @Test
    void clearingTheValuesResetsTheFlagToNotEvaluated() {
        LogSheetEntry entry = entryWith(new HashMap<>(Map.of("pressure", 40)));
        EntrySeverityEvaluator.apply(entry, pressureDefs());

        entry.setFormData(new HashMap<>());
        EntrySeverityEvaluator.apply(entry, pressureDefs());

        assertThat(entry.getMaxSeverity())
                .as("an emptied entry must not keep advertising a breach")
                .isNull();
        assertThat(entry.getBreachedFields()).isNull();

        entry.setFormData(null);
        EntrySeverityEvaluator.apply(entry, pressureDefs());
        assertThat(entry.getMaxSeverity()).isNull();
    }

    // ── Guards ────────────────────────────────────────────────────────────────

    @Test
    void aFieldFromAnotherClassIsNeverUsedToJudgeThisEntry() {
        // Both classes define "pressure", with very different ranges. Class 7's entry reads 40,
        // which is danger for class 7 but perfectly normal for class 9.
        List<FieldDefinition> defs = List.of(
                numericField("pressure", 9L, 100d, 200d, 50d, 250d),
                numericField("pressure", 7L, 10d, 20d, 5d, 25d));
        LogSheetEntry entry = entryWith(Map.of("pressure", 40));

        EntrySeverityEvaluator.apply(entry, defs);

        assertThat(entry.getMaxSeverity())
                .as("must use class 7's ranges even though class 9's definition came first")
                .isEqualTo("DANGER");
    }

    @Test
    void fieldsWithNoValidationOrNoDefinitionAreIgnored() {
        FieldDefinition noValidation = new FieldDefinition();
        noValidation.setKey("note");
        noValidation.setClassId(7L);

        LogSheetEntry entry = entryWith(Map.of("note", "hello", "unknown_key", 999));
        EntrySeverityEvaluator.apply(entry, List.of(noValidation));

        assertThat(entry.getMaxSeverity())
                .as("values nobody configured a range for cannot breach one")
                .isEqualTo("OK");
    }

    @Test
    void deletedDefinitionsDoNotJudgeAnything() {
        FieldDefinition deleted = numericField("pressure", 7L, 10d, 20d, 5d, 25d);
        deleted.setDeleted(true);

        LogSheetEntry entry = entryWith(Map.of("pressure", 40));
        EntrySeverityEvaluator.apply(entry, List.of(deleted));

        assertThat(entry.getMaxSeverity()).isEqualTo("OK");
    }

    @Test
    void nonNumericAndNullValuesDoNotBreach() {
        Map<String, Object> formData = new HashMap<>();
        formData.put("pressure", null);
        LogSheetEntry withNull = entryWith(formData);
        EntrySeverityEvaluator.apply(withNull, pressureDefs());
        assertThat(withNull.getMaxSeverity()).isEqualTo("OK");

        LogSheetEntry withText = entryWith(Map.of("pressure", "not a number"));
        EntrySeverityEvaluator.apply(withText, pressureDefs());
        assertThat(withText.getMaxSeverity()).isEqualTo("OK");
    }

    @Test
    void numericStringsAreStillEvaluated() {
        // The PWA can send a numeric field as a string; it must not silently escape checking.
        LogSheetEntry entry = entryWith(Map.of("pressure", "40"));

        EntrySeverityEvaluator.apply(entry, pressureDefs());

        assertThat(entry.getMaxSeverity()).isEqualTo("DANGER");
    }

    @Test
    void noDefinitionsAtAllYieldsOkRatherThanAnException() {
        LogSheetEntry entry = entryWith(Map.of("pressure", 40));

        EntrySeverityEvaluator.apply(entry, List.of());
        assertThat(entry.getMaxSeverity()).isEqualTo("OK");

        EntrySeverityEvaluator.apply(entry, null);
        assertThat(entry.getMaxSeverity()).isEqualTo("OK");
    }

    @Test
    void aNullEntryIsANoOp() {
        EntrySeverityEvaluator.apply(null, pressureDefs());
    }
}
