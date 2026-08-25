package com.hnp.backendofflinefirst.repository;

import com.hnp.backendofflinefirst.domain.AssignmentType;
import com.hnp.backendofflinefirst.domain.LogSheetStatus;
import com.hnp.backendofflinefirst.entity.LogSheet;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface LogSheetRepository extends JpaRepository<LogSheet, Long> {

    @Query("""
            SELECT s FROM LogSheet s
            WHERE (:unitIds IS NULL OR s.operationalUnitId IN :unitIds)
              AND (:status IS NULL OR s.status = :status)
              AND (LOWER(COALESCE(s.templateName, '')) LIKE LOWER(CONCAT('%', :q, '%'))
                OR LOWER(COALESCE(s.operatorName, '')) LIKE LOWER(CONCAT('%', :q, '%')))
            """)
    Page<LogSheet> searchVisibleWithTerm(@Param("unitIds") Collection<Long> unitIds,
                                         @Param("status") LogSheetStatus status,
                                         @Param("q") String q,
                                         Pageable pageable);

    @Query("""
            SELECT s FROM LogSheet s
            WHERE (:unitIds IS NULL OR s.operationalUnitId IN :unitIds)
              AND (:status IS NULL OR s.status = :status)
            """)
    Page<LogSheet> searchVisible(@Param("unitIds") Collection<Long> unitIds,
                                 @Param("status") LogSheetStatus status,
                                 Pageable pageable);
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
                   SUM(CASE WHEN s.status = com.hnp.backendofflinefirst.domain.LogSheetStatus.SUBMITTED THEN 1 ELSE 0 END),
                   SUM(CASE WHEN s.status = com.hnp.backendofflinefirst.domain.LogSheetStatus.SUBMITTED
                             AND s.dueAt IS NOT NULL
                             AND COALESCE(s.completedAt, s.submittedAt) <= s.dueAt THEN 1 ELSE 0 END),
                   SUM(CASE WHEN s.status = com.hnp.backendofflinefirst.domain.LogSheetStatus.SUBMITTED
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
                   SUM(CASE WHEN s.status = com.hnp.backendofflinefirst.domain.LogSheetStatus.SUBMITTED THEN 1 ELSE 0 END),
                   SUM(CASE WHEN s.status = com.hnp.backendofflinefirst.domain.LogSheetStatus.SUBMITTED
                             AND s.dueAt IS NOT NULL
                             AND COALESCE(s.completedAt, s.submittedAt) <= s.dueAt THEN 1 ELSE 0 END),
                   SUM(CASE WHEN s.status = com.hnp.backendofflinefirst.domain.LogSheetStatus.SUBMITTED
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

    /** Raw lateness values (millis past due) for percentile maths done in Java. */
    @Query("""
            SELECT s.operationalUnitId, (COALESCE(s.completedAt, s.submittedAt) - s.dueAt)
            FROM LogSheet s
            WHERE (:unitIds IS NULL OR s.operationalUnitId IN :unitIds)
              AND (:from IS NULL OR s.createdAt >= :from)
              AND (:to IS NULL OR s.createdAt <= :to)
              AND s.status = com.hnp.backendofflinefirst.domain.LogSheetStatus.SUBMITTED
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
              AND s.status = com.hnp.backendofflinefirst.domain.LogSheetStatus.SUBMITTED
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

    /** Submitted sheets in a window, newest first - the source for the out-of-range scan. */
    @Query("""
            SELECT s FROM LogSheet s
            WHERE (:unitIds IS NULL OR s.operationalUnitId IN :unitIds)
              AND s.status = com.hnp.backendofflinefirst.domain.LogSheetStatus.SUBMITTED
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
     */
    @Query("""
            SELECT s FROM LogSheet s
            WHERE s.status IN (com.hnp.backendofflinefirst.domain.LogSheetStatus.SUBMITTED,
                               com.hnp.backendofflinefirst.domain.LogSheetStatus.VOIDED,
                               com.hnp.backendofflinefirst.domain.LogSheetStatus.EXPIRED,
                               com.hnp.backendofflinefirst.domain.LogSheetStatus.CANCELLED)
              AND s.status IN :statuses
              AND COALESCE(s.completedAt, s.expiredAt, s.cancelledAt) >= :from
              AND COALESCE(s.completedAt, s.expiredAt, s.cancelledAt) <  :to
              AND (:unitId IS NULL OR s.operationalUnitId = :unitId)
              AND (:templateId IS NULL OR s.templateId = :templateId)
            ORDER BY COALESCE(s.completedAt, s.expiredAt, s.cancelledAt) ASC, s.id ASC
            """)
    Page<LogSheet> findExposableToIntegration(@Param("statuses") Collection<LogSheetStatus> statuses,
                                              @Param("from") long from,
                                              @Param("to") long to,
                                              @Param("unitId") Long unitId,
                                              @Param("templateId") Long templateId,
                                              Pageable pageable);

    /**
     * One finished log sheet by id, for the detail endpoint.
     *
     * <p>Empty for an id that does not exist <em>and</em> for one that is still in flight. The
     * caller must not distinguish the two: answering "that sheet exists but you may not see it
     * yet" turns the endpoint into a probe for which ids are live work.
     */
    @Query("""
            SELECT s FROM LogSheet s
            WHERE s.id = :id
              AND s.status IN (com.hnp.backendofflinefirst.domain.LogSheetStatus.SUBMITTED,
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
}
