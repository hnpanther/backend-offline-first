package com.hnp.backendofflinefirst.domain;

import com.hnp.backendofflinefirst.entity.FieldDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FormDataValidationSupportTest {

    private FieldDefinition numberField(String key, boolean required, Map<String, Object> validation) {
        FieldDefinition fd = new FieldDefinition();
        fd.setKey(key);
        fd.setDataType("number");
        fd.setRequired(required);
        fd.setValidation(validation);
        return fd;
    }

    private FieldDefinition selectField(String key, boolean required, List<String> options) {
        FieldDefinition fd = new FieldDefinition();
        fd.setKey(key);
        fd.setDataType("select");
        fd.setRequired(required);
        fd.setValidation(Map.of(FieldValidationSupport.KEY_OPTIONS, options));
        return fd;
    }

    @Test
    void rejectsMissingRequiredField() {
        FieldDefinition temp = numberField("temp", true, null);

        var issues = FormDataValidationSupport.validate(Map.of(), List.of(temp));

        assertThat(issues).hasSize(1);
        assertThat(issues.get(0).fieldKey()).isEqualTo("temp");
        assertThat(issues.get(0).message()).contains("required");
    }

    @Test
    void allowsOptionalBlankField() {
        FieldDefinition temp = numberField("temp", false, null);

        assertThat(FormDataValidationSupport.validate(Map.of(), List.of(temp))).isEmpty();
    }

    @Test
    void rejectsNonNumericValue() {
        Map<String, Object> validation = FieldValidationSupport.build("number", null, 10.0, 90.0, 0.0, 100.0);
        FieldDefinition temp = numberField("temp", true, validation);

        var issues = FormDataValidationSupport.validate(Map.of("temp", "abc"), List.of(temp));

        assertThat(issues).extracting(FormDataValidationSupport.ValidationIssue::message)
                .anyMatch(m -> m.contains("number"));
    }

    @Test
    void rejectsDangerRangeButAllowsWarningRange() {
        Map<String, Object> validation = FieldValidationSupport.build("number", null, 20.0, 80.0, 10.0, 90.0);
        FieldDefinition temp = numberField("temp", false, validation);

        assertThat(FormDataValidationSupport.validate(Map.of("temp", 85), List.of(temp))).isEmpty();
        assertThat(FormDataValidationSupport.validate(Map.of("temp", 95), List.of(temp)))
                .extracting(FormDataValidationSupport.ValidationIssue::message)
                .anyMatch(m -> m.contains("danger"));
    }

    @Test
    void rejectsInvalidSelectOption() {
        FieldDefinition status = selectField("status", true, List.of("OK", "FAIL"));

        assertThat(FormDataValidationSupport.validate(Map.of("status", "MAYBE"), List.of(status)))
                .extracting(FormDataValidationSupport.ValidationIssue::message)
                .anyMatch(m -> m.contains("invalid option"));
    }

    @Test
    void acceptsValidSelectOption() {
        FieldDefinition status = selectField("status", true, List.of("OK", "FAIL"));

        assertThat(FormDataValidationSupport.validate(Map.of("status", "OK"), List.of(status))).isEmpty();
    }

    @Test
    void formatIssuesIncludesAssetIdAndFieldKey() {
        var issues = List.of(new FormDataValidationSupport.ValidationIssue("temp", "required field is missing"));

        String message = FormDataValidationSupport.formatIssues(50L, issues);

        assertThat(message).contains("assetId=50").contains("temp").contains("required");
    }
}
