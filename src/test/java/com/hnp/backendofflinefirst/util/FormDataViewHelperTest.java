package com.hnp.backendofflinefirst.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hnp.backendofflinefirst.domain.FieldValidationSupport;
import com.hnp.backendofflinefirst.entity.FieldDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FormDataViewHelperTest {

    FormDataViewHelper helper;

    @BeforeEach
    void setUp() {
        helper = new FormDataViewHelper(new ObjectMapper());
    }

    @Test
    void rowsIncludeValidationMessageForOutOfRangeNumber() {
        FieldDefinition fd = new FieldDefinition();
        fd.setKey("temp");
        fd.setLabel("دما");
        fd.setDataType("number");
        fd.setUnit("°C");
        fd.setValidation(FieldValidationSupport.build("number", null, 20.0, 80.0, 10.0, 90.0));

        Map<String, Object> formData = Map.of("temp", 95);
        FormDataViewHelper.FormFieldRow row = helper.rows(formData, List.of(fd)).getFirst();

        assertThat(row.value()).isEqualTo("95");
        assertThat(row.validationMessage()).isEqualTo("خارج از بازه خطر است.");
        assertThat(row.validationAlertClass()).isEqualTo("text-danger");
    }

    @Test
    void rowsOmitValidationWhenValueIsInRange() {
        FieldDefinition fd = new FieldDefinition();
        fd.setKey("temp");
        fd.setLabel("دما");
        fd.setDataType("number");
        fd.setValidation(FieldValidationSupport.build("number", null, 20.0, 80.0, 10.0, 90.0));

        FormDataViewHelper.FormFieldRow row = helper.rows(Map.of("temp", 50), List.of(fd)).getFirst();

        assertThat(row.validationMessage()).isNull();
    }
}
