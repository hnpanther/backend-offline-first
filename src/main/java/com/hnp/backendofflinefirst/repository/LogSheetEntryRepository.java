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

    // -- Severity backfill ----------------------------------------------------
    // "Has values but was never evaluated": max_severity NULL is deliberately distinct from
    // 'OK', so these are exactly the rows written before the column existed.
    //
    // "Has values" must mean what EntrySeverityEvaluator means by it, or the backfill never
    // finishes. A sheet is raised with one entry per asset and submitted whether or not every
    // asset was reached, so untouched entries hold form_data = '{}' — not SQL NULL. The
    // evaluator treats an empty map as "nothing to judge" and writes max_severity back to NULL,
    // so a predicate of merely `form_data IS NOT NULL` re-selected the identical rows on every
    // single boot: 3,093 rows read, their sheets loaded, their definition snapshots resolved,
    // nothing stamped, and an INFO line claiming otherwise. Native because JPQL cannot express
    // a jsonb comparison, and `jsonb_typeof` also excludes the json literal `null`, which
    // Hibernate would hand the evaluator as a null map.

    @Query(value = """
            SELECT COUNT(*) FROM log_sheet_entries
            WHERE max_severity IS NULL
              AND form_data IS NOT NULL
              AND jsonb_typeof(form_data) = 'object'
              AND form_data <> '{}'::jsonb
            """, nativeQuery = true)
    long countUnevaluatedWithValues();

    @Query(value = """
            SELECT * FROM log_sheet_entries
            WHERE max_severity IS NULL
              AND form_data IS NOT NULL
              AND jsonb_typeof(form_data) = 'object'
              AND form_data <> '{}'::jsonb
            ORDER BY id
            LIMIT :limit
            """, nativeQuery = true)
    List<LogSheetEntry> findUnevaluatedWithValues(@Param("limit") int limit);

    /**
     * Breach counts grouped by severity — for the overview cards.
     *
     * <p>The dashboard only needs two numbers, and fetching rows to count them was both
     * wasteful and <em>wrong</em>: the row query is capped at a page size, so once a period
     * had more breaches than that cap the headline figure silently stopped growing.
     */
    @Query("""
            SELECT e.maxSeverity, COUNT(e)
            FROM LogSheetEntry e, LogSheet s
            WHERE e.logSheetId = s.id
              AND s.status = com.hnp.backendofflinefirst.domain.LogSheetStatus.SUBMITTED
              AND e.maxSeverity IN ('WARNING', 'DANGER')
              AND (:unitIds IS NULL OR s.operationalUnitId IN :unitIds)
              AND (:from IS NULL OR COALESCE(s.completedAt, s.submittedAt) >= :from)
              AND (:to IS NULL OR COALESCE(s.completedAt, s.submittedAt) <= :to)
            GROUP BY e.maxSeverity
            """)
    List<Object[]> countBreachesBySeverity(@Param("unitIds") java.util.Collection<Long> unitIds,
                                           @Param("from") Long from,
                                           @Param("to") Long to);

    /**
     * Manual-vs-scanned split per unit over submitted work.
     *
     * <p><b>The denominator is entries that actually carry a reading</b>, not every entry on a
     * submitted sheet. A sheet is raised with one entry per asset and submitted whether or not
     * every asset was reached; counting the untouched ones made the rate meaningless — on live
     * data one unit had 94 entries of which 3 held a reading, so a genuinely all-manual round
     * displayed as 2%. An asset the operator never reached is not a scanned reading, it is an
     * absence, and it belongs to the "silent assets" question further down this page.
     *
     * <p>{@code maxSeverity IS NOT NULL} is the has-a-reading test. It is exact rather than
     * approximate: {@code EntrySeverityEvaluator.apply} nulls the column when {@code form_data}
     * is null or empty and always writes at least {@code OK} when it is not, on every path that
     * mutates form data (gotcha #48), and {@code EntrySeverityBackfillRunner} stamps legacy
     * rows at startup. Verified against live data with zero disagreement in either direction.
     *
     * <p>A null entrySource on a filled entry predates the field and is counted as scanned
     * rather than manual: treating unknown as manual would invent a data-quality problem out of
     * old rows.
     */
    @Query("""
            SELECT s.operationalUnitId,
                   COUNT(e),
                   SUM(CASE WHEN e.entrySource = com.hnp.backendofflinefirst.domain.LogSheetEntrySource.PWA_MANUAL
                            THEN 1 ELSE 0 END)
            FROM LogSheetEntry e, LogSheet s
            WHERE e.logSheetId = s.id
              AND s.status = com.hnp.backendofflinefirst.domain.LogSheetStatus.SUBMITTED
              AND e.maxSeverity IS NOT NULL
              AND (:unitIds IS NULL OR s.operationalUnitId IN :unitIds)
              AND (:from IS NULL OR COALESCE(s.completedAt, s.submittedAt) >= :from)
              AND (:to IS NULL OR COALESCE(s.completedAt, s.submittedAt) <= :to)
            GROUP BY s.operationalUnitId
            """)
    List<Object[]> entrySourceSplitByUnit(@Param("unitIds") java.util.Collection<Long> unitIds,
                                          @Param("from") Long from,
                                          @Param("to") Long to);

    /**
     * Most recent submitted reading timestamp per asset, for the silent-asset report.
     *
     * <p>Only entries that <b>carry a reading</b> count. Appearing on a submitted sheet is not
     * the same as having been inspected: without this filter an asset the operator skipped
     * looked freshly read, which is the one error this report must never make — it exists to
     * surface equipment nobody has actually looked at.
     */
    @Query("""
            SELECT e.assetId, MAX(COALESCE(s.completedAt, s.submittedAt))
            FROM LogSheetEntry e, LogSheet s
            WHERE e.logSheetId = s.id
              AND s.status = com.hnp.backendofflinefirst.domain.LogSheetStatus.SUBMITTED
              AND e.maxSeverity IS NOT NULL
              AND e.assetId IN :assetIds
            GROUP BY e.assetId
            """)
    List<Object[]> lastSubmittedReadingPerAsset(@Param("assetIds") java.util.Collection<Long> assetIds);

    /**
     * Entries whose stored severity says they breached a range.
     *
     * <p>Reads the denormalised {@code max_severity} written at save time rather than
     * re-evaluating {@code form_data} — that is the whole point of storing it, and it is what
     * makes this cheap enough for an external job to poll. Hits
     * {@code idx_log_sheet_entries_breaches}.
     *
     * <p>{@code dangerOnly} is passed as a boolean rather than building two queries so the
     * caller cannot accidentally use a different scope predicate for the two cases.
     */
    @Query("""
            SELECT e, s FROM LogSheetEntry e, LogSheet s
            WHERE e.logSheetId = s.id
              AND s.status = com.hnp.backendofflinefirst.domain.LogSheetStatus.SUBMITTED
              AND (e.maxSeverity = 'DANGER' OR (:dangerOnly = FALSE AND e.maxSeverity = 'WARNING'))
              AND (:unitIds IS NULL OR s.operationalUnitId IN :unitIds)
              AND (:from IS NULL OR COALESCE(s.completedAt, s.submittedAt) >= :from)
              AND (:to IS NULL OR COALESCE(s.completedAt, s.submittedAt) <= :to)
            ORDER BY CASE WHEN e.maxSeverity = 'DANGER' THEN 0 ELSE 1 END,
                     COALESCE(s.completedAt, s.submittedAt) DESC
            """)
    List<Object[]> findBreachedEntries(@Param("unitIds") java.util.Collection<Long> unitIds,
                                       @Param("from") Long from,
                                       @Param("to") Long to,
                                       @Param("dangerOnly") boolean dangerOnly,
                                       Pageable pageable);

    /**
     * How many breached entries the same filter matches, for the pager.
     *
     * <p>Counts <em>entries</em>, not report lines: one entry breaching two parameters renders
     * as two lines, so the displayed row count for a page can exceed the page size. Paging on
     * the entry is what keeps the query indexed and the page boundaries stable — pretending to
     * page the expanded lines would mean fetching everything just to know where page 2 starts.
     */
    @Query("""
            SELECT COUNT(e) FROM LogSheetEntry e, LogSheet s
            WHERE e.logSheetId = s.id
              AND s.status = com.hnp.backendofflinefirst.domain.LogSheetStatus.SUBMITTED
              AND (e.maxSeverity = 'DANGER' OR (:dangerOnly = FALSE AND e.maxSeverity = 'WARNING'))
              AND (:unitIds IS NULL OR s.operationalUnitId IN :unitIds)
              AND (:from IS NULL OR COALESCE(s.completedAt, s.submittedAt) >= :from)
              AND (:to IS NULL OR COALESCE(s.completedAt, s.submittedAt) <= :to)
            """)
    long countBreachedEntries(@Param("unitIds") java.util.Collection<Long> unitIds,
                              @Param("from") Long from,
                              @Param("to") Long to,
                              @Param("dangerOnly") boolean dangerOnly);
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
