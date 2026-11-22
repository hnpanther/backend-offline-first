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

    Optional<FormSubmission> findByClientActionId(String clientActionId);

    Optional<FormSubmission> findByReportedByUserIdAndLocalId(Long userId, String localId);

    boolean existsByFormDefinitionId(Long formDefinitionId);

    List<FormSubmission> findByReportedByUserIdAndStatusOrderByUpdatedAtDesc(
            Long userId, FormSubmissionStatus status);

    @Query("""
            SELECT s FROM FormSubmission s
            WHERE s.reportedByUserId = :userId
               OR s.reporterUnitId IN :unitIds
               OR s.subjectUnitId IN :unitIds
            ORDER BY s.updatedAt DESC
            """)
    Page<FormSubmission> findVisible(@Param("userId") Long userId,
                                     @Param("unitIds") Collection<Long> unitIds,
                                     Pageable pageable);

    @Query("""
            SELECT s FROM FormSubmission s
            WHERE (:formId IS NULL OR s.formDefinitionId = :formId)
              AND (:status IS NULL OR s.status = :status)
              AND (
                    :unrestricted = TRUE
                 OR s.reportedByUserId = :userId
                 OR s.reporterUnitId IN :unitIds
                 OR s.subjectUnitId IN :unitIds
              )
            ORDER BY s.updatedAt DESC
            """)
    Page<FormSubmission> findVisibleFiltered(@Param("unrestricted") boolean unrestricted,
                                             @Param("userId") Long userId,
                                             @Param("unitIds") Collection<Long> unitIds,
                                             @Param("formId") Long formId,
                                             @Param("status") FormSubmissionStatus status,
                                             Pageable pageable);

    @Query("""
            SELECT s FROM FormSubmission s
            WHERE s.reportedByUserId = :userId
            ORDER BY s.updatedAt DESC
            """)
    List<FormSubmission> findOwn(@Param("userId") Long userId);
}
