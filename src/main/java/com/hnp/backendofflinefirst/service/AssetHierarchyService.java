package com.hnp.backendofflinefirst.service;

import com.hnp.backendofflinefirst.entity.AssetEntry;
import com.hnp.backendofflinefirst.entity.Location;
import com.hnp.backendofflinefirst.entity.MainFunction;
import com.hnp.backendofflinefirst.entity.PlantSystem;
import com.hnp.backendofflinefirst.entity.SubFunction;
import com.hnp.backendofflinefirst.repository.AssetEntryRepository;
import com.hnp.backendofflinefirst.repository.LocationRepository;
import com.hnp.backendofflinefirst.repository.MainFunctionAncestry;
import com.hnp.backendofflinefirst.repository.MainFunctionRepository;
import com.hnp.backendofflinefirst.repository.PlantSystemAncestry;
import com.hnp.backendofflinefirst.repository.PlantSystemRepository;
import com.hnp.backendofflinefirst.repository.SubFunctionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Owns the asset placement hierarchy:
 * <pre>
 *   Location ─┬─ PlantSystem (tree via parentId) ─┬─ MainFunction (tree via parentId) ─┬─ SubFunction ── AssetEntry
 *            │                                    └──────────────────────────────────┴─ SubFunction
 *            ├─ MainFunction ─ SubFunction
 *            └─ SubFunction
 * </pre>
 * Each {@link MainFunction} / {@link SubFunction} has exactly one <b>direct</b>
 * parent, but its full ancestor chain is denormalized onto it (so a sub-function
 * placed under a main-function also carries that main-function's {@code systemId}
 * and {@code locationId}). This service is the single place that (a) applies the
 * chosen direct parent + fills the ancestry, (b) cascades ancestry to descendants on
 * save, and (c) resolves a scope to the set of sub-functions beneath it via a tree walk.
 */
@Service
@RequiredArgsConstructor
public class AssetHierarchyService {

    public static final String SCOPE_LOCATION = "location";
    public static final String SCOPE_SYSTEM = "system";
    public static final String SCOPE_MAIN_FUNCTION = "mainFunction";

    private final LocationRepository locationRepository;
    private final PlantSystemRepository plantSystemRepository;
    private final MainFunctionRepository mainFunctionRepository;
    private final SubFunctionRepository subFunctionRepository;
    private final AssetEntryRepository assetEntryRepository;

    // ----------------------------------------------------------- persisted saves

    /**
     * Persists a location. Reparenting a location does not cascade denormalized
     * fields onto systems or functions — downstream rows keep the same
     * {@code locationId}; scope walks read the tree at query time.
     */
    @Transactional
    public Location saveLocation(Location loc) {
        validateLocationParentChain(loc);
        return locationRepository.save(loc);
    }

    /** Persists a plant system and cascades location ancestry when it moves. */
    @Transactional
    public PlantSystem savePlantSystem(PlantSystem ps) {
        return savePlantSystem(ps, null, null);
    }

    /**
     * @param priorLocationId location before this save (pass when the entity is already
     *                        mutated in memory so {@code findById} would not see the old value)
     */
    @Transactional
    public PlantSystem savePlantSystem(PlantSystem ps, Long priorLocationId) {
        return savePlantSystem(ps, priorLocationId, null);
    }

    /**
     * @param priorParentId parent system before this save (same semantics as {@code priorLocationId})
     */
    @Transactional
    public PlantSystem savePlantSystem(PlantSystem ps, Long priorLocationId, Long priorParentId) {
        Long priorLocation = priorLocationId;
        Long priorParent = priorParentId;
        if (ps.getId() != null && (priorLocation == null || priorParent == null)) {
            var persisted = plantSystemRepository.findPersistedAncestryById(ps.getId());
            if (persisted.isPresent()) {
                PlantSystemAncestry existing = persisted.get();
                if (priorLocation == null) {
                    priorLocation = existing.getLocationId();
                }
                if (priorParent == null) {
                    priorParent = existing.getParentId();
                }
            }
        }

        validatePlantSystemParentChain(ps);
        applyPlantSystemAncestry(ps);
        Long resolvedLocation = ps.getLocationId();

        PlantSystem saved = plantSystemRepository.save(ps);
        boolean locationChanged = !Objects.equals(priorLocation, resolvedLocation);
        boolean parentChanged = !Objects.equals(priorParent, saved.getParentId());
        if (locationChanged || parentChanged) {
            cascadeAncestryFromSystem(saved);
            cascadeLocationToDescendantSystems(saved);
        }
        return saved;
    }

    /** Persists a main function and refreshes denormalized ancestry on descendants. */
    @Transactional
    public MainFunction saveMainFunction(MainFunction mf) {
        return saveMainFunction(mf, null, null, null);
    }

    /**
     * @param priorSystemId  system before save (when entity already mutated in memory)
     * @param priorLocationId location before save
     * @param priorParentId  parent main function before save
     */
    @Transactional
    public MainFunction saveMainFunction(MainFunction mf, Long priorSystemId,
                                         Long priorLocationId, Long priorParentId) {
        Long priorSystem = priorSystemId;
        Long priorLocation = priorLocationId;
        Long priorParent = priorParentId;
        if (mf.getId() != null && (priorSystem == null || priorLocation == null || priorParent == null)) {
            var persisted = mainFunctionRepository.findPersistedAncestryById(mf.getId());
            if (persisted.isPresent()) {
                MainFunctionAncestry existing = persisted.get();
                if (priorSystem == null) {
                    priorSystem = existing.getSystemId();
                }
                if (priorLocation == null) {
                    priorLocation = existing.getLocationId();
                }
                if (priorParent == null) {
                    priorParent = existing.getParentId();
                }
            }
        }

        validateMainFunctionParentChain(mf);
        applyMainFunctionAncestry(mf);

        MainFunction saved = mainFunctionRepository.save(mf);
        boolean ancestryChanged = !Objects.equals(priorSystem, saved.getSystemId())
                || !Objects.equals(priorLocation, saved.getLocationId())
                || !Objects.equals(priorParent, saved.getParentId());
        if (ancestryChanged) {
            cascadeAncestryToSubFunctionsUnderMainFunction(saved);
            cascadeAncestryToDescendantMainFunctions(saved);
        }
        return saved;
    }

    /** Persists a sub-function (ancestry must already be applied) and bumps linked assets for sync. */
    @Transactional
    public SubFunction saveSubFunction(SubFunction sf) {
        SubFunction saved = subFunctionRepository.save(sf);
        touchAssetsUnderSubFunction(saved.getId());
        return saved;
    }

    // ----------------------------------------------------------- denormalization

    /**
     * Root systems keep their chosen {@code locationId}; child systems inherit location
     * from the root ancestor via {@code parentId}.
     */
    public void applyPlantSystemAncestry(PlantSystem ps) {
        if (ps.getParentId() == null) {
            return;
        }
        plantSystemRepository.findById(ps.getParentId()).ifPresent(parent ->
                ps.setLocationId(resolveLocationIdForSystem(parent)));
    }

    /**
     * Applies the single chosen direct parent to a main function and fills its
     * ancestry. {@code parentType} is {@code system}, {@code location}, or {@code mainFunction}.
     */
    public void applyMainFunctionParent(MainFunction mf, String parentType, Long parentId) {
        mf.setSystemId(null);
        mf.setLocationId(null);
        mf.setParentId(null);
        if (parentType == null || parentId == null) return;
        switch (parentType) {
            case SCOPE_SYSTEM -> {
                mf.setSystemId(parentId);
                plantSystemRepository.findById(parentId)
                        .ifPresent(sys -> mf.setLocationId(resolveLocationIdForSystem(sys)));
            }
            case SCOPE_LOCATION -> mf.setLocationId(parentId);
            case SCOPE_MAIN_FUNCTION -> {
                mf.setParentId(parentId);
                mainFunctionRepository.findById(parentId).ifPresent(parent -> {
                    mf.setSystemId(parent.getSystemId());
                    mf.setLocationId(parent.getLocationId());
                });
            }
            default -> { /* ignore unknown */ }
        }
    }

    /** Child main functions inherit denormalized ancestry from their parent main function. */
    public void applyMainFunctionAncestry(MainFunction mf) {
        if (mf.getParentId() == null) {
            return;
        }
        mainFunctionRepository.findById(mf.getParentId()).ifPresent(parent -> {
            mf.setSystemId(parent.getSystemId());
            mf.setLocationId(parent.getLocationId());
        });
    }

    /**
     * Applies the single chosen direct parent to a sub function and fills its
     * ancestry. {@code parentType} is {@code mainFunction}, {@code system} or {@code location}.
     */
    public void applySubFunctionParent(SubFunction sf, String parentType, Long parentId) {
        sf.setMainFunctionId(null);
        sf.setSystemId(null);
        sf.setLocationId(null);
        if (parentType == null || parentId == null) return;
        switch (parentType) {
            case SCOPE_MAIN_FUNCTION -> {
                sf.setMainFunctionId(parentId);
                mainFunctionRepository.findById(parentId).ifPresent(mf -> {
                    sf.setSystemId(mf.getSystemId());
                    sf.setLocationId(mf.getLocationId());
                });
            }
            case SCOPE_SYSTEM -> {
                sf.setSystemId(parentId);
                plantSystemRepository.findById(parentId)
                        .ifPresent(sys -> sf.setLocationId(resolveLocationIdForSystem(sys)));
            }
            case SCOPE_LOCATION -> sf.setLocationId(parentId);
            default -> { /* ignore unknown */ }
        }
    }

    // ----------------------------------------------------------- cascade propagation

    private void cascadeAncestryFromSystem(PlantSystem system) {
        long now = System.currentTimeMillis();
        for (MainFunction mf : mainFunctionRepository.findBySystemIdAndParentIdIsNull(system.getId())) {
            mf.setLocationId(system.getLocationId());
            mf.setUpdatedAt(now);
            mainFunctionRepository.save(mf);
            cascadeAncestryToSubFunctionsUnderMainFunction(mf, now);
            cascadeAncestryToDescendantMainFunctions(mf, now);
        }
        for (SubFunction sf : subFunctionRepository.findBySystemIdAndMainFunctionIdIsNull(system.getId())) {
            applySubFunctionParent(sf, SCOPE_SYSTEM, system.getId());
            sf.setUpdatedAt(now);
            subFunctionRepository.save(sf);
            touchAssetsUnderSubFunction(sf.getId(), now);
        }
    }

    private void cascadeLocationToDescendantSystems(PlantSystem system) {
        long now = System.currentTimeMillis();
        for (PlantSystem child : plantSystemRepository.findByParentId(system.getId())) {
            child.setLocationId(system.getLocationId());
            child.setUpdatedAt(now);
            plantSystemRepository.save(child);
            cascadeAncestryFromSystem(child);
            cascadeLocationToDescendantSystems(child);
        }
    }

    private void cascadeAncestryToDescendantMainFunctions(MainFunction mf) {
        cascadeAncestryToDescendantMainFunctions(mf, System.currentTimeMillis());
    }

    private void cascadeAncestryToDescendantMainFunctions(MainFunction mf, long now) {
        for (MainFunction child : mainFunctionRepository.findByParentId(mf.getId())) {
            child.setSystemId(mf.getSystemId());
            child.setLocationId(mf.getLocationId());
            child.setUpdatedAt(now);
            mainFunctionRepository.save(child);
            cascadeAncestryToSubFunctionsUnderMainFunction(child, now);
            cascadeAncestryToDescendantMainFunctions(child, now);
        }
    }

    private void cascadeAncestryToSubFunctionsUnderMainFunction(MainFunction mf) {
        cascadeAncestryToSubFunctionsUnderMainFunction(mf, System.currentTimeMillis());
    }

    private void cascadeAncestryToSubFunctionsUnderMainFunction(MainFunction mf, long now) {
        for (SubFunction sf : subFunctionRepository.findByMainFunctionId(mf.getId())) {
            sf.setSystemId(mf.getSystemId());
            sf.setLocationId(mf.getLocationId());
            sf.setUpdatedAt(now);
            subFunctionRepository.save(sf);
            touchAssetsUnderSubFunction(sf.getId(), now);
        }
    }

    private void touchAssetsUnderSubFunction(Long subFunctionId) {
        touchAssetsUnderSubFunction(subFunctionId, System.currentTimeMillis());
    }

    private void touchAssetsUnderSubFunction(Long subFunctionId, long now) {
        for (AssetEntry asset : assetEntryRepository.findBySubFunctionId(subFunctionId)) {
            asset.setUpdatedAt(now);
            assetEntryRepository.save(asset);
        }
    }

    // -------------------------------------------------------------- scope walk

    /**
     * Resolves the set of sub-function IDs beneath a scope, walking the tree:
     * a location scope includes its descendant locations, their systems, main
     * functions and sub-functions; a system scope includes descendant systems,
     * their main functions and sub-functions; a main-function scope includes
     * descendant main functions and their sub-functions.
     */
    public Set<Long> subFunctionIdsInScope(String scopeType, Long scopeId) {
        if (scopeType == null || scopeId == null) return Set.of();
        return switch (scopeType) {
            case SCOPE_LOCATION -> subFunctionIdsUnderLocations(descendantLocationIds(scopeId));
            case SCOPE_SYSTEM -> subFunctionIdsUnderSystems(descendantSystemIds(scopeId));
            case SCOPE_MAIN_FUNCTION -> subFunctionIdsUnderMainFunctions(descendantMainFunctionIds(scopeId));
            default -> Set.of();
        };
    }

    /** A main function plus every main function transitively nested under it via {@code parentId}. */
    public Set<Long> descendantMainFunctionIds(Long rootMainFunctionId) {
        List<MainFunction> all = mainFunctionRepository.findAll();
        Set<Long> result = new HashSet<>();
        result.add(rootMainFunctionId);
        boolean added = true;
        while (added) {
            added = false;
            for (MainFunction mf : all) {
                if (mf.getParentId() != null
                        && result.contains(mf.getParentId())
                        && result.add(mf.getId())) {
                    added = true;
                }
            }
        }
        return result;
    }

    /** A system plus every system transitively nested under it via {@code parentId}. */
    public Set<Long> descendantSystemIds(Long rootSystemId) {
        List<PlantSystem> all = plantSystemRepository.findAll();
        Set<Long> result = new HashSet<>();
        result.add(rootSystemId);
        boolean added = true;
        while (added) {
            added = false;
            for (PlantSystem sys : all) {
                if (sys.getParentId() != null
                        && result.contains(sys.getParentId())
                        && result.add(sys.getId())) {
                    added = true;
                }
            }
        }
        return result;
    }

    /** A location plus every location transitively nested under it via {@code parentId}. */
    private Set<Long> descendantLocationIds(Long rootLocationId) {
        List<Location> all = locationRepository.findAll();
        Set<Long> result = new HashSet<>();
        result.add(rootLocationId);
        boolean added = true;
        while (added) {
            added = false;
            for (Location loc : all) {
                if (loc.getParentId() != null
                        && result.contains(loc.getParentId())
                        && result.add(loc.getId())) {
                    added = true;
                }
            }
        }
        return result;
    }

    private Set<Long> subFunctionIdsUnderLocations(Set<Long> locationIds) {
        Set<Long> systemIds = plantSystemRepository.findAll().stream()
                .filter(s -> s.getLocationId() != null && locationIds.contains(s.getLocationId()))
                .map(PlantSystem::getId)
                .collect(Collectors.toSet());

        Set<Long> mainFunctionIds = mainFunctionRepository.findAll().stream()
                .filter(mf -> (mf.getLocationId() != null && locationIds.contains(mf.getLocationId()))
                        || (mf.getSystemId() != null && systemIds.contains(mf.getSystemId())))
                .map(MainFunction::getId)
                .collect(Collectors.toSet());
        Set<Long> expandedMainFunctionIds = new HashSet<>();
        for (Long mfId : mainFunctionIds) {
            expandedMainFunctionIds.addAll(descendantMainFunctionIds(mfId));
        }

        return subFunctionRepository.findAll().stream()
                .filter(sf -> (sf.getLocationId() != null && locationIds.contains(sf.getLocationId()))
                        || (sf.getSystemId() != null && systemIds.contains(sf.getSystemId()))
                        || (sf.getMainFunctionId() != null && expandedMainFunctionIds.contains(sf.getMainFunctionId())))
                .map(SubFunction::getId)
                .collect(Collectors.toSet());
    }

    private Set<Long> subFunctionIdsUnderSystems(Set<Long> systemIds) {
        Set<Long> mainFunctionIds = mainFunctionRepository.findAll().stream()
                .filter(mf -> mf.getSystemId() != null && systemIds.contains(mf.getSystemId()))
                .map(MainFunction::getId)
                .collect(Collectors.toSet());
        Set<Long> expandedMainFunctionIds = new HashSet<>();
        for (Long mfId : mainFunctionIds) {
            expandedMainFunctionIds.addAll(descendantMainFunctionIds(mfId));
        }

        return subFunctionRepository.findAll().stream()
                .filter(sf -> (sf.getSystemId() != null && systemIds.contains(sf.getSystemId()))
                        || (sf.getMainFunctionId() != null && expandedMainFunctionIds.contains(sf.getMainFunctionId())))
                .map(SubFunction::getId)
                .collect(Collectors.toSet());
    }

    private Set<Long> subFunctionIdsUnderMainFunctions(Set<Long> mainFunctionIds) {
        return subFunctionRepository.findAll().stream()
                .filter(sf -> sf.getMainFunctionId() != null && mainFunctionIds.contains(sf.getMainFunctionId()))
                .map(SubFunction::getId)
                .collect(Collectors.toSet());
    }

    private Long resolveLocationIdForSystem(PlantSystem system) {
        if (system.getLocationId() != null) {
            return system.getLocationId();
        }
        if (system.getParentId() != null) {
            return plantSystemRepository.findById(system.getParentId())
                    .map(this::resolveLocationIdForSystem)
                    .orElse(null);
        }
        return null;
    }

    private void validateLocationParentChain(Location loc) {
        if (loc.getParentId() == null) {
            return;
        }
        if (loc.getId() != null && loc.getId().equals(loc.getParentId())) {
            throw new IllegalArgumentException("Location cannot be its own parent");
        }
        Set<Long> visited = new HashSet<>();
        Long current = loc.getParentId();
        while (current != null) {
            if (loc.getId() != null && loc.getId().equals(current)) {
                throw new IllegalArgumentException("Location parent chain would create a cycle");
            }
            if (!visited.add(current)) {
                break;
            }
            current = locationRepository.findById(current)
                    .map(Location::getParentId)
                    .orElse(null);
        }
    }

    private void validatePlantSystemParentChain(PlantSystem ps) {
        if (ps.getParentId() == null) {
            return;
        }
        if (ps.getId() != null && ps.getId().equals(ps.getParentId())) {
            throw new IllegalArgumentException("Plant system cannot be its own parent");
        }
        Set<Long> visited = new HashSet<>();
        Long current = ps.getParentId();
        while (current != null) {
            if (ps.getId() != null && ps.getId().equals(current)) {
                throw new IllegalArgumentException("Plant system parent chain would create a cycle");
            }
            if (!visited.add(current)) {
                break;
            }
            current = plantSystemRepository.findById(current)
                    .map(PlantSystem::getParentId)
                    .orElse(null);
        }
    }

    private void validateMainFunctionParentChain(MainFunction mf) {
        if (mf.getParentId() == null) {
            return;
        }
        if (mf.getId() != null && mf.getId().equals(mf.getParentId())) {
            throw new IllegalArgumentException("Main function cannot be its own parent");
        }
        Set<Long> visited = new HashSet<>();
        Long current = mf.getParentId();
        while (current != null) {
            if (mf.getId() != null && mf.getId().equals(current)) {
                throw new IllegalArgumentException("Main function parent chain would create a cycle");
            }
            if (!visited.add(current)) {
                break;
            }
            current = mainFunctionRepository.findById(current)
                    .map(MainFunction::getParentId)
                    .orElse(null);
        }
    }
}
