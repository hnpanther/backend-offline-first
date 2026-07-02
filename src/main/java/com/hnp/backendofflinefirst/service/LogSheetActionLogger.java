package com.hnp.backendofflinefirst.service;

import com.hnp.backendofflinefirst.domain.ActionSource;
import com.hnp.backendofflinefirst.domain.LogSheetActionType;
import com.hnp.backendofflinefirst.entity.LogSheetActionLog;
import com.hnp.backendofflinefirst.repository.LogSheetActionLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Writes the immutable audit trail for log-sheet lifecycle actions. Keeping this
 * in one place guarantees every transition records who/what/when consistently.
 */
@Service
@RequiredArgsConstructor
public class LogSheetActionLogger {

    private final LogSheetActionLogRepository repository;

    /**
     * Records an action.
     *
     * @param actionAt       when the action truly happened (device time if offline)
     * @param clientActionId optional idempotency key for replayed offline actions
     */
    public LogSheetActionLog record(Long logSheetId, LogSheetActionType action, ActionSource source,
                                    Long actorUserId, Long fromUserId, Long toUserId,
                                    Long actionAt, String clientActionId) {
        long now = System.currentTimeMillis();
        LogSheetActionLog log = new LogSheetActionLog();
        log.setLogSheetId(logSheetId);
        log.setAction(action);
        log.setSource(source);
        log.setActorUserId(actorUserId);
        log.setFromUserId(fromUserId);
        log.setToUserId(toUserId);
        log.setActionAt(actionAt != null ? actionAt : now);
        log.setRecordedAt(now);
        log.setClientActionId(clientActionId);
        return repository.save(log);
    }

    public boolean isReplay(String clientActionId) {
        return clientActionId != null && repository.existsByClientActionId(clientActionId);
    }

    public List<LogSheetActionLog> history(Long logSheetId) {
        return repository.findByLogSheetIdOrderByActionAtAsc(logSheetId);
    }
}
