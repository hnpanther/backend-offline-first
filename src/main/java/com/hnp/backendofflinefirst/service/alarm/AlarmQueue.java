package com.hnp.backendofflinefirst.service.alarm;

import com.hnp.backendofflinefirst.entity.AlarmEvaluationRequest;
import com.hnp.backendofflinefirst.repository.AlarmEvaluationRequestRepository;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The one call a round-changing path makes: «re-judge this round's assets».
 *
 * <h2>Why this is all a completion path does</h2>
 *
 * <p>A tablet delivering a finished round, the web page completing one, a supervisor voiding or
 * restoring one — none of those may fail because of an alarm. So they insert one row here, in
 * their own transaction, which cannot conflict with anything (no unique key), and that is the
 * whole of their involvement. The evaluation runs after the commit, in a transaction of its own,
 * and a failure there is retried from this row, not reported to the operator.
 *
 * <h2>Prompt, and still durable</h2>
 *
 * <p>After the commit, one background thread is nudged to drain the queue, so an alarm appears
 * within moments of the round arriving. If the nudge is lost — the queue is full, the process
 * stops — the row is still there, and {@link AlarmEvaluationProcessor}'s scheduled pass takes it.
 * The executor is private and single-threaded on purpose: it is not a general pool, and adding a
 * second {@code Executor} bean would change how {@code @Async} resolves its default one.
 */
@Component
@Slf4j
public class AlarmQueue {

    private final AlarmEvaluationRequestRepository requestRepository;
    private final AlarmSettings settings;
    private final ObjectProvider<AlarmEvaluationProcessor> processor;

    /**
     * One worker, one waiting slot, extra nudges discarded. A nudge means «drain the queue», and
     * a drain already running or already waiting will see the new row — so a third is pointless,
     * and discarding it is correct rather than lossy.
     */
    private final ThreadPoolExecutor nudges;

    public AlarmQueue(AlarmEvaluationRequestRepository requestRepository,
                      AlarmSettings settings,
                      ObjectProvider<AlarmEvaluationProcessor> processor) {
        this.requestRepository = requestRepository;
        this.settings = settings;
        this.processor = processor;
        AtomicInteger counter = new AtomicInteger();
        this.nudges = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(1),
                runnable -> {
                    Thread thread = new Thread(runnable, "alarm-eval-" + counter.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.DiscardPolicy());
    }

    /**
     * Records that this round's alarms need re-judging, and arranges for that to happen as soon
     * as the caller's transaction commits.
     *
     * @param reason COMPLETED / VOIDED / RESTORED — for the log, not for the logic
     */
    public void request(Long logSheetId, String reason) {
        if (logSheetId == null) {
            return;
        }
        long now = System.currentTimeMillis();
        AlarmEvaluationRequest request = new AlarmEvaluationRequest();
        request.setLogSheetId(logSheetId);
        request.setReason(reason);
        request.setAttempts(0);
        request.setNextAttemptAt(now);
        request.setCreatedAt(now);
        requestRepository.save(request);

        if (settings.isEvaluateAfterCommit()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    nudge();
                }
            });
        }
    }

    /**
     * The guarantee behind the after-commit nudge: anything a lost nudge, a stopped process or a
     * failed evaluation left queued is taken within a minute.
     *
     * <p><b>It only nudges.</b> Every {@code @Scheduled} method in this application runs on one
     * shared thread (docs/jobs.md §1); doing the evaluation here would make log-sheet generation and
     * expiry wait behind it. The work runs on this class's own thread.
     */
    @Scheduled(fixedDelayString = "${app.alarm.evaluation.poll-ms:60000}",
               initialDelayString = "${app.alarm.evaluation.initial-delay-ms:30000}")
    public void scheduledNudge() {
        nudge();
    }

    /** Asks the background thread to drain the queue. Never throws, never blocks. */
    public void nudge() {
        try {
            nudges.execute(() -> {
                try {
                    processor.getObject().drain();
                } catch (RuntimeException e) {
                    // The rows are still queued; the scheduled pass will take them.
                    log.warn("Alarm evaluation pass failed; queued rounds will be retried: {}",
                            e.getMessage());
                }
            });
        } catch (RejectedExecutionException ignored) {
            // Shutting down, or a pass is already waiting. Either way nothing is lost.
        }
    }

    @PreDestroy
    void shutdown() {
        nudges.shutdownNow();
    }
}
