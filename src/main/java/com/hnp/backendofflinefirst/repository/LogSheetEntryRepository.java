package com.hnp.backendofflinefirst.repository;

import com.hnp.backendofflinefirst.domain.LogSheetStatus;
import com.hnp.backendofflinefirst.entity.LogSheetEntry;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface LogSheetEntryRepository extends JpaRepository<LogSheetEntry, Long> {
    List<LogSheetEntry> findByLogSheetId(Long logSheetId);

    // -- Management reports ---------------------------------------------------

    /** Bulk fetch for the out-of-range scan; avoids one query per sheet. */
    List<LogSheetEntry> findByLogSheetIdIn(java.util.Collection<Long> logSheetIds);

    /**
     * Manual-vs-scanned split per unit over submitted work.
     * <p>A null entrySource predates the field and is counted as scanned rather than manual:
     * treating unknown as manual would invent a data-quality problem out of old rows.
     */
    @Query("""
            SELECT s.operationalUnitId,
                   COUNT(e),
                   SUM(CASE WHEN e.entrySource = com.hnp.backendofflinefirst.domain.LogSheetEntrySource.PWA_MANUAL
                            THEN 1 ELSE 0 END)
            FROM LogSheetEntry e, LogSheet s
            WHERE e.logSheetId = s.id
              AND s.status = com.hnp.backendofflinefirst.domain.LogSheetStatus.SUBMITTED
              AND (:unitIds IS NULL OR s.operationalUnitId IN :unitIds)
              AND (:from IS NULL OR COALESCE(s.completedAt, s.submittedAt) >= :from)
              AND (:to IS NULL OR COALESCE(s.completedAt, s.submittedAt) <= :to)
            GROUP BY s.operationalUnitId
            """)
    List<Object[]> entrySourceSplitByUnit(@Param("unitIds") java.util.Collection<Long> unitIds,
                                          @Param("from") Long from,
                                          @Param("to") Long to);

    /** Most recent submitted reading timestamp per asset, for the silent-asset report. */
    @Query("""
            SELECT e.assetId, MAX(COALESCE(s.completedAt, s.submittedAt))
            FROM LogSheetEntry e, LogSheet s
            WHERE e.logSheetId = s.id
              AND s.status = com.hnp.backendofflinefirst.domain.LogSheetStatus.SUBMITTED
              AND e.assetId IN :assetIds
            GROUP BY e.assetId
            """)
    List<Object[]> lastSubmittedReadingPerAsset(@Param("assetIds") java.util.Collection<Long> assetIds);
    boolean existsByAssetId(Long assetId);

    @Query(value = """
            SELECT e.id, s.id, COALESCE(s.completedAt, s.submittedAt), s.templateName, s.operatorName, e.formData
            FROM LogSheetEntry e, LogSheet s
            WHERE e.logSheetId = s.id
              AND e.assetId = :assetId
              AND s.status = :status
              AND (:from IS NULL OR COALESCE(s.completedAt, s.submittedAt) >= :from)
              AND (:to IS NULL OR COALESCE(s.completedAt, s.submittedAt) <= :to)
            ORDER BY COALESCE(s.completedAt, s.submittedAt) DESC
            """,
            countQuery = """
            SELECT COUNT(e) FROM LogSheetEntry e, LogSheet s
            WHERE e.logSheetId = s.id
              AND e.assetId = :assetId
              AND s.status = :status
              AND (:from IS NULL OR COALESCE(s.completedAt, s.submittedAt) >= :from)
              AND (:to IS NULL OR COALESCE(s.completedAt, s.submittedAt) <= :to)
            """)
    Page<Object[]> findSubmittedReadingRowsByAssetId(@Param("assetId") Long assetId,
                                                       @Param("status") LogSheetStatus status,
                                                       @Param("from") Long from,
                                                       @Param("to") Long to,
                                                       Pageable pageable);

    @Query("""
            SELECT e.id, s.id, COALESCE(s.completedAt, s.submittedAt), s.templateName, s.operatorName, e.formData
            FROM LogSheetEntry e, LogSheet s
            WHERE e.logSheetId = s.id
              AND e.assetId = :assetId
              AND s.status = :status
              AND (:from IS NULL OR COALESCE(s.completedAt, s.submittedAt) >= :from)
              AND (:to IS NULL OR COALESCE(s.completedAt, s.submittedAt) <= :to)
            ORDER BY COALESCE(s.completedAt, s.submittedAt) ASC
            """)
    List<Object[]> findSubmittedReadingRowsByAssetIdAsc(@Param("assetId") Long assetId,
                                                         @Param("status") LogSheetStatus status,
                                                         @Param("from") Long from,
                                                         @Param("to") Long to);
}
