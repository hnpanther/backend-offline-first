package com.hnp.backendofflinefirst.repository;

import com.hnp.backendofflinefirst.domain.FormSubmissionStatus;
import com.hnp.backendofflinefirst.entity.FormSubmission;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface FormSubmissionRepository extends JpaRepository<FormSubmission, Long> {

    @Query(value = "SELECT coalesce(max(position), 0) FROM form_history_counters WHERE reporter_id = :userId", nativeQuery = true)
    long historyHead(Long userId);

    @Query("""
            SELECT s FROM FormSubmission s WHERE s.reportedByUserId = :userId
            AND s.historyPosition > :after AND s.historyPosition <= :through
            AND (s.status = com.hnp.backendofflinefirst.domain.FormSubmissionStatus.DRAFT
                 OR :since = 0 OR coalesce(s.syncedAt, s.submittedAt, s.updatedAt) >= :since)
            ORDER BY s.historyPosition
            """)
    List<FormSubmission> historyPage(Long userId, long after, long through, long since, Pageable limit);

    Optional<FormSubmission> findByReportedByUserIdAndLocalId(Long userId, String localId);

    // A first delivery has no row to lock. Hash collisions only serialize unrelated creations.
    @Query(value = "SELECT 1 FROM pg_advisory_xact_lock(:namespace, hashtext(:key))", nativeQuery = true)
    Integer lockDeliveryKey(int namespace, String key);

    boolean existsByFormDefinitionId(Long formDefinitionId);

    List<FormSubmission> findByReportedByUserIdAndStatusOrderByUpdatedAtDesc(
            Long userId, FormSubmissionStatus status);

    /**
     * Panel / supervisor list: unit-scoped on {@code subject_unit_id} only (mirrors log-sheet
     * {@code operational_unit_id}). Author is <b>not</b> a list predicate — own work reaches the
     * tablet via the owner history feed, like «کارتابل من» for assignees.
     */
    @Query("""
            SELECT s FROM FormSubmission s
            WHERE (:formId IS NULL OR s.formDefinitionId = :formId)
              AND (:status IS NULL OR s.status = :status)
              AND (
                    :unrestricted = TRUE
                 OR s.subjectUnitId IN :unitIds
              )
            ORDER BY s.updatedAt DESC
            """)
    Page<FormSubmission> findVisibleFiltered(@Param("unrestricted") boolean unrestricted,
                                             @Param("unitIds") Collection<Long> unitIds,
                                             @Param("formId") Long formId,
                                             @Param("status") FormSubmissionStatus status,
                                             Pageable pageable);

    @Query("""
            SELECT s FROM FormSubmission s
            WHERE s.reportedByUserId = :userId
              AND (s.updatedAt < :beforeTime OR (s.updatedAt = :beforeTime AND s.id < :beforeId))
            ORDER BY s.updatedAt DESC, s.id DESC
            """)
    List<FormSubmission> legacyHistoryPage(Long userId, long beforeTime, long beforeId, Pageable limit);
}
