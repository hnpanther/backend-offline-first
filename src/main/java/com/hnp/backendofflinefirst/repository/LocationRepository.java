package com.hnp.backendofflinefirst.repository;

import com.hnp.backendofflinefirst.entity.Location;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import com.hnp.backendofflinefirst.domain.AssetUnitScopeSql;

public interface LocationRepository extends JpaRepository<Location, Long> {

    @Query("""
            SELECT l FROM Location l
            WHERE LOWER(l.code) LIKE LOWER(CONCAT('%', :q, '%'))
                OR LOWER(l.name) LIKE LOWER(CONCAT('%', :q, '%'))
            """)
    Page<Location> search(@Param("q") String q, Pageable pageable);
    Optional<Location> findByCode(String code);
    Optional<Location> findByCodeIgnoreCase(String code);
    Optional<Location> findByName(String name);
    boolean existsByParentId(Long parentId);
    List<Location> findAllByOrderByIdDesc();

    /**
     * Locations directly owned by any of these units (join table; a location may be
     * owned by several units). Callers expand downward with
     * {@link #findDescendantIdsIncludingRoots} to get the full location tree.
     */
    @Query("SELECT DISTINCT lu.locationId FROM LocationUnit lu WHERE lu.unitId IN :unitIds")
    List<Long> findIdsByUnitIdIn(@Param("unitIds") Collection<Long> unitIds);

    @Query("""
            SELECT l FROM Location l
            WHERE l.id IN :ids
              AND (LOWER(l.code) LIKE LOWER(CONCAT('%', :q, '%'))
                OR LOWER(l.name) LIKE LOWER(CONCAT('%', :q, '%')))
            """)
    Page<Location> searchInIds(@Param("q") String q, @Param("ids") Collection<Long> ids, Pageable pageable);

    Page<Location> findByIdIn(Collection<Long> ids, Pageable pageable);

    @Query(value = """
            WITH RECURSIVE tree AS (
                SELECT id FROM locations WHERE id IN (:rootIds)
                UNION ALL
                SELECT child.id FROM locations child
                INNER JOIN tree parent ON child.parent_id = parent.id
            )
            SELECT id FROM tree
            """, nativeQuery = true)
    List<Long> findDescendantIdsIncludingRoots(@Param("rootIds") Collection<Long> rootIds);

    /**
     * Locations inside the units the caller may see, filtered by the same free text the
     * unrestricted list uses.
     *
     * <p>Reuses {@code AssetUnitScopeSql.SCOPED_SUBFUNCTIONS_CTE}, which already walks
     * unit → location tree → systems → main-function trees → sub-functions and exposes each
     * level as its own set. Selecting from {@code loc_tree} is therefore the same scope the
     * asset register uses, one level up — not a second definition of "visible" that can drift
     * from it.
     *
     * <p>Chosen by {@code MasterDataScopeService}, never called directly: a null unit set
     * means <em>unrestricted</em> and must take the plain query instead (AGENTS.md #58).
     */
    @Query(value = AssetUnitScopeSql.SCOPED_SUBFUNCTIONS_CTE + """
            SELECT l.* FROM locations l
            INNER JOIN loc_tree scoped ON scoped.id = l.id
            WHERE LOWER(l.code) LIKE LOWER(CONCAT('%', COALESCE(CAST(:q AS TEXT), ''), '%'))
               OR LOWER(l.name) LIKE LOWER(CONCAT('%', COALESCE(CAST(:q AS TEXT), ''), '%'))
            """,
            countQuery = AssetUnitScopeSql.SCOPED_SUBFUNCTIONS_CTE + """
            SELECT count(*) FROM locations l
            INNER JOIN loc_tree scoped ON scoped.id = l.id
            WHERE LOWER(l.code) LIKE LOWER(CONCAT('%', COALESCE(CAST(:q AS TEXT), ''), '%'))
               OR LOWER(l.name) LIKE LOWER(CONCAT('%', COALESCE(CAST(:q AS TEXT), ''), '%'))
            """,
            nativeQuery = true)
    Page<Location> findVisibleByUnitIds(@Param("unitIds") Collection<Long> unitIds,
                                        @Param("q") String q,
                                        Pageable pageable);

    /**
     * Whether every one of these locations is inside the caller's units.
     *
     * <p>The object-level half of the F1 fix. {@code @PreAuthorize} proves the caller may edit
     * <em>a</em> location; only this proves they may edit <em>these</em> ones — and the write
     * handlers take an id from the URL, so without it the list filter alone is a curtain in
     * front of an open door.
     *
     * <p>Takes a collection so that the bulk delete asks the same question in one round trip
     * as the single delete: {@code count(*) = } the number asked about, or something in the
     * set was out of scope. Answered entirely in SQL — a bulk delete of several hundred ids
     * must not become several hundred queries.
     */
    @Query(value = AssetUnitScopeSql.SCOPED_SUBFUNCTIONS_CTE + """
            SELECT count(*) FROM locations l
            INNER JOIN loc_tree scoped ON scoped.id = l.id
            WHERE l.id IN (:ids)
            """, nativeQuery = true)
    long countVisibleByIdInAndUnitIds(@Param("unitIds") Collection<Long> unitIds,
                                      @Param("ids") Collection<Long> ids);
}
