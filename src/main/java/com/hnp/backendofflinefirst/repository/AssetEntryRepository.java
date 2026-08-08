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

    // ── Reporting scope ───────────────────────────────────────────────────────
    // Wider than the registry queries above: also reaches assets the user is
    // responsible for through an accessible log sheet. See AssetUnitScopeSql
    // .REPORTABLE_ASSETS_CTE for why the two scopes differ.

    @Query(value = AssetUnitScopeSql.REPORTABLE_ASSETS_CTE + """
            SELECT a.* FROM asset_entries a
            INNER JOIN reportable_assets r ON a.id = r.id
            WHERE a.id = :assetId
            LIMIT 1
            """, nativeQuery = true)
    Optional<AssetEntry> findReportableByIdAndUnitIds(@Param("unitIds") Collection<Long> unitIds,
                                                      @Param("assetId") Long assetId);

    /**
     * Assets with no submitted reading since {@code since}, worst first — the silent-asset
     * section of the data-quality report.
     *
     * <p>Answered entirely in SQL on purpose. The previous shape fetched {@code limit * 4}
     * assets and filtered them in Java, which does not scale and, worse, is not even correct at
     * scale: past a few hundred assets it reported a slice of whatever the paging query
     * happened to return rather than the plant's actual worst offenders. Ordering and limiting
     * must happen where the whole set is visible.
     *
     * <p>Two conditions matter as much as the join:
     * <ul>
     *   <li><b>{@code max_severity IS NOT NULL}</b> — only entries that carry a reading count as
     *       having been read. Appearing on a submitted sheet the operator never reached is
     *       exactly the case this report exists to catch.</li>
     *   <li><b>{@code a.active}</b> — an inactive asset is excluded from generation, so it
     *       cannot have readings; listing it as silent is a false positive that buries the
     *       real ones.</li>
     * </ul>
     *
     * <p>Nulls sort first: an asset that has never once been read is the most urgent row, not
     * the least.
     */
    @Query(value = AssetUnitScopeSql.REPORTABLE_ASSETS_CTE + """
            SELECT a.id, a.asset_code, a.asset_name, a.sub_function_id, last_read.last_at
            FROM asset_entries a
            INNER JOIN reportable_assets r ON a.id = r.id
            LEFT JOIN LATERAL (
                SELECT MAX(COALESCE(ls.completed_at, ls.submitted_at)) AS last_at
                FROM log_sheet_entries e
                INNER JOIN log_sheets ls ON ls.id = e.log_sheet_id
                WHERE e.asset_id = a.id
                  AND ls.status = 'SUBMITTED'
                  AND e.max_severity IS NOT NULL
            ) last_read ON TRUE
            WHERE a.active = TRUE
              AND (last_read.last_at IS NULL OR last_read.last_at < :since)
            ORDER BY last_read.last_at ASC NULLS FIRST, a.id ASC
            LIMIT :limit
            """, nativeQuery = true)
    List<Object[]> findSilentAssets(@Param("unitIds") Collection<Long> unitIds,
                                    @Param("since") long since,
                                    @Param("limit") int limit);

    /**
     * The same question for an unrestricted viewer (admin), where {@code visibleUnitIds()}
     * returns null.
     *
     * <p>A separate query rather than a nullable parameter: the scoped version is built on a
     * recursive CTE that binds {@code :unitIds}, and SQL has no way to express "match every
     * unit" through that binding — passing null silently matches nothing, which is how the
     * whole section came back empty for an admin. {@code findReportableAssets} splits the two
     * cases the same way for the same reason.
     */
    @Query(value = """
            SELECT a.id, a.asset_code, a.asset_name, a.sub_function_id, last_read.last_at
            FROM asset_entries a
            LEFT JOIN LATERAL (
                SELECT MAX(COALESCE(ls.completed_at, ls.submitted_at)) AS last_at
                FROM log_sheet_entries e
                INNER JOIN log_sheets ls ON ls.id = e.log_sheet_id
                WHERE e.asset_id = a.id
                  AND ls.status = 'SUBMITTED'
                  AND e.max_severity IS NOT NULL
            ) last_read ON TRUE
            WHERE a.active = TRUE
              AND (last_read.last_at IS NULL OR last_read.last_at < :since)
            ORDER BY last_read.last_at ASC NULLS FIRST, a.id ASC
            LIMIT :limit
            """, nativeQuery = true)
    List<Object[]> findSilentAssetsUnrestricted(@Param("since") long since,
                                                @Param("limit") int limit);

    @Query(value = AssetUnitScopeSql.REPORTABLE_ASSETS_CTE + """
            SELECT a.* FROM asset_entries a
            INNER JOIN reportable_assets r ON a.id = r.id
            """,
            countQuery = AssetUnitScopeSql.REPORTABLE_ASSETS_CTE + """
            SELECT count(*) FROM asset_entries a
            INNER JOIN reportable_assets r ON a.id = r.id
            """,
            nativeQuery = true)
    Page<AssetEntry> findReportableByUnitIds(@Param("unitIds") Collection<Long> unitIds, Pageable pageable);

    @Query(value = AssetUnitScopeSql.REPORTABLE_ASSETS_CTE + """
            SELECT a.* FROM asset_entries a
            INNER JOIN reportable_assets r ON a.id = r.id
            WHERE LOWER(a.asset_code) LIKE LOWER(CONCAT('%', :q, '%'))
               OR LOWER(a.asset_name) LIKE LOWER(CONCAT('%', :q, '%'))
               OR LOWER(COALESCE(a.nfc_tag_id, '')) LIKE LOWER(CONCAT('%', :q, '%'))
            """,
            countQuery = AssetUnitScopeSql.REPORTABLE_ASSETS_CTE + """
            SELECT count(*) FROM asset_entries a
            INNER JOIN reportable_assets r ON a.id = r.id
            WHERE LOWER(a.asset_code) LIKE LOWER(CONCAT('%', :q, '%'))
               OR LOWER(a.asset_name) LIKE LOWER(CONCAT('%', :q, '%'))
               OR LOWER(COALESCE(a.nfc_tag_id, '')) LIKE LOWER(CONCAT('%', :q, '%'))
            """,
            nativeQuery = true)
    Page<AssetEntry> searchReportableByUnitIds(@Param("unitIds") Collection<Long> unitIds,
                                               @Param("q") String q,
                                               Pageable pageable);

    @Query(value = AssetUnitScopeSql.SCOPED_SUBFUNCTIONS_CTE + """
            SELECT a.* FROM asset_entries a
            INNER JOIN scoped_sf s ON a.sub_function_id = s.id
            ORDER BY a.id DESC
            """, nativeQuery = true)
    List<AssetEntry> findAllVisibleByUnitIds(@Param("unitIds") Collection<Long> unitIds);

    /**
     * Active assets among {@code assetIds} that also fall within the given unit scope —
     * used to validate a supervisor's hand-picked asset selection for custom log sheets.
     */
    @Query(value = AssetUnitScopeSql.SCOPED_SUBFUNCTIONS_CTE + """
            SELECT a.* FROM asset_entries a
            INNER JOIN scoped_sf s ON a.sub_function_id = s.id
            WHERE a.id IN (:assetIds)
              AND a.active = TRUE
            ORDER BY a.id
            """, nativeQuery = true)
    List<AssetEntry> findVisibleActiveByIdInAndUnitIds(@Param("unitIds") Collection<Long> unitIds,
                                                       @Param("assetIds") Collection<Long> assetIds);

    /**
     * Active assets among the given ids, ignoring unit scope. Used for an EXPLICIT
     * template's frozen list: the selection is authoritative (it may deliberately include
     * assets outside the owning unit), so the only generation-time filter is "still active".
     */
    @Query("SELECT a FROM AssetEntry a WHERE a.id IN :assetIds AND a.active = true")
    List<AssetEntry> findActiveByIdIn(@Param("assetIds") Collection<Long> assetIds);

    Optional<AssetEntry> findByNfcTagId(String nfcTagId);
    Optional<AssetEntry> findByNfcTagIdIgnoreCase(String nfcTagId);
    /** Physical chip serial — unique when present, backed by {@code ux_asset_entries_nfc_serial_lower}. */
    Optional<AssetEntry> findByNfcSerialIgnoreCase(String nfcSerial);
    boolean existsByNfcSerialIgnoreCase(String nfcSerial);
    Optional<AssetEntry> findFirstByAssetCodeIgnoreCase(String assetCode);
    boolean existsByAssetCodeIgnoreCase(String assetCode);
    boolean existsByNfcTagIdIgnoreCase(String nfcTagId);
    boolean existsByNfcTagIdIgnoreCaseAndIdNot(String nfcTagId, Long id);
    List<AssetEntry> findAllByOrderByIdDesc();
    List<AssetEntry> findByClassId(Long classId);

    List<AssetEntry> findByClassIdAndSubFunctionIdIn(Long classId, Collection<Long> subFunctionIds);

    List<AssetEntry> findBySubFunctionIdIn(Collection<Long> subFunctionIds);

    List<AssetEntry> findBySubFunctionId(Long subFunctionId);
    Optional<AssetEntry> findFirstBySubFunctionId(Long subFunctionId);

    /**
     * The single asset currently occupying a sub-function. Inactive assets may pile up on the
     * same sub-function (replaced equipment kept for history), so only the active one is
     * unique — matching the partial index {@code ux_asset_entries_active_sub_function}.
     */
    Optional<AssetEntry> findFirstBySubFunctionIdAndActiveTrue(Long subFunctionId);
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
