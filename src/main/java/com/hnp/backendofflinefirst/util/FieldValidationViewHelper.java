package com.hnp.backendofflinefirst.util;

import com.hnp.backendofflinefirst.domain.FieldValidationSupport;
import org.springframework.stereotype.Component;

import java.util.Map;

/** Thymeleaf helper for field-definition validation display and edit forms. */
@Component("fieldValidation")
public class FieldValidationViewHelper {

    public Double warningMin(Map<String, Object> validation) {
        return FieldValidationSupport.rangeMin(validation, FieldValidationSupport.KEY_WARNING);
    }

    public Double warningMax(Map<String, Object> validation) {
        return FieldValidationSupport.rangeMax(validation, FieldValidationSupport.KEY_WARNING);
    }

    public Double dangerMin(Map<String, Object> validation) {
        return FieldValidationSupport.rangeMin(validation, FieldValidationSupport.KEY_DANGER);
    }

    public Double dangerMax(Map<String, Object> validation) {
        return FieldValidationSupport.rangeMax(validation, FieldValidationSupport.KEY_DANGER);
    }

    public String summary(Map<String, Object> validation) {
        return FieldValidationSupport.summaryFa(validation);
    }
}
