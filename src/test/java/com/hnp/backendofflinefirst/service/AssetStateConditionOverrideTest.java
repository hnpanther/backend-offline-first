package com.hnp.backendofflinefirst.service;

import com.hnp.backendofflinefirst.domain.AssetFieldResolver;
import com.hnp.backendofflinefirst.domain.EntrySeverityEvaluator;
import com.hnp.backendofflinefirst.domain.FieldGateOperators;
import com.hnp.backendofflinefirst.domain.FieldSource;
import com.hnp.backendofflinefirst.domain.FieldValidationSeverity;
import com.hnp.backendofflinefirst.domain.FieldValidationSupport;
import com.hnp.backendofflinefirst.dto.AssetFieldOverrideForm;
import com.hnp.backendofflinefirst.entity.AssetEntry;
import com.hnp.backendofflinefirst.entity.FieldDefinition;
import com.hnp.backendofflinefirst.entity.LogSheetEntry;
import com.hnp.backendofflinefirst.repository.AssetEntryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * A state condition <b>per asset</b> — set it, change it, clear it, and have it survive the
 * freeze.
 *
 * <p>This is the half that is easy to leave half-built, and the failure is silent in both
 * directions. A gate the asset layer cannot carry means one machine of a fleet can only be gated
 * by detaching it to {@code FULL} and severing its inheritance. A gate the asset layer carries
 * but the <em>snapshot</em> drops means the panel shows a condition that the tablet, the stored
 * severity and the alarm evaluation never apply — a false alarm on every reading in the state the
 * gate existed to exclude, with the configuration screen insisting it is handled.
 */
@ExtendWith(MockitoExtension.class)
class AssetStateConditionOverrideTest {

    private static final double NO_LOAD = 4.5;

    @Mock AssetEntryRepository assetEntryRepository;
    @Mock LogSheetFieldDefinitionsService fieldDefinitionsService;
    @InjectMocks AssetFieldOverrideService service;

    private AssetEntry asset;

    @BeforeEach
    void setUp() {
        asset = new AssetEntry();
        asset.setId(7L);
        asset.setClassId(3L);
        asset.setFieldSource(FieldSource.RANGES_ONLY.name());

        lenient().when(assetEntryRepository.findById(7L)).thenReturn(Optional.of(asset));
        lenient().when(assetEntryRepository.save(any(AssetEntry.class))).thenAnswer(i -> i.getArgument(0));
        lenient().when(fieldDefinitionsService.flattenedByClass(List.of(3L)))
                .thenReturn(Map.of(3L, List.of(ampere(), status(), note())));
    }

    // =======================================================================================
    // Writing
    // =======================================================================================

    @Test
    @DisplayName("RANGES_ONLY may set a gate: it says WHEN a band applies, which is part of the band")
    void rangesOnlyMaySetAGate() {
        AssetEntry saved = service.save(7L, FieldSource.RANGES_ONLY,
                Map.of("ampere", Map.of(
                        FieldValidationSupport.KEY_DANGER, rangeOf(NO_LOAD, null),
                        FieldValidationSupport.KEY_CONDITIONS, List.of(conditionOn("status", "روشن")))));

        Map<?, ?> stored = (Map<?, ?>) saved.getFieldOverrides().get("ampere");
        assertThat(stored.containsKey(FieldValidationSupport.KEY_CONDITIONS)).isTrue();

        // And the asset must actually be judged by it, not merely store it.
        assertThat(severityOfResolved(saved, 0, Map.of("status", "روشن", "ampere", 0)))
                .isEqualTo(FieldValidationSeverity.DANGER);
        assertThat(severityOfResolved(saved, 0, Map.of("status", "خاموش", "ampere", 0)))
                .isEqualTo(FieldValidationSeverity.OK);
    }

    @Test
    @DisplayName("an explicit null clears the CLASS's gate for this machine, rather than inheriting it")
    void anExplicitNullClearsTheClassGate() {
        // The class gates ampere; this one asset is a fixed-speed pump that is never off, so its
        // current is always meaningful and the gate is in the way.
        FieldDefinition gatedAmpere = ampere();
        gatedAmpere.setValidation(gatedBand());
        when(fieldDefinitionsService.flattenedByClass(List.of(3L)))
                .thenReturn(Map.of(3L, List.of(gatedAmpere, status(), note())));

        Map<String, Object> override = new LinkedHashMap<>();
        Map<String, Object> field = new LinkedHashMap<>();
        field.put(FieldValidationSupport.KEY_CONDITIONS, null);
        override.put("ampere", field);

        AssetEntry saved = service.save(7L, FieldSource.RANGES_ONLY, override);

        List<FieldDefinition> resolved = AssetFieldResolver.applyAssetOverrides(
                List.of(gatedAmpere, status(), note()), FieldSource.RANGES_ONLY,
                saved.getFieldOverrides(), 3L);
        Map<String, Object> validation = byKey(resolved, "ampere").getValidation();

        assertThat(FieldValidationSupport.conditions(validation)).isEmpty();
        assertThat(FieldValidationSupport.evaluateValue("number", 0, validation, Map.of("status", "خاموش")))
                .as("ungated again: this machine's zero is a danger whatever the status says")
                .isEqualTo(FieldValidationSeverity.DANGER);
    }

    @Test
    @DisplayName("a gate naming a parameter the asset does not have is refused, not stored inert")
    void anUnreachableGateIsRefused() {
        // Refused because it would not degrade to "unconditional" — it would SUPPRESS the field,
        // so a danger band the administrator believes they configured judges nothing at all.
        assertThatThrownBy(() -> service.save(7L, FieldSource.RANGES_ONLY,
                Map.of("ampere", Map.of(
                        FieldValidationSupport.KEY_CONDITIONS, List.of(conditionOn("speed", "روشن"))))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not on this asset");
    }

    @Test
    @DisplayName("self-reference, an unknown operator and a missing operand are each refused")
    void theOtherRefusals() {
        assertThatThrownBy(() -> service.save(7L, FieldSource.RANGES_ONLY,
                Map.of("ampere", Map.of(
                        FieldValidationSupport.KEY_CONDITIONS, List.of(conditionOn("ampere", "روشن"))))))
                .hasMessageContaining("another parameter");

        Map<String, Object> badOp = new LinkedHashMap<>();
        badOp.put(FieldValidationSupport.KEY_GATE_FIELD, "status");
        badOp.put(FieldValidationSupport.KEY_GATE_OP, "roughly");
        assertThatThrownBy(() -> service.save(7L, FieldSource.RANGES_ONLY,
                Map.of("ampere", Map.of(FieldValidationSupport.KEY_CONDITIONS,
                        List.of(Map.of(FieldValidationSupport.KEY_WHEN, badOp))))))
                .hasMessageContaining("operator");

        Map<String, Object> noValues = new LinkedHashMap<>();
        noValues.put(FieldValidationSupport.KEY_GATE_FIELD, "status");
        noValues.put(FieldValidationSupport.KEY_GATE_OP, FieldGateOperators.IN);
        noValues.put(FieldValidationSupport.KEY_GATE_VALUES, List.of());
        assertThatThrownBy(() -> service.save(7L, FieldSource.RANGES_ONLY,
                Map.of("ampere", Map.of(FieldValidationSupport.KEY_CONDITIONS,
                        List.of(Map.of(FieldValidationSupport.KEY_WHEN, noValues))))))
                .hasMessageContaining("at least one value");

        assertThatThrownBy(() -> service.save(7L, FieldSource.RANGES_ONLY,
                Map.of("note", Map.of(
                        FieldValidationSupport.KEY_CONDITIONS, List.of(conditionOn("status", "روشن"))))))
                .as("a condition on a text field is a rule nothing would ever read")
                .hasMessageContaining("number and select");

        assertThatThrownBy(() -> service.save(7L, FieldSource.RANGES_ONLY,
                Map.of("ampere", Map.of(FieldValidationSupport.KEY_CONDITIONS, "when it is on"))))
                .hasMessageContaining("must be a list");
    }

    @Test
    @DisplayName("a per-state band gets the same min ≤ max check an unconditional one gets")
    void perConditionBandsAreValidated() {
        Map<String, Object> condition = new LinkedHashMap<>(conditionOn("status", "روشن"));
        condition.put(FieldValidationSupport.KEY_DANGER, rangeOf(90.0, 10.0));

        assertThatThrownBy(() -> service.save(7L, FieldSource.RANGES_ONLY,
                Map.of("ampere", Map.of(FieldValidationSupport.KEY_CONDITIONS, List.of(condition)))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // =======================================================================================
    // The editor round trip
    // =======================================================================================

    @Test
    @DisplayName("the editor row carries the gate in force, so leaving it alone cannot drop it")
    void theEditorRowIsPreFilledFromTheResolvedGate() {
        FieldDefinition gatedAmpere = ampere();
        gatedAmpere.setValidation(gatedBand());
        when(fieldDefinitionsService.flattenedByClass(List.of(3L)))
                .thenReturn(Map.of(3L, List.of(gatedAmpere, status(), note())));

        var row = service.editorRows(asset).stream()
                .filter(r -> "ampere".equals(r.fieldKey())).findFirst().orElseThrow();

        // Pre-filled from the CLASS's gate, because the form is the complete statement of the
        // override: an empty box here would mean "cleared" on the next save, and the only
        // symptom would be false alarms on this one machine.
        assertThat(row.gated()).isTrue();
        assertThat(row.gateField()).isEqualTo("status");
        assertThat(row.gateOp()).isEqualTo(FieldGateOperators.IN);
        assertThat(row.gateOperand()).isEqualTo("روشن");
        assertThat(row.gatable()).isTrue();
    }

    @Test
    @DisplayName("a row the screen rendered with an empty gate posts an explicit clear")
    void theFormDistinguishesClearedFromNotRendered() {
        AssetFieldOverrideForm form = new AssetFieldOverrideForm();
        form.setFieldSource(FieldSource.RANGES_ONLY.name());

        AssetFieldOverrideForm.Row cleared = new AssetFieldOverrideForm.Row();
        cleared.setFieldKey("ampere");
        cleared.setGateRow(true);
        cleared.setConditionField("");

        AssetFieldOverrideForm.Row untouched = new AssetFieldOverrideForm.Row();
        untouched.setFieldKey("note");

        form.setRows(List.of(cleared, untouched));
        Map<String, Object> overrides = form.toOverrides();

        Map<?, ?> clearedField = (Map<?, ?>) overrides.get("ampere");
        assertThat(clearedField.containsKey(FieldValidationSupport.KEY_CONDITIONS))
                .as("rendered and empty means the administrator cleared it")
                .isTrue();
        assertThat(clearedField.get(FieldValidationSupport.KEY_CONDITIONS))
                .as("and «cleared» is an explicit null, never an absent key")
                .isNull();
        assertThat(overrides).doesNotContainKey("note");
    }

    @Test
    @DisplayName("the form posts the gate in the raw shape the service validates, parsing nothing itself")
    void theFormPostsTheRawShape() {
        AssetFieldOverrideForm form = new AssetFieldOverrideForm();
        form.setFieldSource(FieldSource.RANGES_ONLY.name());
        AssetFieldOverrideForm.Row row = new AssetFieldOverrideForm.Row();
        row.setFieldKey("ampere");
        row.setGateRow(true);
        row.setConditionField("status");
        row.setConditionOp(FieldGateOperators.IN);
        row.setConditionOperand("روشن\nراه‌اندازی");
        form.setRows(List.of(row));

        Map<?, ?> field = (Map<?, ?>) form.toOverrides().get("ampere");
        List<?> conditions = (List<?>) field.get(FieldValidationSupport.KEY_CONDITIONS);
        Map<?, ?> when = (Map<?, ?>) ((Map<?, ?>) conditions.get(0)).get(FieldValidationSupport.KEY_WHEN);

        assertThat(when.get(FieldValidationSupport.KEY_GATE_FIELD)).isEqualTo("status");
        assertThat(when.get(FieldValidationSupport.KEY_GATE_VALUES))
                .isEqualTo(List.of("روشن", "راه‌اندازی"));
    }

    @Test
    @DisplayName("a numeric gate's operand stays a string here — the service owns the parsing")
    void theFormDoesNotParseNumbers() {
        AssetFieldOverrideForm form = new AssetFieldOverrideForm();
        form.setFieldSource(FieldSource.RANGES_ONLY.name());
        AssetFieldOverrideForm.Row row = new AssetFieldOverrideForm.Row();
        row.setFieldKey("status");
        row.setGateRow(true);
        row.setConditionField("ampere");
        row.setConditionOp(FieldGateOperators.GTE);
        row.setConditionOperand(" 4.5 ");
        form.setRows(List.of(row));

        Map<?, ?> field = (Map<?, ?>) form.toOverrides().get("status");
        List<?> conditions = (List<?>) field.get(FieldValidationSupport.KEY_CONDITIONS);
        Map<?, ?> when = (Map<?, ?>) ((Map<?, ?>) conditions.get(0)).get(FieldValidationSupport.KEY_WHEN);
        assertThat(when.get(FieldValidationSupport.KEY_GATE_VALUE)).isEqualTo("4.5");

        // ...and the service turns it into a number, so the stored shape is uniform.
        AssetEntry saved = service.save(7L, FieldSource.RANGES_ONLY, form.toOverrides());
        Map<?, ?> savedWhen = (Map<?, ?>) ((Map<?, ?>) ((List<?>) ((Map<?, ?>) saved.getFieldOverrides()
                .get("status")).get(FieldValidationSupport.KEY_CONDITIONS)).get(0))
                .get(FieldValidationSupport.KEY_WHEN);
        assertThat(savedWhen.get(FieldValidationSupport.KEY_GATE_VALUE)).isEqualTo(4.5);
    }

    // =======================================================================================
    // The freeze
    // =======================================================================================

    @Test
    @DisplayName("the frozen delta carries the gate, so the tablet and the alarm see the same rule")
    void theSnapshotCarriesTheGate() {
        List<FieldDefinition> classFields = List.of(ampere(), status(), note());
        Map<String, Object> overrides = Map.of("ampere", Map.of(
                FieldValidationSupport.KEY_DANGER, rangeOf(NO_LOAD, null),
                FieldValidationSupport.KEY_CONDITIONS, List.of(conditionOn("status", "روشن"))));

        Map<String, Object> snapshot = AssetFieldResolver.snapshotEntry(
                classFields, FieldSource.RANGES_ONLY, overrides, 3L);

        // The snapshot is what the device is handed and what severity is re-judged from. A gate
        // missing here is a feature that works in the panel and nowhere else.
        List<FieldDefinition> replayed = AssetFieldResolver.applyFrozenDelta(classFields, snapshot);
        Map<String, Object> validation = byKey(replayed, "ampere").getValidation();

        assertThat(FieldValidationSupport.conditions(validation)).hasSize(1);
        assertThat(FieldValidationSupport.evaluateValue("number", 0, validation, Map.of("status", "خاموش")))
                .isEqualTo(FieldValidationSeverity.OK);
        assertThat(FieldValidationSupport.evaluateValue("number", 0, validation, Map.of("status", "روشن")))
                .isEqualTo(FieldValidationSeverity.DANGER);
    }

    @Test
    @DisplayName("an asset that re-bands one field does not lose the class's gate on it")
    void aBandOverrideKeepsTheClassGate() {
        FieldDefinition gatedAmpere = ampere();
        gatedAmpere.setValidation(gatedBand());
        List<FieldDefinition> classFields = List.of(gatedAmpere, status(), note());

        // Only the band is re-tuned, the gate is not mentioned at all.
        Map<String, Object> overrides = Map.of("ampere",
                Map.of(FieldValidationSupport.KEY_DANGER, rangeOf(6.0, null)));

        List<FieldDefinition> resolved = AssetFieldResolver.applyAssetOverrides(
                classFields, FieldSource.RANGES_ONLY, overrides, 3L);
        Map<String, Object> validation = byKey(resolved, "ampere").getValidation();

        assertThat(FieldValidationSupport.conditions(validation))
                .as("withBandsOnly merges onto the class's validation; unnamed keys survive")
                .hasSize(1);
        assertThat(FieldValidationSupport.dangerRange(validation).min()).isEqualTo(6.0);
        assertThat(FieldValidationSupport.evaluateValue("number", 5, validation, Map.of("status", "روشن")))
                .as("5 A is below this machine's own 6 A threshold, and it is running")
                .isEqualTo(FieldValidationSeverity.DANGER);
    }

    // =======================================================================================
    // The write path
    // =======================================================================================

    @Test
    @DisplayName("the stored verdict on an entry is the gated one, per asset band and all")
    void theWritePathStampsTheGatedVerdict() {
        FieldDefinition gatedAmpere = ampere();
        gatedAmpere.setValidation(gatedBand());
        List<FieldDefinition> defs = List.of(gatedAmpere, status(), note());
        Map<String, Object> frozenDelta = AssetFieldResolver.snapshotEntry(
                defs, FieldSource.RANGES_ONLY,
                Map.of("ampere", Map.of(FieldValidationSupport.KEY_DANGER, rangeOf(6.0, null))), 3L);

        LogSheetEntry running = entry(Map.of("status", "روشن", "ampere", 5));
        EntrySeverityEvaluator.apply(running, defs, frozenDelta);
        assertThat(running.getMaxSeverity()).isEqualTo(FieldValidationSeverity.DANGER.name());
        assertThat(running.getBreachedFields()).containsExactly("ampere");

        LogSheetEntry stopped = entry(Map.of("status", "خاموش", "ampere", 0));
        EntrySeverityEvaluator.apply(stopped, defs, frozenDelta);
        assertThat(stopped.getMaxSeverity())
                .as("evaluated and clean, not «never evaluated» — the reading is stored and reported")
                .isEqualTo(FieldValidationSeverity.OK.name());
        assertThat(stopped.getBreachedFields()).isNull();
    }

    @Test
    @DisplayName("«ON and the current not recorded» escapes the band — that is what `required` is for")
    void anUnansweredGatedFieldIsNotABreach() {
        FieldDefinition gatedAmpere = ampere();
        gatedAmpere.setValidation(gatedBand());
        List<FieldDefinition> defs = List.of(gatedAmpere, status(), note());

        LogSheetEntry e = entry(Map.of("status", "روشن"));
        EntrySeverityEvaluator.apply(e, defs, null);

        // Documented, not accidental: a band cannot be breached by an absent reading, and the
        // alarm history counts it as «not read», which neither counts nor resets.
        assertThat(e.getMaxSeverity()).isEqualTo(FieldValidationSeverity.OK.name());
    }

    // -------------------------------------------------------------------------------------
    // helpers
    // -------------------------------------------------------------------------------------

    private FieldValidationSeverity severityOfResolved(AssetEntry saved, Object value,
                                                       Map<String, Object> formData) {
        List<FieldDefinition> resolved = AssetFieldResolver.applyAssetOverrides(
                List.of(ampere(), status(), note()), FieldSource.RANGES_ONLY,
                saved.getFieldOverrides(), 3L);
        return FieldValidationSupport.evaluateValue(
                "number", value, byKey(resolved, "ampere").getValidation(), formData);
    }

    private static FieldDefinition byKey(List<FieldDefinition> defs, String key) {
        return defs.stream().filter(fd -> key.equalsIgnoreCase(fd.getKey())).findFirst().orElseThrow();
    }

    /** «The current band applies only while the status says running», as the class stores it. */
    private static Map<String, Object> gatedBand() {
        return FieldValidationSupport.build(
                "number", null, null, null, NO_LOAD, null, null, null, null, true,
                new FieldValidationSupport.GateSpec("status", FieldGateOperators.IN, "روشن"));
    }

    private static Map<String, Object> conditionOn(String field, String value) {
        Map<String, Object> when = new LinkedHashMap<>();
        when.put(FieldValidationSupport.KEY_GATE_FIELD, field);
        when.put(FieldValidationSupport.KEY_GATE_OP, FieldGateOperators.IN);
        when.put(FieldValidationSupport.KEY_GATE_VALUES, List.of(value));
        Map<String, Object> condition = new LinkedHashMap<>();
        condition.put(FieldValidationSupport.KEY_WHEN, when);
        return condition;
    }

    private static Map<String, Object> rangeOf(Double min, Double max) {
        Map<String, Object> range = new LinkedHashMap<>();
        if (min != null) {
            range.put(FieldValidationSupport.KEY_MIN, min);
        }
        if (max != null) {
            range.put(FieldValidationSupport.KEY_MAX, max);
        }
        return range;
    }

    private static LogSheetEntry entry(Map<String, Object> formData) {
        LogSheetEntry e = new LogSheetEntry();
        e.setClassId(3L);
        e.setFormData(new LinkedHashMap<>(formData));
        return e;
    }

    private static FieldDefinition ampere() {
        return field("ampere", "number", 1);
    }

    private static FieldDefinition status() {
        FieldDefinition fd = field("status", "select", 2);
        fd.setValidation(FieldValidationSupport.build(
                "select", "روشن,خاموش", null, null, null, null, null, null));
        return fd;
    }

    private static FieldDefinition note() {
        return field("note", "text", 3);
    }

    private static FieldDefinition field(String key, String dataType, int order) {
        FieldDefinition fd = new FieldDefinition();
        fd.setClassId(3L);
        fd.setKey(key);
        fd.setLabel(key);
        fd.setDataType(dataType);
        fd.setOrder(order);
        return fd;
    }
}
