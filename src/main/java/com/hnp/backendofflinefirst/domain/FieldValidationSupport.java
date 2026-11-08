package com.hnp.backendofflinefirst.domain;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Field-definition validation rules stored in {@code field_definitions.validation} (JSONB).
 * <p>
 * Numeric fields may define two independent display ranges (neither blocks submit):
 * <ul>
 *   <li>{@code warning} — value outside → {@link FieldValidationSeverity#WARNING}</li>
 *   <li>{@code danger} — value outside → {@link FieldValidationSeverity#DANGER}</li>
 * </ul>
 * Legacy {@code {"min": n, "max": m}} is treated as {@code warning}.
 */
public final class FieldValidationSupport {

    public static final String KEY_WARNING = "warning";
    public static final String KEY_DANGER = "danger";
    public static final String KEY_OPTIONS = "options";
    /**
     * Which of a select field's options are a warning when chosen.
     *
     * <p><strong>The polarity is the opposite of {@link #KEY_WARNING}.</strong> A numeric
     * {@code warning} is a range a reading must stay <em>inside</em>; these are the options that
     * <em>are</em> the warning. Reading them as "the acceptable options" inverts every alert on
     * the plant, which is why they are named for the severity and not for the range.
     *
     * <p>A separate list rather than objects inside {@link #KEY_OPTIONS}: the vocabulary is a
     * list of plain strings in every stored row, every frozen sheet snapshot and every tablet
     * already in service, and the submit-time allow-list compares against those strings. Two
     * additive keys leave all of that untouched — an older client ignores them and paints
     * nothing, exactly how the per-asset override itself shipped.
     *
     * <p>Values are matched <strong>case-sensitively</strong> and trimmed, because
     * {@code FormDataValidationSupport}'s allow-list already is: normalising case here would
     * let a stored reading fail its own field's validation.
     */
    public static final String KEY_WARNING_OPTIONS = "warningOptions";
    /** Which options are a danger when chosen. See {@link #KEY_WARNING_OPTIONS}. */
    public static final String KEY_DANGER_OPTIONS = "dangerOptions";
    public static final String KEY_MIN = "min";
    public static final String KEY_MAX = "max";

    /**
     * How a {@code text} or {@code number} field's value may be <b>read off a label</b> instead
     * of typed — a nameplate serial, an instrument tag.
     *
     * <h2>This is not the round's scan mode, and the difference is the whole design</h2>
     *
     * <p>{@code log_sheets.scan_mode} (V11) says how to reach <b>an asset</b>. This says how one
     * <b>question</b> may be answered, on an asset the operator is already standing at with the
     * form open. The two never meet: a scan here must not go through
     * {@code matchLogSheetEntryByTag}, which answers "which asset is this?" and would reject
     * every correct nameplate read; and it must not touch {@code entry_source}, which records
     * how the <em>asset</em> was reached and is what the data-quality report counts.
     *
     * <p>Consequently a round can be {@code NONE} — nothing to scan to open an asset — and still
     * carry a field that wants a barcode.
     *
     * <h2>The stored shape</h2>
     *
     * <pre>{@code
     * "scan": { "sources": ["QR", "BARCODE"], "manual": true }
     * }</pre>
     *
     * <p>{@code sources} is a subset of {@link ScanFieldSource}; an empty one means the field is
     * not scannable and the key is omitted entirely. {@code manual} is whether typing is still
     * allowed — <b>true unless explicitly turned off</b>, because a label in a plant is
     * scratched, greasy, behind glass or missing, and a field that can only be scanned is a
     * round that cannot be finished. It is stored only when false, so the common shape stays
     * small and an older reader that ignores the key behaves correctly by default.
     */
    public static final String KEY_SCAN = "scan";
    public static final String KEY_SCAN_SOURCES = "sources";
    public static final String KEY_SCAN_MANUAL = "manual";

    /** The data types a scan can fill: a label resolves to text, or to a number written as text. */
    public static boolean supportsScanFill(String dataType) {
        return "text".equals(dataType) || "number".equals(dataType);
    }

    /**
     * Which readers this field accepts, or empty when it is typed.
     *
     * <p>Read defensively: this comes out of a JSONB blob that a newer panel, an older panel or
     * a hand edit may have written, so a malformed {@code scan} object yields "not scannable"
     * rather than throwing in the middle of rendering a form.
     */
    @SuppressWarnings("unchecked")
    public static List<String> scanSources(Map<String, Object> validation) {
        if (validation == null) {
            return List.of();
        }
        Object scan = validation.get(KEY_SCAN);
        if (!(scan instanceof Map<?, ?> map)) {
            return List.of();
        }
        Object sources = map.get(KEY_SCAN_SOURCES);
        if (!(sources instanceof Collection<?> raw)) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        for (Object item : raw) {
            if (item != null) {
                names.add(String.valueOf(item));
            }
        }
        return ScanFieldSource.retainKnown(names);
    }

    /**
     * Whether the operator may still type into a scannable field. <b>True unless stored false.</b>
     *
     * <p>The default is the safe direction, and it is the default on purpose: a reader that has
     * never heard of this key, or a blob where it is missing or malformed, lets the operator
     * type. The opposite default would turn an unreadable blob into a field nobody can fill.
     */
    public static boolean scanAllowsManual(Map<String, Object> validation) {
        if (validation == null) {
            return true;
        }
        Object scan = validation.get(KEY_SCAN);
        if (!(scan instanceof Map<?, ?> map)) {
            return true;
        }
        return !Boolean.FALSE.equals(map.get(KEY_SCAN_MANUAL));
    }

    /**
     * What a form post meant by the manual-entry checkbox.
     *
     * <p>An unchecked checkbox posts <b>nothing</b>, so a second parameter has to say the control
     * was on the form at all — otherwise "the administrator unticked it" and "this page predates
     * the box" arrive identically, and they must mean opposite things.
     *
     * <h3>Why the marker has its own name</h3>
     *
     * <p>Because the obvious shape is wrong here. A hidden {@code scanManual=false} placed before
     * the checkbox is the PHP/Rails trick of letting the <em>last</em> value win; Spring takes the
     * <em>first</em>, converting the two-element array by reading element zero. That shipped, and
     * the result was a box that could be ticked, saved and reopened unticked, with scan-only as
     * the only reachable state. One name, one value. See AGENTS.md #171.
     *
     * @param marker the {@code _scanManual} field. Null means the form did not carry the control,
     *        which resolves to "typing allowed" — the safe direction, and the behaviour every
     *        field had before this setting existed.
     * @param checked the {@code scanManual} checkbox itself, null when the operator unticked it.
     */
    public static boolean scanManualFromForm(String marker, Boolean checked) {
        if (marker == null) {
            return true;
        }
        return Boolean.TRUE.equals(checked);
    }

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

    /**
     * The whole {@code validation} object for one field, rebuilt from the form that saved it.
     *
     * <p><strong>This is a rebuild, not a merge, and that is the trap.</strong> Whatever is not
     * written here is gone after the next save of the field — a key added to the database by
     * hand, or by a newer writer whose parameter was not threaded through this signature,
     * vanishes the first time an administrator edits anything about that field. {@code
     * allowNegative} died that way (see the PWA's {@code types/sync.ts}), and the option
     * severities below were added to this signature for the same reason rather than written
     * anywhere closer to the form.
     */
    public static Map<String, Object> build(String dataType,
                                              String selectOptions,
                                              Double warningMin,
                                              Double warningMax,
                                              Double dangerMin,
                                              Double dangerMax,
                                              String warningOptions,
                                              String dangerOptions) {
        return build(dataType, selectOptions, warningMin, warningMax, dangerMin, dangerMax,
                warningOptions, dangerOptions, null, true);
    }

    /**
     * @param scanSources which readers may fill this field — a collection of
     *        {@link ScanFieldSource} names, in display order. Null or empty means the field is
     *        typed, which is every field that existed before this parameter did.
     * @param scanManual whether typing is still allowed alongside the scan. Ignored when the
     *        field is not scannable, because "you may not type into this" is meaningless for a
     *        field with nothing to scan — and storing it would let a later edit that clears the
     *        sources leave an un-fillable field behind.
     */
    public static Map<String, Object> build(String dataType,
                                              String selectOptions,
                                              Double warningMin,
                                              Double warningMax,
                                              Double dangerMin,
                                              Double dangerMax,
                                              String warningOptions,
                                              String dangerOptions,
                                              Collection<String> scanSources,
                                              boolean scanManual) {
        Map<String, Object> validation = new LinkedHashMap<>();
        if (isSelectType(dataType)) {
            List<String> options = splitOptions(selectOptions);
            if (!options.isEmpty()) {
                validation.put(KEY_OPTIONS, options);
            }
            // Severities are narrowed to the vocabulary that actually exists, so an option the
            // admin deleted cannot leave a rule behind that nothing can ever match — and
            // DANGER wins a key claimed by both, so the stored shape is always unambiguous.
            // The form's radios make "both" unreachable; an API caller or a hand edit can still
            // ask for it, and silently resolving it beats storing a contradiction.
            List<String> dangers = retainKnown(splitOptions(dangerOptions), options);
            List<String> warnings = retainKnown(splitOptions(warningOptions), options).stream()
                    .filter(o -> !dangers.contains(o))
                    .toList();
            if (!warnings.isEmpty()) {
                validation.put(KEY_WARNING_OPTIONS, warnings);
            }
            if (!dangers.isEmpty()) {
                validation.put(KEY_DANGER_OPTIONS, dangers);
            }
        }
        if ("number".equals(dataType)) {
            assertMinNotGreaterThanMax("Warning", warningMin, warningMax);
            assertMinNotGreaterThanMax("Danger", dangerMin, dangerMax);
            putRange(validation, KEY_WARNING, warningMin, warningMax);
            putRange(validation, KEY_DANGER, dangerMin, dangerMax);
        }
        putScan(validation, dataType, scanSources, scanManual);
        return validation.isEmpty() ? null : validation;
    }

    /**
     * Writes the {@code scan} object, or leaves it out.
     *
     * <p>Narrowed to the <b>known</b> sources and to a type that can hold a scanned string, for
     * the same reason the option severities are narrowed to the vocabulary that exists: a
     * dropdown is not a boundary, and a stored rule nothing can satisfy is worse than no rule.
     * An unrecognised source name is dropped rather than refused — the editor cannot produce
     * one, and an API caller sending one should not have the rest of the field rejected.
     */
    private static void putScan(Map<String, Object> validation, String dataType,
                                Collection<String> scanSources, boolean scanManual) {
        if (!supportsScanFill(dataType) || scanSources == null) {
            return;
        }
        List<String> sources = ScanFieldSource.retainKnown(scanSources);
        if (sources.isEmpty()) {
            return;
        }
        Map<String, Object> scan = new LinkedHashMap<>();
        scan.put(KEY_SCAN_SOURCES, sources);
        // Written only when it is false. True is the default every reader applies, so storing it
        // would put a key in almost every scannable field to say what its absence already says.
        if (!scanManual) {
            scan.put(KEY_SCAN_MANUAL, false);
        }
        validation.put(KEY_SCAN, scan);
    }

    /** One option per line (or comma, or Arabic comma), trimmed, de-duplicated, order kept. */
    public static List<String> splitOptions(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String part : raw.split("[,\n،]")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                out.add(trimmed);
            }
        }
        return List.copyOf(out);
    }

    private static List<String> retainKnown(List<String> candidates, List<String> vocabulary) {
        if (candidates.isEmpty() || vocabulary.isEmpty()) {
            return List.of();
        }
        return candidates.stream().filter(vocabulary::contains).toList();
    }

    private static void assertMinNotGreaterThanMax(String rangeLabel, Double min, Double max) {
        if (min != null && max != null && min > max) {
            throw new IllegalArgumentException(
                    rangeLabel + " range minimum (" + min + ") must not be greater than maximum (" + max + ").");
        }
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

    /**
     * The same verdict worded for the field's own kind of threshold.
     *
     * <p>A number is <em>outside its range</em>; a chosen option <em>is</em> the alert. Reusing
     * the numeric sentence for a select told an operator that «OF» was «خارج از بازه خطر» —
     * describing a range the field does not have and never had. Every surface that shows a
     * severity to a person must go through here rather than through {@link #messageFa}.
     */
    public static String messageFa(String dataType, FieldValidationSeverity severity) {
        if (!isSelectType(dataType)) {
            return messageFa(severity);
        }
        return switch (severity) {
            case WARNING -> "این گزینه هشدار محسوب می‌شود.";
            case DANGER -> "این گزینه خطر محسوب می‌شود.";
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

    /**
     * The severity of one reading, chosen by the field's type.
     *
     * <p><strong>The single entry point every write path must use.</strong> Severity used to be
     * {@code evaluateNumericValue} everywhere, which answers {@code OK} for anything that is not
     * a number — so a select field could never be anything but fine, however it was configured.
     * Dispatching here keeps the two kinds of threshold in one place and keeps the stored
     * {@code max_severity} meaning the same thing for every type.
     *
     * <p>Anything that is neither numeric nor a choice has no notion of severity and is
     * {@code OK}: a photo, a note and a GPS fix are not in or out of range.
     */
    public static FieldValidationSeverity evaluateValue(String dataType,
                                                        Object value,
                                                        Map<String, Object> validation) {
        if (isSelectType(dataType)) {
            return evaluateOptionValue(value, validation);
        }
        if ("number".equals(dataType)) {
            return evaluateNumericValue(value, validation);
        }
        return FieldValidationSeverity.OK;
    }

    /**
     * The severity of a chosen option, or of the worst of several.
     *
     * <p>A {@code multiselect} reading is a collection, and an operator who ticked one dangerous
     * option among five safe ones has recorded a danger — so the worst wins, exactly as the
     * numeric evaluation prefers {@code danger} over {@code warning}. An option the field's
     * severities do not name is {@code OK}, which is also what an unconfigured field gives, so
     * adding a vocabulary never raises an alert on its own.
     */
    public static FieldValidationSeverity evaluateOptionValue(Object value,
                                                              Map<String, Object> validation) {
        if (value == null || validation == null) {
            return FieldValidationSeverity.OK;
        }
        List<String> dangers = optionList(validation, KEY_DANGER_OPTIONS);
        List<String> warnings = optionList(validation, KEY_WARNING_OPTIONS);
        if (dangers.isEmpty() && warnings.isEmpty()) {
            return FieldValidationSeverity.OK;
        }

        boolean warned = false;
        for (Object chosen : value instanceof Collection<?> many ? many : List.of(value)) {
            if (chosen == null) {
                continue;
            }
            String text = String.valueOf(chosen).trim();
            if (dangers.contains(text)) {
                return FieldValidationSeverity.DANGER;
            }
            if (warnings.contains(text)) {
                warned = true;
            }
        }
        return warned ? FieldValidationSeverity.WARNING : FieldValidationSeverity.OK;
    }

    /** One severity list as stored, tolerating the json shapes Hibernate can hand back. */
    public static List<String> optionList(Map<String, Object> validation, String key) {
        if (validation == null) {
            return List.of();
        }
        Object raw = validation.get(key);
        if (!(raw instanceof Collection<?> collection)) {
            return List.of();
        }
        List<String> out = new ArrayList<>(collection.size());
        for (Object item : collection) {
            if (item == null) {
                continue;
            }
            String text = String.valueOf(item).trim();
            if (!text.isEmpty()) {
                out.add(text);
            }
        }
        return out;
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
        // A select field has no range; what it has is a set of options that are themselves the
        // alert. Both are rendered by the same helper because one field never has both.
        appendOptionSummary(sb, "هشدار", optionList(validation, KEY_WARNING_OPTIONS));
        appendOptionSummary(sb, "خطر", optionList(validation, KEY_DANGER_OPTIONS));
        return sb.isEmpty() ? "—" : sb.toString();
    }

    private static void appendOptionSummary(StringBuilder sb, String label, List<String> options) {
        if (options.isEmpty()) {
            return;
        }
        if (!sb.isEmpty()) {
            sb.append(" · ");
        }
        sb.append(label).append(": ").append(String.join("، ", options));
    }

    private static void appendRangeSummary(StringBuilder sb, String label, NumericRange range) {
        String formatted = formatRange(range);
        if (formatted == null) {
            return;
        }
        if (!sb.isEmpty()) {
            sb.append(" · ");
        }
        sb.append(label).append(": ").append(formatted);
    }

    /**
     * The bounds of one range as a display string ({@code "10–20"}, {@code "≥ 10"}, {@code "≤ 20"}),
     * or null when the range carries no bound at all. Label-free on purpose so callers can render
     * each range on its own — the web fill page shows warning and danger as two separate chips,
     * while {@link #summaryFa} joins them into a single line.
     */
    public static String formatRange(NumericRange range) {
        if (range == null || range.isEmpty()) {
            return null;
        }
        if (range.min() != null && range.max() != null) {
            return formatBound(range.min()) + "–" + formatBound(range.max());
        }
        if (range.min() != null) {
            return "≥ " + formatBound(range.min());
        }
        return "≤ " + formatBound(range.max());
    }

    /** Whole numbers render without the ".0" a raw Double would show (20 rather than 20.0). */
    private static String formatBound(Double value) {
        if (value == null) {
            return "";
        }
        if (value == Math.rint(value) && !value.isInfinite()) {
            return String.valueOf(value.longValue());
        }
        return String.valueOf(value);
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
