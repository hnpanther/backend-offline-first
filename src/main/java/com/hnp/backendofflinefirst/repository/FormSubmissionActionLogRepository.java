package com.hnp.backendofflinefirst.repository;

import com.hnp.backendofflinefirst.entity.FormSubmissionActionLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface FormSubmissionActionLogRepository extends JpaRepository<FormSubmissionActionLog, Long> {

    List<FormSubmissionActionLog> findByFormSubmissionIdOrderByRecordedAtDesc(Long formSubmissionId);
}
