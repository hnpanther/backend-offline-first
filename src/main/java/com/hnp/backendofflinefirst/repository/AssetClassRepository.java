package com.hnp.backendofflinefirst.repository;

import com.hnp.backendofflinefirst.entity.AssetClass;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface AssetClassRepository extends JpaRepository<AssetClass, Long> {

    @Query("SELECT a FROM AssetClass a WHERE LOWER(a.name) LIKE LOWER(CONCAT('%', :q, '%'))")
    Page<AssetClass> search(@Param("q") String q, Pageable pageable);
    Optional<AssetClass> findByName(String name);
    Optional<AssetClass> findByNameIgnoreCase(String name);
    List<AssetClass> findAllByOrderByIdDesc();

    // --- sub-classes (V5) -------------------------------------------------------------------
    //
    // Nesting is one level, enforced by trg_asset_classes_single_level. These three are all the
    // traversal the model needs; anything deeper would be a bug, not a missing query.

    /** The sub-classes of one class, in display order. Empty for a sub-class or a childless class. */
    List<AssetClass> findByParentIdOrderByNameAsc(Long parentId);

    /** Sub-classes of several parents at once — one query when a sheet spans several classes. */
    List<AssetClass> findByParentIdIn(Collection<Long> parentIds);

    /**
     * Top-level classes only, for the pickers.
     *
     * <p>The asset and template forms must not offer a flat list that mixes the two: a
     * sub-class is chosen <em>after</em> its parent, and "Pumps" and "Pumps — high pressure"
     * side by side in one dropdown is how an asset ends up on the wrong schema.
     */
    List<AssetClass> findByParentIdIsNullOrderByIdDesc();

    /** Guard for the delete path and for the one-level rule, without loading the children. */
    boolean existsByParentId(Long parentId);

    /**
     * Parent id of each class in one read, so a caller can resolve a whole sheet's chain without
     * a query per asset. Returns {@code [classId, parentId]} rows; {@code parentId} is null for
     * a top-level class.
     */
    @Query("SELECT a.id, a.parentId FROM AssetClass a WHERE a.id IN (:ids)")
    List<Object[]> findParentIdsByIdIn(@Param("ids") Collection<Long> ids);
}
