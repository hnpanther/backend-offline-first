package com.hnp.backendofflinefirst.domain;

/**
 * Where one asset's form schema comes from — the per-asset layer of the resolution chain
 * documented in {@code docs/roadmap.md} §20.
 *
 * <p>Stored as a string in {@code asset_entries.field_source} and constrained there
 * ({@code ck_asset_entries_field_source}), so an unknown value cannot reach a reader. The
 * database also enforces that the value and {@code field_overrides} agree
 * ({@code ck_asset_entries_field_overrides_match_source}): {@link #INHERIT} with overrides
 * still stored, or a non-inheriting source with none, are both rejected. That is deliberate —
 * an asset whose stored intent and stored data disagree would leave the resolver guessing, and
 * guessing about a danger band is not acceptable.
 */
public enum FieldSource {

    /**
     * Fields and bands both come from the class, or from the sub-class when the asset is in
     * one. The default, and what every asset created before V5 carries.
     */
    INHERIT,

    /**
     * Fields still come from the class — so a field <strong>added to the class reaches this
     * asset</strong> — while the warning and danger bands come from the asset's own overrides.
     *
     * <p>This is the common case and the one that stays correct as the plant changes.
     */
    RANGES_ONLY,

    /**
     * The asset carries its own complete field list and is deliberately detached: a field added
     * to the class does <strong>not</strong> reach it.
     *
     * <p>The detachment is the point, and it is also the hazard — an engineer who adds a field
     * to a class will see forty assets pick it up and three not. Anything that lists assets in
     * this mode must say so visibly; see {@code AssetEntryWebController}.
     */
    FULL;

    /** Null-safe parse for a value read from the database or a form. Unknown → {@link #INHERIT}. */
    public static FieldSource from(String raw) {
        if (raw == null || raw.isBlank()) {
            return INHERIT;
        }
        try {
            return valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return INHERIT;
        }
    }

    /** True when the asset contributes nothing of its own and the class answer stands as-is. */
    public boolean inheritsEverything() {
        return this == INHERIT;
    }

    /** True when the asset's overrides may add, remove or retype fields rather than only re-band them. */
    public boolean ownsFieldList() {
        return this == FULL;
    }
}
