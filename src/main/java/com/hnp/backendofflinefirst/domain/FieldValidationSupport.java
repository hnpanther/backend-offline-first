package com.hnp.backendofflinefirst.domain;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Field-definition validation rules stored in {@code field_definitions.validation} (JSONB).
 * <p>
 * Numeric fields may define two independent ranges:
 * <ul>
 *   <li>{@code warning} — soft limit; value outside → {@link FieldValidationSeverity#WARNING}</li>
 *   <li>{@code danger} — hard limit; value outside → {@link FieldValidationSeverity#DANGER}</li>
 * </ul>
 * Legacy {@code {"min": n, "max": m}} is treated as {@code warning}.
 */
public final class FieldValidationSupport {

    public static final String KEY_WARNING = "warning";
    public static final String KEY_DANGER = "danger";
    public static final String KEY_OPTIONS = "options";
    public static final String KEY_MIN = "min";
    public static final String KEY_MAX = "max";

    public record NumericRange(Double min, Double max) {
        public boolean isEmpty() {
            return min == null && max == null;
        }

        public boolean contains(double value) {
            if (min != null && value < min) return false;
            if (max != null && value > max) return false;
            return true;
        }
    }

    private FieldValidationSupport() {}

    public static Map<String, Object> build(String dataType,
                                              String selectOptions,
                                              Double warningMin,
                                              Double warningMax,
                                              Double dangerMin,
                                              Double dangerMax) {
        Map<String, Object> validation = new LinkedHashMap<>();
        if (isSelectType(dataType) && selectOptions != null && !selectOptions.isBlank()) {
            List<String> options = Arrays.stream(selectOptions.split("[,\n،]"))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .toList();
            if (!options.isEmpty()) {
                validation.put(KEY_OPTIONS, options);
            }
        }
        if ("number".equals(dataType)) {
            putRange(validation, KEY_WARNING, warningMin, warningMax);
            putRange(validation, KEY_DANGER, dangerMin, dangerMax);
        }
        return validation.isEmpty() ? null : validation;
    }

    public static NumericRange warningRange(Map<String, Object> validation) {
        return range(validation, KEY_WARNING);
    }

    public static NumericRange dangerRange(Map<String, Object> validation) {
        return range(validation, KEY_DANGER);
    }

    public static String messageFa(FieldValidationSeverity severity) {
        return switch (severity) {
            case WARNING -> "خارج از بازه هشدار است.";
            case DANGER -> "خارج از بازه خطر است.";
            case OK -> null;
        };
    }

    public static String alertClass(FieldValidationSeverity severity) {
        return switch (severity) {
            case WARNING -> "text-warning";
            case DANGER -> "text-danger";
            case OK -> null;
        };
    }

    public static FieldValidationSeverity evaluateNumericValue(Object value, Map<String, Object> validation) {
        Double numeric = toDouble(value);
        if (numeric == null) {
            return FieldValidationSeverity.OK;
        }
        return evaluateNumeric(numeric, validation);
    }

    public static FieldValidationSeverity evaluateNumeric(double value, Map<String, Object> validation) {
        NumericRange danger = dangerRange(validation);
        if (!danger.isEmpty() && !danger.contains(value)) {
            return FieldValidationSeverity.DANGER;
        }
        NumericRange warning = warningRange(validation);
        if (!warning.isEmpty() && !warning.contains(value)) {
            return FieldValidationSeverity.WARNING;
        }
        return FieldValidationSeverity.OK;
    }

    public static Double rangeMin(Map<String, Object> validation, String rangeKey) {
        NumericRange range = nestedRange(validation, rangeKey);
        return range != null ? range.min() : null;
    }

    public static Double rangeMax(Map<String, Object> validation, String rangeKey) {
        NumericRange range = nestedRange(validation, rangeKey);
        return range != null ? range.max() : null;
    }

    public static String summaryFa(Map<String, Object> validation) {
        if (validation == null || validation.isEmpty()) {
            return "—";
        }
        StringBuilder sb = new StringBuilder();
        appendRangeSummary(sb, "هشدار", warningRange(validation));
        appendRangeSummary(sb, "خطر", dangerRange(validation));
        return sb.isEmpty() ? "—" : sb.toString();
    }

    private static void appendRangeSummary(StringBuilder sb, String label, NumericRange range) {
        if (range == null || range.isEmpty()) {
            return;
        }
        if (!sb.isEmpty()) {
            sb.append(" · ");
        }
        sb.append(label).append(": ");
        if (range.min() != null && range.max() != null) {
            sb.append(range.min()).append("–").append(range.max());
        } else if (range.min() != null) {
            sb.append("≥ ").append(range.min());
        } else {
            sb.append("≤ ").append(range.max());
        }
    }

    private static NumericRange range(Map<String, Object> validation, String rangeKey) {
        NumericRange nested = nestedRange(validation, rangeKey);
        if (nested != null && !nested.isEmpty()) {
            return nested;
        }
        // Legacy flat min/max → warning
        if (KEY_WARNING.equals(rangeKey) && validation != null
                && (validation.containsKey(KEY_MIN) || validation.containsKey(KEY_MAX))) {
            return new NumericRange(toDouble(validation.get(KEY_MIN)), toDouble(validation.get(KEY_MAX)));
        }
        return new NumericRange(null, null);
    }

    @SuppressWarnings("unchecked")
    private static NumericRange nestedRange(Map<String, Object> validation, String rangeKey) {
        if (validation == null || !validation.containsKey(rangeKey)) {
            return null;
        }
        Object raw = validation.get(rangeKey);
        if (!(raw instanceof Map<?, ?> map)) {
            return null;
        }
        return new NumericRange(toDouble(map.get(KEY_MIN)), toDouble(map.get(KEY_MAX)));
    }

    private static void putRange(Map<String, Object> validation, String key,
                                 Double min, Double max) {
        if (min == null && max == null) {
            return;
        }
        Map<String, Object> range = new LinkedHashMap<>();
        if (min != null) {
            range.put(KEY_MIN, min);
        }
        if (max != null) {
            range.put(KEY_MAX, max);
        }
        validation.put(key, range);
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

    private static boolean isSelectType(String dataType) {
        return "select".equals(dataType) || "multiselect".equals(dataType);
    }
}
