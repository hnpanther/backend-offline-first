package com.hnp.backendofflinefirst.domain;

import com.hnp.backendofflinefirst.entity.FieldDefinition;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Display lines for a parameter's <b>frozen</b> band on a filled sheet (roadmap §50).
 *
 * <p>Pure helper: no writes, no severity stored. Built from the {@link FieldDefinition} the
 * page already resolved through {@code LogSheetFieldDefinitionsService.groupByAsset} — the class
 * schema with this asset's frozen delta applied — so a band edited tomorrow cannot rewrite what
 * a 2026 reading was judged against.
 *
 * <h2>What it covers</h2>
 *
 * <ul>
 *   <li>a number: warning and danger ranges, including one-sided limits and the legacy flat
 *       {@code min}/{@code max} (read the same way the PWA and {@link FieldValidationSupport}
 *       read them);</li>
 *   <li>a choice: warning / danger options as labels (the stored vocabulary strings);</li>
 *   <li>a gate (§36): one line per state, and which state applied to <em>this</em> reading;</li>
 *   <li>{@code entryRange} / {@code integerOnly} on a separate line marked as an input limit,
 *       never as a band — and only when there is also a band to show;</li>
 *   <li>nothing at all when the field has no thresholds — <b>no mark</b>, rather than an empty
 *       tooltip.</li>
 * </ul>
 */
public final class BandSummary {

    private final List<String> lines;

    private BandSummary(List<String> lines) {
        this.lines = List.copyOf(lines);
    }

    /** No mark — the field has nothing a reader needs to open. */
    public static BandSummary none() {
        return new BandSummary(List.of());
    }

    /**
     * The band lines for this field against the entry's answers (so a gate can say which state
     * applied). Sibling definitions are optional: when present, a gate names the sibling by its
     * label rather than its key.
     */
    public static BandSummary of(FieldDefinition field, Map<String, Object> formData) {
        return of(field, formData, null);
    }

    public static BandSummary of(FieldDefinition field,
                                 Map<String, Object> formData,
                                 List<FieldDefinition> siblingDefs) {
        if (field == null) {
            return none();
        }
        Map<String, Object> validation = field.getValidation();
        Map<String, Object> siblings = formData == null ? Map.of() : formData;
        Map<String, String> siblingLabels = siblingLabels(siblingDefs);

        List<String> lines = new ArrayList<>();
        List<Map<String, Object>> conditions = FieldValidationSupport.conditions(validation);
        boolean hasBandContent = false;

        if (conditions.isEmpty()) {
            String thresholds = thresholdsLine(validation, field.getUnit());
            if (thresholds != null) {
                lines.add(thresholds);
                hasBandContent = true;
            }
        } else {
            int appliedIndex = matchingConditionIndex(validation, siblings);
            for (int i = 0; i < conditions.size(); i++) {
                Map<String, Object> condition = conditions.get(i);
                String gate = gatePhrase(FieldValidationSupport.gateOf(condition), siblingLabels);
                if (gate == null) {
                    // An unreadable gate never matches; saying so is better than inventing a band.
                    String line = "شرط نامعتبر";
                    if (i == appliedIndex) {
                        line = line + " — اعمال‌شده برای این قرائت";
                    }
                    lines.add(line);
                    continue;
                }
                Map<String, Object> own = thresholdsDeclaredBy(validation, condition);
                Map<String, Object> effective = own != null ? own : validation;
                String thresholds = thresholdsLine(effective, field.getUnit());
                if (thresholds == null) {
                    // A condition with no thresholds of its own and none on the field is not a
                    // band — skip it rather than printing «بدون آستانه».
                    continue;
                }
                StringBuilder line = new StringBuilder("وقتی ").append(gate)
                        .append(": ").append(thresholds);
                if (i == appliedIndex) {
                    line.append(" — اعمال‌شده برای این قرائت");
                }
                lines.add(line.toString());
                hasBandContent = true;
            }
            if (appliedIndex < 0 && hasBandContent) {
                lines.add("هیچ شرطی برای این قرائت برقرار نبود؛ آستانه اعمال نشد.");
            }
        }

        if (!hasBandContent) {
            return none();
        }

        String entryLimit = entryLimitLine(validation);
        if (entryLimit != null) {
            lines.add(entryLimit);
        }
        return new BandSummary(lines);
    }

    public boolean isEmpty() {
        return lines.isEmpty();
    }

    public List<String> lines() {
        return lines;
    }

    /**
     * The popover body: one line per fact, joined by a newline. Rendered as escaped text by
     * Thymeleaf ({@code data-bs-content}); never assembled as HTML in script.
     */
    public String popoverText() {
        return String.join("\n", lines);
    }

    // ------------------------------------------------------------------------------------------

    /**
     * Warning / danger ranges and option severities as one Persian sentence, or null when the
     * field carries none. Unit is appended once at the end when present.
     */
    static String thresholdsLine(Map<String, Object> validation, String unit) {
        if (validation == null || validation.isEmpty()) {
            return null;
        }
        List<String> parts = new ArrayList<>();

        String warning = rangePhrase("محدوده‌ی مجاز (هشدار)", FieldValidationSupport.warningRange(validation));
        if (warning != null) {
            parts.add(warning);
        }
        String danger = rangePhrase("محدوده‌ی خطر", FieldValidationSupport.dangerRange(validation));
        if (danger != null) {
            parts.add(danger);
        }

        List<String> warnOpts = FieldValidationSupport.optionList(
                validation, FieldValidationSupport.KEY_WARNING_OPTIONS);
        if (!warnOpts.isEmpty()) {
            parts.add("گزینه‌های هشدار: " + String.join("، ", warnOpts));
        }
        List<String> dangerOpts = FieldValidationSupport.optionList(
                validation, FieldValidationSupport.KEY_DANGER_OPTIONS);
        if (!dangerOpts.isEmpty()) {
            parts.add("گزینه‌های خطر: " + String.join("، ", dangerOpts));
        }

        if (parts.isEmpty()) {
            return null;
        }
        String body = String.join(" · ", parts);
        if (unit != null && !unit.isBlank()) {
            return body + " · واحد: " + unit.trim();
        }
        return body;
    }

    /** «محدوده‌ی مجاز (هشدار): ۲۰ تا ۷۰» / «حداقل ۲۰» / «حداکثر ۷۰». */
    static String rangePhrase(String label, FieldValidationSupport.NumericRange range) {
        if (range == null || range.isEmpty()) {
            return null;
        }
        if (range.min() != null && range.max() != null) {
            return label + ": " + formatBound(range.min()) + " تا " + formatBound(range.max());
        }
        if (range.min() != null) {
            return label + ": حداقل " + formatBound(range.min());
        }
        return label + ": حداکثر " + formatBound(range.max());
    }

    static String entryLimitLine(Map<String, Object> validation) {
        if (!FieldValidationSupport.hasEntryLimit(validation)) {
            return null;
        }
        String summary = FieldValidationSupport.entryLimitSummaryFa(validation);
        if (summary == null || summary.isBlank()) {
            return null;
        }
        return "محدودیت ورود (نه آستانه): " + summary;
    }

    /**
     * Index of the first matching condition, or {@code -1} when none match (suppressed) or the
     * field has no conditions. Walks the same order as {@link FieldValidationSupport#thresholdsFor}
     * by asking it on a one-condition copy of the map.
     */
    static int matchingConditionIndex(Map<String, Object> validation, Map<String, Object> siblings) {
        List<Map<String, Object>> conditions = FieldValidationSupport.conditions(validation);
        for (int i = 0; i < conditions.size(); i++) {
            Map<String, Object> trimmed = singletonConditionValidation(validation, conditions.get(i));
            if (FieldValidationSupport.thresholdsFor(trimmed, siblings) != null) {
                return i;
            }
        }
        return -1;
    }

    /** A validation map that keeps only one condition, so thresholdsFor can test it alone. */
    private static Map<String, Object> singletonConditionValidation(Map<String, Object> validation,
                                                                    Map<String, Object> condition) {
        Map<String, Object> copy = new LinkedHashMap<>();
        if (validation != null) {
            copy.putAll(validation);
        }
        copy.put(FieldValidationSupport.KEY_CONDITIONS, List.of(condition));
        return copy;
    }

    /**
     * The condition's own threshold keys laid over the field's non-threshold keys, or null when
     * it declares none (the field's own thresholds then apply — same rule as
     * {@link FieldValidationSupport#thresholdsFor}).
     */
    private static Map<String, Object> thresholdsDeclaredBy(Map<String, Object> validation,
                                                            Map<String, Object> condition) {
        boolean declares = false;
        for (String key : FieldValidationSupport.THRESHOLD_KEYS) {
            if (condition.containsKey(key)) {
                declares = true;
                break;
            }
        }
        if (!declares) {
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        if (validation != null) {
            for (Map.Entry<String, Object> entry : validation.entrySet()) {
                if (!FieldValidationSupport.THRESHOLD_KEYS.contains(entry.getKey())
                        && !FieldValidationSupport.KEY_CONDITIONS.equals(entry.getKey())
                        && !FieldValidationSupport.KEY_MIN.equals(entry.getKey())
                        && !FieldValidationSupport.KEY_MAX.equals(entry.getKey())) {
                    out.put(entry.getKey(), entry.getValue());
                }
            }
        }
        for (String key : FieldValidationSupport.THRESHOLD_KEYS) {
            if (condition.containsKey(key)) {
                out.put(key, condition.get(key));
            }
        }
        return out;
    }

    /**
     * A short gate phrase for the popover. Prefer «وضعیت = روشن» for a single {@code in} value;
     * fall back to {@link FieldValidationSupport#gateSummaryFa} for everything else.
     */
    static String gatePhrase(Map<String, Object> gate, Map<String, String> siblingLabels) {
        if (gate == null || gate.isEmpty()) {
            return null;
        }
        String fieldKey = asTrimmed(gate.get(FieldValidationSupport.KEY_GATE_FIELD));
        String op = asTrimmed(gate.get(FieldValidationSupport.KEY_GATE_OP));
        if (fieldKey == null || !FieldGateOperators.isValid(op)) {
            return null;
        }
        String fieldLabel = siblingLabels.getOrDefault(fieldKey.toLowerCase(java.util.Locale.ROOT), fieldKey);

        if (FieldGateOperators.IN.equals(op)) {
            List<String> values = FieldValidationSupport.optionList(gate, FieldValidationSupport.KEY_GATE_VALUES);
            if (values.isEmpty()) {
                return null;
            }
            if (values.size() == 1) {
                return fieldLabel + " = " + values.getFirst();
            }
            return fieldLabel + " یکی از: " + String.join("، ", values);
        }
        if (FieldGateOperators.NOT_IN.equals(op)) {
            List<String> values = FieldValidationSupport.optionList(gate, FieldValidationSupport.KEY_GATE_VALUES);
            if (values.isEmpty()) {
                return null;
            }
            return fieldLabel + " هیچ‌کدام از: " + String.join("، ", values);
        }
        // Numbers and answered/notAnswered — reuse the shared wording, but with the label.
        String shared = FieldValidationSupport.gateSummaryFa(gate);
        if (shared == null) {
            return null;
        }
        if (shared.startsWith(fieldKey)) {
            return fieldLabel + shared.substring(fieldKey.length());
        }
        return shared;
    }

    private static Map<String, String> siblingLabels(List<FieldDefinition> siblingDefs) {
        if (siblingDefs == null || siblingDefs.isEmpty()) {
            return Map.of();
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (FieldDefinition fd : siblingDefs) {
            if (fd == null || fd.getKey() == null) {
                continue;
            }
            String label = fd.getLabel() != null && !fd.getLabel().isBlank() ? fd.getLabel() : fd.getKey();
            out.put(fd.getKey().toLowerCase(java.util.Locale.ROOT), label);
        }
        return out;
    }

    private static String formatBound(Double value) {
        if (value == null) {
            return "";
        }
        if (value == Math.rint(value) && !value.isInfinite()) {
            return String.valueOf(value.longValue());
        }
        return String.valueOf(value);
    }

    private static String asTrimmed(Object raw) {
        if (raw == null) {
            return null;
        }
        String text = String.valueOf(raw).trim();
        return text.isEmpty() ? null : text;
    }
}
