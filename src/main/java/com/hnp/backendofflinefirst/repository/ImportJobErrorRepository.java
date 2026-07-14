package com.hnp.backendofflinefirst.repository;

import com.hnp.backendofflinefirst.entity.ImportJobError;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ImportJobErrorRepository extends JpaRepository<ImportJobError, Long> {

    List<ImportJobError> findTop100ByJobIdOrderByRowNumAsc(Long jobId);

    void deleteByJobId(Long jobId);
}
