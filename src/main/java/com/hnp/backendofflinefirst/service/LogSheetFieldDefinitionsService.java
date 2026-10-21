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
            if (!def.isDeleted() && def.getClassId() != null) {
                byClass.computeIfAbsent(def.getClassId(), k -> new ArrayList<>()).add(def);
            }
        }

        for (Long id : ids) {
            Long parentId = parentById.get(id);
            List<FieldDefinition> own = byClass.getOrDefault(id, List.of());
            List<FieldDefinition> inherited = parentId == null ? List.of() : byClass.getOrDefault(parentId, List.of());
            out.put(id, AssetFieldResolver.flattenClassChain(inherited, own, id));
        }
        return out;
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
        List<FieldDefinition> classFields = resolveForClass(sheet, entry.getClassId());
        return AssetFieldResolver.applyFrozenDelta(classFields, frozenDeltaFor(sheet, entry.getAssetId()));
    }

    /**
     * Per-asset schemas for a whole sheet, keyed by <strong>asset id</strong>.
     *
     * <p>The per-entry counterpart of {@link #groupByClass}. Class resolution is done once per
     * class and shared; only the band substitution is per asset, and that is a map lookup.
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

    public Map<Long, List<FieldDefinition>> groupByClass(LogSheet sheet, Collection<LogSheetEntry> entries) {
        Map<Long, List<FieldDefinition>> grouped = new HashMap<>();
        if (entries == null) {
            return grouped;
        }
        for (LogSheetEntry entry : entries) {
            Long classId = entry.getClassId();
            if (classId != null) {
                grouped.computeIfAbsent(classId, id -> resolveForClass(sheet, id));
            }
        }
        return grouped;
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
            if (live.isDeleted() || live.getKey() == null || live.getClassId() == null || live.getId() == null) {
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
