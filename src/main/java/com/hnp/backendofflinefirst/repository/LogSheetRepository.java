package com.hnp.backendofflinefirst.repository;

import com.hnp.backendofflinefirst.domain.AssetScanMode;
import com.hnp.backendofflinefirst.domain.AssignmentType;
import com.hnp.backendofflinefirst.domain.GenerationMode;
import com.hnp.backendofflinefirst.domain.LogSheetStatus;
import com.hnp.backendofflinefirst.entity.LogSheet;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * <h2>Why this one carries {@link JpaSpecificationExecutor}</h2>
 *
 * <p>The list page filters by status, a search term and a date range over one of three
 * timestamps. As two JPQL strings that was a pair of near-identical queries differing only in
 * the search term, and each new optional predicate doubled the pairing — plus every nullable
 * {@code String} bound inside {@code LOWER(CONCAT(...))} is AGENTS.md #13 waiting to happen
 * (PostgreSQL infers {@code bytea} and the query fails at runtime, which is exactly why the
 * termless twin existed). A {@code Specification} is one builder with one {@code if} per filter
 * and typed expressions throughout, so the next filter is a few lines rather than another
 * variant. The predicates live in {@code LogSheetAccessService}, which already owns what
 * "visible" means.
 */
public interface LogSheetRepository extends JpaRepository<LogSheet, Long>,
        JpaSpecificationExecutor<LogSheet> {

    /**
     * The sheet row, locked, for a read-modify-write of {@code sheet_form_data}.
     *
     * <p>The round-level twin of {@code LogSheetEntryRepository.lockByLogSheetIdAndAssetId}, and
     * for the same reason: reconciling a media field's reference reads the map, changes it and
     * writes it back, and two uploads to one question would otherwise be free to overlap and
     * lose one of the ids. Exactly one row is locked and no other path locks sheets in a
     * different order, so it cannot deadlock against anything.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM LogSheet s WHERE s.id = :id")
    Optional<LogSheet> lockById(@Param("id") Long id);

    List<LogSheet> findByOperationalUnitIdInAndStatus(Collection<Long> unitIds, LogSheetStatus status);

    /**
     * Open team sheets for a supervisor's inbox: the given units, restricted to the open
     * statuses and to assignees other than the supervisor themselves.
     *
     * <p>The filtering is deliberately in SQL. Doing it in Java means loading the unit's whole
     * log-sheet history — which grows with the unit's lifetime, not with the handful of open
     * sheets actually shown — and turning every row into a managed entity (including the large
     * {@code notes} and {@code field_definitions_snapshot} columns). Backed by
     * {@code idx_log_sheets_unit_status}.
     *
     * <p>Ordered newest-first so the mobile list is stable; the caller renders server order.
     */
    @Query("""
            SELECT s FROM LogSheet s
            WHERE s.operationalUnitId IN :unitIds
              AND s.status IN :statuses
              AND s.assigneeUserId IS NOT NULL
              AND s.assigneeUserId <> :excludedAssigneeId
            ORDER BY s.id DESC
            """)
    List<LogSheet> findOpenInUnitsAssignedToOthers(@Param("unitIds") Collection<Long> unitIds,
                                                   @Param("statuses") Collection<LogSheetStatus> statuses,
                                                   @Param("excludedAssigneeId") Long excludedAssigneeId);
    List<LogSheet> findByAssigneeUserId(Long assigneeUserId);
    List<LogSheet> findByStatusInAndDueAtLessThanEqual(Collection<LogSheetStatus> statuses, Long threshold);
    List<LogSheet> findAllByOrderByIdDesc();
    List<LogSheet> findByOperationalUnitIdInOrderByIdDesc(Collection<Long> unitIds);

    boolean existsByOperationalUnitId(Long operationalUnitId);

    /** The round half of a category's delete guard; served by {@code idx_log_sheets_category_id}. */
    boolean existsByCategoryId(Long categoryId);

    /** The template delete guard: a template that raised any round is history (fk_log_sheets_template). */
    boolean existsByTemplateId(Long templateId);

    boolean existsByAssigneeUserId(Long assigneeUserId);

    boolean existsByAssignedByUserId(Long assignedByUserId);

    boolean existsByCompletedByUserId(Long completedByUserId);

    @Query("""
            SELECT s.status, COUNT(s)
            FROM LogSheet s
            WHERE (:unitIds IS NULL OR s.operationalUnitId IN :unitIds)
            GROUP BY s.status
            """)
    List<Object[]> countGroupedByStatus(@Param("unitIds") Collection<Long> unitIds);

    @Query("""
            SELECT s.templateName, COUNT(s)
            FROM LogSheet s
            WHERE (:unitIds IS NULL OR s.operationalUnitId IN :unitIds)
              AND s.templateName IS NOT NULL
            GROUP BY s.templateName
            ORDER BY COUNT(s) DESC
            """)
    List<Object[]> countGroupedByTemplateName(@Param("unitIds") Collection<Long> unitIds);

    // -- Management reports ---------------------------------------------------
    // All of these take the caller's accessible unit ids (null = unrestricted admin),
    // matching countGroupedByStatus above. Windowed on createdAt so a sheet is counted
    // in the period it was raised, not the period it happened to be finished in.

    /**
     * Compliance counters per operational unit.
     * <p>on-time / late only consider SUBMITTED sheets that actually carried a deadline:
     * a sheet with no dueAt can be neither, and counting it as on-time would inflate the rate.
     */
    @Query("""
            SELECT s.operationalUnitId,
                   COUNT(s),
                   SUM(CASE WHEN s.status IN (com.hnp.backendofflinefirst.domain.LogSheetStatus.SUBMITTED, com.hnp.backendofflinefirst.domain.LogSheetStatus.APPROVED) THEN 1 ELSE 0 END),
                   SUM(CASE WHEN s.status IN (com.hnp.backendofflinefirst.domain.LogSheetStatus.SUBMITTED, com.hnp.backendofflinefirst.domain.LogSheetStatus.APPROVED)
                             AND s.dueAt IS NOT NULL
                             AND COALESCE(s.completedAt, s.submittedAt) <= s.dueAt THEN 1 ELSE 0 END),
                   SUM(CASE WHEN s.status IN (com.hnp.backendofflinefirst.domain.LogSheetStatus.SUBMITTED, com.hnp.backendofflinefirst.domain.LogSheetStatus.APPROVED)
                             AND s.dueAt IS NOT NULL
                             AND COALESCE(s.completedAt, s.submittedAt) > s.dueAt THEN 1 ELSE 0 END),
                   SUM(CASE WHEN s.status = com.hnp.backendofflinefirst.domain.LogSheetStatus.EXPIRED THEN 1 ELSE 0 END),
                   SUM(CASE WHEN s.status = com.hnp.backendofflinefirst.domain.LogSheetStatus.CANCELLED THEN 1 ELSE 0 END),
                   SUM(CASE WHEN s.status = com.hnp.backendofflinefirst.domain.LogSheetStatus.VOIDED THEN 1 ELSE 0 END),
                   SUM(CASE WHEN s.status IN (com.hnp.backendofflinefirst.domain.LogSheetStatus.PENDING,
                                              com.hnp.backendofflinefirst.domain.LogSheetStatus.ASSIGNED,
                                              com.hnp.backendofflinefirst.domain.LogSheetStatus.IN_PROGRESS) THEN 1 ELSE 0 END)
            FROM LogSheet s
            WHERE (:unitIds IS NULL OR s.operationalUnitId IN :unitIds)
              AND (:from IS NULL OR s.createdAt >= :from)
              AND (:to IS NULL OR s.createdAt <= :to)
            GROUP BY s.operationalUnitId
            """)
    List<Object[]> complianceByUnit(@Param("unitIds") Collection<Long> unitIds,
                                    @Param("from") Long from,
                                    @Param("to") Long to);

    /** Same counters grouped by template name instead of unit. */
    @Query("""
            SELECT s.templateName,
                   COUNT(s),
                   SUM(CASE WHEN s.status IN (com.hnp.backendofflinefirst.domain.LogSheetStatus.SUBMITTED, com.hnp.backendofflinefirst.domain.LogSheetStatus.APPROVED) THEN 1 ELSE 0 END),
                   SUM(CASE WHEN s.status IN (com.hnp.backendofflinefirst.domain.LogSheetStatus.SUBMITTED, com.hnp.backendofflinefirst.domain.LogSheetStatus.APPROVED)
                             AND s.dueAt IS NOT NULL
                             AND COALESCE(s.completedAt, s.submittedAt) <= s.dueAt THEN 1 ELSE 0 END),
                   SUM(CASE WHEN s.status IN (com.hnp.backendofflinefirst.domain.LogSheetStatus.SUBMITTED, com.hnp.backendofflinefirst.domain.LogSheetStatus.APPROVED)
                             AND s.dueAt IS NOT NULL
                             AND COALESCE(s.completedAt, s.submittedAt) > s.dueAt THEN 1 ELSE 0 END),
                   SUM(CASE WHEN s.status = com.hnp.backendofflinefirst.domain.LogSheetStatus.EXPIRED THEN 1 ELSE 0 END),
                   SUM(CASE WHEN s.status = com.hnp.backendofflinefirst.domain.LogSheetStatus.CANCELLED THEN 1 ELSE 0 END),
                   SUM(CASE WHEN s.status = com.hnp.backendofflinefirst.domain.LogSheetStatus.VOIDED THEN 1 ELSE 0 END),
                   SUM(CASE WHEN s.status IN (com.hnp.backendofflinefirst.domain.LogSheetStatus.PENDING,
                                              com.hnp.backendofflinefirst.domain.LogSheetStatus.ASSIGNED,
                                              com.hnp.backendofflinefirst.domain.LogSheetStatus.IN_PROGRESS) THEN 1 ELSE 0 END)
            FROM LogSheet s
            WHERE (:unitIds IS NULL OR s.operationalUnitId IN :unitIds)
              AND (:from IS NULL OR s.createdAt >= :from)
              AND (:to IS NULL OR s.createdAt <= :to)
              AND s.templateName IS NOT NULL
            GROUP BY s.templateName
            """)
    List<Object[]> complianceByTemplate(@Param("unitIds") Collection<Long> unitIds,
                                        @Param("from") Long from,
                                        @Param("to") Long to);

    /**
     * Same counters grouped by category (V19, roadmap §28) — by the frozen id, so a category
     * renamed half-way through the window is still one row. A round with no category is its
     * own row (null id) rather than dropped, so the rows add up to the same total as the
     * by-unit table above.
     */
    @Query("""
            SELECT s.categoryId,
                   COUNT(s),
                   SUM(CASE WHEN s.status IN (com.hnp.backendofflinefirst.domain.LogSheetStatus.SUBMITTED, com.hnp.backendofflinefirst.domain.LogSheetStatus.APPROVED) THEN 1 ELSE 0 END),
                   SUM(CASE WHEN s.status IN (com.hnp.backendofflinefirst.domain.LogSheetStatus.SUBMITTED, com.hnp.backendofflinefirst.domain.LogSheetStatus.APPROVED)
                             AND s.dueAt IS NOT NULL
                             AND COALESCE(s.completedAt, s.submittedAt) <= s.dueAt THEN 1 ELSE 0 END),
                   SUM(CASE WHEN s.status IN (com.hnp.backendofflinefirst.domain.LogSheetStatus.SUBMITTED, com.hnp.backendofflinefirst.domain.LogSheetStatus.APPROVED)
                             AND s.dueAt IS NOT NULL
                             AND COALESCE(s.completedAt, s.submittedAt) > s.dueAt THEN 1 ELSE 0 END),
                   SUM(CASE WHEN s.status = com.hnp.backendofflinefirst.domain.LogSheetStatus.EXPIRED THEN 1 ELSE 0 END),
                   SUM(CASE WHEN s.status = com.hnp.backendofflinefirst.domain.LogSheetStatus.CANCELLED THEN 1 ELSE 0 END),
                   SUM(CASE WHEN s.status = com.hnp.backendofflinefirst.domain.LogSheetStatus.VOIDED THEN 1 ELSE 0 END),
                   SUM(CASE WHEN s.status IN (com.hnp.backendofflinefirst.domain.LogSheetStatus.PENDING,
                                              com.hnp.backendofflinefirst.domain.LogSheetStatus.ASSIGNED,
                                              com.hnp.backendofflinefirst.domain.LogSheetStatus.IN_PROGRESS) THEN 1 ELSE 0 END)
            FROM LogSheet s
            WHERE (:unitIds IS NULL OR s.operationalUnitId IN :unitIds)
              AND (:from IS NULL OR s.createdAt >= :from)
              AND (:to IS NULL OR s.createdAt <= :to)
            GROUP BY s.categoryId
            """)
    List<Object[]> complianceByCategory(@Param("unitIds") Collection<Long> unitIds,
                                        @Param("from") Long from,
                                        @Param("to") Long to);

    /** Raw lateness values (millis past due) for percentile maths done in Java. */
    @Query("""
            SELECT s.operationalUnitId, (COALESCE(s.completedAt, s.submittedAt) - s.dueAt)
            FROM LogSheet s
            WHERE (:unitIds IS NULL OR s.operationalUnitId IN :unitIds)
              AND (:from IS NULL OR s.createdAt >= :from)
              AND (:to IS NULL OR s.createdAt <= :to)
              AND s.status IN (com.hnp.backendofflinefirst.domain.LogSheetStatus.SUBMITTED, com.hnp.backendofflinefirst.domain.LogSheetStatus.APPROVED)
              AND s.dueAt IS NOT NULL
              AND COALESCE(s.completedAt, s.submittedAt) IS NOT NULL
            """)
    List<Object[]> latenessSamples(@Param("unitIds") Collection<Long> unitIds,
                                   @Param("from") Long from,
                                   @Param("to") Long to);

    /** Per-operator throughput. Attributed to completedByUserId - who actually finished it. */
    @Query("""
            SELECT s.completedByUserId,
                   COUNT(s),
                   SUM(CASE WHEN s.dueAt IS NOT NULL
                             AND COALESCE(s.completedAt, s.submittedAt) > s.dueAt THEN 1 ELSE 0 END),
                   AVG(COALESCE(s.completedAt, s.submittedAt) - COALESCE(s.claimedAt, s.assignedAt, s.createdAt))
            FROM LogSheet s
            WHERE (:unitIds IS NULL OR s.operationalUnitId IN :unitIds)
              AND (:from IS NULL OR s.createdAt >= :from)
              AND (:to IS NULL OR s.createdAt <= :to)
              AND s.status IN (com.hnp.backendofflinefirst.domain.LogSheetStatus.SUBMITTED, com.hnp.backendofflinefirst.domain.LogSheetStatus.APPROVED)
              AND s.completedByUserId IS NOT NULL
            GROUP BY s.completedByUserId
            """)
    List<Object[]> operatorThroughput(@Param("unitIds") Collection<Long> unitIds,
                                      @Param("from") Long from,
                                      @Param("to") Long to);

    /** Per-unit volume plus how work was routed: self-claimed vs supervisor-assigned. */
    @Query("""
            SELECT s.operationalUnitId,
                   COUNT(s),
                   SUM(CASE WHEN s.claimedAt IS NOT NULL THEN 1 ELSE 0 END),
                   SUM(CASE WHEN s.assignedAt IS NOT NULL THEN 1 ELSE 0 END)
            FROM LogSheet s
            WHERE (:unitIds IS NULL OR s.operationalUnitId IN :unitIds)
              AND (:from IS NULL OR s.createdAt >= :from)
              AND (:to IS NULL OR s.createdAt <= :to)
            GROUP BY s.operationalUnitId
            """)
    List<Object[]> unitWorkload(@Param("unitIds") Collection<Long> unitIds,
                                @Param("from") Long from,
                                @Param("to") Long to);

    /** Sheets still open right now, and how many of those are already past due. */
    @Query("""
            SELECT COUNT(s),
                   SUM(CASE WHEN s.dueAt IS NOT NULL AND s.dueAt < :now THEN 1 ELSE 0 END)
            FROM LogSheet s
            WHERE (:unitIds IS NULL OR s.operationalUnitId IN :unitIds)
              AND s.status IN (com.hnp.backendofflinefirst.domain.LogSheetStatus.PENDING,
                               com.hnp.backendofflinefirst.domain.LogSheetStatus.ASSIGNED,
                               com.hnp.backendofflinefirst.domain.LogSheetStatus.IN_PROGRESS)
            """)
    List<Object[]> openWorkloadNow(@Param("unitIds") Collection<Long> unitIds, @Param("now") long now);

    // -- Landing dashboard ----------------------------------------------------
    //
    // One statement for the whole page, grouped by unit. A query per unit is the N+1 this
    // shape exists to avoid: a plant has dozens of operational units and this is the first
    // page of every login, so the cost must not scale with the organisation chart.

    /**
     * Lifecycle census per operational unit for one <b>half-open</b> window on {@code created_at}.
     *
     * <p>Columns, in order: unit id, raised, completed, approved, open, open-and-overdue,
     * expired, cancelled, voided. Every counter describes the same set of rounds — the ones
     * raised in the window — so {@code raised = completed + open + expired + cancelled + voided}
     * holds by construction.
     *
     * <p><b>The column is named in this method's name</b>, and that is on purpose rather than
     * decorative. {@code created_at}, {@code due_at} and {@code completed_at} give genuinely
     * different row sets, a range that silently picks one is read as whichever the reader had in
     * mind (see {@link com.hnp.backendofflinefirst.domain.LogSheetDateField}), and this one is
     * {@code created_at} for the reason {@code docs/reports.md} states for the compliance report:
     * a round belongs to the period it was <em>owed</em> in. Window on completion instead and a
     * backlog cleared today inflates today while hollowing out the month the work was due.
     *
     * <p>{@code from} is inclusive and {@code to} is exclusive, so two adjacent periods add up
     * instead of sharing a millisecond. Both are required: this query has no «unbounded» branch
     * because the dashboard always has a window, and an optional bound would be one more state
     * to be wrong about.
     *
     * <p>«Completed» names {@code SUBMITTED} <em>and</em> {@code APPROVED} on one line — approval
     * is a review step laid on top of completion, not a different kind of it, and a report that
     * counted only the former would show a unit's figures falling as its work got reviewed.
     * {@code approved} is then counted separately so the page can show the review queue;
     * «awaiting review» is derived from the two rather than counted a third time.
     *
     * @param now the instant «overdue» is judged against — a live figure inside a historical
     *            window, passed in rather than read from the database clock so the page and its
     *            rows agree on one «now»
     */
    @Query("""
            SELECT s.operationalUnitId,
                   COUNT(s),
                   SUM(CASE WHEN s.status IN (com.hnp.backendofflinefirst.domain.LogSheetStatus.SUBMITTED, com.hnp.backendofflinefirst.domain.LogSheetStatus.APPROVED) THEN 1 ELSE 0 END),
                   SUM(CASE WHEN s.status = com.hnp.backendofflinefirst.domain.LogSheetStatus.APPROVED THEN 1 ELSE 0 END),
                   SUM(CASE WHEN s.status IN (com.hnp.backendofflinefirst.domain.LogSheetStatus.PENDING,
                                              com.hnp.backendofflinefirst.domain.LogSheetStatus.ASSIGNED,
                                              com.hnp.backendofflinefirst.domain.LogSheetStatus.IN_PROGRESS) THEN 1 ELSE 0 END),
                   SUM(CASE WHEN s.status IN (com.hnp.backendofflinefirst.domain.LogSheetStatus.PENDING,
                                              com.hnp.backendofflinefirst.domain.LogSheetStatus.ASSIGNED,
                                              com.hnp.backendofflinefirst.domain.LogSheetStatus.IN_PROGRESS)
                             AND s.dueAt IS NOT NULL AND s.dueAt < :now THEN 1 ELSE 0 END),
                   SUM(CASE WHEN s.status = com.hnp.backendofflinefirst.domain.LogSheetStatus.EXPIRED THEN 1 ELSE 0 END),
                   SUM(CASE WHEN s.status = com.hnp.backendofflinefirst.domain.LogSheetStatus.CANCELLED THEN 1 ELSE 0 END),
                   SUM(CASE WHEN s.status = com.hnp.backendofflinefirst.domain.LogSheetStatus.VOIDED THEN 1 ELSE 0 END)
            FROM LogSheet s
            WHERE (:unitIds IS NULL OR s.operationalUnitId IN :unitIds)
              AND s.createdAt >= :fromAt
              AND s.createdAt < :toAt
            GROUP BY s.operationalUnitId
            """)
    List<Object[]> lifecycleCensusByUnitCreatedBetween(@Param("unitIds") Collection<Long> unitIds,
                                                       @Param("fromAt") long fromAt,
                                                       @Param("toAt") long toAt,
                                                       @Param("now") long now);

    /** Submitted sheets in a window, newest first - the source for the out-of-range scan. */
    @Query("""
            SELECT s FROM LogSheet s
            WHERE (:unitIds IS NULL OR s.operationalUnitId IN :unitIds)
              AND s.status IN (com.hnp.backendofflinefirst.domain.LogSheetStatus.SUBMITTED, com.hnp.backendofflinefirst.domain.LogSheetStatus.APPROVED)
              AND (:from IS NULL OR COALESCE(s.completedAt, s.submittedAt) >= :from)
              AND (:to IS NULL OR COALESCE(s.completedAt, s.submittedAt) <= :to)
            ORDER BY COALESCE(s.completedAt, s.submittedAt) DESC
            """)
    List<LogSheet> findSubmittedInWindow(@Param("unitIds") Collection<Long> unitIds,
                                         @Param("from") Long from,
                                         @Param("to") Long to,
                                         Pageable pageable);

    /** Distinct operators assigned to each unit — the denominator for workload per head. */
    @Query("""
            SELECT o.unitId, COUNT(DISTINCT o.userId)
            FROM UnitOperator o
            WHERE (:unitIds IS NULL OR o.unitId IN :unitIds)
            GROUP BY o.unitId
            """)
    List<Object[]> operatorCountPerUnit(@Param("unitIds") Collection<Long> unitIds);

    @Query("""
            SELECT COUNT(s)
            FROM LogSheet s
            WHERE (:unitIds IS NULL OR s.operationalUnitId IN :unitIds)
            """)
    long countVisible(@Param("unitIds") Collection<Long> unitIds);

    /**
     * Atomic self-claim: only succeeds when the sheet is still {@code PENDING}.
     * Returns 1 if this caller won, 0 if another claim/assign already took it.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE LogSheet s
            SET s.assigneeUserId = :userId,
                s.assignmentType = :assignmentType,
                s.assignedByUserId = null,
                s.status = :newStatus,
                s.claimedAt = :now,
                s.startedAt = :now,
                s.operatorName = :operatorName,
                s.updatedAt = :now
            WHERE s.id = :sheetId
              AND s.status = :expectedStatus
            """)
    int claimIfPending(@Param("sheetId") Long sheetId,
                       @Param("userId") Long userId,
                       @Param("assignmentType") AssignmentType assignmentType,
                       @Param("newStatus") LogSheetStatus newStatus,
                       @Param("expectedStatus") LogSheetStatus expectedStatus,
                       @Param("now") long now,
                       @Param("operatorName") String operatorName);

    /**
     * Atomic supervisor assign: only succeeds when the sheet is still {@code PENDING}.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE LogSheet s
            SET s.assigneeUserId = :targetOperatorId,
                s.assignmentType = :assignmentType,
                s.assignedByUserId = :supervisorId,
                s.status = :newStatus,
                s.assignedAt = :now,
                s.claimedAt = null,
                s.startedAt = null,
                s.operatorName = :operatorName,
                s.updatedAt = :now
            WHERE s.id = :sheetId
              AND s.status = :expectedStatus
            """)
    int assignIfPending(@Param("sheetId") Long sheetId,
                        @Param("targetOperatorId") Long targetOperatorId,
                        @Param("supervisorId") Long supervisorId,
                        @Param("assignmentType") AssignmentType assignmentType,
                        @Param("newStatus") LogSheetStatus newStatus,
                        @Param("expectedStatus") LogSheetStatus expectedStatus,
                        @Param("now") long now,
                        @Param("operatorName") String operatorName);

    /**
     * Atomic completion: succeeds only while the sheet is still completable and the device
     * completion time is within {@code dueAt} (when a deadline exists). Includes {@code EXPIRED}
     * so a late sync of on-time offline work can still win against the scheduler.
     * <p>
     * {@code expectedAssigneeUserId} is the ownership guard, and it exists to make one promise:
     * the row must still be assigned to whom the caller read a moment ago, so a concurrent
     * takeover, reassign or release cannot lose to a stale submit. Null means the caller holds
     * a capability that reaches across ownership ({@code CAP:LOGSHEET_COMPLETE_WEB_ANY}) and the
     * status and deadline clauses are the whole guard.
     * <p>
     * There used to be a second, inverted guard — {@code requireUnassigned}, "the row must still
     * have no assignee" — for the expiry scheduler auto-finalising a web draft on a pool sheet.
     * That behaviour is gone (see {@code LogSheetScheduler.expireOverdueSheets}), and with it the
     * only caller that ever passed it; the clause was a tautology for everyone else, so removing
     * it changes nothing for any surviving path. If an unassigned-completion path is ever
     * reintroduced, bring the guard back with it rather than leaving the update unguarded.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE LogSheet s
            SET s.status = :submittedStatus,
                s.completedByUserId = :actorUserId,
                s.completedAt = :completedAt,
                s.submittedAt = :submittedAt,
                s.syncedAt = :syncedAt,
                s.syncStatus = CASE WHEN :syncStatus IS NULL THEN s.syncStatus ELSE :syncStatus END,
                s.operatorName = CASE WHEN :operatorName IS NULL THEN s.operatorName ELSE :operatorName END,
                s.draftSavedAt = NULL,
                s.draftSavedByUserId = NULL,
                s.draftSource = NULL,
                s.expiredAt = NULL,
                s.updatedAt = :syncedAt
            WHERE s.id = :sheetId
              AND s.status IN :completableStatuses
              AND (s.dueAt IS NULL OR s.dueAt >= :completedAt)
              AND (:expectedAssigneeUserId IS NULL OR s.assigneeUserId = :expectedAssigneeUserId)
            """)
    int submitIfStillCompletable(@Param("sheetId") Long sheetId,
                                 @Param("actorUserId") Long actorUserId,
                                 @Param("completedAt") long completedAt,
                                 @Param("submittedAt") long submittedAt,
                                 @Param("syncedAt") long syncedAt,
                                 @Param("syncStatus") String syncStatus,
                                 @Param("operatorName") String operatorName,
                                 @Param("submittedStatus") LogSheetStatus submittedStatus,
                                 @Param("completableStatuses") Collection<LogSheetStatus> completableStatuses,
                                 @Param("expectedAssigneeUserId") Long expectedAssigneeUserId);

    /**
     * Atomic expiry: only marks overdue sheets that are still open (not already submitted).
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE LogSheet s
            SET s.status = :expiredStatus,
                s.expiredAt = :now,
                s.updatedAt = :now
            WHERE s.id = :sheetId
              AND s.status IN :openStatuses
              AND s.dueAt IS NOT NULL
              AND s.dueAt <= :now
            """)
    int expireIfStillOpenAndOverdue(@Param("sheetId") Long sheetId,
                                    @Param("now") long now,
                                    @Param("expiredStatus") LogSheetStatus expiredStatus,
                                    @Param("openStatuses") Collection<LogSheetStatus> openStatuses);

    /**
     * Atomic takeover: succeeds only while the sheet is still open and ownership matches the
     * snapshot observed when the request started (compare-and-set).
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE LogSheet s
            SET s.assigneeUserId = :supervisorId,
                s.assignmentType = :assignmentType,
                s.assignedByUserId = :supervisorId,
                s.status = :newStatus,
                s.claimedAt = :now,
                s.startedAt = :now,
                s.operatorName = :operatorName,
                s.updatedAt = :now
            WHERE s.id = :sheetId
              AND s.status IN :openStatuses
              AND ((:expectedAssigneeUserId IS NULL AND s.assigneeUserId IS NULL)
                   OR s.assigneeUserId = :expectedAssigneeUserId)
              AND ((:expectedAssignmentType IS NULL AND s.assignmentType IS NULL)
                   OR s.assignmentType = :expectedAssignmentType)
            """)
    int takeoverIfStillOpen(@Param("sheetId") Long sheetId,
                            @Param("supervisorId") Long supervisorId,
                            @Param("assignmentType") AssignmentType assignmentType,
                            @Param("newStatus") LogSheetStatus newStatus,
                            @Param("openStatuses") Collection<LogSheetStatus> openStatuses,
                            @Param("expectedAssigneeUserId") Long expectedAssigneeUserId,
                            @Param("expectedAssignmentType") AssignmentType expectedAssignmentType,
                            @Param("now") long now,
                            @Param("operatorName") String operatorName);

    /**
     * Atomic reassign: only while still supervisor-assigned to the expected operator and open.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE LogSheet s
            SET s.assigneeUserId = :targetOperatorId,
                s.assignmentType = :assignmentType,
                s.assignedByUserId = :supervisorId,
                s.status = :newStatus,
                s.assignedAt = :now,
                s.claimedAt = null,
                s.startedAt = null,
                s.operatorName = :operatorName,
                s.updatedAt = :now
            WHERE s.id = :sheetId
              AND s.assignmentType = :expectedAssignmentType
              AND s.assigneeUserId = :expectedAssigneeUserId
              AND s.status IN :openStatuses
            """)
    int reassignIfStillOpen(@Param("sheetId") Long sheetId,
                            @Param("targetOperatorId") Long targetOperatorId,
                            @Param("supervisorId") Long supervisorId,
                            @Param("assignmentType") AssignmentType assignmentType,
                            @Param("expectedAssignmentType") AssignmentType expectedAssignmentType,
                            @Param("expectedAssigneeUserId") Long expectedAssigneeUserId,
                            @Param("newStatus") LogSheetStatus newStatus,
                            @Param("openStatuses") Collection<LogSheetStatus> openStatuses,
                            @Param("now") long now,
                            @Param("operatorName") String operatorName);

    /**
     * Atomic release back to the pool: only while ownership still matches the request snapshot.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE LogSheet s
            SET s.assigneeUserId = null,
                s.assignmentType = null,
                s.assignedByUserId = null,
                s.status = :pendingStatus,
                s.assignedAt = null,
                s.claimedAt = null,
                s.startedAt = null,
                s.operatorName = null,
                s.updatedAt = :now
            WHERE s.id = :sheetId
              AND s.status IN :openStatuses
              AND s.assigneeUserId = :expectedAssigneeUserId
              AND s.assignmentType = :expectedAssignmentType
            """)
    int releaseIfStillOpen(@Param("sheetId") Long sheetId,
                           @Param("pendingStatus") LogSheetStatus pendingStatus,
                           @Param("openStatuses") Collection<LogSheetStatus> openStatuses,
                           @Param("expectedAssigneeUserId") Long expectedAssigneeUserId,
                           @Param("expectedAssignmentType") AssignmentType expectedAssignmentType,
                           @Param("now") long now);

    // -- Third-party integration API ------------------------------------------
    // Two methods, and the rule that keeps them safe is written into the queries themselves:
    // the terminal-status list is a LITERAL in the JPQL, not only a bound parameter.
    //
    // The caller already validates its :statuses argument (IntegrationLogSheetQuery), so the
    // literal looks redundant — and that is exactly the point. A filter that lives only in the
    // caller is a filter the next person to reuse the method will not know exists, and the
    // thing being filtered here is "half-finished work must not leave the building". The
    // duplication costs one line and removes a whole class of future mistake, which is the
    // same reasoning docs/security.md gives for not putting access rules in controllers alone.
    //
    // A sheet that is PENDING, ASSIGNED or IN_PROGRESS cannot be returned by either method
    // however they are called.

    /**
     * Finished log sheets whose completion instant falls in {@code [from, to)}.
     *
     * <p><b>The COALESCE is the definition of "when did this finish".</b> It is a different
     * column per state and each is written exactly once, so the fallback chain is a fact about
     * the lifecycle rather than a guess:
     * <ul>
     *   <li>{@code SUBMITTED} / {@code VOIDED} — {@code completedAt}. Every completion path
     *       ({@code tryApplyCompletion}) writes it from a primitive, so it is never null; a
     *       voided sheet was submitted first and keeps it.</li>
     *   <li>{@code EXPIRED} — {@code expiredAt}. Completion would have made it SUBMITTED, so
     *       {@code completedAt} is null and the chain falls through correctly.</li>
     *   <li>{@code CANCELLED} — {@code cancelledAt}, for the same reason.</li>
     * </ul>
     *
     * <p>Half-open on purpose: {@code >= from AND < to}. See {@code IntegrationLogSheetQuery}.
     *
     * <p>Backed by {@code idx_log_sheets_status_finalized_at}, whose expression matches this
     * one exactly — which is what lets one index serve both the filter and the ordering.
     * Ordering is by that instant and then by id, so a page boundary cannot repeat or skip a
     * row when two sheets finish in the same millisecond.
     *
     * <h2>The shape filters, and why three of them are {@code EXISTS}</h2>
     *
     * <p>A consumer almost never wants every finished round: an ERP importing gauge readings has
     * no use for a shift-handover questionnaire, and a form-processing system has no use for 47
     * pumps. {@code withAssets}, {@code classIds}, {@code assetIds} and {@code severities} are
     * that distinction, and all four are properties of the round's <em>entries</em> rather than
     * of the sheet row — so each is a correlated {@code EXISTS} against
     * {@code log_sheet_entries}, served by {@code idx_log_sheet_entries_log_sheet_id}.
     *
     * <p><b>Not a join.</b> A join to the entries would multiply the sheet by its assets, which
     * breaks the page size (one sheet becomes 47 rows), the count query and the ordering all at
     * once; {@code DISTINCT} would paper over the first and leave the plan sorting what it just
     * de-duplicated. A semi-join asks the only question being asked — "does such a row exist" —
     * and stops at the first match.
     *
     * <p><b>Why {@code withAssets} is a nullable Boolean with two branches</b> rather than an
     * enum: the two answers need {@code EXISTS} and {@code NOT EXISTS}, which no single
     * parameterised predicate expresses, and null-means-no-restriction is the pattern every
     * other filter here already uses.
     *
     * <p>{@code categoryIds} is the category frozen onto the sheet (V19) — the caller's codes
     * resolved and narrowed by the key's scope (V20). Never an empty collection: a filter that
     * nothing satisfies is answered by the service without asking.
     *
     * <p>{@code scanModes} and {@code origins} are plain column filters. {@code scan_mode} is the
     * value frozen onto the sheet by V11 — {@code NOT NULL DEFAULT 'NFC'}, so every round raised
     * before V11 answers {@code NFC}, which is what it was.
     *
     * <p>Every one of them is skipped when its parameter is null, so the unfiltered call plans
     * exactly as it did before they existed.
     */
    @Query("""
            SELECT s FROM LogSheet s
            WHERE s.status IN (com.hnp.backendofflinefirst.domain.LogSheetStatus.SUBMITTED,
                               com.hnp.backendofflinefirst.domain.LogSheetStatus.APPROVED,
                               com.hnp.backendofflinefirst.domain.LogSheetStatus.VOIDED,
                               com.hnp.backendofflinefirst.domain.LogSheetStatus.EXPIRED,
                               com.hnp.backendofflinefirst.domain.LogSheetStatus.CANCELLED)
              AND s.status IN :statuses
              AND COALESCE(s.completedAt, s.expiredAt, s.cancelledAt) >= :from
              AND COALESCE(s.completedAt, s.expiredAt, s.cancelledAt) <  :to
              AND (:unitIds IS NULL OR s.operationalUnitId IN :unitIds)
              AND (:templateIds IS NULL OR s.templateId IN :templateIds)
              AND (:scanModes IS NULL OR s.scanMode IN :scanModes)
              AND (:origins IS NULL OR s.origin IN :origins)
              AND (:categoryIds IS NULL OR s.categoryId IN :categoryIds)
              AND (:withAssets IS NULL
                   OR (:withAssets = TRUE AND EXISTS (
                           SELECT 1 FROM LogSheetEntry ewith WHERE ewith.logSheetId = s.id))
                   OR (:withAssets = FALSE AND NOT EXISTS (
                           SELECT 1 FROM LogSheetEntry ewithout WHERE ewithout.logSheetId = s.id)))
              AND (:classIds IS NULL OR EXISTS (
                      SELECT 1 FROM LogSheetEntry eclass
                      WHERE eclass.logSheetId = s.id AND eclass.classId IN :classIds))
              AND (:assetIds IS NULL OR EXISTS (
                      SELECT 1 FROM LogSheetEntry easset
                      WHERE easset.logSheetId = s.id AND easset.assetId IN :assetIds))
              AND (:severities IS NULL OR EXISTS (
                      SELECT 1 FROM LogSheetEntry esev
                      WHERE esev.logSheetId = s.id AND esev.maxSeverity IN :severities))
            ORDER BY COALESCE(s.completedAt, s.expiredAt, s.cancelledAt) ASC, s.id ASC
            """)
    Page<LogSheet> findExposableToIntegration(@Param("statuses") Collection<LogSheetStatus> statuses,
                                              @Param("from") long from,
                                              @Param("to") long to,
                                              @Param("unitIds") Collection<Long> unitIds,
                                              @Param("templateIds") Collection<Long> templateIds,
                                              @Param("withAssets") Boolean withAssets,
                                              @Param("classIds") Collection<Long> classIds,
                                              @Param("assetIds") Collection<Long> assetIds,
                                              @Param("scanModes") Collection<AssetScanMode> scanModes,
                                              @Param("origins") Collection<GenerationMode> origins,
                                              @Param("severities") Collection<String> severities,
                                              @Param("categoryIds") Collection<Long> categoryIds,
                                              Pageable pageable);

    /**
     * One finished log sheet by id, for the detail endpoint.
     *
     * <p>Empty for an id that does not exist <em>and</em> for one that is still in flight. The
     * caller must not distinguish the two: answering "that sheet exists but you may not see it
     * yet" turns the endpoint into a probe for which ids are live work. A sheet outside the
     * calling key's scope is judged by the service on the row this returns, and answered the
     * same way.
     */
    @Query("""
            SELECT s FROM LogSheet s
            WHERE s.id = :id
              AND s.status IN (com.hnp.backendofflinefirst.domain.LogSheetStatus.SUBMITTED,
                               com.hnp.backendofflinefirst.domain.LogSheetStatus.APPROVED,
                               com.hnp.backendofflinefirst.domain.LogSheetStatus.VOIDED,
                               com.hnp.backendofflinefirst.domain.LogSheetStatus.EXPIRED,
                               com.hnp.backendofflinefirst.domain.LogSheetStatus.CANCELLED)
            """)
    Optional<LogSheet> findExposableToIntegrationById(@Param("id") Long id);

    /**
     * Atomic progress stamp: succeeds only while this caller still holds the sheet and its
     * deadline has not passed.
     *
     * <p>Compare-and-set for the same reason {@code submitIfStillCompletable} is one. A tablet
     * pushes progress on a timer, so a takeover, a reassign, a release or a cancel can land in
     * the middle of one — and a progress push that wrote its values after losing ownership would
     * put a departed operator's readings onto somebody else's round with no trace.
     *
     * <p>{@code ASSIGNED → IN_PROGRESS} happens here too, which is the transition the documented
     * state machine has always called "start (first draft save)" and which nothing actually
     * performed until now. {@code startedAt} is set once and then left alone: it means when the
     * round was first worked on, not when it was last touched.
     *
     * <p><b>The deadline is judged on the server clock here, not on a device time</b> — unlike a
     * completion, which is judged on {@code completedAt} because the work was genuinely done
     * before the deadline even if it arrives afterwards. Progress is a live report about a round
     * still being walked; there is no earlier moment it could belong to, and accepting one after
     * {@code due_at} would let a tablet keep editing an expired sheet. A round finished in time
     * and delivered late is still accepted, by the completion path, exactly as before.
     *
     * @return {@code 1} when this call won, {@code 0} when ownership, status or the clock moved
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE LogSheet s
            SET s.status = :inProgressStatus,
                s.startedAt = CASE WHEN s.startedAt IS NULL THEN :now ELSE s.startedAt END,
                s.draftSavedAt = :now,
                s.draftSavedByUserId = :actorUserId,
                s.draftSource = :draftSource,
                s.operatorName = CASE WHEN s.operatorName IS NULL THEN :operatorName ELSE s.operatorName END,
                s.updatedAt = :now
            WHERE s.id = :sheetId
              AND s.status IN :openStatuses
              AND s.assigneeUserId = :actorUserId
              AND (s.dueAt IS NULL OR s.dueAt >= :now)
            """)
    int markProgressIfStillOwned(@Param("sheetId") Long sheetId,
                                 @Param("actorUserId") Long actorUserId,
                                 @Param("now") long now,
                                 @Param("draftSource") String draftSource,
                                 @Param("operatorName") String operatorName,
                                 @Param("inProgressStatus") LogSheetStatus inProgressStatus,
                                 @Param("openStatuses") Collection<LogSheetStatus> openStatuses);

    /**
     * Claims the right to write this round's one {@code START} action row.
     *
     * <p><b>Finding 18f.</b> The caller used to decide that from a field it had read a moment
     * earlier — {@code sheet.getStartedAt() == null} — and then write the row after a separate
     * atomic update. {@code started_at} itself was never at risk, because
     * {@link #markProgressIfStillOwned} sets it with a {@code CASE WHEN ... IS NULL}; what was not
     * atomic was the <em>decision to log</em>, so two progress pushes from one assignee on two
     * devices — a configuration this system supports — could each read {@code null} and each
     * append a {@code START} row to a history whose entire value is that it has eleven rows that
     * matter.
     *
     * <p>Now the decision is a row count: whoever this statement returns 1 to is the first
     * reporter, and nobody else can be. It runs only when the caller's own read already said the
     * round had not started, so an ordinary push — every push after the first — costs nothing
     * extra. The guards are the same three the progress update applies, so a caller who has lost
     * ownership cannot stamp {@code started_at} on somebody else's round.
     *
     * @return {@code 1} when this call set {@code started_at}, {@code 0} when it was already set
     *         or the round is no longer this actor's to report on
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE LogSheet s
            SET s.startedAt = :now
            WHERE s.id = :sheetId
              AND s.startedAt IS NULL
              AND s.status IN :openStatuses
              AND s.assigneeUserId = :actorUserId
              AND (s.dueAt IS NULL OR s.dueAt >= :now)
            """)
    int claimFirstProgressReport(@Param("sheetId") Long sheetId,
                                 @Param("actorUserId") Long actorUserId,
                                 @Param("now") long now,
                                 @Param("openStatuses") Collection<LogSheetStatus> openStatuses);
}
