package com.hnp.backendofflinefirst.repository;

import com.hnp.backendofflinefirst.entity.LogSheetVoidSubmission;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface LogSheetVoidSubmissionRepository extends JpaRepository<LogSheetVoidSubmission, Long> {
    List<LogSheetVoidSubmission> findByLogSheetId(Long logSheetId);
    boolean existsBySubmittedByUserId(Long submittedByUserId);

    /** The one late-progress row for (sheet, operator), if any — V25's partial unique index. */
    Optional<LogSheetVoidSubmission> findFirstByLogSheetIdAndSubmittedByUserIdAndLateProgressTrue(
            Long logSheetId, Long submittedByUserId);

    /** A refused submission with exactly this content, already kept (V26, issue B-04 §2). */
    Optional<LogSheetVoidSubmission> findFirstByLogSheetIdAndSubmittedByUserIdAndPayloadHashAndLateProgressFalse(
            Long logSheetId, Long submittedByUserId, String payloadHash);

    /** How many refused submissions this person has left on this sheet (V26, issue B-04 §2). */
    long countByLogSheetIdAndSubmittedByUserIdAndLateProgressFalse(Long logSheetId, Long submittedByUserId);
}
