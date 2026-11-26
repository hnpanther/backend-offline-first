package com.hnp.backendofflinefirst.repository;

import com.hnp.backendofflinefirst.domain.FormSubmissionActionType;
import com.hnp.backendofflinefirst.entity.FormSubmissionActionLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface FormSubmissionActionLogRepository extends JpaRepository<FormSubmissionActionLog, Long> {

    Optional<FormSubmissionActionLog> findByClientActionId(String key);

    boolean existsByFormSubmissionIdAndAction(Long submissionId, FormSubmissionActionType action);

    List<FormSubmissionActionLog> findByFormSubmissionIdOrderByRecordedAtDesc(Long formSubmissionId);
}
