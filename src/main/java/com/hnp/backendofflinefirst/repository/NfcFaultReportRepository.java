package com.hnp.backendofflinefirst.repository;

import com.hnp.backendofflinefirst.domain.NfcFaultReportStatus;
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

    /**
     * One page of the review queue: unit-scoped, optionally narrowed by status and free text.
     *
     * <p>Replaces {@code findAllByOrderByCreatedAtDesc} / {@code findByOperationalUnitIdIn…},
     * which loaded <b>every report ever filed</b> to render a page of them. That is the same
     * unbounded read {@code findOpenForUnits} was already fixed for; the browse page kept it
     * because it was the page nobody looked at while the table was small.
     *
     * <p>{@code unitIds} null means unrestricted (admin) — the panel-wide convention, see
     * {@code OperationalUnitScopeService.visibleUnitIds()}. An <em>empty</em> collection must
     * never reach here: {@code IN ()} is not portable, and the caller short-circuits instead.
     *
     * <p>{@code q} is matched against the report's own text and, through a subquery, the asset's
     * code and name — the two things a reviewer actually knows when hunting for a report. It must
     * arrive already lower-cased and wrapped in {@code %}; doing that here would mean
     * {@code LOWER(:q)} on every row.
     *
     * <p>Ordered newest first with the id as tie-break. {@code created_at} is the reporting
     * clock and repeats freely — a phone syncing a backlog files several reports in the same
     * millisecond — and without the tie-break those rows could swap between pages and be shown
     * twice or not at all.
     */
    @Query("""
            SELECT r FROM NfcFaultReport r
            WHERE (:unitIds IS NULL OR r.operationalUnitId IN :unitIds)
              AND (:status IS NULL OR r.status = :status)
              AND (:q IS NULL
                   OR LOWER(r.reason) LIKE :q
                   OR LOWER(r.reportedByName) LIKE :q
                   OR r.assetId IN (SELECT a.id FROM AssetEntry a
                                    WHERE LOWER(a.assetCode) LIKE :q OR LOWER(a.assetName) LIKE :q))
            ORDER BY r.createdAt DESC, r.id DESC
            """)
    org.springframework.data.domain.Page<NfcFaultReport> search(
            @org.springframework.data.repository.query.Param("unitIds") Collection<Long> unitIds,
            @org.springframework.data.repository.query.Param("status") NfcFaultReportStatus status,
            @org.springframework.data.repository.query.Param("q") String q,
            org.springframework.data.domain.Pageable pageable);

    /**
     * How many reports in scope are still open — the figure on the page header, which has to be
     * the whole backlog rather than however many of them landed on the page being looked at.
     */
    @Query("""
            SELECT COUNT(r) FROM NfcFaultReport r
            WHERE (:unitIds IS NULL OR r.operationalUnitId IN :unitIds)
              AND r.status = com.hnp.backendofflinefirst.domain.NfcFaultReportStatus.OPEN
            """)
    long countOpenInScope(
            @org.springframework.data.repository.query.Param("unitIds") Collection<Long> unitIds);

    boolean existsByClientActionId(String clientActionId);
}
