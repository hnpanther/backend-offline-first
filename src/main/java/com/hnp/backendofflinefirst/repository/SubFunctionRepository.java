package com.hnp.backendofflinefirst.repository;

import com.hnp.backendofflinefirst.entity.SubFunction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.QueryHint;
import org.hibernate.jpa.HibernateHints;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import com.hnp.backendofflinefirst.domain.AssetUnitScopeSql;

public interface SubFunctionRepository extends JpaRepository<SubFunction, Long> {

    @Query("""
            SELECT s FROM SubFunction s
            WHERE LOWER(s.code) LIKE LOWER(CONCAT('%', :q, '%'))
                OR LOWER(s.name) LIKE LOWER(CONCAT('%', :q, '%'))
            """)
    Page<SubFunction> search(@Param("q") String q, Pageable pageable);
    Optional<SubFunction> findByCode(String code);
    Optional<SubFunction> findByCodeIgnoreCase(String code);
    Optional<SubFunction> findByTagIgnoreCase(String tag);
    Optional<SubFunction> findByName(String name);
    List<SubFunction> findAllByOrderByIdDesc();

    List<SubFunction> findByMainFunctionId(Long mainFunctionId);

    List<SubFunction> findByMainFunctionIdAndParentIdIsNull(Long mainFunctionId);

    List<SubFunction> findBySystemIdAndMainFunctionIdIsNull(Long systemId);

    List<SubFunction> findBySystemIdAndMainFunctionIdIsNullAndParentIdIsNull(Long systemId);

    List<SubFunction> findByParentId(Long parentId);
    boolean existsByParentId(Long parentId);
    boolean existsByMainFunctionId(Long mainFunctionId);
    boolean existsBySystemId(Long systemId);
    boolean existsByLocationId(Long locationId);

    @Query("SELECT sf.id FROM SubFunction sf WHERE sf.locationId IN :locationIds")
    List<Long> findIdsByLocationIdIn(@Param("locationIds") Collection<Long> locationIds);

    @Query("SELECT sf.id FROM SubFunction sf WHERE sf.systemId IN :systemIds")
    List<Long> findIdsBySystemIdIn(@Param("systemIds") Collection<Long> systemIds);

    @Query("SELECT sf.id FROM SubFunction sf WHERE sf.mainFunctionId IN :mainFunctionIds")
    List<Long> findIdsByMainFunctionIdIn(@Param("mainFunctionIds") Collection<Long> mainFunctionIds);

    @Query(value = """
            WITH RECURSIVE tree AS (
                SELECT id FROM sub_functions WHERE id IN (:rootIds)
                UNION ALL
                SELECT child.id FROM sub_functions child
                INNER JOIN tree parent ON child.parent_id = parent.id
            )
            SELECT id FROM tree
            """, nativeQuery = true)
    List<Long> findDescendantIdsIncludingRoots(@Param("rootIds") Collection<Long> rootIds);

    @Query("""
            SELECT sf.mainFunctionId AS mainFunctionId, sf.systemId AS systemId,
                   sf.locationId AS locationId, sf.parentId AS parentId
            FROM SubFunction sf WHERE sf.id = :id
            """)
    @QueryHints(@QueryHint(name = HibernateHints.HINT_FLUSH_MODE, value = "COMMIT"))
    Optional<SubFunctionAncestry> findPersistedAncestryById(@Param("id") Long id);

    /**
     * Sub functions inside the units the caller may see, filtered by the same free text the
     * unrestricted list uses.
     *
     * <p>Reuses {@code AssetUnitScopeSql.SCOPED_SUBFUNCTIONS_CTE}, which already walks
     * unit → location tree → systems → main-function trees → sub-functions and exposes each
     * level as its own set. Selecting from {@code scoped_sf} is therefore the same scope the
     * asset register uses, one level up — not a second definition of "visible" that can drift
     * from it.
     *
     * <p>Chosen by {@code MasterDataScopeService}, never called directly: a null unit set
     * means <em>unrestricted</em> and must take the plain query instead (AGENTS.md #58).
     */
    @Query(value = AssetUnitScopeSql.SCOPED_SUBFUNCTIONS_CTE + """
            SELECT s.* FROM sub_functions s
            INNER JOIN scoped_sf scoped ON scoped.id = s.id
            WHERE LOWER(s.code) LIKE LOWER(CONCAT('%', COALESCE(CAST(:q AS TEXT), ''), '%'))
               OR LOWER(s.name) LIKE LOWER(CONCAT('%', COALESCE(CAST(:q AS TEXT), ''), '%'))
            """,
            countQuery = AssetUnitScopeSql.SCOPED_SUBFUNCTIONS_CTE + """
            SELECT count(*) FROM sub_functions s
            INNER JOIN scoped_sf scoped ON scoped.id = s.id
            WHERE LOWER(s.code) LIKE LOWER(CONCAT('%', COALESCE(CAST(:q AS TEXT), ''), '%'))
               OR LOWER(s.name) LIKE LOWER(CONCAT('%', COALESCE(CAST(:q AS TEXT), ''), '%'))
            """,
            nativeQuery = true)
    Page<SubFunction> findVisibleByUnitIds(@Param("unitIds") Collection<Long> unitIds,
                                        @Param("q") String q,
                                        Pageable pageable);

    /**
     * Whether every one of these sub functions is inside the caller's units.
     *
     * <p>The object-level half of the F1 fix. {@code @PreAuthorize} proves the caller may edit
     * <em>a</em> sub function; only this proves they may edit <em>these</em> ones — and the write
     * handlers take an id from the URL, so without it the list filter alone is a curtain in
     * front of an open door.
     *
     * <p>Takes a collection so that the bulk delete asks the same question in one round trip
     * as the single delete: {@code count(*) = } the number asked about, or something in the
     * set was out of scope. Answered entirely in SQL — a bulk delete of several hundred ids
     * must not become several hundred queries.
     */
    @Query(value = AssetUnitScopeSql.SCOPED_SUBFUNCTIONS_CTE + """
            SELECT count(*) FROM sub_functions s
            INNER JOIN scoped_sf scoped ON scoped.id = s.id
            WHERE s.id IN (:ids)
            """, nativeQuery = true)
    long countVisibleByIdInAndUnitIds(@Param("unitIds") Collection<Long> unitIds,
                                      @Param("ids") Collection<Long> ids);
}
