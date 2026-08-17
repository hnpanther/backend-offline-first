package com.hnp.backendofflinefirst.repository;

import com.hnp.backendofflinefirst.entity.NfcFaultReport;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;

public interface NfcFaultReportRepository extends JpaRepository<NfcFaultReport, Long> {

    /**
     * Unresolved fault reports for the caller's units.
     *
     * <p>Scoped in SQL. This used to be {@code findAll()} filtered in Java, which loaded every
     * report ever filed into memory to display a handful — fine with a few rows, linear in the
     * whole table forever after.
     */
    @Query("""
            SELECT r FROM NfcFaultReport r
            WHERE r.status = com.hnp.backendofflinefirst.domain.NfcFaultReportStatus.OPEN
              AND r.assetId IS NOT NULL
              AND (:unitIds IS NULL OR r.operationalUnitId IN :unitIds)
            """)
    java.util.List<NfcFaultReport> findOpenForUnits(
            @org.springframework.data.repository.query.Param("unitIds") java.util.Collection<Long> unitIds);

    /**
     * How many assets carry an unresolved fault — the total behind the pager.
     *
     * <p>Counted over assets rather than reports because the section shows one row per asset:
     * counting reports would offer pages that do not exist.
     */
    @Query("""
            SELECT COUNT(DISTINCT r.assetId) FROM NfcFaultReport r
            WHERE r.status = com.hnp.backendofflinefirst.domain.NfcFaultReportStatus.OPEN
              AND r.assetId IS NOT NULL
              AND (:unitIds IS NULL OR r.operationalUnitId IN :unitIds)
            """)
    long countAssetsWithOpenFaults(
            @org.springframework.data.repository.query.Param("unitIds") java.util.Collection<Long> unitIds);

    /**
     * One page of asset ids, oldest unresolved first.
     *
     * <p>The grouping and the ordering are the database's job. Doing them in Java meant loading
     * every open report in the plant to display twenty-five rows — bounded only by how diligently
     * somebody works the queue, which is not a bound.
     *
     * <p>Ties break on the id so paging is stable: without it, two faults reported in the same
     * millisecond could swap places between page one and page two and be shown twice, or not at
     * all.
     */
    @Query("""
            SELECT r.assetId FROM NfcFaultReport r
            WHERE r.status = com.hnp.backendofflinefirst.domain.NfcFaultReportStatus.OPEN
              AND r.assetId IS NOT NULL
              AND (:unitIds IS NULL OR r.operationalUnitId IN :unitIds)
            GROUP BY r.assetId
            ORDER BY MIN(r.createdAt) ASC, r.assetId ASC
            """)
    List<Long> findAssetIdsWithOpenFaults(
            @org.springframework.data.repository.query.Param("unitIds") java.util.Collection<Long> unitIds,
            org.springframework.data.domain.Pageable pageable);

    /**
     * How many unresolved reports exist — the overview's single figure.
     *
     * <p>Counted in SQL. The overview used to build the whole grouped list and sum it, which is
     * the same unbounded read the section itself had, for one number on a dashboard.
     */
    @Query("""
            SELECT COUNT(r) FROM NfcFaultReport r
            WHERE r.status = com.hnp.backendofflinefirst.domain.NfcFaultReportStatus.OPEN
              AND r.assetId IS NOT NULL
              AND (:unitIds IS NULL OR r.operationalUnitId IN :unitIds)
            """)
    long countOpenForUnits(
            @org.springframework.data.repository.query.Param("unitIds") java.util.Collection<Long> unitIds);

    /** The open reports of the assets on the current page — never of every asset. */
    @Query("""
            SELECT r FROM NfcFaultReport r
            WHERE r.status = com.hnp.backendofflinefirst.domain.NfcFaultReportStatus.OPEN
              AND r.assetId IN :assetIds
            """)
    List<NfcFaultReport> findOpenForAssets(
            @org.springframework.data.repository.query.Param("assetIds") java.util.Collection<Long> assetIds);
    List<NfcFaultReport> findByLogSheetIdOrderByCreatedAtDesc(Long logSheetId);
    List<NfcFaultReport> findByOperationalUnitIdInOrderByCreatedAtDesc(Collection<Long> unitIds);
    List<NfcFaultReport> findAllByOrderByCreatedAtDesc();
    boolean existsByClientActionId(String clientActionId);
}
