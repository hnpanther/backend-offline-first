package com.hnp.backendofflinefirst.dto;

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
        boolean custom
) {}
