package com.hnp.backendofflinefirst.repository;

import com.hnp.backendofflinefirst.entity.MainFunction;
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

public interface MainFunctionRepository extends JpaRepository<MainFunction, Long> {

    @Query("""
            SELECT m FROM MainFunction m
            WHERE LOWER(m.code) LIKE LOWER(CONCAT('%', :q, '%'))
                OR LOWER(m.name) LIKE LOWER(CONCAT('%', :q, '%'))
            """)
    Page<MainFunction> search(@Param("q") String q, Pageable pageable);

    @Query("""
            SELECT m FROM MainFunction m
            WHERE m.locationId IN :locationIds
              AND (LOWER(m.code) LIKE LOWER(CONCAT('%', :q, '%'))
                OR LOWER(m.name) LIKE LOWER(CONCAT('%', :q, '%')))
            """)
    Page<MainFunction> searchByLocationIdIn(@Param("q") String q,
                                            @Param("locationIds") Collection<Long> locationIds,
                                            Pageable pageable);

    Page<MainFunction> findByLocationIdIn(Collection<Long> locationIds, Pageable pageable);

    Optional<MainFunction> findByCode(String code);
    Optional<MainFunction> findByCodeIgnoreCase(String code);
    Optional<MainFunction> findByName(String name);
    List<MainFunction> findAllByOrderByIdDesc();

    List<MainFunction> findBySystemId(Long systemId);

    List<MainFunction> findBySystemIdAndParentIdIsNull(Long systemId);

    List<MainFunction> findByParentId(Long parentId);
    boolean existsByParentId(Long parentId);
    boolean existsBySystemId(Long systemId);
    boolean existsByLocationId(Long locationId);

    @Query("SELECT mf.id FROM MainFunction mf WHERE mf.locationId IN :locationIds")
    List<Long> findIdsByLocationIdIn(@Param("locationIds") Collection<Long> locationIds);

    @Query("SELECT mf.id FROM MainFunction mf WHERE mf.systemId IN :systemIds")
    List<Long> findIdsBySystemIdIn(@Param("systemIds") Collection<Long> systemIds);

    @Query(value = """
            WITH RECURSIVE tree AS (
                SELECT id FROM main_functions WHERE id IN (:rootIds)
                UNION ALL
                SELECT child.id FROM main_functions child
                INNER JOIN tree parent ON child.parent_id = parent.id
            )
            SELECT id FROM tree
            """, nativeQuery = true)
    List<Long> findDescendantIdsIncludingRoots(@Param("rootIds") Collection<Long> rootIds);

    @Query("""
            SELECT mf.systemId AS systemId, mf.locationId AS locationId, mf.parentId AS parentId
            FROM MainFunction mf WHERE mf.id = :id
            """)
    @QueryHints(@QueryHint(name = HibernateHints.HINT_FLUSH_MODE, value = "COMMIT"))
    Optional<MainFunctionAncestry> findPersistedAncestryById(@Param("id") Long id);

    /**
     * Main functions inside the units the caller may see, filtered by the same free text the
     * unrestricted list uses.
     *
     * <p>Reuses {@code AssetUnitScopeSql.SCOPED_SUBFUNCTIONS_CTE}, which already walks
     * unit → location tree → systems → main-function trees → sub-functions and exposes each
     * level as its own set. Selecting from {@code main_tree} is therefore the same scope the
     * asset register uses, one level up — not a second definition of "visible" that can drift
     * from it.
     *
     * <p>Chosen by {@code MasterDataScopeService}, never called directly: a null unit set
     * means <em>unrestricted</em> and must take the plain query instead (AGENTS.md #58).
     */
    @Query(value = AssetUnitScopeSql.SCOPED_SUBFUNCTIONS_CTE + """
            SELECT m.* FROM main_functions m
            INNER JOIN main_tree scoped ON scoped.id = m.id
            WHERE LOWER(m.code) LIKE LOWER(CONCAT('%', COALESCE(CAST(:q AS TEXT), ''), '%'))
               OR LOWER(m.name) LIKE LOWER(CONCAT('%', COALESCE(CAST(:q AS TEXT), ''), '%'))
            """,
            countQuery = AssetUnitScopeSql.SCOPED_SUBFUNCTIONS_CTE + """
            SELECT count(*) FROM main_functions m
            INNER JOIN main_tree scoped ON scoped.id = m.id
            WHERE LOWER(m.code) LIKE LOWER(CONCAT('%', COALESCE(CAST(:q AS TEXT), ''), '%'))
               OR LOWER(m.name) LIKE LOWER(CONCAT('%', COALESCE(CAST(:q AS TEXT), ''), '%'))
            """,
            nativeQuery = true)
    Page<MainFunction> findVisibleByUnitIds(@Param("unitIds") Collection<Long> unitIds,
                                        @Param("q") String q,
                                        Pageable pageable);

    /**
     * Whether every one of these main functions is inside the caller's units.
     *
     * <p>The object-level half of the F1 fix. {@code @PreAuthorize} proves the caller may edit
     * <em>a</em> main function; only this proves they may edit <em>these</em> ones — and the write
     * handlers take an id from the URL, so without it the list filter alone is a curtain in
     * front of an open door.
     *
     * <p>Takes a collection so that the bulk delete asks the same question in one round trip
     * as the single delete: {@code count(*) = } the number asked about, or something in the
     * set was out of scope. Answered entirely in SQL — a bulk delete of several hundred ids
     * must not become several hundred queries.
     */
    @Query(value = AssetUnitScopeSql.SCOPED_SUBFUNCTIONS_CTE + """
            SELECT count(*) FROM main_functions m
            INNER JOIN main_tree scoped ON scoped.id = m.id
            WHERE m.id IN (:ids)
            """, nativeQuery = true)
    long countVisibleByIdInAndUnitIds(@Param("unitIds") Collection<Long> unitIds,
                                      @Param("ids") Collection<Long> ids);
}
