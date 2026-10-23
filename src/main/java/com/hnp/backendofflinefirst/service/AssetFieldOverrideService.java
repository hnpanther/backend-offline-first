package com.hnp.backendofflinefirst.service;

import com.hnp.backendofflinefirst.domain.AssetFieldResolver;
import com.hnp.backendofflinefirst.domain.FieldDataTypes;
import com.hnp.backendofflinefirst.domain.FieldSource;
import com.hnp.backendofflinefirst.domain.FieldValidationSupport;
import com.hnp.backendofflinefirst.dto.AssetParameterRow;
import com.hnp.backendofflinefirst.entity.AssetEntry;
import com.hnp.backendofflinefirst.entity.FieldDefinition;
import com.hnp.backendofflinefirst.repository.AssetEntryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Reads and writes one asset's own parameter layer — {@code field_source} and
 * {@code field_overrides}.
 *
 * <p>Separate from {@link AssetEntryService} on purpose: that one owns identity, placement and
 * status, which the import and the NFC flows also write. This owns a band, and a band is the
 * number an operator's reading is judged against. Everything here validates before it stores,
 * because a malformed override does not fail loudly — it resolves to "no band" and a dangerous
 * reading records as OK.
 *
 * <p>The resolution chain itself is {@link AssetFieldResolver}; this class only decides what is
 * allowed to be written into it. See {@code docs/roadmap.md} §20.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssetFieldOverrideService {

    /**
     * The same rule the class field editor enforces — {@code MasterDataUniquenessValidator}.
     *
     * <p>A key the asset invents ends up in {@code form_data} beside the class's, travels to
     * the device, and comes back in a JSON object. Letting a space or a dot through here would
     * strand the reading in exactly the ways that list was arrived at one bug at a time, and
     * it would do it for one asset, which is the hardest kind to notice. Persian belongs in
     * the label.
     */
    private static final java.util.regex.Pattern VALID_FIELD_KEY =
            java.util.regex.Pattern.compile("^[A-Za-z0-9_-]+$");

    /** Keys a {@code RANGES_ONLY} override may carry. Anything else is rejected, not ignored. */
    private static final Set<String> RANGE_KEYS =
            Set.of(FieldValidationSupport.KEY_WARNING, FieldValidationSupport.KEY_DANGER);

    /** Keys a {@code FULL} override may additionally carry. */
    /**
     * Which options are the alert — a threshold, not structure.
     *
     * <p>Allowed under {@code RANGES_ONLY} alongside the numeric bands, for the same reason:
     * the class owns the <em>vocabulary</em>, the asset may say which of those choices mean
     * trouble on this particular machine. Validated as a list of options rather than as a
     * {@code {min,max}} pair, which is why it is a separate set from {@link #RANGE_KEYS}.
     */
    private static final Set<String> OPTION_SEVERITY_KEYS =
            Set.of(FieldValidationSupport.KEY_WARNING_OPTIONS,
                   FieldValidationSupport.KEY_DANGER_OPTIONS);

    private static final Set<String> STRUCTURAL_KEYS =
            Set.of("label", "dataType", "unit", "required", "sortOrder", "fieldKey",
                   FieldValidationSupport.KEY_OPTIONS, AssetFieldResolver.OVERRIDE_REMOVED);

    private final AssetEntryRepository assetEntryRepository;
    private final LogSheetFieldDefinitionsService fieldDefinitionsService;

    /**
     * What the editor opens with: the asset's class fields, already carrying whatever the asset
     * overrides today.
     *
     * <p>This is the "pre-fill from the class, then let the admin change it" behaviour — the
     * admin never starts from a blank form, because a blank form is how a field silently loses
     * its band.
     */
    @Transactional(readOnly = true)
    public List<FieldDefinition> effectiveFields(AssetEntry asset) {
        if (asset == null || asset.getClassId() == null) {
            return List.of();
        }
        List<FieldDefinition> classFields = fieldDefinitionsService
                .flattenedByClass(List.of(asset.getClassId()))
                .getOrDefault(asset.getClassId(), List.of());
        return AssetFieldResolver.applyAssetOverrides(
                classFields,
                FieldSource.from(asset.getFieldSource()),
                asset.getFieldOverrides(),
                asset.getClassId());
    }

    /** The class's fields as they stand, with no asset layer — what "reset to class" restores. */
    @Transactional(readOnly = true)
    public List<FieldDefinition> classFields(AssetEntry asset) {
        if (asset == null || asset.getClassId() == null) {
            return List.of();
        }
        return fieldDefinitionsService.flattenedByClass(List.of(asset.getClassId()))
                .getOrDefault(asset.getClassId(), List.of());
    }

    /**
     * Stores this asset's layer, after validating it against the class it belongs to.
     *
     * <p><strong>Switching to {@code INHERIT} discards the overrides</strong>, and the database
     * requires it ({@code ck_asset_entries_field_overrides_match_source}). The alternative —
     * keeping them dormant — is worse: a later reader that forgets to check {@code field_source}
     * would apply bands an administrator had explicitly turned off, which is the one failure
     * mode this whole feature exists to prevent. The UI warns before calling this.
     *
     * @throws IllegalArgumentException with a message the web layer shows as-is
     */
    @Transactional
    public AssetEntry save(Long assetId, FieldSource source, Map<String, Object> rawOverrides) {
        AssetEntry asset = assetEntryRepository.findById(assetId)
                .orElseThrow(() -> new IllegalArgumentException("Asset not found: " + assetId));

        FieldSource effective = source == null ? FieldSource.INHERIT : source;
        if (effective.inheritsEverything()) {
            asset.setFieldSource(FieldSource.INHERIT.name());
            asset.setFieldOverrides(null);
            asset.setUpdatedAt(System.currentTimeMillis());
            return assetEntryRepository.save(asset);
        }

        Map<String, Object> sanitised = sanitise(effective, rawOverrides, classFields(asset));
        if (sanitised.isEmpty()) {
            // Nothing survived validation, so the asset changes nothing — store INHERIT rather
            // than a mode with an empty map, which the database would reject anyway.
            asset.setFieldSource(FieldSource.INHERIT.name());
            asset.setFieldOverrides(null);
        } else {
            asset.setFieldSource(effective.name());
            asset.setFieldOverrides(sanitised);
        }
        asset.setUpdatedAt(System.currentTimeMillis());

        AssetEntry saved = assetEntryRepository.save(asset);
        log.info("Asset {} field source set to {} with {} override(s)",
                assetId, saved.getFieldSource(),
                saved.getFieldOverrides() == null ? 0 : saved.getFieldOverrides().size());
        return saved;
    }

    // ---------------------------------------------------------------------------------------

    /**
     * Rejects what must not be stored and drops what would do nothing.
     *
     * <p>Rejecting rather than ignoring matters for the ranges: an administrator who types a
     * minimum above a maximum has made a mistake that silently produces a band nothing can
     * satisfy, so every reading breaches. Dropping rather than rejecting matters for unknown
     * field keys under {@code RANGES_ONLY}: the class owns the list there, and a key it has
     * since removed is a leftover, not an error.
     */
    private Map<String, Object> sanitise(FieldSource source,
                                          Map<String, Object> raw,
                                          List<FieldDefinition> classFields) {
        if (raw == null || raw.isEmpty()) {
            return Map.of();
        }
        Map<String, FieldDefinition> byKey = classFields.stream()
                .filter(fd -> fd.getKey() != null)
                .collect(Collectors.toMap(
                        fd -> fd.getKey().toLowerCase(Locale.ROOT), fd -> fd, (a, b) -> a,
                        LinkedHashMap::new));

        Map<String, Object> out = new LinkedHashMap<>();
        Set<String> seenKeys = new java.util.HashSet<>();
        for (Map.Entry<String, Object> entry : raw.entrySet()) {
            String key = entry.getKey() == null ? "" : entry.getKey().trim();
            if (key.isEmpty() || !(entry.getValue() instanceof Map<?, ?> rawValue)) {
                continue;
            }
            FieldDefinition classField = byKey.get(key.toLowerCase(Locale.ROOT));
            if (classField == null && !source.ownsFieldList()) {
                // RANGES_ONLY follows the class's list; a key it no longer has is a leftover.
                continue;
            }
            if (classField == null) {
                // A field this asset adds. Validated as the identifier it is about to become.
                if (!VALID_FIELD_KEY.matcher(key).matches()) {
                    throw new IllegalArgumentException(
                            "Field key '" + key + "' may contain only English letters, digits, - and _ "
                                    + "(no spaces). Persian belongs in the label.");
                }
                if (!seenKeys.add(key.toLowerCase(Locale.ROOT))) {
                    throw new IllegalArgumentException(
                            "Field key '" + key + "' appears twice; keys are unique per asset.");
                }
            }
            Map<String, Object> value = sanitiseOne(source, key, rawValue, classField);
            if (!value.isEmpty()) {
                out.put(key, value);
            }
        }
        return out;
    }

    private Map<String, Object> sanitiseOne(FieldSource source, String key,
                                             Map<?, ?> raw, FieldDefinition classField) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : raw.entrySet()) {
            String k = String.valueOf(e.getKey());
            boolean allowed = RANGE_KEYS.contains(k)
                    || OPTION_SEVERITY_KEYS.contains(k)
                    || (source.ownsFieldList() && STRUCTURAL_KEYS.contains(k));
            if (!allowed) {
                throw new IllegalArgumentException(
                        "Unsupported override key '" + k + "' on field '" + key + "'.");
            }
            if (RANGE_KEYS.contains(k)) {
                out.put(k, e.getValue() == null ? null : validRange(key, k, e.getValue()));
            } else if (OPTION_SEVERITY_KEYS.contains(k)) {
                // null is kept as null on purpose: it is how an asset says "I have no such
                // list", which is a decision and not the same as inheriting the class's.
                out.put(k, e.getValue() == null ? null : normaliseOptions(key, e.getValue()));
            } else {
                out.put(k, e.getValue());
            }
        }

        // A field the asset adds must name a type the fill controls can render. The one list
        // is FieldDataTypes, shared with the class editor and mirrored in the PWA's union —
        // an unknown type falls through to a plain text input on the device, silently.
        if (classField == null && out.containsKey("dataType")) {
            String declared = String.valueOf(out.get("dataType"));
            if (!FieldDataTypes.isValid(declared)) {
                throw new IllegalArgumentException(
                        "Field '" + key + "' names an unknown type '" + declared + "'.");
            }
        }

        // A band may only be set on something a band means anything for. Putting one on a text
        // field stores a rule no evaluator will ever read, which reads to an administrator as
        // "this asset is bounded" while nothing bounds it.
        String dataType = out.containsKey("dataType")
                ? String.valueOf(out.get("dataType"))
                : (classField != null ? classField.getDataType() : null);
        boolean hasBand = out.keySet().stream().anyMatch(RANGE_KEYS::contains);
        if (hasBand && dataType != null && !"number".equals(dataType)) {
            throw new IllegalArgumentException(
                    "Field '" + key + "' is " + dataType + "; warning and danger ranges apply to numbers only.");
        }

        // And the mirror of it. An option severity on a number is the same mistake in the other
        // direction: a rule stored where no evaluator will read it, which reads to an
        // administrator as "this machine is watched" while nothing watches it.
        boolean hasOptionSeverity = out.keySet().stream().anyMatch(OPTION_SEVERITY_KEYS::contains);
        if (hasOptionSeverity && dataType != null && !isSelectType(dataType)) {
            throw new IllegalArgumentException(
                    "Field '" + key + "' is " + dataType
                            + "; option severities apply to select fields only.");
        }

        // A vocabulary belongs to a field that offers a choice. Stored anywhere else it is a
        // rule nothing reads, and the fill control would ignore it — so the administrator would
        // see their options accepted and never rendered.
        if (out.containsKey(FieldValidationSupport.KEY_OPTIONS)) {
            if (dataType != null && !isSelectType(dataType)) {
                throw new IllegalArgumentException(
                        "Field '" + key + "' is " + dataType + "; options apply to select fields only.");
            }
            List<String> options = normaliseOptions(key, out.get(FieldValidationSupport.KEY_OPTIONS));
            if (options.isEmpty()) {
                out.remove(FieldValidationSupport.KEY_OPTIONS);
            } else {
                out.put(FieldValidationSupport.KEY_OPTIONS, options);
            }
        }
        return out;
    }

    static boolean isSelectType(String dataType) {
        return "select".equals(dataType) || "multiselect".equals(dataType);
    }

    /**
     * An option vocabulary fit to store: non-blank strings, trimmed, duplicates collapsed,
     * the admin's order kept.
     *
     * <p>Values are compared <strong>case-sensitively</strong> everywhere they are read — the
     * submit-time allow-list in {@code FormDataValidationSupport} already does, so normalising
     * case here would make a stored reading fail its own field's validation.
     */
    private List<String> normaliseOptions(String fieldKey, Object raw) {
        if (raw == null) {
            return List.of();
        }
        if (!(raw instanceof Collection<?> collection)) {
            throw new IllegalArgumentException(
                    "The options of '" + fieldKey + "' must be a list.");
        }
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (Object item : collection) {
            if (item == null) {
                continue;
            }
            String text = String.valueOf(item).trim();
            if (!text.isEmpty()) {
                out.add(text);
            }
        }
        return List.copyOf(out);
    }

    /** One {@code {min, max}} band, with the same min ≤ max rule the class editor enforces. */
    private Map<String, Object> validRange(String fieldKey, String bandKey, Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException(
                    "The " + bandKey + " range of '" + fieldKey + "' is malformed.");
        }
        Double min = toDouble(map.get(FieldValidationSupport.KEY_MIN), fieldKey, bandKey);
        Double max = toDouble(map.get(FieldValidationSupport.KEY_MAX), fieldKey, bandKey);
        if (min != null && max != null && min > max) {
            throw new IllegalArgumentException(
                    "The " + bandKey + " minimum of '" + fieldKey + "' (" + min
                            + ") must not be greater than its maximum (" + max + ").");
        }
        if (min == null && max == null) {
            // An empty band is not "unbounded" by accident — the caller sends an explicit null
            // for that. An object with neither bound is a half-filled form.
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        if (min != null) {
            out.put(FieldValidationSupport.KEY_MIN, min);
        }
        if (max != null) {
            out.put(FieldValidationSupport.KEY_MAX, max);
        }
        return out;
    }

    private Double toDouble(Object value, String fieldKey, String bandKey) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return guardFinite(number.doubleValue(), fieldKey, bandKey);
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            return guardFinite(Double.parseDouble(text), fieldKey, bandKey);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "The " + bandKey + " range of '" + fieldKey + "' must be a number, not '" + text + "'.");
        }
    }

    /**
     * NaN and the infinities are rejected rather than stored.
     *
     * <p>They survive JSON, and every comparison against NaN is false — so a band containing one
     * silently never breaches. That is indistinguishable from a correctly configured safe asset.
     */
    private double guardFinite(double value, String fieldKey, String bandKey) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            throw new IllegalArgumentException(
                    "The " + bandKey + " range of '" + fieldKey + "' must be a finite number.");
        }
        return value;
    }

    /**
     * The editor's rows, resolved and formatted — the asset's effective values, with each row
     * flagged when it already differs from what the class alone would say.
     *
     * <p>Built here rather than in the template so the page arrives in its final shape at the
     * first paint (AGENTS.md #126), and so "is this row overridden" is answered by comparing
     * resolved validation maps rather than by a reader comparing eight numbers by eye.
     */
    @Transactional(readOnly = true)
    public List<AssetParameterRow> editorRows(AssetEntry asset) {
        // Resolve the class chain ONCE. effectiveFields() and classFields() each read it, so
        // calling both here would open the page on four queries where two will do — and the
        // two answers must come from the same read anyway, or a class edited between them
        // would make "differs from the class" compare against a class that no longer exists.
        List<FieldDefinition> classFields = classFields(asset);
        List<FieldDefinition> effective = AssetFieldResolver.applyAssetOverrides(
                classFields,
                FieldSource.from(asset == null ? null : asset.getFieldSource()),
                asset == null ? null : asset.getFieldOverrides(),
                asset == null ? null : asset.getClassId());

        Map<String, FieldDefinition> classByKey = new LinkedHashMap<>();
        for (FieldDefinition fd : classFields) {
            if (fd.getKey() != null) {
                classByKey.put(fd.getKey().toLowerCase(Locale.ROOT), fd);
            }
        }

        // The rows the asset still has, by key, so the class's own order can be walked below.
        Map<String, FieldDefinition> effectiveByKey = new LinkedHashMap<>();
        for (FieldDefinition fd : effective) {
            if (fd.getKey() != null) {
                effectiveByKey.put(fd.getKey().toLowerCase(Locale.ROOT), fd);
            }
        }

        // ---------------------------------------------------------------------------------
        // Every class field, THEN whatever the asset added — not the resolved list.
        //
        // The resolved list omits what a FULL asset removed, which is exactly what must still
        // be shown (ticked). The form is the complete statement of the override: a field
        // missing from it is a field with no override, so rendering only the survivors made
        // the next save resurrect every earlier removal. See AssetParameterRow#removed.
        // ---------------------------------------------------------------------------------
        List<AssetParameterRow> rows = new ArrayList<>(classFields.size() + effective.size());
        for (FieldDefinition classField : classFields) {
            if (classField.getKey() == null) {
                continue;
            }
            FieldDefinition resolved = effectiveByKey.get(classField.getKey().toLowerCase(Locale.ROOT));
            // Absent from the resolved list means this asset dropped it. Its bands are then the
            // class's — there is nothing asset-specific left to show, and the admin is deciding
            // whether to bring it back, not how to band it.
            rows.add(row(resolved != null ? resolved : classField, classField, resolved == null));
        }
        for (FieldDefinition fd : effective) {
            if (fd.getKey() != null && !classByKey.containsKey(fd.getKey().toLowerCase(Locale.ROOT))) {
                rows.add(row(fd, null, false));
            }
        }
        return rows;
    }

    /** One editor row. {@code classField} is null for a parameter only this asset has. */
    private AssetParameterRow row(FieldDefinition fd, FieldDefinition classField, boolean removed) {
        boolean differs = classField == null
                || !java.util.Objects.equals(classField.getValidation(), fd.getValidation());
        return new AssetParameterRow(
                fd.getKey(),
                fd.getLabel() != null ? fd.getLabel() : fd.getKey(),
                fd.getUnit(),
                fd.getDataType(),
                bound(fd, FieldValidationSupport.KEY_WARNING, true),
                bound(fd, FieldValidationSupport.KEY_WARNING, false),
                bound(fd, FieldValidationSupport.KEY_DANGER, true),
                bound(fd, FieldValidationSupport.KEY_DANGER, false),
                "number".equals(fd.getDataType()),
                // A removed field follows the class by definition, so it is not "customised".
                differs && !removed,
                classField == null,
                removed,
                isSelectType(fd.getDataType()),
                FieldValidationSupport.optionList(fd.getValidation(), FieldValidationSupport.KEY_OPTIONS),
                optionSeverities(fd.getValidation()));
    }

    /**
     * Each option mapped to the severity this asset gives it.
     *
     * <p>Every option appears, including the ones carrying nothing — the editor renders one
     * radio group per option and needs a value for each, and "absent" would leave a group with
     * nothing checked, which is a form that cannot say "normal".
     */
    private Map<String, String> optionSeverities(Map<String, Object> validation) {
        List<String> options =
                FieldValidationSupport.optionList(validation, FieldValidationSupport.KEY_OPTIONS);
        if (options.isEmpty()) {
            return Map.of();
        }
        List<String> warnings = FieldValidationSupport.optionList(
                validation, FieldValidationSupport.KEY_WARNING_OPTIONS);
        List<String> dangers = FieldValidationSupport.optionList(
                validation, FieldValidationSupport.KEY_DANGER_OPTIONS);
        Map<String, String> out = new LinkedHashMap<>();
        for (String option : options) {
            out.put(option, dangers.contains(option) ? "danger"
                    : warnings.contains(option) ? "warning" : "ok");
        }
        return out;
    }

    /**
     * One bound as the page should print it, or the empty string when there is none.
     *
     * <p>An absent bound must render as an empty input, never as 0 — "no upper limit" and "the
     * upper limit is zero" are opposite instructions to an operator.
     */
    private String bound(FieldDefinition fd, String bandKey, boolean min) {
        Double value = min
                ? FieldValidationSupport.rangeMin(fd.getValidation(), bandKey)
                : FieldValidationSupport.rangeMax(fd.getValidation(), bandKey);
        if (value == null) {
            return "";
        }
        return value == Math.rint(value) && !Double.isInfinite(value)
                ? String.valueOf((long) (double) value)
                : String.valueOf(value);
    }
}
