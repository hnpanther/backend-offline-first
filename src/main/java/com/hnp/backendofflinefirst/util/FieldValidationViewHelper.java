package com.hnp.backendofflinefirst.util;

import com.hnp.backendofflinefirst.domain.FieldValidationSupport;
import org.springframework.stereotype.Component;

import java.util.List;
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

    /**
     * Bounds of the warning range for display, or null when none is configured.
     * Used by the web fill page to show operators the acceptable band <em>before</em> they type —
     * matching what the PWA already renders under each numeric field.
     */
    public String warningText(Map<String, Object> validation) {
        return FieldValidationSupport.formatRange(FieldValidationSupport.warningRange(validation));
    }

    /** Bounds of the danger range for display, or null when none is configured. */
    public String dangerText(Map<String, Object> validation) {
        return FieldValidationSupport.formatRange(FieldValidationSupport.dangerRange(validation));
    }

    // ===========================================================================================
    // Option severities — which CHOICES are the alert
    // ===========================================================================================

    /** The field's vocabulary, in the order it was declared; empty when it has none. */
    public List<String> options(Map<String, Object> validation) {
        return FieldValidationSupport.optionList(validation, FieldValidationSupport.KEY_OPTIONS);
    }

    /**
     * The severity one option carries: {@code "danger"}, {@code "warning"} or {@code "ok"}.
     *
     * <p>A single string rather than two booleans so the edit form can render one radio group
     * per option with the right one already checked. That matters more than it looks: the
     * severity table has to arrive in its final shape at the <strong>first paint</strong>
     * (AGENTS.md #126) — a table assembled by script at {@code DOMContentLoaded} shows an
     * administrator every option as "normal" for a frame, which is the one reading that is
     * never true of a field somebody bothered to configure.
     */
    public String optionSeverity(Map<String, Object> validation, String option) {
        if (option == null) {
            return "ok";
        }
        String trimmed = option.trim();
        if (FieldValidationSupport.optionList(validation, FieldValidationSupport.KEY_DANGER_OPTIONS)
                .contains(trimmed)) {
            return "danger";
        }
        if (FieldValidationSupport.optionList(validation, FieldValidationSupport.KEY_WARNING_OPTIONS)
                .contains(trimmed)) {
            return "warning";
        }
        return "ok";
    }

    /** Which options are a warning when chosen. */
    public List<String> warningOptions(Map<String, Object> validation) {
        return FieldValidationSupport.optionList(
                validation, FieldValidationSupport.KEY_WARNING_OPTIONS);
    }

    /** Which options are a danger when chosen. */
    public List<String> dangerOptions(Map<String, Object> validation) {
        return FieldValidationSupport.optionList(
                validation, FieldValidationSupport.KEY_DANGER_OPTIONS);
    }

    /** The vocabulary as the textarea shows it — one option per line. */
    public String optionsText(Map<String, Object> validation) {
        return String.join(System.lineSeparator(), options(validation));
    }
}
