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
    Optional<LogSheet> findByLocalId(String localId);
    List<LogSheet> findByOperationalUnitIdIn(Collection<Long> unitIds);
    List<LogSheet> findByOperationalUnitIdInAndStatus(Collection<Long> unitIds, LogSheetStatus status);
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
     * When {@code expectedAssigneeUserId} is non-null, the row must still be assigned to that
     * user so a concurrent takeover/reassign/release cannot lose to a stale submit.
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
}
