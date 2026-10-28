package com.hnp.backendofflinefirst.service;

import com.hnp.backendofflinefirst.entity.Location;
import com.hnp.backendofflinefirst.entity.MainFunction;
import com.hnp.backendofflinefirst.entity.PlantSystem;
import com.hnp.backendofflinefirst.entity.SubFunction;
import com.hnp.backendofflinefirst.repository.LocationRepository;
import com.hnp.backendofflinefirst.repository.MainFunctionRepository;
import com.hnp.backendofflinefirst.repository.PlantSystemRepository;
import com.hnp.backendofflinefirst.repository.SubFunctionRepository;
import com.hnp.backendofflinefirst.ui.WebListSupport;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * The four hierarchy lists, narrowed to the operational units the caller may see.
 *
 * <p><strong>Why this exists.</strong> Every master-data list used to read its repository
 * directly, with no unit filter anywhere — safe only because the permissions that open those
 * pages happened to be granted exclusively to plant-wide roles. Nothing enforced that
 * coincidence: the Roles page lets an administrator tick {@code GET:/locations} onto a
 * unit-scoped role, and that user would then receive the whole plant's hierarchy with no code
 * path objecting. {@code docs/security.md} F1.
 *
 * <p><strong>Nothing changes for anyone today.</strong> {@code visibleUnitIds()} returns null
 * for a plant-wide caller, and the null branch runs the <em>same query the page ran before
 * this class existed</em> — same statement, same plan, same measured cost. The recursive CTE is
 * paid for only by a caller who actually has a scope, and today there is none.
 *
 * <p><strong>One definition of "visible", shared with the asset register.</strong> All four
 * scoped queries select from {@code AssetUnitScopeSql.SCOPED_SUBFUNCTIONS_CTE}, which walks
 * unit → location tree → systems → main-function trees → sub-functions and exposes each level
 * as its own set. A second walk written by hand would be a second answer to the same question,
 * and the two would drift.
 *
 * <p><strong>A note on what a scoped user would actually see today.</strong> That CTE begins at
 * {@code location_units}, and on the operational database that table has a single row. Until
 * locations are mapped to units, a unit-scoped user granted one of these permissions sees an
 * <em>empty</em> list rather than a partial one. That is the safe direction — it fails closed —
 * but it is a configuration gap, not a bug in this class, and it will look like one.
 */
@Service
@RequiredArgsConstructor
public class MasterDataScopeService {

    private final AssetAccessService assetAccessService;
    private final LocationRepository locationRepository;
    private final PlantSystemRepository plantSystemRepository;
    private final MainFunctionRepository mainFunctionRepository;
    private final SubFunctionRepository subFunctionRepository;

    @Transactional(readOnly = true)
    public Page<Location> findVisibleLocations(String q, Pageable pageable) {
        return scoped(q, pageable,
                locationRepository::findAll, locationRepository::search,
                (units, term) -> locationRepository.findVisibleByUnitIds(units, term, pageable));
    }

    @Transactional(readOnly = true)
    public Page<PlantSystem> findVisiblePlantSystems(String q, Pageable pageable) {
        return scoped(q, pageable,
                plantSystemRepository::findAll, plantSystemRepository::search,
                (units, term) -> plantSystemRepository.findVisibleByUnitIds(units, term, pageable));
    }

    @Transactional(readOnly = true)
    public Page<MainFunction> findVisibleMainFunctions(String q, Pageable pageable) {
        return scoped(q, pageable,
                mainFunctionRepository::findAll, mainFunctionRepository::search,
                (units, term) -> mainFunctionRepository.findVisibleByUnitIds(units, term, pageable));
    }

    @Transactional(readOnly = true)
    public Page<SubFunction> findVisibleSubFunctions(String q, Pageable pageable) {
        return scoped(q, pageable,
                subFunctionRepository::findAll, subFunctionRepository::search,
                (units, term) -> subFunctionRepository.findVisibleByUnitIds(units, term, pageable));
    }

    // =======================================================================================
    // The write paths — @PreAuthorize says "may edit one", these say "may edit THIS one"
    // =======================================================================================

    /** @throws AccessDeniedException if any of these locations is outside the caller's units */
    public void requireVisibleLocations(Collection<Long> ids) {
        require(ids, "location", locationRepository::countVisibleByIdInAndUnitIds);
    }

    public void requireVisiblePlantSystems(Collection<Long> ids) {
        require(ids, "plant system", plantSystemRepository::countVisibleByIdInAndUnitIds);
    }

    public void requireVisibleMainFunctions(Collection<Long> ids) {
        require(ids, "main function", mainFunctionRepository::countVisibleByIdInAndUnitIds);
    }

    public void requireVisibleSubFunctions(Collection<Long> ids) {
        require(ids, "sub function", subFunctionRepository::countVisibleByIdInAndUnitIds);
    }

    /**
     * All of them, or none of them.
     *
     * <p>A bulk delete that silently dropped the out-of-scope ids and deleted the rest would be
     * worse than refusing: the operator is told it worked, and finds out later that it half
     * did. Refusing the whole call is the only outcome that cannot be misread.
     *
     * <p>One query regardless of how many ids: {@code count(*)} against the scope CTE, compared
     * with how many distinct ids were asked about. A per-id loop would turn a bulk delete of
     * three hundred rows into three hundred recursive walks.
     */
    private void require(Collection<Long> ids,
                         String what,
                         BiFunction<Set<Long>, Collection<Long>, Long> countVisible) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        Set<Long> unitIds = assetAccessService.visibleUnitIds();
        if (unitIds == null) {
            return;   // unrestricted — null is not "no units", see scoped() below
        }
        Set<Long> distinct = ids.stream().filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet());
        if (distinct.isEmpty()) {
            return;
        }
        if (unitIds.isEmpty() || countVisible.apply(unitIds, distinct) != distinct.size()) {
            throw new AccessDeniedException(
                    "This " + what + " is outside the operational units you administer.");
        }
    }

    /** Single-id convenience for the per-row handlers. */
    public void requireVisibleLocation(Long id) {
        requireVisibleLocations(id == null ? Set.of() : Set.of(id));
    }

    public void requireVisiblePlantSystem(Long id) {
        requireVisiblePlantSystems(id == null ? Set.of() : Set.of(id));
    }

    public void requireVisibleMainFunction(Long id) {
        requireVisibleMainFunctions(id == null ? Set.of() : Set.of(id));
    }

    public void requireVisibleSubFunction(Long id) {
        requireVisibleSubFunctions(id == null ? Set.of() : Set.of(id));
    }

    /**
     * The three-way branch, written once.
     *
     * <p>{@code null} is <strong>unrestricted</strong> and takes the untouched path;
     * {@code empty} is "scoped to nothing", which is not the same thing and must not be bound
     * into {@code IN (:unitIds)} — PostgreSQL rejects an empty list rather than matching none.
     * Getting these two the wrong way round is AGENTS.md #58, and it shows up as an
     * administrator staring at an empty page.
     */
    private <T> Page<T> scoped(String q,
                               Pageable pageable,
                               Function<Pageable, Page<T>> unrestrictedAll,
                               BiFunction<String, Pageable, Page<T>> unrestrictedSearch,
                               BiFunction<Set<Long>, String, Page<T>> byUnits) {
        Set<Long> unitIds = assetAccessService.visibleUnitIds();
        if (unitIds == null) {
            return WebListSupport.pagedList(q, pageable, unrestrictedAll, unrestrictedSearch);
        }
        if (unitIds.isEmpty()) {
            return Page.empty(pageable);
        }
        // The scoped query carries its own COALESCE on the term, so one call covers both the
        // searched and the unsearched case — there is no second statement to keep in step.
        return byUnits.apply(unitIds, WebListSupport.searchTerm(q));
    }
}
