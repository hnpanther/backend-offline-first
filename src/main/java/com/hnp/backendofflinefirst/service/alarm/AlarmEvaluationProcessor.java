package com.hnp.backendofflinefirst.service.alarm;

import com.hnp.backendofflinefirst.entity.AlarmEvaluationRequest;
import com.hnp.backendofflinefirst.repository.AlarmEvaluationRequestRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Drains {@code alarm_evaluation_requests}: one round per transaction, failures retried.
 *
 * <h2>Two ways in, one drain</h2>
 *
 * <p>Right after a round's transaction commits, {@link AlarmQueue} nudges a background thread
 * here, so an alarm appears within moments. And {@link AlarmQueue#scheduledNudge()} nudges it every
 * minute regardless — the guarantee, for the nudge that was discarded, the process that stopped,
 * the evaluation that failed. Both end in {@link #drain()}, which holds a lock so passes never
 * interleave inside one JVM; across JVMs the rows are claimed with {@code FOR UPDATE SKIP LOCKED}
 * and leased, so two instances take different rows (roadmap §19d's shape, safe under N replicas
 * from day one).
 *
 * <p><b>Never on the scheduler thread.</b> Every {@code @Scheduled} method in this application
 * shares one thread (docs/jobs.md §1), so a pass run there would delay log-sheet generation and
 * expiry for as long as it took. The schedule only nudges; the work is on the alarm thread.
 *
 * <h2>A failure is retried, never surfaced</h2>
 *
 * <p>Each round is evaluated in its own {@code REQUIRES_NEW} transaction. If it fails — an
 * optimistic-lock collision with a supervisor acknowledging the same alarm, a lost race on the
 * one-live-alarm index, a database hiccup — that transaction rolls back, the request stays queued
 * with a backoff and its last error, and the rest of the batch carries on. One bad round must not
 * wedge the queue: the tablet's attachment delete queue once stopped every deletion behind one row
 * the server did not recognise, and this is that lesson applied in advance.
 */
@Component
@Slf4j
public class AlarmEvaluationProcessor {

    static final int BATCH_SIZE = 20;
    static final int MAX_BATCHES_PER_PASS = 50;
    static final long LEASE_MS = 5 * 60_000L;
    static final long MAX_BACKOFF_MS = 60 * 60_000L;

    private final AlarmEvaluationRequestRepository requestRepository;
    private final AlarmEvaluationService evaluationService;
    private final ObjectProvider<AlarmWebhookDispatcher> dispatcher;
    private final TransactionTemplate requiresNew;
    private final ReentrantLock lock = new ReentrantLock();

    public AlarmEvaluationProcessor(AlarmEvaluationRequestRepository requestRepository,
                                    AlarmEvaluationService evaluationService,
                                    ObjectProvider<AlarmWebhookDispatcher> dispatcher,
                                    PlatformTransactionManager transactionManager) {
        this.requestRepository = requestRepository;
        this.evaluationService = evaluationService;
        this.dispatcher = dispatcher;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Evaluates every due request. Blocks while another pass in this JVM is running, then runs —
     * so a caller that returns from this knows everything queued before the call has been tried.
     *
     * @return how many requests were evaluated successfully
     */
    public int drain() {
        lock.lock();
        try {
            int succeeded = 0;
            for (int batch = 0; batch < MAX_BATCHES_PER_PASS; batch++) {
                long now = System.currentTimeMillis();
                List<Long> ids = requiresNew.execute(status -> {
                    List<Long> due = requestRepository.lockDue(now, BATCH_SIZE);
                    if (!due.isEmpty()) {
                        requestRepository.lease(due, now + LEASE_MS);
                    }
                    return due;
                });
                if (ids == null || ids.isEmpty()) {
                    break;
                }
                for (Long id : ids) {
                    if (processOne(id)) {
                        succeeded++;
                    }
                }
            }
            if (succeeded > 0) {
                dispatcher.ifAvailable(AlarmWebhookDispatcher::nudge);
            }
            return succeeded;
        } finally {
            lock.unlock();
        }
    }

    private boolean processOne(Long requestId) {
        try {
            requiresNew.executeWithoutResult(status -> {
                AlarmEvaluationRequest request = requestRepository.findById(requestId).orElse(null);
                if (request == null) {
                    return; // its round was deleted, and the row with it
                }
                evaluationService.evaluateSheet(request.getLogSheetId(), request.getReason());
                requestRepository.delete(request);
            });
            return true;
        } catch (RuntimeException e) {
            recordFailure(requestId, e);
            return false;
        }
    }

    private void recordFailure(Long requestId, RuntimeException failure) {
        String message = failure.getClass().getSimpleName()
                + (failure.getMessage() == null ? "" : ": " + failure.getMessage());
        try {
            requiresNew.executeWithoutResult(status ->
                    requestRepository.findById(requestId).ifPresent(request -> {
                        int attempts = request.getAttempts() + 1;
                        request.setAttempts(attempts);
                        request.setNextAttemptAt(System.currentTimeMillis() + backoffMs(attempts));
                        request.setLastError(message.length() <= 1000 ? message : message.substring(0, 1000));
                        requestRepository.save(request);
                        if (attempts >= 5) {
                            log.error("Alarm evaluation of log sheet {} has failed {} times: {}",
                                    request.getLogSheetId(), attempts, message);
                        } else {
                            log.warn("Alarm evaluation of log sheet {} failed (attempt {}), will retry: {}",
                                    request.getLogSheetId(), attempts, message);
                        }
                    }));
        } catch (RuntimeException e) {
            // The lease will expire and the row be retried anyway.
            log.warn("Could not record the alarm evaluation failure for request {}: {}",
                    requestId, e.getMessage());
        }
    }

    /** 30 s, 1 min, 2 min, … capped at an hour. Never gives up: an alarm left unjudged is a bug. */
    static long backoffMs(int attempts) {
        int exponent = Math.max(0, Math.min(attempts - 1, 20));
        return Math.min(30_000L << exponent, MAX_BACKOFF_MS);
    }
}
