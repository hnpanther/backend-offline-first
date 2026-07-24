package com.hnp.backendofflinefirst.repository;

import com.hnp.backendofflinefirst.domain.AssetUnitScopeSql;
import com.hnp.backendofflinefirst.entity.AssetEntry;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface AssetEntryRepository extends JpaRepository<AssetEntry, Long> {

    @Query("""
            SELECT a FROM AssetEntry a
            WHERE LOWER(a.assetCode) LIKE LOWER(CONCAT('%', :q, '%'))
                OR LOWER(a.assetName) LIKE LOWER(CONCAT('%', :q, '%'))
                OR LOWER(COALESCE(a.nfcTagId, '')) LIKE LOWER(CONCAT('%', :q, '%'))
            """)
    Page<AssetEntry> search(@Param("q") String q, Pageable pageable);

    /** Unrestricted listing (admin). Prefer {@link #findVisibleByUnitIds} for unit-scoped users. */
    @Query("""
            SELECT a FROM AssetEntry a
            WHERE (:subFunctionIds IS NULL OR a.subFunctionId IN :subFunctionIds)
            """)
    Page<AssetEntry> findVisible(@Param("subFunctionIds") Collection<Long> subFunctionIds, Pageable pageable);

    /** Unrestricted search (admin). Prefer {@link #searchVisibleByUnitIds} for unit-scoped users. */
    @Query("""
            SELECT a FROM AssetEntry a
            WHERE (:subFunctionIds IS NULL OR a.subFunctionId IN :subFunctionIds)
              AND (LOWER(a.assetCode) LIKE LOWER(CONCAT('%', :q, '%'))
                OR LOWER(a.assetName) LIKE LOWER(CONCAT('%', :q, '%'))
                OR LOWER(COALESCE(a.nfcTagId, '')) LIKE LOWER(CONCAT('%', :q, '%')))
            """)
    Page<AssetEntry> searchVisible(@Param("subFunctionIds") Collection<Long> subFunctionIds,
                                     @Param("q") String q,
                                     Pageable pageable);

    @Query("""
            SELECT a FROM AssetEntry a
            WHERE (:subFunctionIds IS NULL OR a.subFunctionId IN :subFunctionIds)
              AND LOWER(a.assetCode) = LOWER(:assetCode)
            """)
    Optional<AssetEntry> findVisibleByAssetCodeIgnoreCase(@Param("subFunctionIds") Collection<Long> subFunctionIds,
                                                           @Param("assetCode") String assetCode);

    @Query(value = AssetUnitScopeSql.SCOPED_SUBFUNCTIONS_CTE + """
            SELECT a.* FROM asset_entries a
            INNER JOIN scoped_sf s ON a.sub_function_id = s.id
            """,
            countQuery = AssetUnitScopeSql.SCOPED_SUBFUNCTIONS_CTE + """
            SELECT count(*) FROM asset_entries a
            INNER JOIN scoped_sf s ON a.sub_function_id = s.id
            """,
            nativeQuery = true)
    Page<AssetEntry> findVisibleByUnitIds(@Param("unitIds") Collection<Long> unitIds, Pageable pageable);

    @Query(value = AssetUnitScopeSql.SCOPED_SUBFUNCTIONS_CTE + """
            SELECT a.* FROM asset_entries a
            INNER JOIN scoped_sf s ON a.sub_function_id = s.id
            WHERE LOWER(a.asset_code) LIKE LOWER(CONCAT('%', :q, '%'))
               OR LOWER(a.asset_name) LIKE LOWER(CONCAT('%', :q, '%'))
               OR LOWER(COALESCE(a.nfc_tag_id, '')) LIKE LOWER(CONCAT('%', :q, '%'))
            """,
            countQuery = AssetUnitScopeSql.SCOPED_SUBFUNCTIONS_CTE + """
            SELECT count(*) FROM asset_entries a
            INNER JOIN scoped_sf s ON a.sub_function_id = s.id
            WHERE LOWER(a.asset_code) LIKE LOWER(CONCAT('%', :q, '%'))
               OR LOWER(a.asset_name) LIKE LOWER(CONCAT('%', :q, '%'))
               OR LOWER(COALESCE(a.nfc_tag_id, '')) LIKE LOWER(CONCAT('%', :q, '%'))
            """,
            nativeQuery = true)
    Page<AssetEntry> searchVisibleByUnitIds(@Param("unitIds") Collection<Long> unitIds,
                                            @Param("q") String q,
                                            Pageable pageable);

    @Query(value = AssetUnitScopeSql.SCOPED_SUBFUNCTIONS_CTE + """
            SELECT a.* FROM asset_entries a
            INNER JOIN scoped_sf s ON a.sub_function_id = s.id
            WHERE LOWER(a.asset_code) = LOWER(:assetCode)
            LIMIT 1
            """, nativeQuery = true)
    Optional<AssetEntry> findVisibleByAssetCodeIgnoreCaseAndUnitIds(@Param("unitIds") Collection<Long> unitIds,
                                                                     @Param("assetCode") String assetCode);

    @Query(value = AssetUnitScopeSql.SCOPED_SUBFUNCTIONS_CTE + """
            SELECT a.* FROM asset_entries a
            INNER JOIN scoped_sf s ON a.sub_function_id = s.id
            WHERE a.id = :assetId
            LIMIT 1
            """, nativeQuery = true)
    Optional<AssetEntry> findVisibleByIdAndUnitIds(@Param("unitIds") Collection<Long> unitIds,
                                                     @Param("assetId") Long assetId);

    @Query(value = AssetUnitScopeSql.SCOPED_SUBFUNCTIONS_CTE + """
            SELECT EXISTS (
                SELECT 1 FROM asset_entries a
                INNER JOIN scoped_sf s ON a.sub_function_id = s.id
                WHERE a.id = :assetId
            )
            """, nativeQuery = true)
    boolean existsVisibleByIdAndUnitIds(@Param("unitIds") Collection<Long> unitIds,
                                        @Param("assetId") Long assetId);

    @Query(value = AssetUnitScopeSql.SCOPED_SUBFUNCTIONS_CTE + """
            SELECT a.* FROM asset_entries a
            INNER JOIN scoped_sf s ON a.sub_function_id = s.id
            ORDER BY a.id DESC
            """, nativeQuery = true)
    List<AssetEntry> findAllVisibleByUnitIds(@Param("unitIds") Collection<Long> unitIds);

    Optional<AssetEntry> findByNfcTagId(String nfcTagId);
    Optional<AssetEntry> findByNfcTagIdIgnoreCase(String nfcTagId);
    Optional<AssetEntry> findFirstByAssetCodeIgnoreCase(String assetCode);
    boolean existsByAssetCodeIgnoreCase(String assetCode);
    boolean existsByNfcTagIdIgnoreCase(String nfcTagId);
    boolean existsByNfcTagIdIgnoreCaseAndIdNot(String nfcTagId, Long id);
    List<AssetEntry> findByUpdatedAtGreaterThanEqual(Long since);
    List<AssetEntry> findAllByOrderByIdDesc();
    List<AssetEntry> findByClassId(Long classId);

    List<AssetEntry> findByClassIdAndSubFunctionIdIn(Long classId, Collection<Long> subFunctionIds);

    List<AssetEntry> findBySubFunctionIdIn(Collection<Long> subFunctionIds);

    List<AssetEntry> findBySubFunctionId(Long subFunctionId);
    boolean existsBySubFunctionId(Long subFunctionId);

    /**
     * Assets of {@code classId} under a location tree (same scope rules as
     * {@code AssetHierarchyService.subFunctionIdsInScope(location, …)}).
     * Hierarchy + class filter run entirely in SQL — no large ID list in Java.
     */
    @Query(value = """
            WITH RECURSIVE loc_tree AS (
                SELECT id FROM locations WHERE id = :scopeId
                UNION ALL
                SELECT l.id FROM locations l INNER JOIN loc_tree t ON l.parent_id = t.id
            ),
            systems AS (
                SELECT id FROM plant_systems WHERE location_id IN (SELECT id FROM loc_tree)
            ),
            main_roots AS (
                SELECT id FROM main_functions
                WHERE location_id IN (SELECT id FROM loc_tree)
                   OR system_id IN (SELECT id FROM systems)
            ),
            main_tree AS (
                SELECT id FROM main_functions WHERE id IN (SELECT id FROM main_roots)
                UNION ALL
                SELECT mf.id FROM main_functions mf INNER JOIN main_tree t ON mf.parent_id = t.id
            ),
            scoped_sf AS (
                SELECT id FROM sub_functions
                WHERE location_id IN (SELECT id FROM loc_tree)
                   OR system_id IN (SELECT id FROM systems)
                   OR main_function_id IN (SELECT id FROM main_tree)
            )
            SELECT a.* FROM asset_entries a
            INNER JOIN scoped_sf s ON a.sub_function_id = s.id
            WHERE a.class_id = :classId
              AND a.active = TRUE
            ORDER BY a.id
            """, nativeQuery = true)
    List<AssetEntry> findByClassIdInLocationScope(@Param("classId") Long classId,
                                                    @Param("scopeId") Long scopeId);

    @Query(value = """
            WITH RECURSIVE sys_tree AS (
                SELECT id FROM plant_systems WHERE id = :scopeId
                UNION ALL
                SELECT ps.id FROM plant_systems ps INNER JOIN sys_tree t ON ps.parent_id = t.id
            ),
            main_roots AS (
                SELECT id FROM main_functions WHERE system_id IN (SELECT id FROM sys_tree)
            ),
            main_tree AS (
                SELECT id FROM main_functions WHERE id IN (SELECT id FROM main_roots)
                UNION ALL
                SELECT mf.id FROM main_functions mf INNER JOIN main_tree t ON mf.parent_id = t.id
            ),
            scoped_sf AS (
                SELECT id FROM sub_functions
                WHERE system_id IN (SELECT id FROM sys_tree)
                   OR main_function_id IN (SELECT id FROM main_tree)
            )
            SELECT a.* FROM asset_entries a
            INNER JOIN scoped_sf s ON a.sub_function_id = s.id
            WHERE a.class_id = :classId
              AND a.active = TRUE
            ORDER BY a.id
            """, nativeQuery = true)
    List<AssetEntry> findByClassIdInSystemScope(@Param("classId") Long classId,
                                                  @Param("scopeId") Long scopeId);

    @Query(value = """
            WITH RECURSIVE main_tree AS (
                SELECT id FROM main_functions WHERE id = :scopeId
                UNION ALL
                SELECT mf.id FROM main_functions mf INNER JOIN main_tree t ON mf.parent_id = t.id
            )
            SELECT a.* FROM asset_entries a
            WHERE a.class_id = :classId
              AND a.active = TRUE
              AND a.sub_function_id IN (
                  SELECT id FROM sub_functions WHERE main_function_id IN (SELECT id FROM main_tree)
              )
            ORDER BY a.id
            """, nativeQuery = true)
    List<AssetEntry> findByClassIdInMainFunctionScope(@Param("classId") Long classId,
                                                        @Param("scopeId") Long scopeId);

    @Query(value = """
            WITH RECURSIVE sf_tree AS (
                SELECT id FROM sub_functions WHERE id = :scopeId
                UNION ALL
                SELECT sf.id FROM sub_functions sf INNER JOIN sf_tree t ON sf.parent_id = t.id
            )
            SELECT a.* FROM asset_entries a
            WHERE a.class_id = :classId
              AND a.active = TRUE
              AND a.sub_function_id IN (SELECT id FROM sf_tree)
            ORDER BY a.id
            """, nativeQuery = true)
    List<AssetEntry> findByClassIdInSubFunctionScope(@Param("classId") Long classId,
                                                       @Param("scopeId") Long scopeId);
}
