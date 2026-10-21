package com.hnp.backendofflinefirst.domain;

import com.hnp.backendofflinefirst.entity.FieldDefinition;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * The resolution chain for one asset's form schema: <strong>class → sub-class → asset</strong>.
 *
 * <p>Stateless and side-effect free, and deliberately so: it is called once per asset when a
 * sheet is generated, and again on every severity evaluation, so it must be cheap and it must
 * never mutate what it is given. <strong>Every method copies before it changes anything</strong>
 * — the {@link FieldDefinition} instances handed in come from a repository read or from a
 * snapshot shared across assets, and writing through one of them would re-band every other
 * asset in the sheet.
 *
 * <p>The design, the alternatives weighed and the measurements behind them are
 * {@code docs/roadmap.md} §20.
 *
 * <h2>The two steps</h2>
 * <ol>
 *   <li>{@link #flattenClassChain} — a sub-class inherits its parent's fields and replaces them
 *       <em>by {@code field_key}</em>. Inheritance, not copying: a field added to the parent
 *       reaches every sub-class without anyone touching them.</li>
 *   <li>{@link #applyAssetOverrides} — the asset's own layer, whose reach depends on its
 *       {@link FieldSource}.</li>
 * </ol>
 *
 * <h2>Key matching is case-insensitive</h2>
 * {@code ux_field_definitions_class_key_lower} already scopes uniqueness by {@code lower(key)},
 * so {@code Ampere} and {@code ampere} cannot both exist on one class. Matching an override
 * case-sensitively would therefore let a typo silently do nothing — the worst outcome for a
 * danger band, because the asset reads as configured and is judged on the class's range.
 */
public final class AssetFieldResolver {

    /** Override key: drop this field for this asset. Honoured only for {@link FieldSource#FULL}. */
    public static final String OVERRIDE_REMOVED = "removed";

    private AssetFieldResolver() {}

    // ---------------------------------------------------------------------------------------
    // Step 1 — class chain
    // ---------------------------------------------------------------------------------------

    /**
     * Merges a parent class's definitions with a sub-class's, the child winning per key.
     *
     * <p>The result carries the <strong>child's {@code classId}</strong> on every row, including
     * the ones inherited untouched. That is what makes the whole feature free on the client: the
     * PWA filters the sheet snapshot with {@code fd.classId === entry.classId}, and an entry of
     * a sub-class asset carries the sub-class id. An inherited field left stamped with the
     * parent's id would simply not be found, and the operator would get a form missing exactly
     * the fields nobody customised.
     *
     * @param parentFields the top-level class's definitions; may be empty
     * @param childFields  the sub-class's own definitions; may be empty
     * @param childClassId the id to stamp on every resulting row
     */
    public static List<FieldDefinition> flattenClassChain(Collection<FieldDefinition> parentFields,
                                                          Collection<FieldDefinition> childFields,
                                                          Long childClassId) {
        Map<String, FieldDefinition> byKey = new LinkedHashMap<>();
        for (FieldDefinition parent : nonDeleted(parentFields)) {
            byKey.put(normalise(parent.getKey()), restamp(parent, childClassId));
        }
        for (FieldDefinition child : nonDeleted(childFields)) {
            // put, not putIfAbsent: the child's definition replaces the parent's wholesale, so a
            // sub-class may change a band, a unit or a label without the parent's value leaking
            // back in through a half-merge.
            byKey.put(normalise(child.getKey()), restamp(child, childClassId));
        }
        return sorted(byKey.values());
    }

    // ---------------------------------------------------------------------------------------
    // Step 2 — the asset's own layer
    // ---------------------------------------------------------------------------------------

    /**
     * Applies one asset's overrides to the definitions its class resolved to.
     *
     * <p>{@link FieldSource#INHERIT} returns the input untouched (and uncopied — nothing is
     * changed, so nothing needs copying). {@link FieldSource#RANGES_ONLY} replaces only the
     * {@code warning} and {@code danger} bands and <strong>ignores everything else in the
     * override</strong>, which is what keeps a field added to the class flowing through to this
     * asset. {@link FieldSource#FULL} may also relabel, retype, reorder, add and remove.
     *
     * <p>An override naming a key the class does not have is <strong>ignored under
     * {@code RANGES_ONLY}</strong> and <strong>added under {@code FULL}</strong>. The asymmetry
     * is deliberate: under {@code RANGES_ONLY} the class owns the field list, so a leftover
     * override for a field the class has since removed must not resurrect it as a bandless
     * ghost. It is kept in storage, because the field may come back.
     */
    public static List<FieldDefinition> applyAssetOverrides(List<FieldDefinition> classFields,
                                                            FieldSource source,
                                                            Map<String, Object> overrides,
                                                            Long classId) {
        FieldSource effective = source == null ? FieldSource.INHERIT : source;
        if (effective.inheritsEverything() || overrides == null || overrides.isEmpty()) {
            return classFields == null ? List.of() : classFields;
        }

        Map<String, Map<String, Object>> byKey = normaliseOverrides(overrides);
        Map<String, FieldDefinition> out = new LinkedHashMap<>();

        for (FieldDefinition base : nonDeleted(classFields)) {
            String key = normalise(base.getKey());
            Map<String, Object> override = byKey.get(key);
            if (override == null) {
                out.put(key, base);
                continue;
            }
            if (effective.ownsFieldList() && isRemoved(override)) {
                continue;
            }
            out.put(key, effective.ownsFieldList()
                    ? withFullOverride(base, override)
                    : withBandsOnly(base, override));
        }

        if (effective.ownsFieldList()) {
            for (Map.Entry<String, Map<String, Object>> extra : byKey.entrySet()) {
                if (out.containsKey(extra.getKey()) || isRemoved(extra.getValue())) {
                    continue;
                }
                FieldDefinition added = fromOverride(extra.getKey(), extra.getValue(), classId);
                if (added != null) {
                    out.put(extra.getKey(), added);
                }
            }
        }

        return sorted(out.values());
    }

    /** Snapshot key: which {@link FieldSource} the asset was in when the sheet was raised. */
    public static final String SNAPSHOT_SOURCE = "source";
    /** Snapshot key: the override map itself, {@code {fieldKey: {...}}}. */
    public static final String SNAPSHOT_FIELDS = "fields";

    /**
     * One asset's entry in {@code log_sheets.field_overrides_snapshot}.
     *
     * <p>Shape:
     * <pre>{"source": "RANGES_ONLY", "fields": {"Ampere": {"warning": {...}}}}</pre>
     *
     * <p><strong>The source is stored, not inferred.</strong> The read side replays the override
     * through {@link #applyAssetOverrides}, which behaves differently for {@code RANGES_ONLY}
     * and {@code FULL} — the first may only re-band, the second may add and remove fields.
     * Guessing which one a stored map meant would make a removed field reappear, or an added
     * one vanish, depending on nothing more than whether a key happened to look structural.
     *
     * <p><strong>It stays a delta.</strong> Under {@code RANGES_ONLY} only the fields whose
     * bands actually differ from the class are carried — an asset re-banded on one field of
     * eleven costs one key, not eleven. Under {@code FULL} the whole override map is carried,
     * because an addition or a removal is not expressible as a difference in bands; that is the
     * price of detaching, and {@code FULL} is the rare mode.
     *
     * <p>Returns an empty map when the asset changes nothing, so the caller can leave it out
     * of the snapshot entirely rather than storing an empty object per asset.
     */
    public static Map<String, Object> snapshotEntry(List<FieldDefinition> classFields,
                                                     FieldSource source,
                                                     Map<String, Object> overrides,
                                                     Long classId) {
        FieldSource effective = source == null ? FieldSource.INHERIT : source;
        if (effective.inheritsEverything() || overrides == null || overrides.isEmpty()) {
            return Map.of();
        }

        Map<String, Object> fields = effective.ownsFieldList()
                ? new LinkedHashMap<>(overrides)
                : prunedBandOverrides(classFields, overrides, classId);
        if (fields.isEmpty()) {
            return Map.of();
        }

        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put(SNAPSHOT_SOURCE, effective.name());
        entry.put(SNAPSHOT_FIELDS, fields);
        return entry;
    }

    /**
     * Replays one asset's frozen override onto the definitions its class froze.
     *
     * <p>The read-side counterpart of {@link #snapshotEntry}, and deliberately the <em>same</em>
     * code path as live resolution: it unpacks the stored source and hands both to
     * {@link #applyAssetOverrides}. A separate read-side implementation is how a frozen sheet
     * and a live preview start disagreeing about a danger band.
     */
    @SuppressWarnings("unchecked")
    public static List<FieldDefinition> applyFrozenDelta(List<FieldDefinition> definitions,
                                                          Map<String, Object> snapshotEntry) {
        if (definitions == null || definitions.isEmpty() || snapshotEntry == null || snapshotEntry.isEmpty()) {
            return definitions == null ? List.of() : definitions;
        }
        Object fields = snapshotEntry.get(SNAPSHOT_FIELDS);
        if (!(fields instanceof Map<?, ?> map) || map.isEmpty()) {
            return definitions;
        }
        FieldSource source = FieldSource.from(asString(snapshotEntry.get(SNAPSHOT_SOURCE)));
        if (source.inheritsEverything()) {
            // A stored entry whose source says INHERIT cannot have come from this code, so the
            // safe reading is "apply the bands and change nothing structural".
            source = FieldSource.RANGES_ONLY;
        }
        Long classId = definitions.get(0).getClassId();
        return applyAssetOverrides(definitions, source, (Map<String, Object>) map, classId);
    }

    /** Band overrides with every field whose bands already match the class dropped. */
    private static Map<String, Object> prunedBandOverrides(List<FieldDefinition> classFields,
                                                            Map<String, Object> overrides,
                                                            Long classId) {
        List<FieldDefinition> resolved =
                applyAssetOverrides(classFields, FieldSource.RANGES_ONLY, overrides, classId);
        Map<String, FieldDefinition> baseByKey = new LinkedHashMap<>();
        for (FieldDefinition base : nonDeleted(classFields)) {
            baseByKey.put(normalise(base.getKey()), base);
        }

        Map<String, Object> pruned = new LinkedHashMap<>();
        for (FieldDefinition field : resolved) {
            FieldDefinition base = baseByKey.get(normalise(field.getKey()));
            if (base != null && Objects.equals(base.getValidation(), field.getValidation())) {
                continue;
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            putIfPresent(entry, FieldValidationSupport.KEY_WARNING, field.getValidation());
            putIfPresent(entry, FieldValidationSupport.KEY_DANGER, field.getValidation());
            if (!entry.isEmpty()) {
                // The resolved key, not the override's spelling: the client matches it against
                // the definition's key, and those must agree exactly.
                pruned.put(field.getKey(), entry);
            }
        }
        return pruned;
    }

    // ---------------------------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------------------------

    /**
     * A copy carrying the class's definition with the override's bands substituted.
     *
     * <p>{@code options}, {@code min}, {@code max} and anything else already in the class's
     * {@code validation} survive — only the two band keys are replaced, and only when the
     * override actually carries them. An override that mentions {@code warning} but not
     * {@code danger} leaves the class's danger band standing, which is the only reading of
     * "I set the warning range" that does not quietly widen a danger band to nothing.
     */
    private static FieldDefinition withBandsOnly(FieldDefinition base, Map<String, Object> override) {
        Map<String, Object> validation = new LinkedHashMap<>();
        if (base.getValidation() != null) {
            validation.putAll(base.getValidation());
        }
        boolean touched = false;
        for (String bandKey : new String[]{FieldValidationSupport.KEY_WARNING, FieldValidationSupport.KEY_DANGER}) {
            if (!override.containsKey(bandKey)) {
                continue;
            }
            Object band = override.get(bandKey);
            touched = true;
            if (band == null) {
                // An explicit null means "this asset has no such band", which is not the same as
                // "inherit the class's" — an operator told us this machine is unbounded here.
                validation.remove(bandKey);
            } else {
                validation.put(bandKey, band);
            }
        }
        if (!touched) {
            return base;
        }
        FieldDefinition copy = copyOf(base);
        copy.setValidation(validation.isEmpty() ? null : validation);
        return copy;
    }

    /** A copy with every supported attribute the override names replaced. {@code FULL} only. */
    private static FieldDefinition withFullOverride(FieldDefinition base, Map<String, Object> override) {
        FieldDefinition copy = copyOf(withBandsOnly(base, override));
        if (override.containsKey("label")) {
            copy.setLabel(asString(override.get("label")));
        }
        if (override.containsKey("dataType")) {
            copy.setDataType(asString(override.get("dataType")));
        }
        if (override.containsKey("unit")) {
            copy.setUnit(asString(override.get("unit")));
        }
        if (override.containsKey("required")) {
            copy.setRequired(Boolean.TRUE.equals(override.get("required")));
        }
        if (override.containsKey("sortOrder")) {
            copy.setOrder(asInteger(override.get("sortOrder")));
        }
        if (override.containsKey(FieldValidationSupport.KEY_OPTIONS)) {
            Map<String, Object> validation = copy.getValidation() == null
                    ? new LinkedHashMap<>() : new LinkedHashMap<>(copy.getValidation());
            Object options = override.get(FieldValidationSupport.KEY_OPTIONS);
            if (options == null) {
                validation.remove(FieldValidationSupport.KEY_OPTIONS);
            } else {
                validation.put(FieldValidationSupport.KEY_OPTIONS, options);
            }
            copy.setValidation(validation.isEmpty() ? null : validation);
        }
        return copy;
    }

    /**
     * A field the asset adds that its class does not have. {@code FULL} only.
     *
     * <p>Carries <strong>no id</strong>: it corresponds to no {@code field_definitions} row, and
     * inventing one would collide with a real definition the moment anything indexes by id. The
     * key is the identity here, as it is everywhere else in this schema.
     */
    private static FieldDefinition fromOverride(String key, Map<String, Object> override, Long classId) {
        if (key == null || key.isBlank()) {
            return null;
        }
        FieldDefinition fd = new FieldDefinition();
        fd.setClassId(classId);
        fd.setKey(asString(override.getOrDefault("fieldKey", key)));
        fd.setLabel(asString(override.getOrDefault("label", key)));
        fd.setDataType(asString(override.getOrDefault("dataType", "number")));
        fd.setUnit(asString(override.get("unit")));
        fd.setRequired(Boolean.TRUE.equals(override.get("required")));
        fd.setOrder(asInteger(override.get("sortOrder")));
        fd.setDeleted(false);

        Map<String, Object> validation = new LinkedHashMap<>();
        for (String k : new String[]{FieldValidationSupport.KEY_WARNING,
                                     FieldValidationSupport.KEY_DANGER,
                                     FieldValidationSupport.KEY_OPTIONS}) {
            Object v = override.get(k);
            if (v != null) {
                validation.put(k, v);
            }
        }
        fd.setValidation(validation.isEmpty() ? null : validation);
        return fd;
    }

    private static FieldDefinition copyOf(FieldDefinition source) {
        FieldDefinition copy = new FieldDefinition();
        copy.setId(source.getId());
        copy.setClassId(source.getClassId());
        copy.setKey(source.getKey());
        copy.setLabel(source.getLabel());
        copy.setDataType(source.getDataType());
        copy.setUnit(source.getUnit());
        copy.setRequired(source.isRequired());
        copy.setValidation(source.getValidation());
        copy.setOrder(source.getOrder());
        copy.setVersion(source.getVersion());
        copy.setDeleted(source.isDeleted());
        copy.setSynced(source.isSynced());
        copy.setCreatedAt(source.getCreatedAt());
        copy.setUpdatedAt(source.getUpdatedAt());
        return copy;
    }

    /** The same definition stamped with another class's id — see {@link #flattenClassChain}. */
    private static FieldDefinition restamp(FieldDefinition source, Long classId) {
        if (classId == null || Objects.equals(source.getClassId(), classId)) {
            return source;
        }
        FieldDefinition copy = copyOf(source);
        copy.setClassId(classId);
        return copy;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, Object>> normaliseOverrides(Map<String, Object> raw) {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : raw.entrySet()) {
            if (entry.getKey() == null || !(entry.getValue() instanceof Map<?, ?> value)) {
                continue;
            }
            out.put(normalise(entry.getKey()), (Map<String, Object>) value);
        }
        return out;
    }

    private static boolean isRemoved(Map<String, Object> override) {
        return Boolean.TRUE.equals(override.get(OVERRIDE_REMOVED));
    }

    private static void putIfPresent(Map<String, Object> target, String key, Map<String, Object> validation) {
        if (validation != null && validation.containsKey(key)) {
            target.put(key, validation.get(key));
        }
    }

    private static List<FieldDefinition> nonDeleted(Collection<FieldDefinition> source) {
        if (source == null) {
            return List.of();
        }
        List<FieldDefinition> out = new ArrayList<>(source.size());
        for (FieldDefinition def : source) {
            if (def != null && def.getKey() != null && !def.isDeleted()) {
                out.add(def);
            }
        }
        return out;
    }

    private static List<FieldDefinition> sorted(Collection<FieldDefinition> source) {
        List<FieldDefinition> out = new ArrayList<>(source);
        out.sort(Comparator
                .comparing((FieldDefinition f) -> f.getOrder() != null ? f.getOrder() : Integer.MAX_VALUE)
                .thenComparing(FieldDefinition::getKey, Comparator.nullsLast(String::compareTo)));
        return out;
    }

    private static String normalise(String key) {
        return key == null ? "" : key.trim().toLowerCase(Locale.ROOT);
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Integer asInteger(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Integer.valueOf(text.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }
}
