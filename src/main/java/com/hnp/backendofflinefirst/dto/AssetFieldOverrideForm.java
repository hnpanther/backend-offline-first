package com.hnp.backendofflinefirst.dto;

import com.hnp.backendofflinefirst.domain.AssetFieldResolver;
import com.hnp.backendofflinefirst.domain.FieldGateOperators;
import com.hnp.backendofflinefirst.domain.FieldSource;
import com.hnp.backendofflinefirst.domain.FieldValidationSupport;
import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * The asset parameter screen's form, as the browser posts it.
 *
 * <p>Deliberately all strings. The band inputs are {@code <input type="number">} fields that may
 * legitimately be left blank, and blank is not zero — a warning maximum of nothing means "no
 * upper bound", while a warning maximum of 0 means "anything above zero is a warning". Binding
 * these to {@code Double} would make Spring turn the first into the second, or into a binding
 * error, depending on the field's nullability. Parsing happens once, in
 * {@link #toOverrides()}, and the validation that matters happens after that in
 * {@code AssetFieldOverrideService}.
 */
@Data
public class AssetFieldOverrideForm {

    /** {@code INHERIT}, {@code RANGES_ONLY} or {@code FULL}; anything else reads as INHERIT. */
    private String fieldSource;

    private List<Row> rows = new ArrayList<>();

    @Data
    public static class Row {
        private String fieldKey;
        private String warningMin;
        private String warningMax;
        private String dangerMin;
        private String dangerMax;
        /**
         * FULL only: switch this field off for this asset.
         *
         * <p>Off, not gone. The row's bands and — for a parameter this asset added — its
         * label, unit, type and vocabulary are still written, so unticking the box restores
         * the asset's own configuration rather than falling back to the class's.
         */
        private boolean removed;
        /** FULL only, and only for a row the admin added. */
        private String label;
        private String unit;
        private String dataType;
        /**
         * The vocabulary of an added {@code select}/{@code multiselect}, one option per line.
         *
         * <p>Text rather than a list, and parsed in one place, for the same reason the class's
         * field form takes a textarea: an administrator types a vocabulary, they do not build
         * a collection. Split on the same separators {@code FieldValidationSupport.build}
         * uses, so a vocabulary means the same thing wherever it was entered.
         */
        private String selectOptions;
        /**
         * Which of this field's options are a warning / a danger <em>for this asset</em>, one
         * per line.
         *
         * <p>Not {@code FULL}-only: these are thresholds, not structure. The class owns the
         * vocabulary and keeps owning it; the asset says which of those choices mean trouble on
         * this particular machine, exactly as {@code RANGES_ONLY} re-bands a number without
         * taking over its field list.
         */
        private String warningOptions;
        private String dangerOptions;
        /**
         * <b>When</b> this asset's thresholds apply — the state gate, as three form inputs.
         *
         * <p>Not {@code FULL}-only, for the same reason the option severities are not: this says
         * when a threshold is judged, which is part of the threshold and not part of the field.
         * One machine of a fleet may be the one whose current is only meaningful while it runs,
         * and «re-band this asset» has to be able to say that without detaching the asset to
         * {@code FULL} and severing its inheritance.
         *
         * <p>{@code conditionOperand} carries both shapes, as the editor has one box: options one
         * per line, or a single number, or nothing for {@code answered} / {@code notAnswered}.
         */
        private String conditionField;
        private String conditionOp;
        private String conditionOperand;
        /**
         * True when the screen rendered the gate controls for this row.
         *
         * <p>The same marker the option severities need, and needed for the same reason with a
         * sharper edge: without it, «the admin cleared the condition» and «this row never offered
         * one» arrive identically. Reading the first as the second would silently keep an
         * inherited gate the administrator had just removed; reading the second as the first
         * would <em>drop</em> the class's gate from every row the page did not render a control
         * for, and a parameter that loses its gate is judged unconditionally — a false alarm on
         * every machine in the state the gate existed to exclude.
         */
        private boolean gateRow;
        /** True for a row the admin added that the class does not have. */
        private boolean custom;
        /**
         * True when the screen rendered an option-severity control for this row.
         *
         * <p>Posted as a hidden flag rather than inferred from the type, because the type of a
         * class field is not in this form at all — only a row the admin added carries one. It is
         * what tells {@code toOverrides} that an empty severity answer means "cleared" rather
         * than "this row has no such control".
         */
        private boolean choiceRow;
    }

    public FieldSource source() {
        return FieldSource.from(fieldSource);
    }

    /**
     * The override map, with rows that say nothing left out.
     *
     * <p>"Says nothing" means every band blank and nothing structural — which is what a field
     * the admin simply did not touch looks like. Including those would store the class's own
     * values as the asset's, and the asset would stop following the class the next time
     * somebody re-tuned it, silently.
     */
    public Map<String, Object> toOverrides() {
        FieldSource source = source();
        Map<String, Object> out = new LinkedHashMap<>();
        if (source.inheritsEverything()) {
            return out;
        }
        for (Row row : rows) {
            if (row == null || row.getFieldKey() == null || row.getFieldKey().isBlank()) {
                continue;
            }
            Map<String, Object> value = new LinkedHashMap<>();

            // Switched off, and that is ALL it means.
            //
            // This used to write `{removed: true}` and nothing else, discarding whatever the
            // row carried. The result was a one-way door: the asset's own bands, and for a
            // parameter the asset had added its label, unit, type and vocabulary, were gone
            // the moment the box was ticked — so turning it back on restored a nameless
            // numeric field banded by its class. Disabling is reversible or it is not worth
            // having, so everything else on the row is written exactly as it would be for a
            // live row and the flag rides alongside it.
            boolean switchedOff = source.ownsFieldList() && row.isRemoved();
            if (switchedOff) {
                value.put(AssetFieldResolver.OVERRIDE_REMOVED, true);
            }

            putBand(value, FieldValidationSupport.KEY_WARNING, row.getWarningMin(), row.getWarningMax());
            putBand(value, FieldValidationSupport.KEY_DANGER, row.getDangerMin(), row.getDangerMax());
            // Option severities ride beside the bands and under the same rule. An EMPTY list is
            // written, not skipped, when the row rendered a vocabulary: the form is the complete
            // statement of this asset's layer, so "the admin unticked everything" has to be
            // expressible — skipping it would read as "inherit the class's", and a severity
            // somebody deliberately cleared would come back.
            boolean offersOptionSeverity = row.isCustom()
                    // An added row declares its own type, so that is the authority. Asking the
                    // DOM instead ("was a selectOptions box submitted?") answers yes for a
                    // NUMERIC row too: the box is hidden, not removed, so it posts an empty
                    // string — and an empty severity list on a number is refused by the
                    // sanitiser, which would make adding a numeric parameter fail outright.
                    ? isSelectType(row.getDataType())
                    // A class row does not carry its type in this form at all; the screen says
                    // whether it rendered the control.
                    : row.isChoiceRow();
            putOptionSeverity(value, FieldValidationSupport.KEY_WARNING_OPTIONS,
                    row.getWarningOptions(), offersOptionSeverity);
            putOptionSeverity(value, FieldValidationSupport.KEY_DANGER_OPTIONS,
                    row.getDangerOptions(), offersOptionSeverity);
            // The gate rides beside them under the same rule, and with the same marker: the form
            // is the complete statement of this asset's layer, so a rendered-but-empty gate is a
            // cleared gate and must be written as an explicit null.
            putGate(value, row);

            if (source.ownsFieldList() && row.isCustom()) {
                // Only a row the admin added carries its own definition; an inherited row that
                // was merely re-banded must keep following the class for everything else.
                putIfPresent(value, "label", row.getLabel());
                putIfPresent(value, "unit", row.getUnit());
                putIfPresent(value, "dataType", row.getDataType());
                if (isSelectType(row.getDataType())) {
                    List<String> options = parseOptions(row.getSelectOptions());
                    if (!options.isEmpty()) {
                        value.put(FieldValidationSupport.KEY_OPTIONS, options);
                    }
                }
            }

            if (!value.isEmpty()) {
                out.put(row.getFieldKey().trim(), value);
            }
        }
        return out;
    }

    /**
     * One severity list, or nothing at all.
     *
     * <p>{@code rendered} says whether the screen actually showed this control for the row. When
     * it did, an empty answer is a real answer and is stored as an empty list; when it did not —
     * a numeric row, or a type with no vocabulary — the key is left out entirely so the asset
     * keeps inheriting whatever the class says.
     */
    private static boolean isSelectType(String dataType) {
        return "select".equals(dataType) || "multiselect".equals(dataType);
    }

    private static void putOptionSeverity(Map<String, Object> target, String key,
                                           String raw, boolean rendered) {
        if (!rendered) {
            return;
        }
        target.put(key, parseOptions(raw));
    }

    /**
     * This row's state gate, or an explicit «no gate», or nothing at all.
     *
     * <p>Three states, exactly like {@link #putBand}:
     * <ul>
     *   <li>the row did not render the control — say nothing, so the asset keeps inheriting
     *       whatever the class gates on;</li>
     *   <li>it rendered it and the parameter box is empty — the administrator <em>cleared</em>
     *       it, written as an explicit {@code null} so the resolver drops the class's gate for
     *       this machine rather than reinstating it;</li>
     *   <li>otherwise — the gate as typed, in the raw shape
     *       {@code AssetFieldOverrideService.validGate} validates. Nothing is parsed into a
     *       number here: the service owns that, and parsing in two places is how one of them
     *       ends up accepting what the other refuses.</li>
     * </ul>
     */
    private static void putGate(Map<String, Object> target, Row row) {
        if (!row.isGateRow()) {
            return;
        }
        String field = row.getConditionField();
        if (field == null || field.isBlank()) {
            target.put(FieldValidationSupport.KEY_CONDITIONS, null);
            return;
        }
        Map<String, Object> when = new LinkedHashMap<>();
        when.put(FieldValidationSupport.KEY_GATE_FIELD, field.trim());
        String op = row.getConditionOp() == null ? "" : row.getConditionOp().trim();
        when.put(FieldValidationSupport.KEY_GATE_OP, op);
        if (FieldGateOperators.takesOptions(op)) {
            when.put(FieldValidationSupport.KEY_GATE_VALUES, parseOptions(row.getConditionOperand()));
        } else if (FieldGateOperators.takesNumber(op)) {
            String operand = row.getConditionOperand();
            when.put(FieldValidationSupport.KEY_GATE_VALUE,
                    operand == null ? null : operand.trim());
        }
        Map<String, Object> condition = new LinkedHashMap<>();
        condition.put(FieldValidationSupport.KEY_WHEN, when);
        target.put(FieldValidationSupport.KEY_CONDITIONS, List.of(condition));
    }

    /**
     * One band, and the three states a pair of inputs can be in.
     *
     * <p><strong>`null` and `""` are different answers, and the difference is the whole fix.</strong>
     * Spring binds an input that was <em>not on the page</em> to {@code null} and one that was
     * there and <em>left empty</em> to {@code ""} — no {@code StringTrimmerEditor} is registered,
     * so the empty string survives. The screen renders band inputs only for a numeric row, so:
     *
     * <ul>
     *   <li>both {@code null} — this row never offered a band (a select or a text field).
     *       Say nothing, or the server would reject a band on a non-numeric field.</li>
     *   <li>both blank — the operator <em>cleared</em> it. Store an explicit {@code null},
     *       which the resolver reads as "this machine has no such band".</li>
     *   <li>otherwise — the range as typed.</li>
     * </ul>
     *
     * <p>Omitting the key for a cleared band is what the bug was: the override simply vanished,
     * resolution fell back to the class, and the class's number reappeared in the box the
     * operator had just emptied — indistinguishable, to them, from the save not working.
     *
     * <p>This is safe only because the form arrives <em>pre-filled</em> from the class, so a
     * blank box is always something somebody emptied rather than something nobody filled.
     */
    private static void putBand(Map<String, Object> target, String key, String min, String max) {
        if (min == null && max == null) {
            return;
        }
        // Null-safe on each side independently: the page always renders the pair together, but
        // a hand-built POST need not, and this must refuse rather than throw.
        boolean hasMin = min != null && !min.isBlank();
        boolean hasMax = max != null && !max.isBlank();
        if (!hasMin && !hasMax) {
            target.put(key, null);
            return;
        }
        Map<String, Object> range = new LinkedHashMap<>();
        if (hasMin) {
            range.put(FieldValidationSupport.KEY_MIN, min.trim());
        }
        if (hasMax) {
            range.put(FieldValidationSupport.KEY_MAX, max.trim());
        }
        target.put(key, range);
    }

    /**
     * One option per line, as the class's field form also reads them.
     *
     * <p>Separators match {@code FieldValidationSupport.build} exactly — newline, comma and the
     * Arabic comma — because the same vocabulary may be typed on either screen and the two must
     * not disagree about where one option ends. Blank lines are dropped and duplicates are
     * collapsed, keeping the admin's order.
     */
    static List<String> parseOptions(String raw) {
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

    private static void putIfPresent(Map<String, Object> target, String key, String value) {
        if (value != null && !value.isBlank()) {
            target.put(key, value.trim());
        }
    }
}
