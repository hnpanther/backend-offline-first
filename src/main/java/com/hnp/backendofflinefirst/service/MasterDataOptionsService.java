package com.hnp.backendofflinefirst.service;

import com.hnp.backendofflinefirst.dto.SelectOptionDto;
import com.hnp.backendofflinefirst.entity.AssetEntry;
import com.hnp.backendofflinefirst.entity.Location;
import com.hnp.backendofflinefirst.entity.MainFunction;
import com.hnp.backendofflinefirst.entity.OperationalUnit;
import com.hnp.backendofflinefirst.entity.PlantSystem;
import com.hnp.backendofflinefirst.entity.SubFunction;
import com.hnp.backendofflinefirst.repository.AssetEntryRepository;
import com.hnp.backendofflinefirst.repository.LocationRepository;
import com.hnp.backendofflinefirst.repository.MainFunctionRepository;
import com.hnp.backendofflinefirst.repository.OperationalUnitRepository;
import com.hnp.backendofflinefirst.repository.PlantSystemRepository;
import com.hnp.backendofflinefirst.repository.SubFunctionRepository;
import com.hnp.backendofflinefirst.util.ReferenceLabelService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Searchable option lists for admin forms — never returns unbounded master-data tables.
 */
@Service
@RequiredArgsConstructor
public class MasterDataOptionsService {

    public static final int DEFAULT_LIMIT = 30;

    private final SubFunctionRepository subFunctionRepository;
    private final MainFunctionRepository mainFunctionRepository;
    private final PlantSystemRepository plantSystemRepository;
    private final LocationRepository locationRepository;
    private final AssetEntryRepository assetEntryRepository;
    private final OperationalUnitRepository operationalUnitRepository;
    private final AssetHierarchyService assetHierarchyService;

    public List<SelectOptionDto> searchSubFunctions(String q, int limit) {
        int size = clamp(limit);
        var page = hasQuery(q)
                ? subFunctionRepository.search(q.trim(), PageRequest.of(0, size))
                : subFunctionRepository.findAll(PageRequest.of(0, size,
                        org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "id")));
        return page.getContent().stream()
                .map(sf -> SelectOptionDto.of(String.valueOf(sf.getId()), label(sf)))
                .toList();
    }

    public SelectOptionDto subFunctionOption(Long id) {
        if (id == null) return null;
        return subFunctionRepository.findById(id)
                .map(sf -> SelectOptionDto.of(String.valueOf(sf.getId()), label(sf)))
                .orElse(null);
    }

    /** Parent picker for sub-function forms: searches SF / MF / system / location. */
    public List<SelectOptionDto> searchHierarchyParents(String q, int limit) {
        int size = Math.max(5, clamp(limit) / 4);
        List<SelectOptionDto> out = new ArrayList<>();
        if (hasQuery(q)) {
            String term = q.trim();
            subFunctionRepository.search(term, PageRequest.of(0, size)).forEach(sf ->
                    out.add(SelectOptionDto.of("subFunction:" + sf.getId(), label(sf), "تابع فرعی")));
            mainFunctionRepository.search(term, PageRequest.of(0, size)).forEach(mf ->
                    out.add(SelectOptionDto.of("mainFunction:" + mf.getId(), label(mf), "تابع اصلی")));
            plantSystemRepository.search(term, PageRequest.of(0, size)).forEach(ps ->
                    out.add(SelectOptionDto.of("system:" + ps.getId(), label(ps), "سیستم واحد")));
            locationRepository.search(term, PageRequest.of(0, size)).forEach(loc ->
                    out.add(SelectOptionDto.of("location:" + loc.getId(), label(loc), "مکان")));
        } else {
            subFunctionRepository.findAll(PageRequest.of(0, size,
                            org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "id")))
                    .forEach(sf -> out.add(SelectOptionDto.of("subFunction:" + sf.getId(), label(sf), "تابع فرعی")));
            mainFunctionRepository.findAll(PageRequest.of(0, size,
                            org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "id")))
                    .forEach(mf -> out.add(SelectOptionDto.of("mainFunction:" + mf.getId(), label(mf), "تابع اصلی")));
            plantSystemRepository.findAll(PageRequest.of(0, size,
                            org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "id")))
                    .forEach(ps -> out.add(SelectOptionDto.of("system:" + ps.getId(), label(ps), "سیستم واحد")));
            locationRepository.findAll(PageRequest.of(0, size,
                            org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "id")))
                    .forEach(loc -> out.add(SelectOptionDto.of("location:" + loc.getId(), label(loc), "مکان")));
        }
        return out;
    }

    public List<SelectOptionDto> searchLocations(String q, int limit) {
        int size = clamp(limit);
        var page = hasQuery(q)
                ? locationRepository.search(q.trim(), PageRequest.of(0, size))
                : locationRepository.findAll(PageRequest.of(0, size,
                        org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "id")));
        return page.getContent().stream()
                .map(loc -> SelectOptionDto.of(String.valueOf(loc.getId()), label(loc)))
                .toList();
    }

    /** Locations under the given operational unit (including nested locations). */
    public List<SelectOptionDto> searchLocationsForUnit(String q, Long unitId, int limit) {
        Set<Long> locationIds = assetHierarchyService.locationIdsForOperationalUnit(unitId);
        if (locationIds.isEmpty()) {
            return List.of();
        }
        int size = clamp(limit);
        Page<Location> page = hasQuery(q)
                ? locationRepository.searchInIds(q.trim(), locationIds, PageRequest.of(0, size))
                : locationRepository.findByIdIn(locationIds, PageRequest.of(0, size, descId()));
        return page.getContent().stream()
                .map(loc -> SelectOptionDto.of(String.valueOf(loc.getId()), label(loc)))
                .toList();
    }

    public List<SelectOptionDto> searchPlantSystems(String q, int limit) {
        int size = clamp(limit);
        var page = hasQuery(q)
                ? plantSystemRepository.search(q.trim(), PageRequest.of(0, size))
                : plantSystemRepository.findAll(PageRequest.of(0, size,
                        org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "id")));
        return page.getContent().stream()
                .map(ps -> SelectOptionDto.of(String.valueOf(ps.getId()), label(ps)))
                .toList();
    }

    /** Plant systems whose location belongs to the given operational unit. */
    public List<SelectOptionDto> searchPlantSystemsForUnit(String q, Long unitId, int limit) {
        Set<Long> locationIds = assetHierarchyService.locationIdsForOperationalUnit(unitId);
        if (locationIds.isEmpty()) {
            return List.of();
        }
        int size = clamp(limit);
        Page<PlantSystem> page = hasQuery(q)
                ? plantSystemRepository.searchByLocationIdIn(q.trim(), locationIds, PageRequest.of(0, size))
                : plantSystemRepository.findByLocationIdIn(locationIds, PageRequest.of(0, size, descId()));
        return page.getContent().stream()
                .map(ps -> SelectOptionDto.of(String.valueOf(ps.getId()), label(ps)))
                .toList();
    }

    public List<SelectOptionDto> searchMainFunctions(String q, int limit) {
        int size = clamp(limit);
        var page = hasQuery(q)
                ? mainFunctionRepository.search(q.trim(), PageRequest.of(0, size))
                : mainFunctionRepository.findAll(PageRequest.of(0, size,
                        org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "id")));
        return page.getContent().stream()
                .map(mf -> SelectOptionDto.of(String.valueOf(mf.getId()), label(mf)))
                .toList();
    }

    /** Main functions whose location belongs to the given operational unit. */
    public List<SelectOptionDto> searchMainFunctionsForUnit(String q, Long unitId, int limit) {
        Set<Long> locationIds = assetHierarchyService.locationIdsForOperationalUnit(unitId);
        if (locationIds.isEmpty()) {
            return List.of();
        }
        int size = clamp(limit);
        Page<MainFunction> page = hasQuery(q)
                ? mainFunctionRepository.searchByLocationIdIn(q.trim(), locationIds, PageRequest.of(0, size))
                : mainFunctionRepository.findByLocationIdIn(locationIds, PageRequest.of(0, size, descId()));
        return page.getContent().stream()
                .map(mf -> SelectOptionDto.of(String.valueOf(mf.getId()), label(mf)))
                .toList();
    }

    /**
     * Active assets that fall within the given operational unit's scope — used by the
     * custom log-sheet asset picker. Only active assets are offered (inactive assets are
     * excluded from generation).
     */
    public List<SelectOptionDto> searchAssetsForUnit(String q, Long unitId, int limit) {
        if (unitId == null) {
            return List.of();
        }
        int size = clamp(limit);
        Page<AssetEntry> page = hasQuery(q)
                ? assetEntryRepository.searchVisibleByUnitIds(Set.of(unitId), q.trim(), PageRequest.of(0, size))
                : assetEntryRepository.findVisibleByUnitIds(Set.of(unitId), PageRequest.of(0, size, descId()));
        return page.getContent().stream()
                .filter(AssetEntry::isActive)
                .map(a -> SelectOptionDto.of(String.valueOf(a.getId()), assetLabel(a)))
                .toList();
    }

    /**
     * Active assets anywhere in the plant — the unrestricted counterpart of
     * {@link #searchAssetsForUnit}, used by the EXPLICIT template picker when a plant-wide
     * role deliberately selects assets outside the owning unit. Callers must gate this on
     * that authority; it applies no unit scope of its own.
     */
    public List<SelectOptionDto> searchActiveAssets(String q, int limit) {
        int size = clamp(limit);
        Page<AssetEntry> page = hasQuery(q)
                ? assetEntryRepository.search(q.trim(), PageRequest.of(0, size))
                : assetEntryRepository.findAll(PageRequest.of(0, size, descId()));
        return page.getContent().stream()
                .filter(AssetEntry::isActive)
                .map(a -> SelectOptionDto.of(String.valueOf(a.getId()), assetLabel(a)))
                .toList();
    }

    /**
     * Labels for an already-persisted EXPLICIT asset selection, so the edit form can render
     * the frozen list as chips. Order follows {@code ids}; ids that no longer resolve are
     * dropped. Inactive assets are still labelled here — they are part of the saved list and
     * hiding them would make the form silently drop them on the next save.
     */
    public List<SelectOptionDto> assetOptionsByIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        Map<Long, AssetEntry> byId = assetEntryRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(AssetEntry::getId, a -> a));
        return ids.stream()
                .map(byId::get)
                .filter(Objects::nonNull)
                .map(a -> SelectOptionDto.of(String.valueOf(a.getId()), assetLabel(a)))
                .toList();
    }

    /**
     * Searchable operational-unit list for the location form. There are ~100 units, far too many
     * for a plain multi-select, so the picker pages through this instead of dumping the table.
     */
    public List<SelectOptionDto> searchOperationalUnits(String q, int limit) {
        int size = clamp(limit);
        Page<OperationalUnit> page = hasQuery(q)
                ? operationalUnitRepository.search(q.trim(), PageRequest.of(0, size))
                : operationalUnitRepository.findAll(PageRequest.of(0, size, descId()));
        return page.getContent().stream()
                .map(u -> SelectOptionDto.of(String.valueOf(u.getId()), operationalUnitLabel(u)))
                .toList();
    }

    /**
     * Same as {@link #searchOperationalUnits}, confined to an explicit id set. Callers that are
     * unit-scoped pass their visible ids so the picker cannot enumerate other units.
     */
    public List<SelectOptionDto> searchOperationalUnitsInIds(String q, Collection<Long> ids, int limit) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        int size = clamp(limit);
        Page<OperationalUnit> page = hasQuery(q)
                ? operationalUnitRepository.searchInIds(q.trim(), ids, PageRequest.of(0, size))
                : operationalUnitRepository.findByIdIn(ids, PageRequest.of(0, size, descId()));
        return page.getContent().stream()
                .map(u -> SelectOptionDto.of(String.valueOf(u.getId()), operationalUnitLabel(u)))
                .toList();
    }

    /** Labels for an already-saved unit selection, so the edit form can render it as chips. */
    public List<SelectOptionDto> operationalUnitOptionsByIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        Map<Long, OperationalUnit> byId = operationalUnitRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(OperationalUnit::getId, u -> u));
        return ids.stream()
                .map(byId::get)
                .filter(Objects::nonNull)
                .map(u -> SelectOptionDto.of(String.valueOf(u.getId()), operationalUnitLabel(u)))
                .toList();
    }

    private static String operationalUnitLabel(OperationalUnit u) {
        return u.getName() + " (" + u.getCode() + ")";
    }

    /** Parent picker for main-function forms: system / location / mainFunction. */
    public List<SelectOptionDto> searchMainFunctionParents(String q, int limit) {
        int size = Math.max(5, clamp(limit) / 3);
        List<SelectOptionDto> out = new ArrayList<>();
        if (hasQuery(q)) {
            String term = q.trim();
            plantSystemRepository.search(term, PageRequest.of(0, size)).forEach(ps ->
                    out.add(SelectOptionDto.of("system:" + ps.getId(), label(ps), "سیستم واحد")));
            locationRepository.search(term, PageRequest.of(0, size)).forEach(loc ->
                    out.add(SelectOptionDto.of("location:" + loc.getId(), label(loc), "مکان")));
            mainFunctionRepository.search(term, PageRequest.of(0, size)).forEach(mf ->
                    out.add(SelectOptionDto.of("mainFunction:" + mf.getId(), label(mf), "تابع اصلی")));
        } else {
            plantSystemRepository.findAll(PageRequest.of(0, size, descId()))
                    .forEach(ps -> out.add(SelectOptionDto.of("system:" + ps.getId(), label(ps), "سیستم واحد")));
            locationRepository.findAll(PageRequest.of(0, size, descId()))
                    .forEach(loc -> out.add(SelectOptionDto.of("location:" + loc.getId(), label(loc), "مکان")));
            mainFunctionRepository.findAll(PageRequest.of(0, size, descId()))
                    .forEach(mf -> out.add(SelectOptionDto.of("mainFunction:" + mf.getId(), label(mf), "تابع اصلی")));
        }
        return out;
    }

    public SelectOptionDto hierarchyParentOption(String parentRef) {
        if (parentRef == null || !parentRef.contains(":")) return null;
        int i = parentRef.indexOf(':');
        String type = parentRef.substring(0, i);
        String idStr = parentRef.substring(i + 1);
        if (idStr.isBlank()) return null;
        Long id = Long.valueOf(idStr);
        return switch (type) {
            case "subFunction" -> subFunctionRepository.findById(id)
                    .map(sf -> SelectOptionDto.of(parentRef, label(sf), "تابع فرعی")).orElse(null);
            case "mainFunction" -> mainFunctionRepository.findById(id)
                    .map(mf -> SelectOptionDto.of(parentRef, label(mf), "تابع اصلی")).orElse(null);
            case "system" -> plantSystemRepository.findById(id)
                    .map(ps -> SelectOptionDto.of(parentRef, label(ps), "سیستم واحد")).orElse(null);
            case "location" -> locationRepository.findById(id)
                    .map(loc -> SelectOptionDto.of(parentRef, label(loc), "مکان")).orElse(null);
            default -> null;
        };
    }

    public SelectOptionDto locationOption(Long id) {
        if (id == null) return null;
        return locationRepository.findById(id)
                .map(loc -> SelectOptionDto.of(String.valueOf(loc.getId()), label(loc)))
                .orElse(null);
    }

    public SelectOptionDto plantSystemOption(Long id) {
        if (id == null) return null;
        return plantSystemRepository.findById(id)
                .map(ps -> SelectOptionDto.of(String.valueOf(ps.getId()), label(ps)))
                .orElse(null);
    }

    public SelectOptionDto mainFunctionOption(Long id) {
        if (id == null) return null;
        return mainFunctionRepository.findById(id)
                .map(mf -> SelectOptionDto.of(String.valueOf(mf.getId()), label(mf)))
                .orElse(null);
    }

    public SelectOptionDto scopeOption(String scopeType, Long scopeId) {
        if (scopeType == null || scopeId == null) return null;
        return switch (scopeType) {
            case "location" -> locationOption(scopeId);
            case "system" -> plantSystemOption(scopeId);
            case "mainFunction" -> mainFunctionOption(scopeId);
            default -> null;
        };
    }

    private static org.springframework.data.domain.Sort descId() {
        return org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "id");
    }

    private static String label(SubFunction sf) {
        return ReferenceLabelService.codeAndTitle(sf.getCode(), sf.getName(), sf.getId());
    }

    private static String label(MainFunction mf) {
        return ReferenceLabelService.codeAndTitle(mf.getCode(), mf.getName(), mf.getId());
    }

    private static String label(PlantSystem ps) {
        return ReferenceLabelService.codeAndTitle(ps.getCode(), ps.getName(), ps.getId());
    }

    private static String label(Location loc) {
        return ReferenceLabelService.codeAndTitle(loc.getCode(), loc.getName(), loc.getId());
    }

    private static String assetLabel(AssetEntry a) {
        return ReferenceLabelService.codeAndTitle(a.getAssetCode(), a.getAssetName(), a.getId());
    }

    private static boolean hasQuery(String q) {
        return q != null && !q.isBlank();
    }

    private static int clamp(int limit) {
        if (limit <= 0) return DEFAULT_LIMIT;
        return Math.min(limit, 100);
    }
}
