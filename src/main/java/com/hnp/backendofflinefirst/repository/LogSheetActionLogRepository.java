package com.hnp.backendofflinefirst.repository;

import com.hnp.backendofflinefirst.entity.LogSheetActionLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LogSheetActionLogRepository extends JpaRepository<LogSheetActionLog, Long> {
    List<LogSheetActionLog> findByLogSheetIdOrderByActionAtAsc(Long logSheetId);
    boolean existsByClientActionId(String clientActionId);
    boolean existsByActorUserId(Long actorUserId);
    boolean existsByFromUserId(Long fromUserId);
    boolean existsByToUserId(Long toUserId);
}
