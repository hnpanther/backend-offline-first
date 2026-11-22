package com.hnp.backendofflinefirst.repository;

import com.hnp.backendofflinefirst.entity.FormSubmissionRevision;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface FormSubmissionRevisionRepository extends JpaRepository<FormSubmissionRevision, Long> {

    List<FormSubmissionRevision> findByFormSubmissionIdOrderByIdAsc(Long formSubmissionId);
}
