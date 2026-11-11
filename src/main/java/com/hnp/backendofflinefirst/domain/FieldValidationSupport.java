package com.hnp.backendofflinefirst.domain;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
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
     * Thresholds that apply only while <b>another answer on the same entry</b> says so — a
     * <i>state gate</i>.
     *
     * <h2>Why this is not a band</h2>
     *
     * <p>«Warn me when the pump is on and drawing no current» cannot be written as a lower
     * bound. The obvious attempt — a danger minimum of zero — does nothing at all, because
     * {@link NumericRange#contains} is a <b>closed</b> interval and {@code 0 < 0} is false; and
     * even with a real threshold it fires on every legitimately stopped machine, which is the
     * nuisance alarm ISA-18.2 / IEC 62682 exist to prevent. What the condition needs is the
     * equipment's state, which lives in a different field of the same {@code form_data}.
     *
     * <h2>The stored shape</h2>
     *
     * <pre>{@code
     * "conditions": [
     *   { "when":   { "field": "status", "op": "in", "values": ["روشن"] },
     *     "danger": { "min": 4.5, "max": 34.8 } }
     * ]
     * }</pre>
     *
     * <p>A list, not a single object, so a second state can be added later without a migration
     * and without re-reading every stored field. Today's editor writes exactly one.
     *
     * <h2>The evaluation order, which is the whole of the semantics</h2>
     *
     * <ol>
     *   <li><b>No {@code conditions} key</b> (or an unreadable one) — judged exactly as it was
     *       before this key existed. Every field in service today takes this branch, which is
     *       why the feature cannot change an existing verdict.</li>
     *   <li><b>The first condition whose {@code when} matches</b> decides. If it carries any of
     *       the four threshold keys ({@code warning}, {@code danger}, {@code warningOptions},
     *       {@code dangerOptions}) those are the <em>complete</em> set for this state, and one
     *       it omits is absent rather than inherited. If it carries none, the field's own
     *       thresholds apply — which is what the editor's single gate writes, and the reason
     *       «gate my existing bands» needs no second pair of inputs.</li>
     *   <li><b>No condition matches</b> — {@link FieldValidationSeverity#OK}, suppressed by
     *       design. Not «not read»: the value is still stored, still reported and still
     *       trended. Only the judgement is withheld.</li>
     * </ol>
     *
     * <p><b>A condition is a complete statement about a state; an asset override is a layer.</b>
     * That is why an omitted key means «no band» here and «keep the class's band» in
     * {@code AssetFieldResolver.withBandsOnly}. The two look alike and must not be made
     * consistent: a state the administrator described as «danger only» has no warning band, and
     * inheriting one meant for the other state would alert on readings that state calls normal.
     */
    public static final String KEY_CONDITIONS = "conditions";
    /** The gate object inside one condition. */
    public static final String KEY_WHEN = "when";
    /** Gate: which sibling field to read. Matched case-insensitively — see {@link #gateMatches}. */
    public static final String KEY_GATE_FIELD = "field";
    /** Gate: which {@link FieldGateOperators} operator to apply. */
    public static final String KEY_GATE_OP = "op";
    /** Gate: the option list an {@code in} / {@code notIn} compares against. */
    public static final String KEY_GATE_VALUES = "values";
    /** Gate: the single number a {@code gt} / {@code gte} / {@code lt} / {@code lte} compares against. */
    public static final String KEY_GATE_VALUE = "value";

    /**
     * The keys that say «how bad is this reading», and nothing else.
     *
     * <p>One list because three places must agree on it: a condition may carry exactly these,
     * {@code AssetFieldOverrideService} allows exactly these under {@code RANGES_ONLY}, and
     * {@link #thresholdsFor} replaces exactly these when a condition declares its own. A fourth
     * place that re-states the list is how a threshold ends up stored where nothing reads it.
     */
    public static final List<String> THRESHOLD_KEYS =
            List.of(KEY_WARNING, KEY_DANGER, KEY_WARNING_OPTIONS, KEY_DANGER_OPTIONS);

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
        return build(dataType, selectOptions, warningMin, warningMax, dangerMin, dangerMax,
                warningOptions, dangerOptions, scanSources, scanManual, null);
    }

    /**
     * One state gate, as a field editor posts it.
     *
     * <p>Three strings because that is what a form has: the sibling's key, the operator, and the
     * operand typed beside it — options one per line for {@code in} / {@code notIn}, a single
     * number for the comparisons, nothing for {@code answered} / {@code notAnswered}.
     *
     * @param field the sibling field's key; blank means «this field has no gate»
     */
    public record GateSpec(String field, String op, String operand) {
        public boolean isBlank() {
            return field == null || field.isBlank() || op == null || op.isBlank();
        }
    }

    /**
     * @param gate the field's single {@link #KEY_CONDITIONS state gate}, or null for none.
     *
     * <p><strong>Null erases an existing gate</strong>, exactly as a null band erases a band —
     * {@code build} rebuilds the whole object and the class comment says what that costs. The
     * ten-argument overload therefore writes no gate, which is correct for the one caller that
     * uses it: a <em>sheet-level</em> question belongs to the round and has no per-asset
     * {@code form_data} to gate on, so there is nothing for it to read. A path that gains
     * siblings must come through here instead of growing its own key.
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
                                              boolean scanManual,
                                              GateSpec gate) {
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
        putGate(validation, dataType, gate);
        return validation.isEmpty() ? null : validation;
    }

    /**
     * Writes the single-condition {@code conditions} list, or leaves the key out.
     *
     * <p><b>Refused rather than dropped, unlike {@code scan}.</b> An unrecognised scan source is
     * a control the editor cannot even produce, and dropping it costs nothing. A gate is the
     * administrator's whole statement about <em>when</em> a danger band applies: silently
     * ignoring a malformed one leaves a field that reads as state-aware on the screen they just
     * saved and is judged unconditionally on the plant. The two readings differ by every false
     * alarm on every stopped machine, so this throws and the editor says so.
     *
     * <p>Only a type that carries a threshold at all can carry a gate. A gate on a photo or a
     * note is a rule nothing will ever read — the same mistake
     * {@code AssetFieldOverrideService} refuses for a band on a text field.
     */
    private static void putGate(Map<String, Object> validation, String dataType, GateSpec gate) {
        if (gate == null || gate.isBlank()) {
            return;
        }
        if (!isSelectType(dataType) && !"number".equals(dataType)) {
            throw new IllegalArgumentException("Field is " + dataType
                    + "; a state condition applies to number and select fields only.");
        }
        String field = gate.field().trim();
        String op = gate.op().trim();
        if (!FieldGateOperators.isValid(op)) {
            throw new IllegalArgumentException("Unknown state condition operator '" + op + "'.");
        }
        Map<String, Object> when = new LinkedHashMap<>();
        when.put(KEY_GATE_FIELD, field);
        when.put(KEY_GATE_OP, op);
        if (FieldGateOperators.takesOptions(op)) {
            List<String> values = splitOptions(gate.operand());
            if (values.isEmpty()) {
                throw new IllegalArgumentException(
                        "A state condition using '" + op + "' needs at least one value to compare against.");
            }
            when.put(KEY_GATE_VALUES, values);
        } else if (FieldGateOperators.takesNumber(op)) {
            Double bound = toDouble(gate.operand());
            if (bound == null || bound.isNaN() || bound.isInfinite()) {
                throw new IllegalArgumentException(
                        "A state condition using '" + op + "' needs a number to compare against.");
            }
            when.put(KEY_GATE_VALUE, bound);
        }
        // The condition carries no thresholds of its own: the field's own bands are what the gate
        // switches on and off. See KEY_CONDITIONS step 2.
        Map<String, Object> condition = new LinkedHashMap<>();
        condition.put(KEY_WHEN, when);
        validation.put(KEY_CONDITIONS, List.of(condition));
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
        return evaluateValue(dataType, value, validation, null);
    }

    /**
     * As {@link #evaluateValue(String, Object, Map)}, with the rest of this entry's answers so a
     * {@link #KEY_CONDITIONS state gate} can be judged.
     *
     * <p><strong>Prefer this overload on every path that has the answers in hand</strong> — and
     * every one of them does, because severity is computed from a {@code form_data} map that is
     * already open. The three-argument form cannot match a gate at all, so a gated field read
     * through it resolves to {@link FieldValidationSeverity#OK}. That is the safe direction for
     * a notification system (a missed alert beats a plant full of false ones) but it is still
     * wrong, so a new caller passes the map.
     *
     * @param siblings every answer on this entry, keyed as {@code form_data} keys it. Null means
     *                 «not available», which suppresses a gated field rather than guessing.
     */
    public static FieldValidationSeverity evaluateValue(String dataType,
                                                        Object value,
                                                        Map<String, Object> validation,
                                                        Map<String, Object> siblings) {
        Map<String, Object> thresholds = thresholdsFor(validation, siblings);
        if (thresholds == null) {
            // Suppressed by design: this state has no threshold for this parameter.
            return FieldValidationSeverity.OK;
        }
        if (isSelectType(dataType)) {
            return evaluateOptionValue(value, thresholds);
        }
        if ("number".equals(dataType)) {
            return evaluateNumericValue(value, thresholds);
        }
        return FieldValidationSeverity.OK;
    }

    // ===========================================================================================
    // State gates — see KEY_CONDITIONS for the shape and the semantics
    // ===========================================================================================

    /**
     * The thresholds in force for this reading, or <b>null</b> when the field is suppressed.
     *
     * <p>Returns the {@code validation} map <em>itself</em>, unchanged and un-copied, for the
     * overwhelmingly common case of a field with no conditions — this runs once per answered
     * parameter per entry on every save, and allocating a copy of every field's validation to
     * change nothing would be a cost paid by every round in the plant.
     */
    public static Map<String, Object> thresholdsFor(Map<String, Object> validation,
                                                    Map<String, Object> siblings) {
        List<Map<String, Object>> conditions = conditions(validation);
        if (conditions.isEmpty()) {
            return validation;
        }
        Map<String, String> lookup = siblingKeyLookup(siblings);
        for (Map<String, Object> condition : conditions) {
            if (!gateMatches(gateOf(condition), siblings, lookup)) {
                continue;
            }
            Map<String, Object> own = declaredThresholds(validation, condition);
            return own != null ? own : validation;
        }
        return null;
    }

    /**
     * The conditions as stored, read defensively.
     *
     * <p>This comes out of a jsonb blob that a newer panel, an older panel or a hand edit may
     * have written, so anything that is not a list of objects reads as «no conditions» — which
     * restores the pre-gate behaviour — rather than throwing in the middle of stamping an
     * operator's reading.
     */
    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> conditions(Map<String, Object> validation) {
        if (validation == null) {
            return List.of();
        }
        Object raw = validation.get(KEY_CONDITIONS);
        if (!(raw instanceof Collection<?> collection) || collection.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>(collection.size());
        for (Object item : collection) {
            if (item instanceof Map<?, ?> map && !map.isEmpty()) {
                out.add((Map<String, Object>) map);
            }
        }
        return List.copyOf(out);
    }

    /** One condition's gate object, or an empty map when it carries none. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> gateOf(Map<String, Object> condition) {
        if (condition == null) {
            return Map.of();
        }
        Object raw = condition.get(KEY_WHEN);
        return raw instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    /**
     * The condition's own thresholds laid over the field's non-threshold keys, or null when it
     * declares none.
     *
     * <p>The field's {@code options} vocabulary, {@code scan} block and anything else a future
     * writer adds are carried through: they are not thresholds, and a condition that re-bands a
     * parameter must not strip the list of answers it offers.
     */
    private static Map<String, Object> declaredThresholds(Map<String, Object> validation,
                                                          Map<String, Object> condition) {
        boolean declares = false;
        for (String key : THRESHOLD_KEYS) {
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
                if (!THRESHOLD_KEYS.contains(entry.getKey())
                        && !KEY_CONDITIONS.equals(entry.getKey())
                        && !KEY_MIN.equals(entry.getKey())
                        && !KEY_MAX.equals(entry.getKey())) {
                    out.put(entry.getKey(), entry.getValue());
                }
            }
        }
        for (String key : THRESHOLD_KEYS) {
            if (condition.containsKey(key)) {
                Object value = condition.get(key);
                if (value != null) {
                    out.put(key, value);
                }
            }
        }
        return out;
    }

    /**
     * Whether one gate holds for this entry's answers.
     *
     * <p><b>Nothing here throws and nothing here matches by accident.</b> A malformed gate, an
     * operator this build does not know, a field the entry has no answer for — every one of them
     * is «does not match», which falls through to the next condition and, failing that,
     * suppresses the field. The opposite default would turn an unreadable blob into an alert.
     */
    public static boolean gateMatches(Map<String, Object> gate, Map<String, Object> siblings) {
        return gateMatches(gate, siblings, siblingKeyLookup(siblings));
    }

    private static boolean gateMatches(Map<String, Object> gate,
                                       Map<String, Object> siblings,
                                       Map<String, String> lookup) {
        if (gate == null || gate.isEmpty() || siblings == null) {
            return false;
        }
        String field = asTrimmed(gate.get(KEY_GATE_FIELD));
        String op = asTrimmed(gate.get(KEY_GATE_OP));
        if (field == null || !FieldGateOperators.isValid(op)) {
            return false;
        }
        // Case-insensitive, matching `AssetFieldResolver`: `ux_field_definitions_class_key_lower`
        // already scopes key uniqueness by lower(key), so `Status` and `status` cannot both
        // exist — and a case mismatch here would make a correctly configured danger gate
        // silently never match, which is the worst outcome a threshold can have.
        String actualKey = lookup.get(field.toLowerCase(Locale.ROOT));
        Object answer = actualKey == null ? null : siblings.get(actualKey);
        boolean answered = isAnswered(answer);

        if (FieldGateOperators.ANSWERED.equals(op)) {
            return answered;
        }
        if (FieldGateOperators.NOT_ANSWERED.equals(op)) {
            return !answered;
        }
        // «We do not know» is never «it was in this state» — the same rule the alarm history
        // applies to an unread parameter (docs/alarms.md §4).
        if (!answered) {
            return false;
        }
        if (FieldGateOperators.takesOptions(op)) {
            List<String> wanted = optionList(gate, KEY_GATE_VALUES);
            if (wanted.isEmpty()) {
                return false;
            }
            boolean any = false;
            for (Object chosen : answer instanceof Collection<?> many ? many : List.of(answer)) {
                if (chosen == null) {
                    continue;
                }
                // Trimmed and CASE-SENSITIVE, exactly as `evaluateOptionValue` compares, because
                // the submit-time allow-list is case-sensitive: a looser rule here would let a
                // gate match a spelling the server refuses to store.
                if (wanted.contains(String.valueOf(chosen).trim())) {
                    any = true;
                    break;
                }
            }
            return FieldGateOperators.IN.equals(op) ? any : !any;
        }
        if (FieldGateOperators.takesNumber(op)) {
            Double actual = toDouble(answer);
            Double bound = toDouble(gate.get(KEY_GATE_VALUE));
            if (actual == null || bound == null) {
                return false;
            }
            return switch (op) {
                case FieldGateOperators.GT -> actual > bound;
                case FieldGateOperators.GTE -> actual >= bound;
                case FieldGateOperators.LT -> actual < bound;
                case FieldGateOperators.LTE -> actual <= bound;
                default -> false;
            };
        }
        return false;
    }

    /**
     * Lowercased key to the key as {@code form_data} actually spells it.
     *
     * <p>Built once per entry rather than per field: a 22-parameter asset would otherwise walk
     * its own answers 22 times to find one sibling.
     */
    private static Map<String, String> siblingKeyLookup(Map<String, Object> siblings) {
        if (siblings == null || siblings.isEmpty()) {
            return Map.of();
        }
        Map<String, String> out = new LinkedHashMap<>(siblings.size());
        for (String key : siblings.keySet()) {
            if (key != null) {
                out.putIfAbsent(key.toLowerCase(Locale.ROOT), key);
            }
        }
        return out;
    }

    /**
     * Whether an answer is present at all.
     *
     * <p>Mirrors {@code AlarmReadingHistory.isAnswered} deliberately: a blank string is
     * <em>absent</em>, not an answer of "". The two must agree, or a gate would hold for a
     * reading the alarm history counts as unread.
     */
    public static boolean isAnswered(Object raw) {
        if (raw == null) {
            return false;
        }
        if (raw instanceof CharSequence text) {
            return !text.toString().isBlank();
        }
        if (raw instanceof Collection<?> collection) {
            return !collection.isEmpty();
        }
        if (raw instanceof Map<?, ?> map) {
            return !map.isEmpty();
        }
        return true;
    }

    private static String asTrimmed(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
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

    /**
     * The thresholds as a person reads them — <b>including when they apply</b>.
     *
     * <p>The gate has to be in here, not beside it. This string is the "allowed range" column of
     * the exception report and the field list, and a reader who sees «خطر: ≥ 4.5» on a standby
     * pump with no mention of the state concludes the band is wrong and re-tunes it. One line,
     * one complete rule.
     */
    public static String summaryFa(Map<String, Object> validation) {
        List<Map<String, Object>> conditions = conditions(validation);
        if (conditions.isEmpty()) {
            return thresholdSummaryFa(validation);
        }
        StringBuilder out = new StringBuilder();
        for (Map<String, Object> condition : conditions) {
            Map<String, Object> own = declaredThresholds(validation, condition);
            String bands = thresholdSummaryFa(own != null ? own : validation);
            String gate = gateSummaryFa(gateOf(condition));
            if (!out.isEmpty()) {
                out.append(" · ");
            }
            if (gate == null) {
                // A condition with no readable gate never matches, so saying only what its bands
                // are would describe a rule that does not run.
                out.append("شرط نامعتبر");
                continue;
            }
            out.append("فقط وقتی ").append(gate);
            if (!"—".equals(bands)) {
                out.append(" → ").append(bands);
            }
        }
        return out.isEmpty() ? "—" : out.toString();
    }

    /** One gate in words, or null when it cannot be read. */
    public static String gateSummaryFa(Map<String, Object> gate) {
        if (gate == null || gate.isEmpty()) {
            return null;
        }
        String field = asTrimmed(gate.get(KEY_GATE_FIELD));
        String op = asTrimmed(gate.get(KEY_GATE_OP));
        if (field == null || !FieldGateOperators.isValid(op)) {
            return null;
        }
        StringBuilder out = new StringBuilder(field).append(' ').append(FieldGateOperators.label(op));
        if (FieldGateOperators.takesOptions(op)) {
            List<String> values = optionList(gate, KEY_GATE_VALUES);
            if (values.isEmpty()) {
                return null;
            }
            out.append(": ").append(String.join("، ", values));
        } else if (FieldGateOperators.takesNumber(op)) {
            Double bound = toDouble(gate.get(KEY_GATE_VALUE));
            if (bound == null) {
                return null;
            }
            out.append(' ').append(formatBound(bound));
        }
        return out.toString();
    }

    /** The bands and option severities alone, with nothing said about when they apply. */
    public static String thresholdSummaryFa(Map<String, Object> validation) {
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
