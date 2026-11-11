package com.hnp.backendofflinefirst.util;

import com.hnp.backendofflinefirst.domain.FieldGateOperators;
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

    // ===========================================================================================
    // State condition — WHEN the thresholds above apply
    // ===========================================================================================

    /** Whether this field's thresholds are gated on another answer. */
    public boolean hasGate(Map<String, Object> validation) {
        return gate(validation) != null;
    }

    /**
     * The gate in words — «status یکی از: روشن» — or null.
     *
     * <p>Shown to the <b>operator</b> beside the band chips as well as to the administrator. A
     * band printed without its condition is read as the rule, so an operator standing at a
     * stopped pump would see «خطر: ≥ 4.5» against a reading of 0 and either re-read the meter or
     * type something that is not what the instrument says.
     */
    public String gateSummary(Map<String, Object> validation) {
        return FieldValidationSupport.gateSummaryFa(gate(validation));
    }

    /** The gated field's key, for the editor's dropdown. */
    public String gateField(Map<String, Object> validation) {
        Map<String, Object> gate = gate(validation);
        return gate == null ? null : asText(gate.get(FieldValidationSupport.KEY_GATE_FIELD));
    }

    /** The operator, for the editor's dropdown. */
    public String gateOp(Map<String, Object> validation) {
        Map<String, Object> gate = gate(validation);
        return gate == null ? null : asText(gate.get(FieldValidationSupport.KEY_GATE_OP));
    }

    /**
     * The operand as its one input shows it: options newline-joined, a number as typed, empty for
     * an operator that takes none.
     *
     * <p>One input for both shapes because the editor has one box. Newline-joined like every
     * other option list in this project, so a comma inside an option is impossible either way.
     */
    public String gateOperandText(Map<String, Object> validation) {
        Map<String, Object> gate = gate(validation);
        if (gate == null) {
            return "";
        }
        List<String> values = FieldValidationSupport.optionList(
                gate, FieldValidationSupport.KEY_GATE_VALUES);
        if (!values.isEmpty()) {
            return String.join(System.lineSeparator(), values);
        }
        String single = asText(gate.get(FieldValidationSupport.KEY_GATE_VALUE));
        return single == null ? "" : single;
    }

    /** Every operator with its Persian label, for the editor's dropdown. */
    public Map<String, String> gateOperators() {
        return FieldGateOperators.labels();
    }

    /**
     * The first condition's gate, which is the only one today's editor can write.
     *
     * <p>Reading only the first is honest rather than lossy: the stored shape is a list so a
     * second state can be added without a migration, and until the editor can show two, showing
     * the first is the one that round-trips. {@code field-definitions.html} says so where an
     * administrator can see it.
     */
    private Map<String, Object> gate(Map<String, Object> validation) {
        List<Map<String, Object>> conditions = FieldValidationSupport.conditions(validation);
        if (conditions.isEmpty()) {
            return null;
        }
        Map<String, Object> gate = FieldValidationSupport.gateOf(conditions.get(0));
        return gate.isEmpty() ? null : gate;
    }

    /** How many conditions are stored — the editor warns when it is showing only the first. */
    public int conditionCount(Map<String, Object> validation) {
        return FieldValidationSupport.conditions(validation).size();
    }

    private static String asText(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
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
    /** Which readers may fill this field, for the editor's checkboxes. */
    public List<String> scanSources(Map<String, Object> validation) {
        return FieldValidationSupport.scanSources(validation);
    }

    /** Whether this field is scanned at all — the editor shows its block only then. */
    public boolean isScannable(Map<String, Object> validation) {
        return !FieldValidationSupport.scanSources(validation).isEmpty();
    }

    /** Whether typing is still allowed. True for every field that says nothing about it. */
    public boolean scanAllowsManual(Map<String, Object> validation) {
        return FieldValidationSupport.scanAllowsManual(validation);
    }

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
