package com.hnp.backendofflinefirst.domain;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FieldValidationSupportTest {

    @Test
    void buildStoresWarningAndDangerRanges() {
        Map<String, Object> validation = FieldValidationSupport.build(
                "number", null, 20.0, 80.0, 10.0, 90.0, null, null);

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
                "number", null, 20.0, 80.0, 10.0, 90.0, null, null);

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
                "number", null, 20.0, 80.0, 10.0, 90.0, null, null);

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
        assertThatThrownBy(() -> FieldValidationSupport.build("number", null, 80.0, 20.0, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Warning range minimum");
    }

    @Test
    void buildRejectsDangerMinGreaterThanMax() {
        assertThatThrownBy(() -> FieldValidationSupport.build("number", null, null, null, 90.0, 10.0, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Danger range minimum");
    }

    @Test
    void buildAllowsMinEqualToMax() {
        Map<String, Object> validation = FieldValidationSupport.build(
                "number", null, 50.0, 50.0, 50.0, 50.0, null, null);

        assertThat(FieldValidationSupport.warningRange(validation).min()).isEqualTo(50.0);
        assertThat(FieldValidationSupport.warningRange(validation).max()).isEqualTo(50.0);
    }

    @Test
    void buildAllowsOneSidedRanges() {
        Map<String, Object> validation = FieldValidationSupport.build(
                "number", null, 20.0, null, null, 90.0, null, null);

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
                "text", null, 80.0, 20.0, null, null, null, null);

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
                "number", null, 20.0, 80.0, 10.0, 90.0, null, null);

        assertThat(FieldValidationSupport.summaryFa(validation)).isEqualTo("هشدار: 20–80 · خطر: 10–90");
    }

    @Test
    void summaryFallsBackWhenNoRangeIsConfigured() {
        assertThat(FieldValidationSupport.summaryFa(null)).isEqualTo("—");
        assertThat(FieldValidationSupport.summaryFa(Map.of())).isEqualTo("—");
    }

    // =======================================================================================
    // Option severity — which CHOICES are the alert
    //
    // The polarity is the opposite of a numeric band: `warning` is a range a reading must stay
    // inside, `warningOptions` are the options that ARE the warning. Every case below asserts
    // that direction, because reading it the other way round inverts every alert on a plant.
    // =======================================================================================

    private static Map<String, Object> vocabulary(String warn, String danger) {
        return FieldValidationSupport.build("select", "OF,ON,Idle", null, null, null, null,
                warn, danger);
    }

    @Test
    void buildStoresWhichOptionsAreAWarningOrADanger() {
        Map<String, Object> v = vocabulary("Idle", "OF");

        assertThat(v).containsEntry(FieldValidationSupport.KEY_OPTIONS, List.of("OF", "ON", "Idle"));
        assertThat(v).containsEntry(FieldValidationSupport.KEY_WARNING_OPTIONS, List.of("Idle"));
        assertThat(v).containsEntry(FieldValidationSupport.KEY_DANGER_OPTIONS, List.of("OF"));
    }

    @Test
    void buildDropsASeverityForAnOptionThatDoesNotExist() {
        // The admin deleted "Tripped" from the vocabulary but its severity was still posted.
        // Keeping it would store a rule nothing can ever match.
        Map<String, Object> v = vocabulary("Tripped", "OF");

        assertThat(v).containsEntry(FieldValidationSupport.KEY_DANGER_OPTIONS, List.of("OF"));
        assertThat(v).doesNotContainKey(FieldValidationSupport.KEY_WARNING_OPTIONS);
    }

    @Test
    void buildLetsDangerWinAnOptionClaimedByBoth() {
        // Unreachable through the form's radios; an API caller or a hand edit can still ask for
        // it, and resolving it deterministically beats storing a contradiction.
        Map<String, Object> v = vocabulary("OF", "OF");

        assertThat(v).containsEntry(FieldValidationSupport.KEY_DANGER_OPTIONS, List.of("OF"));
        assertThat(v).doesNotContainKey(FieldValidationSupport.KEY_WARNING_OPTIONS);
    }

    @Test
    void buildIgnoresOptionSeveritiesOnANumberField() {
        Map<String, Object> v = FieldValidationSupport.build(
                "number", null, 0.0, 10.0, null, null, "OF", "ON");

        assertThat(v).doesNotContainKeys(
                FieldValidationSupport.KEY_WARNING_OPTIONS, FieldValidationSupport.KEY_DANGER_OPTIONS);
    }

    @Test
    void aChosenOptionCarriesItsOwnSeverity() {
        Map<String, Object> v = vocabulary("Idle", "OF");

        assertThat(FieldValidationSupport.evaluateValue("select", "OF", v))
                .isEqualTo(FieldValidationSeverity.DANGER);
        assertThat(FieldValidationSupport.evaluateValue("select", "Idle", v))
                .isEqualTo(FieldValidationSeverity.WARNING);
        assertThat(FieldValidationSupport.evaluateValue("select", "ON", v))
                .isEqualTo(FieldValidationSeverity.OK);
    }

    @Test
    void anUnknownOrBlankChoiceIsNotAnAlert() {
        Map<String, Object> v = vocabulary("Idle", "OF");

        assertThat(FieldValidationSupport.evaluateValue("select", "Maintenance", v))
                .isEqualTo(FieldValidationSeverity.OK);
        assertThat(FieldValidationSupport.evaluateValue("select", null, v))
                .isEqualTo(FieldValidationSeverity.OK);
    }

    @Test
    void aMultiselectTakesTheWorstOfWhatWasChosen() {
        Map<String, Object> v = FieldValidationSupport.build(
                "multiselect", "OF,ON,Idle", null, null, null, null, "Idle", "OF");

        assertThat(FieldValidationSupport.evaluateValue("multiselect", List.of("ON", "Idle"), v))
                .isEqualTo(FieldValidationSeverity.WARNING);
        // One dangerous choice among safe ones is still a danger.
        assertThat(FieldValidationSupport.evaluateValue("multiselect", List.of("ON", "Idle", "OF"), v))
                .isEqualTo(FieldValidationSeverity.DANGER);
        assertThat(FieldValidationSupport.evaluateValue("multiselect", List.of("ON"), v))
                .isEqualTo(FieldValidationSeverity.OK);
        assertThat(FieldValidationSupport.evaluateValue("multiselect", List.of(), v))
                .isEqualTo(FieldValidationSeverity.OK);
    }

    @Test
    void optionMatchingIsTrimmedButCaseSensitive() {
        Map<String, Object> v = vocabulary("Idle", "OF");

        assertThat(FieldValidationSupport.evaluateValue("select", "  OF  ", v))
                .as("a reading arrives with whatever whitespace the control gave it")
                .isEqualTo(FieldValidationSeverity.DANGER);
        assertThat(FieldValidationSupport.evaluateValue("select", "of", v))
                .as("the submit-time allow-list is case-sensitive, so this must be too")
                .isEqualTo(FieldValidationSeverity.OK);
    }

    @Test
    void aFieldWithNoSeveritiesIsNeverAnAlert() {
        Map<String, Object> plain = FieldValidationSupport.build(
                "select", "OF,ON", null, null, null, null, null, null);

        assertThat(FieldValidationSupport.evaluateValue("select", "OF", plain))
                .as("adding a vocabulary must not raise an alert on its own")
                .isEqualTo(FieldValidationSeverity.OK);
    }

    @Test
    void atypeWithNoNotionOfSeverityIsAlwaysOk() {
        Map<String, Object> v = vocabulary("Idle", "OF");

        // The same stored map on a text field: a note is not in or out of range.
        assertThat(FieldValidationSupport.evaluateValue("text", "OF", v))
                .isEqualTo(FieldValidationSeverity.OK);
        assertThat(FieldValidationSupport.evaluateValue("image", "OF", v))
                .isEqualTo(FieldValidationSeverity.OK);
    }

    @Test
    void theSummaryNamesTheAlertingOptions() {
        String summary = FieldValidationSupport.summaryFa(vocabulary("Idle", "OF"));

        assertThat(summary).contains("Idle").contains("OF");
    }

}
