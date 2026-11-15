package com.hnp.backendofflinefirst.service;

import com.hnp.backendofflinefirst.domain.AssetFieldResolver;
import com.hnp.backendofflinefirst.domain.FieldGateOperators;
import com.hnp.backendofflinefirst.domain.FieldSource;
import com.hnp.backendofflinefirst.domain.FieldValidationSeverity;
import com.hnp.backendofflinefirst.domain.FieldValidationSupport;
import com.hnp.backendofflinefirst.domain.FieldValidationSupport.EntryLimit;
import com.hnp.backendofflinefirst.domain.FieldValidationSupport.FieldBehaviour;
import com.hnp.backendofflinefirst.domain.FormDataValidationSupport;
import com.hnp.backendofflinefirst.dto.AssetFieldOverrideForm;
import com.hnp.backendofflinefirst.entity.AssetEntry;
import com.hnp.backendofflinefirst.entity.FieldDefinition;
import com.hnp.backendofflinefirst.repository.AssetEntryRepository;
import com.hnp.backendofflinefirst.ui.ErrorTranslator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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

/**
 * A number field's entry limit — {@code validation.entryRange} and {@code validation.integerOnly},
 * roadmap §40 — from the editor that writes it to the validator that refuses with it.
 *
 * <p>Three properties carry the feature, and each nested class below pins one:
 * <ol>
 *   <li><b>It is a rebuild-safe part of the field</b>: {@code build} writes it, refuses the three
 *       shapes that would make a field nobody can fill, and writes nothing for a field without
 *       one — so every field in service today is byte-for-byte what it was.</li>
 *   <li><b>It refuses and never judges</b>: outside the limit is a validation issue worded with
 *       the limit; inside it, severity is exactly what it was. A field without a limit refuses
 *       nothing it did not refuse before.</li>
 *   <li><b>The asset's layer reaches the round</b> — including an asset's removal of a class
 *       value, which the frozen delta used to drop.</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class EntryLimitTest {

    private static Map<String, Object> lamps(Double min, Double max, boolean whole) {
        return FieldValidationSupport.build("number", null, null, 6.0, null, null, null, null,
                null, true, null, new FieldBehaviour(false, null, new EntryLimit(min, max, whole)));
    }

    // =========================================================================================
    // 1. Writing it
    // =========================================================================================

    @Nested
    @DisplayName("build — what the editors store")
    class Build {

        @Test
        @DisplayName("stores the range and integerOnly beside the bands")
        void storesTheLimit() {
            Map<String, Object> v = lamps(0.0, 32.0, true);

            assertThat(v.get(FieldValidationSupport.KEY_ENTRY_RANGE)).isEqualTo(Map.of("min", 0.0, "max", 32.0));
            assertThat(v.get(FieldValidationSupport.KEY_INTEGER_ONLY)).isEqualTo(true);
            assertThat(v.get(FieldValidationSupport.KEY_WARNING)).isEqualTo(Map.of("max", 6.0));
        }

        @Test
        @DisplayName("writes nothing for a field without a limit — every field in service is unchanged")
        void writesNothingWithoutALimit() {
            Map<String, Object> v = lamps(null, null, false);

            assertThat(v).doesNotContainKeys(FieldValidationSupport.KEY_ENTRY_RANGE,
                    FieldValidationSupport.KEY_INTEGER_ONLY);
            assertThat(v).isEqualTo(FieldValidationSupport.build(
                    "number", null, null, 6.0, null, null, null, null));
        }

        @Test
        @DisplayName("ignores the limit for any type but number — a hidden control still posts")
        void ignoredForOtherTypes() {
            Map<String, Object> v = FieldValidationSupport.build("text", null, null, null, null, null,
                    null, null, null, true, null, new FieldBehaviour(false, null, new EntryLimit(0.0, 8.0, true)));
            assertThat(v).isNull();
        }

        @Test
        @DisplayName("keeps one bound alone")
        void oneBound() {
            assertThat(lamps(null, 8.0, false).get(FieldValidationSupport.KEY_ENTRY_RANGE))
                    .isEqualTo(Map.of("max", 8.0));
        }

        @Test
        @DisplayName("refuses an inverted range, a non-finite bound and a fractional bound on a whole field")
        void refusesWhatNobodyCouldFill() {
            assertThatThrownBy(() -> lamps(10.0, 2.0, false))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageStartingWith("Entry range minimum (");
            assertThatThrownBy(() -> lamps(Double.NEGATIVE_INFINITY, 2.0, false))
                    .hasMessage("The entry range must be a finite number.");
            assertThatThrownBy(() -> lamps(0.0, Double.NaN, false))
                    .hasMessage("The entry range must be a finite number.");
            assertThatThrownBy(() -> lamps(2.2, 2.8, true))
                    .hasMessageStartingWith("The entry range of a whole-number field must itself be whole numbers");
            // A fractional bound is fine when fractions are fine.
            assertThat(lamps(0.5, 2.5, false).get(FieldValidationSupport.KEY_ENTRY_RANGE))
                    .isEqualTo(Map.of("min", 0.5, "max", 2.5));
        }

        @Test
        @DisplayName("the checkbox counts only with its own marker (AGENTS.md #171)")
        void markerDecides() {
            assertThat(EntryLimit.fromForm(null, null, "on", true).integerOnly()).isTrue();
            assertThat(EntryLimit.fromForm(null, null, "on", null).integerOnly()).isFalse();
            assertThat(EntryLimit.fromForm(null, null, null, true).integerOnly())
                    .as("a request that never showed the control narrows nothing").isFalse();
        }
    }

    // =========================================================================================
    // 2. Refusing with it, and judging nothing
    // =========================================================================================

    @Nested
    @DisplayName("validation — refused, worded with the limit")
    class Refusing {

        private FieldDefinition field(Map<String, Object> validation) {
            FieldDefinition fd = new FieldDefinition();
            fd.setKey("lamps");
            fd.setLabel("Lamps");
            fd.setDataType("number");
            fd.setValidation(validation);
            return fd;
        }

        private List<String> issues(Object value, Map<String, Object> validation) {
            return FormDataValidationSupport.validate(Map.of("lamps", value), List.of(field(validation))).stream()
                    .map(FormDataValidationSupport.ValidationIssue::message)
                    .toList();
        }

        @Test
        @DisplayName("inside the closed interval, the bounds included, is accepted")
        void insideIsAccepted() {
            Map<String, Object> v = lamps(0.0, 32.0, true);
            assertThat(issues(0, v)).isEmpty();
            assertThat(issues("32", v)).isEmpty();
            assertThat(issues(17.0, v)).isEmpty();
            assertThat(issues("5.0", v)).as("5.0 is a whole number").isEmpty();
        }

        @Test
        @DisplayName("outside is refused, naming the limit")
        void outsideIsRefused() {
            assertThat(issues(33, lamps(0.0, 32.0, true))).containsExactly("must be between 0 and 32");
            assertThat(issues("-1", lamps(0.0, null, false))).containsExactly("must be at least 0");
            assertThat(issues(9, lamps(null, 8.0, false))).containsExactly("must be at most 8");
            assertThat(issues(0.25, lamps(0.5, 2.5, false))).containsExactly("must be between 0.5 and 2.5");
        }

        @Test
        @DisplayName("a fraction on a whole-number field is refused before the range is even looked at")
        void wholeOnly() {
            assertThat(issues("2.5", lamps(0.0, 32.0, true))).containsExactly("must be a whole number");
            assertThat(issues(2.5, lamps(null, null, true))).containsExactly("must be a whole number");
        }

        @Test
        @DisplayName("a limited field refuses Infinity; an unlimited one keeps today's behaviour exactly")
        void nonFinite() {
            assertThat(issues("Infinity", lamps(0.0, 8.0, false))).containsExactly("must be a number");
            Map<String, Object> plain = FieldValidationSupport.build("number", null, null, 6.0, null, null, null, null);
            assertThat(issues("Infinity", plain)).isEmpty();
            assertThat(issues(1e12, plain)).isEmpty();
            assertThat(issues(-3.75, null)).isEmpty();
            assertThat(issues("abc", plain)).containsExactly("must be a number");
        }

        @Test
        @DisplayName("the partial validation a progress push and a web draft use refuses it too")
        void partialToo() {
            assertThat(FormDataValidationSupport.validatePartialEntry(
                    Map.of("lamps", 40), List.of(field(lamps(0.0, 32.0, true)))))
                    .extracting(FormDataValidationSupport.ValidationIssue::message)
                    .containsExactly("must be between 0 and 32");
        }

        @Test
        @DisplayName("a malformed or inverted stored limit is no limit — never a field nobody can fill")
        void readDefensively() {
            Map<String, Object> inverted = new LinkedHashMap<>();
            inverted.put(FieldValidationSupport.KEY_ENTRY_RANGE, Map.of("min", 10, "max", 2));
            assertThat(issues(50, inverted)).isEmpty();

            Map<String, Object> garbage = new LinkedHashMap<>();
            garbage.put(FieldValidationSupport.KEY_ENTRY_RANGE, "0-8");
            garbage.put(FieldValidationSupport.KEY_INTEGER_ONLY, "true");
            assertThat(issues(2.5, garbage)).isEmpty();
            assertThat(FieldValidationSupport.hasEntryLimit(garbage)).isFalse();
        }

        @Test
        @DisplayName("the limit never changes a verdict: severity is the bands', exactly as before")
        void severityUntouched() {
            Map<String, Object> v = lamps(0.0, 8.0, true);
            Map<String, Object> plain = FieldValidationSupport.build("number", null, null, 6.0, null, null, null, null);
            for (double value : new double[]{0, 6, 7, 8, 40, -5}) {
                assertThat(FieldValidationSupport.evaluateValue("number", value, v))
                        .isEqualTo(FieldValidationSupport.evaluateValue("number", value, plain));
            }
            assertThat(FieldValidationSupport.summaryFa(v))
                    .as("the exception report's 'allowed range' stays the thresholds alone")
                    .isEqualTo(FieldValidationSupport.summaryFa(plain));
        }

        @Test
        @DisplayName("bounds in a message are plain digits — the translator finds them by pattern")
        void noExponent() {
            assertThat(FieldValidationSupport.entryLimitViolation(2e10, lamps(null, 1e10, false)))
                    .isEqualTo("must be at most 10000000000");
            assertThat(FieldValidationSupport.entryLimitSummaryFa(lamps(0.0, 32.0, true)))
                    .isEqualTo("0–32، فقط عدد صحیح");
        }

        @Test
        @DisplayName("the operator reads it in Persian, on the tablet, the panel and a round-level question")
        void persianWording() {
            String perAsset = "Form data validation failed (asset 'Panel 1' / P-1): field 'Lamps': must be between 0 and 32";
            assertThat(ErrorTranslator.toFa(perAsset))
                    .isEqualTo("داده‌های فرم معتبر نیست — دارایی «Panel 1» (کد P-1): فیلد «Lamps» باید بین 0 و 32 باشد");
            assertThat(ErrorTranslator.toFa(
                    "Form data validation failed (asset 'Panel 1' / P-1): field 'Lamps': must be a whole number; "
                            + "field 'Volts': must be at least -2.5"))
                    .contains("«Lamps» باید عدد صحیح باشد")
                    .contains("«Volts» باید حداقل -2.5 باشد");
            assertThat(ErrorTranslator.toFa("Sheet form validation failed: field 'Count': must be at most 8"))
                    .contains("پرسش «Count» باید حداکثر 8 باشد");
            assertThat(ErrorTranslator.toFa("Entry range minimum (9.0) must not be greater than maximum (2.0)."))
                    .isEqualTo("حداقل حد ورود نمی‌تواند بیشتر از حداکثر آن باشد.");
        }
    }

    // =========================================================================================
    // 3. The asset's layer, and the frozen delta
    // =========================================================================================

    @Nested
    @DisplayName("per asset — the 32-lamp board in an 8-lamp class")
    class PerAsset {

        @Mock AssetEntryRepository assetEntryRepository;
        @Mock LogSheetFieldDefinitionsService fieldDefinitionsService;
        @InjectMocks AssetFieldOverrideService service;

        private FieldDefinition classLamps;

        @BeforeEach
        void setUp() {
            classLamps = classField("lamps", "number", lamps(0.0, 8.0, true));
            AssetEntry asset = new AssetEntry();
            asset.setId(7L);
            asset.setClassId(3L);
            lenient().when(assetEntryRepository.findById(7L)).thenReturn(Optional.of(asset));
            lenient().when(assetEntryRepository.save(any(AssetEntry.class))).thenAnswer(i -> i.getArgument(0));
            lenient().when(fieldDefinitionsService.flattenedByClass(List.of(3L)))
                    .thenReturn(Map.of(3L, List.of(classLamps, classField("note", "text", null))));
        }

        @Test
        @DisplayName("RANGES_ONLY may set its own range; the class's integerOnly stays")
        void rangesOnlySetsItsOwnRange() {
            AssetEntry saved = service.save(7L, FieldSource.RANGES_ONLY,
                    Map.of("lamps", Map.of(FieldValidationSupport.KEY_ENTRY_RANGE, Map.of("min", "0", "max", "32"))));

            Map<String, Object> resolved = resolve(saved).getValidation();
            assertThat(FieldValidationSupport.entryRange(resolved).max()).isEqualTo(32.0);
            assertThat(FieldValidationSupport.integerOnly(resolved)).isTrue();
            assertThat(FieldValidationSupport.entryLimitViolation(20, resolved)).isNull();
            assertThat(FieldValidationSupport.entryLimitViolation(2.5, resolved)).isEqualTo("must be a whole number");
        }

        @Test
        @DisplayName("an explicit null removes the class's range from this asset")
        void nullRemoves() {
            Map<String, Object> layer = new LinkedHashMap<>();
            layer.put(FieldValidationSupport.KEY_ENTRY_RANGE, null);
            AssetEntry saved = service.save(7L, FieldSource.RANGES_ONLY, Map.of("lamps", layer));

            assertThat(FieldValidationSupport.entryRange(resolve(saved).getValidation()).isEmpty()).isTrue();
        }

        @Test
        @DisplayName("refuses an inverted range, a fraction on a whole field, and a range on text")
        void refusals() {
            assertThatThrownBy(() -> service.save(7L, FieldSource.RANGES_ONLY,
                    Map.of("lamps", Map.of(FieldValidationSupport.KEY_ENTRY_RANGE, Map.of("min", 9, "max", 2)))))
                    .hasMessageStartingWith("The entryRange minimum of 'lamps'");
            assertThatThrownBy(() -> service.save(7L, FieldSource.RANGES_ONLY,
                    Map.of("lamps", Map.of(FieldValidationSupport.KEY_ENTRY_RANGE, Map.of("max", 7.5)))))
                    .hasMessageStartingWith("The entry range of 'lamps' must be whole numbers");
            assertThatThrownBy(() -> service.save(7L, FieldSource.RANGES_ONLY,
                    Map.of("note", Map.of(FieldValidationSupport.KEY_ENTRY_RANGE, Map.of("max", 7)))))
                    .hasMessageContaining("an entry range applies to numbers only");
            assertThatThrownBy(() -> service.save(7L, FieldSource.RANGES_ONLY,
                    Map.of("lamps", Map.of(FieldValidationSupport.KEY_INTEGER_ONLY, true))))
                    .as("integerOnly is the class's, never an asset's")
                    .hasMessageContaining("Unsupported override key");
        }

        @Test
        @DisplayName("the screen's three states — not shown, emptied, a range — post as nothing, null, a range")
        void formTriState() {
            AssetFieldOverrideForm form = new AssetFieldOverrideForm();
            form.setFieldSource("RANGES_ONLY");
            AssetFieldOverrideForm.Row emptied = row("lamps", "", "");
            AssetFieldOverrideForm.Row typed = row("volts", "0", "32");
            AssetFieldOverrideForm.Row absent = row("note", null, null);
            form.setRows(List.of(emptied, typed, absent));

            Map<String, Object> out = form.toOverrides();
            Map<?, ?> lampsOut = (Map<?, ?>) out.get("lamps");
            assertThat(lampsOut.containsKey(FieldValidationSupport.KEY_ENTRY_RANGE)).isTrue();
            assertThat(lampsOut.get(FieldValidationSupport.KEY_ENTRY_RANGE)).isNull();
            assertThat(((Map<?, ?>) out.get("volts")).get(FieldValidationSupport.KEY_ENTRY_RANGE))
                    .isEqualTo(Map.of("min", "0", "max", "32"));
            assertThat(out).doesNotContainKey("note");
        }

        private AssetFieldOverrideForm.Row row(String key, String min, String max) {
            AssetFieldOverrideForm.Row row = new AssetFieldOverrideForm.Row();
            row.setFieldKey(key);
            row.setEntryMin(min);
            row.setEntryMax(max);
            return row;
        }

        private FieldDefinition resolve(AssetEntry saved) {
            return AssetFieldResolver.applyAssetOverrides(List.of(classLamps), FieldSource.RANGES_ONLY,
                    saved.getFieldOverrides(), 3L).get(0);
        }
    }

    @Nested
    @DisplayName("the frozen delta — what the round, the tablet and the alarms are handed")
    class FrozenDelta {

        @Test
        @DisplayName("carries the asset's own range, and replays to exactly the live resolution")
        void carriesTheRange() {
            FieldDefinition lamps = classField("lamps", "number", lamps(0.0, 8.0, true));
            Map<String, Object> overrides = Map.of("lamps",
                    Map.of(FieldValidationSupport.KEY_ENTRY_RANGE, Map.of("min", 0.0, "max", 32.0)));

            Map<String, Object> snapshot = AssetFieldResolver.snapshotEntry(
                    List.of(lamps), FieldSource.RANGES_ONLY, overrides, 3L);
            List<FieldDefinition> live = AssetFieldResolver.applyAssetOverrides(
                    List.of(lamps), FieldSource.RANGES_ONLY, overrides, 3L);

            assertThat(AssetFieldResolver.applyFrozenDelta(List.of(lamps), snapshot).get(0).getValidation())
                    .isEqualTo(live.get(0).getValidation());
        }

        @Test
        @DisplayName("an asset's removal of a class band, gate or range survives the freeze as an explicit null")
        void removalsSurviveTheFreeze() {
            // The bug this pins: the delta copied only the keys the resolved field HAD, so a key
            // the asset had cleared was simply absent — and replaying a delta that does not mention
            // a key keeps the class's. The panel showed «no band», every round used the class's.
            Map<String, Object> classValidation = new LinkedHashMap<>(lamps(0.0, 8.0, true));
            classValidation.put(FieldValidationSupport.KEY_DANGER, Map.of("max", 7.0));
            classValidation.put(FieldValidationSupport.KEY_CONDITIONS, List.of(gate()));
            FieldDefinition lamps = classField("lamps", "number", classValidation);

            Map<String, Object> layer = new LinkedHashMap<>();
            layer.put(FieldValidationSupport.KEY_WARNING, null);
            layer.put(FieldValidationSupport.KEY_CONDITIONS, null);
            layer.put(FieldValidationSupport.KEY_ENTRY_RANGE, null);
            Map<String, Object> overrides = Map.of("lamps", layer);

            Map<String, Object> snapshot = AssetFieldResolver.snapshotEntry(
                    List.of(lamps), FieldSource.RANGES_ONLY, overrides, 3L);
            Map<String, Object> replayed =
                    AssetFieldResolver.applyFrozenDelta(List.of(lamps), snapshot).get(0).getValidation();
            Map<String, Object> live = AssetFieldResolver.applyAssetOverrides(
                    List.of(lamps), FieldSource.RANGES_ONLY, overrides, 3L).get(0).getValidation();

            assertThat(replayed).isEqualTo(live);
            assertThat(replayed).doesNotContainKeys(FieldValidationSupport.KEY_WARNING,
                    FieldValidationSupport.KEY_CONDITIONS, FieldValidationSupport.KEY_ENTRY_RANGE);
            assertThat(replayed).containsKey(FieldValidationSupport.KEY_DANGER);
            // 6.5 is above the class's warning maximum of 6, which this asset removed: OK, not WARNING.
            assertThat(FieldValidationSupport.evaluateValue("number", 6.5, replayed, Map.of()))
                    .isEqualTo(FieldValidationSeverity.OK);
        }

        @Test
        @DisplayName("a FULL asset's added parameter keeps its gate and its range on the server, as on the tablet")
        void addedParameterKeepsItsGate() {
            // The third bug of the same family: the editor let a gate be set on an added
            // parameter, the tablet applied it, and the server's resolver dropped it.
            FieldDefinition status = classField("status", "select",
                    FieldValidationSupport.build("select", "روشن,خاموش", null, null, null, null, null, null));
            Map<String, Object> added = new LinkedHashMap<>();
            added.put("label", "Extra");
            added.put("dataType", "number");
            added.put(FieldValidationSupport.KEY_DANGER, Map.of("min", 4.5));
            added.put(FieldValidationSupport.KEY_CONDITIONS, List.of(gate()));
            added.put(FieldValidationSupport.KEY_ENTRY_RANGE, Map.of("min", 0.0, "max", 50.0));

            FieldDefinition extra = AssetFieldResolver.applyAssetOverrides(List.of(status), FieldSource.FULL,
                    Map.of("extra", added), 3L).stream()
                    .filter(fd -> "extra".equals(fd.getKey())).findFirst().orElseThrow();

            assertThat(FieldValidationSupport.conditions(extra.getValidation())).hasSize(1);
            assertThat(FieldValidationSupport.entryRange(extra.getValidation()).max()).isEqualTo(50.0);
            assertThat(FieldValidationSupport.evaluateValue("number", 0, extra.getValidation(), Map.of("status", "خاموش")))
                    .as("stopped: suppressed by the gate, as the tablet says")
                    .isEqualTo(FieldValidationSeverity.OK);
            assertThat(FieldValidationSupport.evaluateValue("number", 0, extra.getValidation(), Map.of("status", "روشن")))
                    .isEqualTo(FieldValidationSeverity.DANGER);
        }

        private Map<String, Object> gate() {
            Map<String, Object> when = new LinkedHashMap<>();
            when.put(FieldValidationSupport.KEY_GATE_FIELD, "status");
            when.put(FieldValidationSupport.KEY_GATE_OP, FieldGateOperators.IN);
            when.put(FieldValidationSupport.KEY_GATE_VALUES, List.of("روشن"));
            return Map.of(FieldValidationSupport.KEY_WHEN, when);
        }
    }

    private static FieldDefinition classField(String key, String dataType, Map<String, Object> validation) {
        FieldDefinition fd = new FieldDefinition();
        fd.setClassId(3L);
        fd.setKey(key);
        fd.setLabel(key);
        fd.setDataType(dataType);
        fd.setOrder(1);
        fd.setValidation(validation);
        return fd;
    }
}
