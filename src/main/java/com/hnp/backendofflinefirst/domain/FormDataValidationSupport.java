package com.hnp.backendofflinefirst.domain;

import com.hnp.backendofflinefirst.entity.FieldDefinition;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Server-side validation of submitted {@code formData} against frozen field definitions.
 * <p>
 * Warning ranges are soft (display only). Danger ranges, required fields, type checks,
 * and select-option membership block submission.
 */
public final class FormDataValidationSupport {

    public record ValidationIssue(String fieldKey, String message) {}

    private FormDataValidationSupport() {}

    public static List<ValidationIssue> validate(Map<String, Object> formData, List<FieldDefinition> fieldDefs) {
        if (fieldDefs == null || fieldDefs.isEmpty()) {
            return List.of();
        }
        List<ValidationIssue> issues = new ArrayList<>();
        for (FieldDefinition field : fieldDefs) {
            if (field == null || field.getKey() == null || field.getKey().isBlank()) {
                continue;
            }
            Object value = formData != null ? formData.get(field.getKey()) : null;
            validateField(field, value, issues);
        }
        return issues;
    }

    public static String formatIssues(Long assetId, List<ValidationIssue> issues) {
        if (issues == null || issues.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder("Form data validation failed (assetId=");
        sb.append(assetId).append("): ");
        for (int i = 0; i < issues.size(); i++) {
            if (i > 0) {
                sb.append("; ");
            }
            ValidationIssue issue = issues.get(i);
            sb.append("field '").append(issue.fieldKey()).append("': ").append(issue.message());
        }
        return sb.toString();
    }

    private static void validateField(FieldDefinition field, Object value, List<ValidationIssue> issues) {
        String key = field.getKey();
        String dataType = field.getDataType() != null ? field.getDataType() : "text";

        if (field.isRequired() && isBlank(value, dataType)) {
            issues.add(new ValidationIssue(key, "required field is missing"));
            return;
        }
        if (isBlank(value, dataType)) {
            return;
        }

        switch (dataType) {
            case "number" -> validateNumber(key, value, field.getValidation(), issues);
            case "select" -> validateSelect(key, value, field.getValidation(), false, issues);
            case "multiselect" -> validateSelect(key, value, field.getValidation(), true, issues);
            case "checkbox" -> validateCheckbox(key, value, issues);
            default -> { /* text, textarea, date, etc. — required check only */ }
        }
    }

    private static void validateNumber(String key, Object value, Map<String, Object> validation,
                                       List<ValidationIssue> issues) {
        Double numeric = toDouble(value);
        if (numeric == null) {
            issues.add(new ValidationIssue(key, "must be a number"));
            return;
        }
        if (FieldValidationSupport.evaluateNumeric(numeric, validation) == FieldValidationSeverity.DANGER) {
            issues.add(new ValidationIssue(key, "is outside the danger range"));
        }
    }

    private static void validateSelect(String key, Object value, Map<String, Object> validation,
                                       boolean multi, List<ValidationIssue> issues) {
        List<String> options = selectOptions(validation);
        if (options.isEmpty()) {
            return;
        }
        Set<String> allowed = new LinkedHashSet<>(options);
        if (multi) {
            Collection<?> values = asCollection(value);
            if (values.isEmpty()) {
                return;
            }
            for (Object item : values) {
                if (item == null || !allowed.contains(String.valueOf(item).trim())) {
                    issues.add(new ValidationIssue(key, "has an invalid option"));
                    return;
                }
            }
            return;
        }
        if (!allowed.contains(String.valueOf(value).trim())) {
            issues.add(new ValidationIssue(key, "has an invalid option"));
        }
    }

    private static void validateCheckbox(String key, Object value, List<ValidationIssue> issues) {
        if (value instanceof Boolean) {
            return;
        }
        String text = String.valueOf(value).trim().toLowerCase();
        if ("true".equals(text) || "false".equals(text) || "1".equals(text) || "0".equals(text)) {
            return;
        }
        issues.add(new ValidationIssue(key, "must be a boolean value"));
    }

    @SuppressWarnings("unchecked")
    private static List<String> selectOptions(Map<String, Object> validation) {
        if (validation == null || !validation.containsKey(FieldValidationSupport.KEY_OPTIONS)) {
            return List.of();
        }
        Object raw = validation.get(FieldValidationSupport.KEY_OPTIONS);
        if (!(raw instanceof Collection<?> collection)) {
            return List.of();
        }
        List<String> options = new ArrayList<>();
        for (Object item : collection) {
            if (item != null) {
                String option = String.valueOf(item).trim();
                if (!option.isEmpty()) {
                    options.add(option);
                }
            }
        }
        return options;
    }

    private static Collection<?> asCollection(Object value) {
        if (value instanceof Collection<?> collection) {
            return collection;
        }
        if (value instanceof Object[] array) {
            return List.of(array);
        }
        if (value == null) {
            return List.of();
        }
        return List.of(value);
    }

    private static boolean isBlank(Object value, String dataType) {
        if (value == null) {
            return true;
        }
        if (value instanceof String s) {
            return s.isBlank();
        }
        if (value instanceof Collection<?> c) {
            return c.isEmpty();
        }
        if ("checkbox".equals(dataType)) {
            if (value instanceof Boolean b) {
                return !b;
            }
            String text = String.valueOf(value).trim().toLowerCase();
            return text.isEmpty() || "false".equals(text) || "0".equals(text);
        }
        return false;
    }

    private static Double toDouble(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
