package com.hnp.backendofflinefirst.repository;

import com.hnp.backendofflinefirst.entity.LogSheetVoidSubmission;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LogSheetVoidSubmissionRepository extends JpaRepository<LogSheetVoidSubmission, Long> {
    List<LogSheetVoidSubmission> findByLogSheetId(Long logSheetId);
    boolean existsBySubmittedByUserId(Long submittedByUserId);
}
