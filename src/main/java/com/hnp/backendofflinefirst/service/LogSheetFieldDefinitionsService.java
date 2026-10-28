package com.hnp.backendofflinefirst.service;

import com.hnp.backendofflinefirst.domain.AssetFieldResolver;
import com.hnp.backendofflinefirst.domain.FieldDefinitionSnapshot;
import com.hnp.backendofflinefirst.domain.FieldSource;
import com.hnp.backendofflinefirst.entity.AssetClass;
import com.hnp.backendofflinefirst.entity.AssetEntry;
import com.hnp.backendofflinefirst.entity.FieldDefinition;
import com.hnp.backendofflinefirst.entity.LogSheet;
import com.hnp.backendofflinefirst.entity.LogSheetEntry;
import com.hnp.backendofflinefirst.repository.AssetClassRepository;
import com.hnp.backendofflinefirst.repository.FieldDefinitionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Captures field definitions when a log sheet is generated and resolves the frozen
 * schema for bundles, web UI, and submit-time validation.
 */
@Service
@RequiredArgsConstructor
public class LogSheetFieldDefinitionsService {

    private final FieldDefinitionRepository fieldDefinitionRepository;
    private final AssetClassRepository assetClassRepository;

    public List<FieldDefinitionSnapshot> captureSnapshot(Long classId) {
        if (classId == null) {
            return List.of();
        }
        return captureSnapshot(Set.of(classId));
    }

    /**
     * Captures the frozen schema across several asset classes (custom/multi-class
     * log sheets). Fields are grouped per class so the snapshot stays readable, but
     * ordering is not relied upon at read time — {@link #resolveForClassIds} filters
     * by {@code classId} and re-sorts by display order.
     */
    public List<FieldDefinitionSnapshot> captureSnapshot(Collection<Long> classIds) {
        if (classIds == null || classIds.isEmpty()) {
            return List.of();
        }
        Set<Long> ids = classIds.stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
        if (ids.isEmpty()) {
            return List.of();
        }
        return flattenedByClass(ids).values().stream()
                .flatMap(List::stream)
                .sorted(multiClassSnapshotOrder())
                .map(FieldDefinitionSnapshot::from)
                .toList();
    }

    /**
     * The effective definitions of each requested class, with any parent's fields inherited.
     *
     * <p>Two reads regardless of how many classes are involved: one for the parent ids, one for
     * every definition across both generations. A sheet spanning twenty classes must not cost
     * forty queries at generation time.
     *
     * <p>Keyed by the <em>requested</em> class id, and every definition in the value is stamped
     * with that id — including the ones inherited untouched from a parent. The client filters
     * the snapshot by {@code classId}, so a field left carrying the parent's id would simply go
     * missing from the form. See {@link AssetFieldResolver#flattenClassChain}.
     */
    public Map<Long, List<FieldDefinition>> flattenedByClass(Collection<Long> classIds) {
        Map<Long, List<FieldDefinition>> out = new LinkedHashMap<>();
        if (classIds == null || classIds.isEmpty()) {
            return out;
        }
        Set<Long> ids = classIds.stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
        if (ids.isEmpty()) {
            return out;
        }

        RawChain chain = rawChain(ids);
        for (Long id : ids) {
            out.put(id, AssetFieldResolver.flattenClassChain(
                    chain.inheritedRowsOf(id), chain.ownRowsOf(id), id));
        }
        return out;
    }

    /**
     * One asset's parameters <strong>for a report</strong>: the live ones, plus every parameter
     * that has since been switched off — on its class, on its sub-class, or on the asset itself
     * — carried through marked {@code deleted}.
     *
     * <p><strong>Not for anything that produces a form.</strong> Generation, the bundle, the
     * fill page and severity all resolve through {@link #flattenedByClass} and
     * {@code resolveForEntry}, and must keep doing so: for them a disabled parameter is simply
     * not offered, which is the whole point of disabling it. A report is the one place where
     * the opposite is true — the readings taken while the parameter existed are still stored,
     * and a page that cannot find their definition prints a bare key with no unit and calls a
     * danger reading {@code OK}.
     *
     * <p>Costs the same two queries as the live path; the extra work is one more in-memory
     * resolution. See {@link AssetFieldResolver#resolveIncludingDisabled}.
     */
    public List<FieldDefinition> resolveForReporting(AssetEntry asset) {
        if (asset == null || asset.getClassId() == null) {
            return List.of();
        }
        Long classId = asset.getClassId();
        RawChain chain = rawChain(Set.of(classId));
        return AssetFieldResolver.resolveIncludingDisabled(
                chain.inheritedRowsOf(classId),
                chain.ownRowsOf(classId),
                classId,
                FieldSource.from(asset.getFieldSource()),
                asset.getFieldOverrides());
    }

    /**
     * The class chain as it is <em>stored</em> — disabled rows included, nothing resolved yet.
     *
     * <p>Two queries regardless of how many classes are asked about: one for the parent ids,
     * one for every definition across both generations. Shared by the live path and the
     * reporting path so they can never disagree about which rows exist; they differ only in
     * what they then do with {@code deleted}.
     */
    private RawChain rawChain(Set<Long> ids) {
        Map<Long, Long> parentById = new HashMap<>();
        for (Object[] row : assetClassRepository.findParentIdsByIdIn(ids)) {
            if (row[0] != null && row[1] != null) {
                parentById.put((Long) row[0], (Long) row[1]);
            }
        }

        Set<Long> toLoad = new java.util.LinkedHashSet<>(ids);
        toLoad.addAll(parentById.values());

        Map<Long, List<FieldDefinition>> byClass = new HashMap<>();
        for (FieldDefinition def : fieldDefinitionRepository.findByClassIdIn(toLoad)) {
            if (def.getClassId() != null) {
                byClass.computeIfAbsent(def.getClassId(), k -> new ArrayList<>()).add(def);
            }
        }
        return new RawChain(parentById, byClass);
    }

    /**
     * One read of the class chain, asked two ways.
     *
     * <p>{@code live*} drops what is switched off, which is what every form-producing path
     * wants; {@code *Rows} keeps it, which only the report does.
     */
    private record RawChain(Map<Long, Long> parentById, Map<Long, List<FieldDefinition>> byClass) {

        /** The class's own rows as stored. */
        List<FieldDefinition> ownRowsOf(Long classId) {
            return byClass.getOrDefault(classId, List.of());
        }

        /** Its parent's rows as stored, or empty when it is top-level. */
        List<FieldDefinition> inheritedRowsOf(Long classId) {
            Long parentId = parentById.get(classId);
            return parentId == null ? List.of() : byClass.getOrDefault(parentId, List.of());
        }
    }

    /**
     * The per-asset band delta frozen onto a sheet beside its field definitions.
     *
     * <p>Keyed by asset id <em>as a string</em>, because that is what a JSON object key is and
     * round-tripping it through Jackson as a {@code Long} would depend on the deserialiser's
     * mood. Assets that override nothing are left out entirely rather than mapped to an empty
     * object — on a 47-entry sheet where three pumps are re-banded, that is three keys, not
     * forty-seven.
     *
     * <p>Returns null when nothing overrides anything, so the column stays null and a sheet
     * that uses no overrides is byte-identical to one generated before V5.
     */
    public Map<String, Map<String, Object>> captureOverridesSnapshot(Collection<AssetEntry> assets) {
        if (assets == null || assets.isEmpty()) {
            return null;
        }
        List<AssetEntry> customised = assets.stream()
                .filter(Objects::nonNull)
                .filter(a -> a.getId() != null)
                .filter(a -> !FieldSource.from(a.getFieldSource()).inheritsEverything())
                .filter(a -> a.getFieldOverrides() != null && !a.getFieldOverrides().isEmpty())
                .toList();
        if (customised.isEmpty()) {
            return null;
        }

        Set<Long> classIds = customised.stream()
                .map(AssetEntry::getClassId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, List<FieldDefinition>> flattened = flattenedByClass(classIds);

        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        for (AssetEntry asset : customised) {
            List<FieldDefinition> classFields = flattened.getOrDefault(asset.getClassId(), List.of());
            Map<String, Object> delta = AssetFieldResolver.snapshotEntry(
                    classFields,
                    FieldSource.from(asset.getFieldSource()),
                    asset.getFieldOverrides(),
                    asset.getClassId());
            if (!delta.isEmpty()) {
                out.put(String.valueOf(asset.getId()), delta);
            }
        }
        return out.isEmpty() ? null : out;
    }

    /**
     * The schema one entry is filled and judged against: its class's frozen definitions, with
     * that asset's frozen bands applied on top.
     *
     * <p><strong>This is the method every write path must use.</strong> Resolving by class alone
     * judges a re-banded pump against its class's range, which is the precise defect this
     * feature exists to remove — and it fails silently, because the reading still stores and
     * still reports, just against the wrong threshold.
     */
    public List<FieldDefinition> resolveForEntry(LogSheet sheet, LogSheetEntry entry) {
        if (entry == null) {
            return List.of();
        }
        return resolveForAsset(sheet, entry.getClassId(), entry.getAssetId());
    }

    /**
     * The same resolution keyed by ids rather than by a {@link LogSheetEntry}.
     *
     * <p>For the callers that hold an {@link AssetEntry} or a bare pair of ids — the voided
     * submission viewer and the attachment kind check — and must still see the asset's own
     * fields. Resolving by class there is not merely a cosmetic difference: a {@code FULL}
     * asset that adds a photo field has no such field on its class, so an upload against it is
     * refused with "not part of this asset's class" while the fill form offers the control.
     */
    public List<FieldDefinition> resolveForAsset(LogSheet sheet, Long classId, Long assetId) {
        List<FieldDefinition> classFields = resolveForClass(sheet, classId);
        return AssetFieldResolver.applyFrozenDelta(classFields, frozenDeltaFor(sheet, assetId));
    }

    /**
     * Per-asset schemas for a whole sheet, keyed by <strong>asset id</strong>.
     *
     * <p>Class resolution is done once per class and shared; only the band substitution is per
     * asset, and that is a map lookup.
     *
     * <p>There was a {@code groupByClass} beside this, and it is deliberately gone. It was what
     * the fill and detail pages called, which is how a re-banded pump came to be rendered
     * against its class's range — and it survived the fix as a public method with no callers,
     * the shape AGENTS.md #149 is about. A map keyed by class cannot answer "what is this
     * asset's schema", and leaving one available invites the same bug back.
     */
    public Map<Long, List<FieldDefinition>> groupByAsset(LogSheet sheet, Collection<LogSheetEntry> entries) {
        Map<Long, List<FieldDefinition>> out = new LinkedHashMap<>();
        if (entries == null || entries.isEmpty()) {
            return out;
        }
        Map<Long, List<FieldDefinition>> perClass = new HashMap<>();
        for (LogSheetEntry entry : entries) {
            if (entry == null || entry.getAssetId() == null) {
                continue;
            }
            List<FieldDefinition> classFields = perClass.computeIfAbsent(
                    entry.getClassId(), id -> resolveForClass(sheet, id));
            out.put(entry.getAssetId(),
                    AssetFieldResolver.applyFrozenDelta(classFields, frozenDeltaFor(sheet, entry.getAssetId())));
        }
        return out;
    }

    /** One asset's frozen band delta, or null when the sheet or the asset has none. */
    public Map<String, Object> frozenDeltaFor(LogSheet sheet, Long assetId) {
        if (sheet == null || assetId == null || sheet.getFieldOverridesSnapshot() == null) {
            return null;
        }
        return sheet.getFieldOverridesSnapshot().get(String.valueOf(assetId));
    }

    public List<FieldDefinition> resolve(LogSheet sheet) {
        return resolveForClassIds(sheet, null);
    }

    public List<FieldDefinition> resolveForEntries(LogSheet sheet, Collection<LogSheetEntry> entries) {
        if (sheet.getFieldDefinitionsSnapshot() != null) {
            return resolveForClassIds(sheet, null);
        }
        if (entries == null || entries.isEmpty()) {
            return List.of();
        }
        Set<Long> classIds = entries.stream()
                .map(LogSheetEntry::getClassId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        return loadLiveDefinitions(classIds);
    }

    public List<FieldDefinition> resolveForClass(LogSheet sheet, Long classId) {
        if (classId == null) {
            return List.of();
        }
        return resolveForClassIds(sheet, Set.of(classId));
    }

    public List<FieldDefinition> resolveForBundle(LogSheet sheet, Set<Long> classIds) {
        if (classIds == null || classIds.isEmpty()) {
            return List.of();
        }
        return resolveForClassIds(sheet, classIds);
    }

    private List<FieldDefinition> resolveForClassIds(LogSheet sheet, Set<Long> classIds) {
        if (sheet.getFieldDefinitionsSnapshot() != null) {
            List<FieldDefinition> resolved = sheet.getFieldDefinitionsSnapshot().stream()
                    .filter(snapshot -> classIds == null || classIds.contains(snapshot.getClassId()))
                    .sorted(FieldDefinitionSnapshot.displayOrder())
                    .map(FieldDefinitionSnapshot::toFieldDefinition)
                    .collect(Collectors.toCollection(ArrayList::new));
            enrichMissingIds(resolved);
            return resolved;
        }
        return loadLiveDefinitions(classIds);
    }

    /**
     * Older snapshots omitted field ids. Mobile clients that index fields by {@code id}
     * would then keep only one definition per class — restore live ids by classId+key.
     */
    private void enrichMissingIds(List<FieldDefinition> definitions) {
        boolean needsEnrichment = definitions.stream().anyMatch(def -> def.getId() == null);
        if (!needsEnrichment) {
            return;
        }
        Set<Long> classIds = definitions.stream()
                .map(FieldDefinition::getClassId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (classIds.isEmpty()) {
            return;
        }
        Map<String, Long> idByClassAndKey = new HashMap<>();
        for (FieldDefinition live : fieldDefinitionRepository.findByClassIdIn(classIds)) {
            // `deleted` is NOT a filter here, and that is deliberate. This restores the id of a
            // field a pre-id snapshot froze, and an id belongs to the row whether the field is
            // switched on or off. Skipping disabled rows would make disabling a parameter
            // silently strip the id from every old sheet that still carries it — and the PWA
            // indexes definitions by id, so it would keep one per class and render a form
            // missing everything else. Disabling must not reach back into a raised sheet.
            if (live.getKey() == null || live.getClassId() == null || live.getId() == null) {
                continue;
            }
            idByClassAndKey.putIfAbsent(live.getClassId() + "\0" + live.getKey(), live.getId());
        }
        for (FieldDefinition def : definitions) {
            if (def.getId() != null || def.getClassId() == null || def.getKey() == null) {
                continue;
            }
            Long liveId = idByClassAndKey.get(def.getClassId() + "\0" + def.getKey());
            if (liveId != null) {
                def.setId(liveId);
            }
        }
    }

    /**
     * Live definitions for a sheet that carries no snapshot — i.e. one generated before the
     * snapshot existed. Flattened through the class chain like everything else, so a pre-V5
     * sheet whose asset was later moved to a sub-class still renders a complete form.
     */
    private List<FieldDefinition> loadLiveDefinitions(Set<Long> classIds) {
        if (classIds == null || classIds.isEmpty()) {
            return List.of();
        }
        return flattenedByClass(classIds).values().stream()
                .flatMap(List::stream)
                .sorted(fieldOrder())
                .collect(Collectors.toList());
    }

    private static Comparator<FieldDefinition> fieldOrder() {
        return Comparator
                .comparing((FieldDefinition f) -> f.getOrder() != null ? f.getOrder() : Integer.MAX_VALUE)
                .thenComparing(FieldDefinition::getId, Comparator.nullsLast(Comparator.naturalOrder()));
    }

    private static Comparator<FieldDefinition> snapshotOrder() {
        return Comparator
                .comparing((FieldDefinition f) -> f.getOrder() != null ? f.getOrder() : Integer.MAX_VALUE)
                .thenComparing(FieldDefinition::getKey, Comparator.nullsLast(String::compareTo));
    }

    /** Keeps fields grouped per class, then by display order, for multi-class snapshots. */
    private static Comparator<FieldDefinition> multiClassSnapshotOrder() {
        return Comparator
                .comparing(FieldDefinition::getClassId, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(f -> f.getOrder() != null ? f.getOrder() : Integer.MAX_VALUE)
                .thenComparing(FieldDefinition::getKey, Comparator.nullsLast(String::compareTo));
    }
}
