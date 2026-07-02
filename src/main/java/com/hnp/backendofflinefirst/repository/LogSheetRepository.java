package com.hnp.backendofflinefirst.repository;

import com.hnp.backendofflinefirst.domain.LogSheetStatus;
import com.hnp.backendofflinefirst.entity.LogSheet;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface LogSheetRepository extends JpaRepository<LogSheet, Long> {
    Optional<LogSheet> findByLocalId(String localId);
    List<LogSheet> findByOperationalUnitIdIn(Collection<Long> unitIds);
    List<LogSheet> findByOperationalUnitIdInAndStatus(Collection<Long> unitIds, LogSheetStatus status);
    List<LogSheet> findByAssigneeUserId(Long assigneeUserId);
    List<LogSheet> findByStatusInAndDueAtLessThanEqual(Collection<LogSheetStatus> statuses, Long threshold);
}
