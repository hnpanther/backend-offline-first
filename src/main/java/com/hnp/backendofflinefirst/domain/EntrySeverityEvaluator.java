package com.hnp.backendofflinefirst.domain;

import com.hnp.backendofflinefirst.entity.FieldDefinition;
import com.hnp.backendofflinefirst.entity.LogSheetEntry;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Stamps {@code max_severity} / {@code breached_fields} onto a log-sheet entry.
 *
 * <p><strong>Why these are stored rather than derived on read.</strong> Severity is not a SQL
 * predicate: the value lives inside {@code form_data} (jsonb) and its thresholds live inside a
 * <em>different</em> row's {@code validation} json, so answering "which assets are out of
 * range" by reading meant opening every candidate sheet and evaluating in Java. That is fine
 * for a person opening a report page; it is not fine for a job polling every few minutes.
 * Computing once at write turns the question into one indexed query.
 *
 * <p><strong>The write is the only place this may be computed.</strong> Every path that can
 * change an entry's values must call {@link #apply} immediately after
 * {@code setFormData(...)} — today that is the mobile batch merge and the web fill save. A
 * path that mutates values without re-evaluating leaves a stale flag, which is worse than no
 * flag at all because it reads as authoritative. This class is deliberately stateless and
 * side-effect free apart from the two setters so it is cheap to call on every save.
 *
 * <p><strong>Definitions come from the caller, and the caller reads the sheet's snapshot.</strong>
 * Both write paths resolve definitions through {@code LogSheetFieldDefinitionsService}, which
 * prefers {@code log_sheets.field_definitions_snapshot} — the ranges in force when the sheet
 * was raised. Re-tuning a range later therefore does not silently re-judge history, and the
 * stored flag continues to mean what it meant at the time it was written.
 */
public final class EntrySeverityEvaluator {

    private EntrySeverityEvaluator() {}

    /**
     * Recomputes the entry's severity from its current {@code formData}.
     *
     * <p>Always assigns both fields, including clearing them: an entry whose values were
     * wiped (reset to draft, cleared by an operator) must lose a previous breach rather than
     * keep advertising one. {@code OK} is stored explicitly for an entry that has values and
     * breaches nothing, so "evaluated and clean" is distinguishable from "never evaluated"
     * ({@code null}) — the latter is what pre-existing rows look like before backfill.
     */
    public static void apply(LogSheetEntry entry, Collection<FieldDefinition> fieldDefinitions) {
        apply(entry, fieldDefinitions, null);
    }

    /**
     * As {@link #apply(LogSheetEntry, Collection)}, with this asset's frozen band override
     * applied on top of its class's definitions.
     *
     * <p><strong>Prefer this overload on every write path.</strong> The two-argument form judges
     * a re-banded asset against its class's range, and does it silently: the reading still
     * stores, the entry still reports, and only the threshold is wrong. The delta comes from
     * {@code log_sheets.field_overrides_snapshot} via
     * {@code LogSheetFieldDefinitionsService.frozenDeltaFor}, so it is the band that was in
     * force when the sheet was raised rather than whatever the asset carries today — the same
     * rule the definitions themselves have followed since V1.
     *
     * @param assetOverride this asset's {@code {fieldKey: {warning, danger}}} delta, or null
     */
    public static void apply(LogSheetEntry entry,
                             Collection<FieldDefinition> fieldDefinitions,
                             Map<String, Object> assetOverride) {
        if (entry == null) {
            return;
        }
        Map<String, Object> formData = entry.getFormData();
        if (formData == null || formData.isEmpty()) {
            entry.setMaxSeverity(null);
            entry.setBreachedFields(null);
            return;
        }

        // ORDER MATTERS: narrow to this entry's class FIRST, then apply the asset's override.
        //
        // A multi-class sheet's definitions may contain the same key twice — `ampere` on both
        // the class and one of its sub-classes is the ordinary case since V5. Applying the
        // override across the unfiltered list re-keys it by field key alone, so those two
        // collapse into one and the survivor may belong to the other class; the entry then
        // resolves to no definition at all and a danger reading is recorded as OK. Silently.
        Map<String, FieldDefinition> defs = definitionsFor(
                AssetFieldResolver.applyFrozenDelta(
                        definitionsForClassAsList(fieldDefinitions, entry.getClassId()),
                        assetOverride),
                entry.getClassId());
        List<String> warnings = new ArrayList<>();
        List<String> dangers = new ArrayList<>();

        for (Map.Entry<String, Object> field : formData.entrySet()) {
            FieldDefinition def = defs.get(field.getKey());
            if (def == null || def.getValidation() == null) {
                continue;
            }
            // Type-aware, not numeric-only. `evaluateNumericValue` answers OK for anything
            // that is not a number, so a select field could never be anything but fine
            // however it was configured — and this is the only place max_severity is written,
            // so that silence reached the exception report and every external poller.
            FieldValidationSeverity severity = FieldValidationSupport.evaluateValue(
                    def.getDataType(), field.getValue(), def.getValidation());
            if (severity == FieldValidationSeverity.DANGER) {
                dangers.add(field.getKey());
            } else if (severity == FieldValidationSeverity.WARNING) {
                warnings.add(field.getKey());
            }
        }

        if (!dangers.isEmpty()) {
            entry.setMaxSeverity(FieldValidationSeverity.DANGER.name());
            // Danger first, then warnings: one list, most severe first, so a reader does not
            // have to consult max_severity to know which keys caused it.
            dangers.addAll(warnings);
            entry.setBreachedFields(dangers);
        } else if (!warnings.isEmpty()) {
            entry.setMaxSeverity(FieldValidationSeverity.WARNING.name());
            entry.setBreachedFields(warnings);
        } else {
            entry.setMaxSeverity(FieldValidationSeverity.OK.name());
            entry.setBreachedFields(null);
        }
    }

    /**
     * Definitions keyed by field key, narrowed to the entry's own class.
     *
     * <p>A multi-class sheet snapshots definitions for every class it covers, and two classes
     * may legitimately share a key (say {@code pressure}) with different ranges. Without the
     * class filter the first matching definition would win and a reading could be judged
     * against another class's thresholds.
     */
    /** This entry's class's definitions, in order, before any per-asset override is applied. */
    private static List<FieldDefinition> definitionsForClassAsList(Collection<FieldDefinition> all, Long classId) {
        if (all == null) {
            return List.of();
        }
        List<FieldDefinition> out = new ArrayList<>();
        for (FieldDefinition def : all) {
            if (def == null || def.getKey() == null || def.isDeleted()) {
                continue;
            }
            if (classId != null && def.getClassId() != null && !Objects.equals(classId, def.getClassId())) {
                continue;
            }
            out.add(def);
        }
        return out;
    }

    private static Map<String, FieldDefinition> definitionsFor(Collection<FieldDefinition> all, Long classId) {
        Map<String, FieldDefinition> out = new LinkedHashMap<>();
        if (all == null) {
            return out;
        }
        for (FieldDefinition def : all) {
            if (def == null || def.getKey() == null || def.isDeleted()) {
                continue;
            }
            if (classId != null && def.getClassId() != null && !Objects.equals(classId, def.getClassId())) {
                continue;
            }
            out.putIfAbsent(def.getKey(), def);
        }
        return out;
    }
}
