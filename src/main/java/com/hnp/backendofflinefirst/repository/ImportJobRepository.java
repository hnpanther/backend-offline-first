package com.hnp.backendofflinefirst.repository;

import com.hnp.backendofflinefirst.domain.ImportJobStatus;
import com.hnp.backendofflinefirst.entity.ImportJob;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ImportJobRepository extends JpaRepository<ImportJob, Long> {

    Optional<ImportJob> findByJobUuid(String jobUuid);

    List<ImportJob> findTop50BySubmittedByUserIdOrderByCreatedAtDesc(Long userId);

    List<ImportJob> findBySubmittedByUserIdAndStatusInOrderByCreatedAtDesc(
            Long userId, Collection<ImportJobStatus> statuses);

    List<ImportJob> findByStatus(ImportJobStatus status);
}
