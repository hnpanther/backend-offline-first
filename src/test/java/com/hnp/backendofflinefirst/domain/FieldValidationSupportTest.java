package com.hnp.backendofflinefirst.domain;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FieldValidationSupportTest {

    @Test
    void buildStoresWarningAndDangerRanges() {
        Map<String, Object> validation = FieldValidationSupport.build(
                "number", null, 20.0, 80.0, 10.0, 90.0);

        assertThat(FieldValidationSupport.warningRange(validation).min()).isEqualTo(20.0);
        assertThat(FieldValidationSupport.warningRange(validation).max()).isEqualTo(80.0);
        assertThat(FieldValidationSupport.dangerRange(validation).min()).isEqualTo(10.0);
        assertThat(FieldValidationSupport.dangerRange(validation).max()).isEqualTo(90.0);
    }

    @Test
    void legacyFlatMinMaxTreatedAsWarning() {
        Map<String, Object> legacy = Map.of("min", 0, "max", 100);

        assertThat(FieldValidationSupport.warningRange(legacy).min()).isEqualTo(0.0);
        assertThat(FieldValidationSupport.warningRange(legacy).max()).isEqualTo(100.0);
        assertThat(FieldValidationSupport.dangerRange(legacy).isEmpty()).isTrue();
    }

    @Test
    void evaluateReturnsDangerBeforeWarning() {
        Map<String, Object> validation = FieldValidationSupport.build(
                "number", null, 20.0, 80.0, 10.0, 90.0);

        assertThat(FieldValidationSupport.evaluateNumeric(50, validation))
                .isEqualTo(FieldValidationSeverity.OK);
        assertThat(FieldValidationSupport.evaluateNumeric(85, validation))
                .isEqualTo(FieldValidationSeverity.WARNING);
        assertThat(FieldValidationSupport.evaluateNumeric(95, validation))
                .isEqualTo(FieldValidationSeverity.DANGER);
    }

    @Test
    void evaluateNumericValueParsesStringValues() {
        Map<String, Object> validation = FieldValidationSupport.build(
                "number", null, 20.0, 80.0, 10.0, 90.0);

        assertThat(FieldValidationSupport.evaluateNumericValue("95", validation))
                .isEqualTo(FieldValidationSeverity.DANGER);
    }

    @Test
    void messageFaReturnsPersianText() {
        assertThat(FieldValidationSupport.messageFa(FieldValidationSeverity.WARNING))
                .isEqualTo("خارج از بازه هشدار است.");
        assertThat(FieldValidationSupport.messageFa(FieldValidationSeverity.DANGER))
                .isEqualTo("خارج از بازه خطر است.");
    }

    @Test
    void buildRejectsWarningMinGreaterThanMax() {
        assertThatThrownBy(() -> FieldValidationSupport.build("number", null, 80.0, 20.0, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Warning range minimum");
    }

    @Test
    void buildRejectsDangerMinGreaterThanMax() {
        assertThatThrownBy(() -> FieldValidationSupport.build("number", null, null, null, 90.0, 10.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Danger range minimum");
    }

    @Test
    void buildAllowsMinEqualToMax() {
        Map<String, Object> validation = FieldValidationSupport.build(
                "number", null, 50.0, 50.0, 50.0, 50.0);

        assertThat(FieldValidationSupport.warningRange(validation).min()).isEqualTo(50.0);
        assertThat(FieldValidationSupport.warningRange(validation).max()).isEqualTo(50.0);
    }

    @Test
    void buildAllowsOneSidedRanges() {
        Map<String, Object> validation = FieldValidationSupport.build(
                "number", null, 20.0, null, null, 90.0);

        assertThat(FieldValidationSupport.warningRange(validation).min()).isEqualTo(20.0);
        assertThat(FieldValidationSupport.warningRange(validation).max()).isNull();
        assertThat(FieldValidationSupport.dangerRange(validation).min()).isNull();
        assertThat(FieldValidationSupport.dangerRange(validation).max()).isEqualTo(90.0);
    }

    @Test
    void buildIgnoresBackwardsRangeForNonNumberDataType() {
        // Ranges are only meaningful (and only stored) for "number" fields — a backwards
        // min/max submitted alongside another data type must not block saving that field.
        Map<String, Object> validation = FieldValidationSupport.build(
                "text", null, 80.0, 20.0, null, null);

        assertThat(validation).isNull();
    }

    // ---- display formatting (web fill page range chips + summary line) ----------------------

    @Test
    void formatRangeRendersBothBoundsAndOpenEndedRanges() {
        assertThat(FieldValidationSupport.formatRange(new FieldValidationSupport.NumericRange(20.0, 80.0))).isEqualTo("20–80");
        assertThat(FieldValidationSupport.formatRange(new FieldValidationSupport.NumericRange(20.0, null))).isEqualTo("≥ 20");
        assertThat(FieldValidationSupport.formatRange(new FieldValidationSupport.NumericRange(null, 80.0))).isEqualTo("≤ 80");
    }

    /** Whole numbers must not render as "20.0" — the chips sit next to the operator's own input. */
    @Test
    void formatRangeDropsTheTrailingZeroOnWholeNumbers() {
        assertThat(FieldValidationSupport.formatRange(new FieldValidationSupport.NumericRange(20.0, 80.0))).doesNotContain(".0");
        assertThat(FieldValidationSupport.formatRange(new FieldValidationSupport.NumericRange(-2.5, 3.25))).isEqualTo("-2.5–3.25");
    }

    @Test
    void formatRangeReturnsNullWhenThereIsNoBound() {
        assertThat(FieldValidationSupport.formatRange(new FieldValidationSupport.NumericRange(null, null))).isNull();
        assertThat(FieldValidationSupport.formatRange(null)).isNull();
    }

    @Test
    void summaryJoinsWarningAndDangerWithoutTrailingZeros() {
        Map<String, Object> validation = FieldValidationSupport.build(
                "number", null, 20.0, 80.0, 10.0, 90.0);

        assertThat(FieldValidationSupport.summaryFa(validation)).isEqualTo("هشدار: 20–80 · خطر: 10–90");
    }

    @Test
    void summaryFallsBackWhenNoRangeIsConfigured() {
        assertThat(FieldValidationSupport.summaryFa(null)).isEqualTo("—");
        assertThat(FieldValidationSupport.summaryFa(Map.of())).isEqualTo("—");
    }
}
