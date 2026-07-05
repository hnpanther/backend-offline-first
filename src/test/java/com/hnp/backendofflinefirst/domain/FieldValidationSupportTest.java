package com.hnp.backendofflinefirst.domain;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

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
}
