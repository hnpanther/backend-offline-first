package com.hnp.backendofflinefirst.repository;

import com.hnp.backendofflinefirst.domain.AssetStatusRequestStatus;
import com.hnp.backendofflinefirst.entity.AssetStatusChangeRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface AssetStatusChangeRequestRepository extends JpaRepository<AssetStatusChangeRequest, Long> {

    /**
     * The asset's newest request, which is the <b>only</b> one whose approval may be undone.
     *
     * <p>Ordering by id, not by {@code requestedAt}: two requests raised in the same millisecond
     * are perfectly possible when a sheet completes several assets at once, and a timestamp tie
     * would make "latest" ambiguous exactly where the guard has to be exact. The id sequence is
     * monotonic and never ties.
     */
    Optional<AssetStatusChangeRequest> findFirstByAssetIdOrderByIdDesc(Long assetId);

    /** One asset's requests, newest first — the asset history timeline. */
    List<AssetStatusChangeRequest> findByAssetIdOrderByIdDesc(Long assetId);

    /**
     * Whether this sheet already raised a request for this asset that is still open or in force.
     *
     * <p>Guards against duplicates when a sheet is completed more than once — reopened for a
     * correction, or restored after being voided. A rejected request is deliberately excluded:
     * once a supervisor has said no, a re-completion asking again is a new question.
     */
    @Query("""
            SELECT COUNT(r) > 0 FROM AssetStatusChangeRequest r
            WHERE r.logSheetId = :logSheetId
              AND r.assetId = :assetId
              AND r.status <> com.hnp.backendofflinefirst.domain.AssetStatusRequestStatus.REJECTED
            """)
    boolean existsOpenOrApprovedForSheetAndAsset(@Param("logSheetId") Long logSheetId,
                                                 @Param("assetId") Long assetId);

    /**
     * The approval queue, unit-scoped through the asset's log sheets and locations.
     *
     * <p>{@code unitIds} null means unrestricted (admin). Filters are all optional so one query
     * serves the whole page.
     *
     * <p>{@code q} is matched against the request's own text and, through subqueries, the
     * <b>asset's code and name</b> and the <b>requester's and decider's names</b> — the things a
     * supervisor working the queue actually knows. It must arrive already lower-cased and
     * wrapped in {@code %}; doing that here would mean {@code LOWER(:q)} per row.
     *
     * <p>Ordered by id, which is monotonic and never ties. {@code requested_at} would: several
     * requests are raised in the same millisecond when one sheet completes many assets, and a
     * tie there lets rows swap between pages and be shown twice or not at all.
     */
    @Query("""
            SELECT r FROM AssetStatusChangeRequest r
            WHERE (:status IS NULL OR r.status = :status)
              AND (:assetId IS NULL OR r.assetId = :assetId)
              AND (:assetIds IS NULL OR r.assetId IN :assetIds)
              AND (:q IS NULL
                   OR LOWER(COALESCE(r.reason, '')) LIKE :q
                   OR LOWER(COALESCE(r.requestedStatus, '')) LIKE :q
                   OR LOWER(COALESCE(r.previousStatus, '')) LIKE :q
                   OR r.assetId IN (SELECT a.id FROM AssetEntry a
                                    WHERE LOWER(a.assetCode) LIKE :q OR LOWER(a.assetName) LIKE :q)
                   OR r.requestedByUserId IN (SELECT u.id FROM User u
                                    WHERE LOWER(COALESCE(u.fullName, '')) LIKE :q
                                       OR LOWER(u.username) LIKE :q)
                   OR r.decidedByUserId IN (SELECT u.id FROM User u
                                    WHERE LOWER(COALESCE(u.fullName, '')) LIKE :q
                                       OR LOWER(u.username) LIKE :q))
            ORDER BY r.id DESC
            """)
    Page<AssetStatusChangeRequest> search(@Param("status") AssetStatusRequestStatus status,
                                          @Param("assetId") Long assetId,
                                          @Param("assetIds") Collection<Long> assetIds,
                                          @Param("q") String q,
                                          Pageable pageable);

    long countByStatus(AssetStatusRequestStatus status);

    /**
     * The filter half of the scoped queue, declared once because it is used twice.
     *
     * <p>A paged native query needs its own {@code countQuery}, and the two must apply the
     * <b>same</b> predicate or the page and its total describe different result sets — a pager
     * offering pages that do not exist, or a heading that does not match the rows under it.
     * Written out twice they would eventually disagree; concatenated from here they cannot.
     */
    String SCOPED_QUEUE_PREDICATE = """
              AND (CAST(:status AS text) IS NULL OR r.status = CAST(:status AS text))
              AND (CAST(:assetId AS bigint) IS NULL OR r.asset_id = CAST(:assetId AS bigint))
              AND (CAST(:q AS text) IS NULL
                   OR LOWER(COALESCE(r.reason, '')) LIKE CAST(:q AS text)
                   OR LOWER(COALESCE(r.requested_status, '')) LIKE CAST(:q AS text)
                   OR LOWER(COALESCE(r.previous_status, '')) LIKE CAST(:q AS text)
                   OR EXISTS (SELECT 1 FROM asset_entries a WHERE a.id = r.asset_id
                              AND (LOWER(a.asset_code) LIKE CAST(:q AS text)
                                   OR LOWER(a.asset_name) LIKE CAST(:q AS text)))
                   OR EXISTS (SELECT 1 FROM users u WHERE u.id = r.requested_by_user_id
                              AND (LOWER(COALESCE(u.full_name, '')) LIKE CAST(:q AS text)
                                   OR LOWER(u.username) LIKE CAST(:q AS text)))
                   OR EXISTS (SELECT 1 FROM users u WHERE u.id = r.decided_by_user_id
                              AND (LOWER(COALESCE(u.full_name, '')) LIKE CAST(:q AS text)
                                   OR LOWER(u.username) LIKE CAST(:q AS text))))
            """;

    /**
     * The same queue for a <b>unit-scoped</b> caller, with the scope resolved in SQL.
     *
     * <h2>What this replaces, and why it had to be native</h2>
     *
     * <p>The controller used to materialise the caller's reportable asset ids —
     * {@code PageRequest.of(0, 5000)} — and pass them to {@link #search} as an {@code IN} list.
     * Two things were wrong with that, and both were silent. A scope larger than 5000 assets was
     * <b>truncated</b>, so requests for the assets past the cut simply were not in the queue, on
     * any page, with no error and no hint; and because the underlying query carries no
     * {@code ORDER BY}, <b>which</b> 5000 was never defined, so the set could differ between two
     * loads of the same page.
     *
     * <p>Pagination does not help: the cap is on the filter, computed whole before the first row
     * is fetched, not on the result. And it could not simply be raised — a scope of 200,000
     * assets would exceed PostgreSQL's 65,535 bind parameters and fail outright.
     *
     * <p>So the scope goes into the statement. {@code REPORTABLE_ASSETS_CTE} is the same
     * definition {@code AssetAccessService.findReportableAssets} uses, which is what keeps "what
     * this page lists" and "what this user may act on" one rule rather than two. Native because
     * that CTE is recursive and JPQL cannot express it.
     *
     * <p><b>An {@code EXISTS} semi-join, not an {@code IN}.</b> The CTE is non-recursive at its
     * last step and referenced once, so PostgreSQL may inline it and stop at the first matching
     * asset instead of building the whole reportable set for every page.
     *
     * <h2>Reading it</h2>
     *
     * <p>Every optional filter is wrapped in {@code CAST(:param AS …)}: a native query gives the
     * driver no type for a bound {@code null}, and PostgreSQL then refuses the statement with
     * <em>could not determine data type of parameter</em>. {@code status} arrives as a
     * {@code String} for the same reason — an enum has no native binding here.
     *
     * <p>{@code unitIds} must be non-empty. {@code IN ()} is not valid SQL, and "this user may
     * see nothing" is answered by the caller without a query. Unrestricted callers use
     * {@link #search}, which is untouched by this and stays JPQL.
     *
     * <p>Ordered by id like its sibling, for the same reason: {@code requested_at} ties whenever
     * one sheet completes several assets, and a tie lets rows swap between pages.
     */
    @Query(value = com.hnp.backendofflinefirst.domain.AssetUnitScopeSql.REPORTABLE_ASSETS_CTE + """
            SELECT r.* FROM asset_status_change_requests r
            WHERE EXISTS (SELECT 1 FROM reportable_assets ra WHERE ra.id = r.asset_id)
            """ + SCOPED_QUEUE_PREDICATE + """
            ORDER BY r.id DESC
            """,
            countQuery = com.hnp.backendofflinefirst.domain.AssetUnitScopeSql.REPORTABLE_ASSETS_CTE + """
            SELECT count(*) FROM asset_status_change_requests r
            WHERE EXISTS (SELECT 1 FROM reportable_assets ra WHERE ra.id = r.asset_id)
            """ + SCOPED_QUEUE_PREDICATE,
            nativeQuery = true)
    Page<AssetStatusChangeRequest> searchInScope(@Param("unitIds") Collection<Long> unitIds,
                                                 @Param("status") String status,
                                                 @Param("assetId") Long assetId,
                                                 @Param("q") String q,
                                                 Pageable pageable);

    /**
     * How many requests of one status the caller may act on.
     *
     * <p>The header figure used to be {@link #countByStatus}, which takes no scope at all: a
     * supervisor restricted to one unit was shown the count for the <b>whole plant</b>. The
     * number and the list it sat above could not agree, and it disclosed how much was happening
     * in units the reader cannot see.
     */
    @Query(value = com.hnp.backendofflinefirst.domain.AssetUnitScopeSql.REPORTABLE_ASSETS_CTE + """
            SELECT count(*) FROM asset_status_change_requests r
            WHERE EXISTS (SELECT 1 FROM reportable_assets ra WHERE ra.id = r.asset_id)
              AND r.status = CAST(:status AS text)
            """,
            nativeQuery = true)
    long countByStatusInScope(@Param("unitIds") Collection<Long> unitIds,
                              @Param("status") String status);
}
