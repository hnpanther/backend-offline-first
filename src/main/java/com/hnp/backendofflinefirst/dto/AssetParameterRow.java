package com.hnp.backendofflinefirst.dto;

import java.util.List;
import java.util.Map;

/**
 * One row of the asset parameter screen, fully resolved by the controller.
 *
 * <p>The bounds are strings because blank and zero are different answers — blank means "no
 * bound", zero means zero — and because the page emits them straight into `value` attributes.
 * Formatting them here rather than in the template keeps the markup in its final shape at the
 * first paint, which is the rule AGENTS.md #126 exists for.
 *
 * @param differsFromClass true when this asset already overrides the field, so the screen can
 *                         mark it without the reader comparing eight numbers by eye
 */

public record AssetParameterRow(
        String fieldKey,
        String label,
        String unit,
        String dataType,
        String warningMin,
        String warningMax,
        String dangerMin,
        String dangerMax,
        boolean numeric,
        boolean differsFromClass,
        /**
         * True when this field exists only on the asset — FULL mode's added parameter.
         *
         * <p>It round-trips: the form posts it back so the server knows the row carries its own
         * definition rather than a re-banded copy of the class's. Without it, re-saving an
         * asset would quietly strip the label and unit off every field it had added.
         */
        boolean custom,
        /**
         * True when this asset has already dropped this class field — {@code FULL} only.
         *
         * <p><strong>A removed field must still be rendered, ticked.</strong> The editor used to
         * build its rows from the <em>resolved</em> schema, which by definition no longer
         * contains what the asset removed — so the row vanished, the next save posted a form
         * that never mentioned it, and because the form is the complete statement of the
         * override, every previously removed field <em>came back</em>. Removing a second
         * parameter restored the first.
         *
         * <p>It is also the only way back: without a ticked row there is no control to untick,
         * so a field dropped by mistake could be restored only by resetting the whole asset to
         * «از کلاس» and re-entering every band.
         */
        boolean removed,
        /**
         * True for a {@code select}/{@code multiselect} — the row offers option severities
         * rather than a numeric band.
         *
         * <p>Computed here rather than compared in the template: a Thymeleaf expression testing
         * two string values is exactly the kind of thing that renders nothing and reports
         * nothing when the value is absent (see AGENTS.md on {@code "off"} being falsy).
         */
        boolean choice,
        /** This field's vocabulary as the asset resolves it, in declaration order. */
        List<String> options,
        /**
         * The severity each option carries for <em>this asset</em>, keyed by option text.
         *
         * <p>Values are {@code "ok"}, {@code "warning"} or {@code "danger"}, so one radio group
         * per option can be rendered with the right one already checked — the table has to
         * arrive in its final shape (AGENTS.md #126), and a map the template can index by option
         * is what lets the server do that instead of a script.
         */
        Map<String, String> optionSeverity
) {

    /**
     * How many of this field's options carry an alert — the number the collapsed row shows.
     *
     * <p>Derived rather than passed: it is the same answer as {@link #optionSeverity}, and two
     * fields that must agree are two fields that can disagree.
     */
    public long markedOptionCount() {
        return optionSeverity == null ? 0
                : optionSeverity.values().stream().filter(v -> !"ok".equals(v)).count();
    }

    /** The warning options, for the hidden input the form posts back. */
    public List<String> warningOptionList() {
        return optionsWith("warning");
    }

    /** The danger options, for the hidden input the form posts back. */
    public List<String> dangerOptionList() {
        return optionsWith("danger");
    }

    /**
     * In the vocabulary's own order, not the map's.
     *
     * <p>{@code optionSeverity} is insertion-ordered today, but the order the operator and the
     * server both care about is the one the class declared, and that is {@link #options}.
     */
    private List<String> optionsWith(String severity) {
        if (options == null || optionSeverity == null) {
            return List.of();
        }
        return options.stream().filter(o -> severity.equals(optionSeverity.get(o))).toList();
    }
}
