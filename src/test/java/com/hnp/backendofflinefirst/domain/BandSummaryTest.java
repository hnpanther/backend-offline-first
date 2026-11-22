package com.hnp.backendofflinefirst.domain;

import com.hnp.backendofflinefirst.entity.FieldDefinition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BandSummaryTest {

    @Nested
    @DisplayName("no mark")
    class NoMark {

        @Test
        @DisplayName("a field with no thresholds yields nothing — no empty tooltip")
        void noBandMeansNoMark() {
            FieldDefinition fd = numberField("temp", "دما", "°C", null);
            assertThat(BandSummary.of(fd, Map.of("temp", 12)).isEmpty()).isTrue();
            assertThat(BandSummary.of(fd, Map.of("temp", 12)).popoverText()).isEmpty();
        }

        @Test
        @DisplayName("an entry limit alone is not a band, so there is still no mark")
        void entryLimitAloneIsNotAMark() {
            Map<String, Object> validation = new LinkedHashMap<>();
            validation.put(FieldValidationSupport.KEY_ENTRY_RANGE, Map.of("min", 0, "max", 100));
            validation.put(FieldValidationSupport.KEY_INTEGER_ONLY, true);
            FieldDefinition fd = numberField("amps", "جریان", "A", validation);

            assertThat(BandSummary.of(fd, Map.of("amps", 12)).isEmpty()).isTrue();
        }

        @Test
        @DisplayName("a null field is nothing")
        void nullFieldIsEmpty() {
            assertThat(BandSummary.of(null, Map.of()).isEmpty()).isTrue();
        }

        @Test
        @DisplayName("a gated field whose every condition carries no thresholds is nothing")
        void gateWithoutThresholdsIsEmpty() {
            Map<String, Object> validation = new LinkedHashMap<>();
            validation.put(FieldValidationSupport.KEY_CONDITIONS, List.of(
                    Map.of("when", Map.of("field", "status", "op", "in", "values", List.of("روشن")))));
            FieldDefinition fd = numberField("amps", "جریان", "A", validation);

            assertThat(BandSummary.of(fd, Map.of("status", "روشن", "amps", 0)).isEmpty()).isTrue();
        }
    }

    @Nested
    @DisplayName("numeric ranges")
    class NumericRanges {

        @Test
        @DisplayName("both warning and danger, with the unit")
        void bothRangesWithUnit() {
            FieldDefinition fd = numberField("temp", "دما", "°C",
                    FieldValidationSupport.build("number", null, 20.0, 70.0, 10.0, 85.0, null, null));

            BandSummary summary = BandSummary.of(fd, Map.of("temp", 55));

            assertThat(summary.isEmpty()).isFalse();
            assertThat(summary.popoverText())
                    .contains("محدوده‌ی مجاز (هشدار): 20 تا 70")
                    .contains("محدوده‌ی خطر: 10 تا 85")
                    .contains("واحد: °C");
        }

        @Test
        @DisplayName("one-sided limits word as حداقل / حداکثر")
        void oneSidedLimits() {
            Map<String, Object> validation = new LinkedHashMap<>();
            validation.put(FieldValidationSupport.KEY_WARNING, Map.of("min", 20));
            validation.put(FieldValidationSupport.KEY_DANGER, Map.of("max", 90));
            FieldDefinition fd = numberField("temp", "دما", null, validation);

            String text = BandSummary.of(fd, Map.of("temp", 50)).popoverText();

            assertThat(text)
                    .contains("محدوده‌ی مجاز (هشدار): حداقل 20")
                    .contains("محدوده‌ی خطر: حداکثر 90")
                    .doesNotContain(" تا ");
        }

        @Test
        @DisplayName("legacy flat min/max is read as the warning band — same as the PWA")
        void legacyFlatMinMax() {
            Map<String, Object> validation = new LinkedHashMap<>();
            validation.put(FieldValidationSupport.KEY_MIN, 5);
            validation.put(FieldValidationSupport.KEY_MAX, 15);
            FieldDefinition fd = numberField("level", "سطح", null, validation);

            assertThat(BandSummary.of(fd, Map.of("level", 10)).popoverText())
                    .isEqualTo("محدوده‌ی مجاز (هشدار): 5 تا 15");
        }

        @Test
        @DisplayName("warning alone, no danger")
        void warningOnly() {
            Map<String, Object> validation = new LinkedHashMap<>();
            validation.put(FieldValidationSupport.KEY_WARNING, Map.of("min", 0, "max", 100));
            FieldDefinition fd = numberField("x", "X", null, validation);

            assertThat(BandSummary.of(fd, Map.of("x", 1)).popoverText())
                    .isEqualTo("محدوده‌ی مجاز (هشدار): 0 تا 100")
                    .doesNotContain("خطر");
        }
    }

    @Nested
    @DisplayName("choice options")
    class ChoiceOptions {

        @Test
        @DisplayName("warning and danger options as labels, not codes of another vocabulary")
        void optionSeverities() {
            FieldDefinition fd = new FieldDefinition();
            fd.setKey("state");
            fd.setLabel("وضعیت");
            fd.setDataType("select");
            fd.setValidation(FieldValidationSupport.build(
                    "select", "ON,Idle,OF", null, null, null, null, "Idle", "OF"));

            assertThat(BandSummary.of(fd, Map.of("state", "ON")).popoverText())
                    .contains("گزینه‌های هشدار: Idle")
                    .contains("گزینه‌های خطر: OF");
        }
    }

    @Nested
    @DisplayName("state gates (§36)")
    class Gates {

        @Test
        @DisplayName("one line per state, and which state applied to this reading")
        void appliedStateIsMarked() {
            FieldDefinition status = new FieldDefinition();
            status.setKey("status");
            status.setLabel("وضعیت");
            status.setDataType("select");

            Map<String, Object> validation = FieldValidationSupport.build(
                    "number", null, 4.5, 34.8, 2.0, 40.0, null, null,
                    null, true,
                    new FieldValidationSupport.GateSpec("status", FieldGateOperators.IN, "روشن"));
            FieldDefinition amps = numberField("amps", "جریان", "A", validation);

            BandSummary on = BandSummary.of(amps, Map.of("status", "روشن", "amps", 0), List.of(status, amps));
            assertThat(on.popoverText())
                    .contains("وقتی وضعیت = روشن:")
                    .contains("اعمال‌شده برای این قرائت")
                    .contains("محدوده‌ی مجاز (هشدار)");

            BandSummary off = BandSummary.of(amps, Map.of("status", "خاموش", "amps", 0), List.of(status, amps));
            assertThat(off.popoverText())
                    .contains("وقتی وضعیت = روشن:")
                    .contains("هیچ شرطی برای این قرائت برقرار نبود؛ آستانه اعمال نشد.")
                    .doesNotContain("اعمال‌شده برای این قرائت");
        }

        @Test
        @DisplayName("a condition with its own thresholds replaces the field's for that state")
        void conditionDeclaresOwnThresholds() {
            Map<String, Object> condition = new LinkedHashMap<>();
            condition.put("when", Map.of("field", "status", "op", "in", "values", List.of("روشن")));
            condition.put("danger", Map.of("min", 4.5));

            Map<String, Object> validation = new LinkedHashMap<>();
            validation.put(FieldValidationSupport.KEY_WARNING, Map.of("min", 0, "max", 100));
            validation.put(FieldValidationSupport.KEY_CONDITIONS, List.of(condition));
            FieldDefinition fd = numberField("amps", "جریان", null, validation);

            // Applied: only the condition's danger, not the field's warning.
            String applied = BandSummary.of(fd, Map.of("status", "روشن", "amps", 1)).popoverText();
            assertThat(applied)
                    .contains("محدوده‌ی خطر: حداقل 4.5")
                    .doesNotContain("محدوده‌ی مجاز (هشدار): 0 تا 100");
        }

        @Test
        @DisplayName("without sibling defs the gate still names the field by its key")
        void gateFallsBackToKey() {
            Map<String, Object> validation = FieldValidationSupport.build(
                    "number", null, 1.0, 10.0, null, null, null, null,
                    null, true,
                    new FieldValidationSupport.GateSpec("status", FieldGateOperators.IN, "ON"));
            FieldDefinition fd = numberField("amps", "جریان", null, validation);

            assertThat(BandSummary.of(fd, Map.of("status", "ON")).popoverText())
                    .contains("وقتی status = ON:");
        }
    }

    @Nested
    @DisplayName("entry limit beside a band")
    class EntryLimit {

        @Test
        @DisplayName("entryRange and integerOnly ride on a separate line marked as an input limit")
        void entryLimitIsSeparateLine() {
            Map<String, Object> validation = FieldValidationSupport.build(
                    "number", null, 20.0, 70.0, 10.0, 85.0, null, null);
            // Rebuild with an entry limit — build() may not expose it on every overload; put it on.
            validation = new LinkedHashMap<>(validation);
            validation.put(FieldValidationSupport.KEY_ENTRY_RANGE, Map.of("min", 0, "max", 100));
            validation.put(FieldValidationSupport.KEY_INTEGER_ONLY, true);
            FieldDefinition fd = numberField("temp", "دما", "°C", validation);

            List<String> lines = BandSummary.of(fd, Map.of("temp", 50)).lines();

            assertThat(lines).hasSize(2);
            assertThat(lines.getFirst()).contains("محدوده‌ی مجاز (هشدار)");
            assertThat(lines.get(1))
                    .startsWith("محدودیت ورود (نه آستانه):")
                    .contains("فقط عدد صحیح");
        }
    }

    @Nested
    @DisplayName("frozen / override / pre-V5 shape")
    class FrozenAndLegacySheets {

        @Test
        @DisplayName("the mark reads whatever validation the FieldDefinition already carries — including an override delta already applied")
        void overrideAlreadyOnTheDefinition() {
            // groupByAsset applies the frozen delta before the page sees the FieldDefinition.
            // BandSummary must not re-resolve today's class — it trusts the definition it is given.
            Map<String, Object> assetWarning = Map.of(
                    FieldValidationSupport.KEY_WARNING, Map.of("min", 1, "max", 2));
            FieldDefinition overridden = numberField("amps", "جریان", "A", assetWarning);

            assertThat(BandSummary.of(overridden, Map.of("amps", 1.5)).popoverText())
                    .isEqualTo("محدوده‌ی مجاز (هشدار): 1 تا 2 · واحد: A");
        }

        @Test
        @DisplayName("a pre-V5 sheet (no field_overrides_snapshot) still shows the class bands on the definition the page resolved")
        void preV5ShowsWhateverThePageResolved() {
            // Without a freeze, groupByAsset falls back to the class snapshot / live definitions.
            // BandSummary does not know that — and must not: inventing "today vs then" here would
            // re-judge. The page hands it the definition it resolved; we just render it.
            FieldDefinition classBand = numberField("temp", "دما", "°C",
                    FieldValidationSupport.build("number", null, 0.0, 100.0, null, null, null, null));

            assertThat(BandSummary.of(classBand, Map.of("temp", 40)).popoverText())
                    .contains("0 تا 100");
        }
    }

    private static FieldDefinition numberField(String key, String label, String unit,
                                               Map<String, Object> validation) {
        FieldDefinition fd = new FieldDefinition();
        fd.setKey(key);
        fd.setLabel(label);
        fd.setDataType("number");
        fd.setUnit(unit);
        fd.setValidation(validation);
        return fd;
    }
}
