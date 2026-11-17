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
     * Records an action with no explanatory comment — the shape every action used before
     * comments existed, and still the right call for actions that carry no user-entered reason
     * (GENERATE, CLAIM, EXPIRE, …).
     *
     * @param actionAt       when the action truly happened (device time if offline)
     * @param clientActionId optional idempotency key for replayed offline actions
     */
    public LogSheetActionLog record(Long logSheetId, LogSheetActionType action, ActionSource source,
                                    Long actorUserId, Long fromUserId, Long toUserId,
                                    Long actionAt, String clientActionId) {
        return record(logSheetId, action, source, actorUserId, fromUserId, toUserId,
                actionAt, clientActionId, null);
    }

    /**
     * Records an action together with the actor's optional explanation.
     *
     * @param comment free-text reason, or null/blank when the actor gave none — never required
     */
    public LogSheetActionLog record(Long logSheetId, LogSheetActionType action, ActionSource source,
                                    Long actorUserId, Long fromUserId, Long toUserId,
                                    Long actionAt, String clientActionId, String comment) {
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
        log.setComment(comment);
        return repository.save(log);
    }

    public boolean isReplay(String clientActionId) {
        return clientActionId != null && repository.existsByClientActionId(clientActionId);
    }

    /**
     * The action a replayed idempotency key was first recorded on — only when the same person
     * recorded it. Issue B-03: "does this key exist" is not the question; a key recorded on a
     * {@code SUPERSEDE} (the submission was refused and kept) must not be answered
     * {@code DUPLICATE} (delivered), and a key presented by somebody else is not their replay.
     */
    public java.util.Optional<LogSheetActionType> replayedAs(String clientActionId, Long actorUserId) {
        if (clientActionId == null) return java.util.Optional.empty();
        return repository.findFirstByClientActionId(clientActionId)
                .filter(row -> actorUserId != null && actorUserId.equals(row.getActorUserId()))
                .map(LogSheetActionLog::getAction);
    }

    /** Whether any action row already carries this key, whoever recorded it. */
    public boolean keyTaken(String clientActionId) {
        return isReplay(clientActionId);
    }

    /** See {@link LogSheetActionLogRepository#everHeldBy}. */
    public boolean everHeldBy(Long logSheetId, Long userId) {
        return logSheetId != null && userId != null && repository.everHeldBy(logSheetId, userId);
    }

    public List<LogSheetActionLog> history(Long logSheetId) {
        return repository.findByLogSheetIdOrderByActionAtAsc(logSheetId);
    }
}
