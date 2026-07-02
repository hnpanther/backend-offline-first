package com.hnp.backendofflinefirst.service;

import com.hnp.backendofflinefirst.entity.Location;
import com.hnp.backendofflinefirst.entity.MainFunction;
import com.hnp.backendofflinefirst.entity.PlantSystem;
import com.hnp.backendofflinefirst.entity.SubFunction;
import com.hnp.backendofflinefirst.repository.LocationRepository;
import com.hnp.backendofflinefirst.repository.MainFunctionRepository;
import com.hnp.backendofflinefirst.repository.PlantSystemRepository;
import com.hnp.backendofflinefirst.repository.SubFunctionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Owns the asset placement hierarchy:
 * <pre>
 *   Location ─┬─ PlantSystem ─┬─ MainFunction ─┬─ SubFunction ── AssetEntry
 *            │               └────────────────┴─ SubFunction
 *            ├─ MainFunction ─ SubFunction
 *            └─ SubFunction
 * </pre>
 * Each {@link MainFunction} / {@link SubFunction} has exactly one <b>direct</b>
 * parent, but its full ancestor chain is denormalized onto it (so a sub-function
 * placed under a main-function also carries that main-function's {@code systemId}
 * and {@code locationId}). This service is the single place that (a) applies the
 * chosen direct parent + fills the ancestry, and (b) resolves a scope to the set
 * of sub-functions beneath it via a real tree walk.
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

    // ----------------------------------------------------------- denormalization

    /**
     * Applies the single chosen direct parent to a main function and fills its
     * ancestry. {@code parentType} is {@code system} or {@code location}.
     */
    public void applyMainFunctionParent(MainFunction mf, String parentType, Long parentId) {
        mf.setSystemId(null);
        mf.setLocationId(null);
        if (parentType == null || parentId == null) return;
        switch (parentType) {
            case SCOPE_SYSTEM -> {
                mf.setSystemId(parentId);
                plantSystemRepository.findById(parentId)
                        .ifPresent(sys -> mf.setLocationId(sys.getLocationId()));
            }
            case SCOPE_LOCATION -> mf.setLocationId(parentId);
            default -> { /* ignore unknown */ }
        }
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
                        .ifPresent(sys -> sf.setLocationId(sys.getLocationId()));
            }
            case SCOPE_LOCATION -> sf.setLocationId(parentId);
            default -> { /* ignore unknown */ }
        }
    }

    // -------------------------------------------------------------- scope walk

    /**
     * Resolves the set of sub-function IDs beneath a scope, walking the tree:
     * a location scope includes its descendant locations, their systems, main
     * functions and sub-functions; a system scope includes its main functions and
     * sub-functions; a main-function scope includes its sub-functions.
     */
    public Set<Long> subFunctionIdsInScope(String scopeType, Long scopeId) {
        if (scopeType == null || scopeId == null) return Set.of();
        return switch (scopeType) {
            case SCOPE_LOCATION -> subFunctionIdsUnderLocations(descendantLocationIds(scopeId));
            case SCOPE_SYSTEM -> subFunctionIdsUnderSystems(Set.of(scopeId));
            case SCOPE_MAIN_FUNCTION -> subFunctionIdsUnderMainFunctions(Set.of(scopeId));
            default -> Set.of();
        };
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

        return subFunctionRepository.findAll().stream()
                .filter(sf -> (sf.getLocationId() != null && locationIds.contains(sf.getLocationId()))
                        || (sf.getSystemId() != null && systemIds.contains(sf.getSystemId()))
                        || (sf.getMainFunctionId() != null && mainFunctionIds.contains(sf.getMainFunctionId())))
                .map(SubFunction::getId)
                .collect(Collectors.toSet());
    }

    private Set<Long> subFunctionIdsUnderSystems(Set<Long> systemIds) {
        Set<Long> mainFunctionIds = mainFunctionRepository.findAll().stream()
                .filter(mf -> mf.getSystemId() != null && systemIds.contains(mf.getSystemId()))
                .map(MainFunction::getId)
                .collect(Collectors.toSet());

        return subFunctionRepository.findAll().stream()
                .filter(sf -> (sf.getSystemId() != null && systemIds.contains(sf.getSystemId()))
                        || (sf.getMainFunctionId() != null && mainFunctionIds.contains(sf.getMainFunctionId())))
                .map(SubFunction::getId)
                .collect(Collectors.toSet());
    }

    private Set<Long> subFunctionIdsUnderMainFunctions(Set<Long> mainFunctionIds) {
        return subFunctionRepository.findAll().stream()
                .filter(sf -> sf.getMainFunctionId() != null && mainFunctionIds.contains(sf.getMainFunctionId()))
                .map(SubFunction::getId)
                .collect(Collectors.toSet());
    }
}
