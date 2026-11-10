package com.hnp.backendofflinefirst.service.alarm;

import com.hnp.backendofflinefirst.domain.AlarmWebhookState;
import com.hnp.backendofflinefirst.entity.AlarmWebhookDelivery;
import com.hnp.backendofflinefirst.repository.AlarmWebhookDeliveryRepository;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * POSTs queued alarm events to the configured webhook — the drain of the outbox.
 *
 * <h2>The request</h2>
 *
 * <pre>
 * POST {app.alarm.webhook.url}
 * Content-Type: application/json; charset=utf-8
 * X-API-Key:    {app.alarm.webhook.api-key}      (omitted when none is configured)
 * X-Event-Id:   {uuid}                           (the receiver deduplicates on it)
 * X-Event-Type: ALARM_RAISED | ALARM_ESCALATED
 * </pre>
 *
 * <p>Any 2xx is delivered. Anything else — another status, a refused connection, a timeout — is
 * an attempt that failed: retried with backoff (30 s, 1 min, 2 min … capped at an hour) until
 * {@code app.alarm.webhook.max-attempts}, then left {@code FAILED}, visible on the rules page with
 * its last error, and sent again only when an administrator asks.
 *
 * <h2>Rules the code keeps</h2>
 * <ul>
 *   <li><b>No call is made inside a transaction.</b> The row is claimed and leased in one short
 *       transaction, the POST happens with none open, the outcome is written in another. A slow
 *       receiver therefore holds no database connection and no row lock.</li>
 *   <li><b>At-least-once, stated.</b> A process killed between the receiver's 2xx and the
 *       «delivered» write sends the event again when the lease expires. The receiver must
 *       deduplicate on {@code X-Event-Id}; docs say so.</li>
 *   <li><b>Redirects are not followed</b> and both timeouts are mandatory, so a hung or hostile
 *       receiver cannot hold a thread or bounce the server somewhere else.</li>
 *   <li><b>The key is never logged</b> and never written into {@code last_error}.</li>
 * </ul>
 */
@Component
@Slf4j
public class AlarmWebhookDispatcher {

    static final int BATCH_SIZE = 25;
    static final int MAX_BATCHES_PER_PASS = 20;
    static final long MAX_BACKOFF_MS = 60 * 60_000L;
    private static final int MAX_ERROR_BODY = 300;

    private final AlarmSettings settings;
    private final AlarmWebhookDeliveryRepository deliveryRepository;
    private final TransactionTemplate requiresNew;
    private final ReentrantLock lock = new ReentrantLock();
    private final ThreadPoolExecutor nudges;
    private volatile HttpClient client;

    public AlarmWebhookDispatcher(AlarmSettings settings,
                                  AlarmWebhookDeliveryRepository deliveryRepository,
                                  PlatformTransactionManager transactionManager) {
        this.settings = settings;
        this.deliveryRepository = deliveryRepository;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.nudges = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(1),
                runnable -> {
                    Thread thread = new Thread(runnable, "alarm-webhook");
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.DiscardPolicy());
    }

    /**
     * Retries and anything a lost nudge left behind.
     *
     * <p><b>It only nudges.</b> Every {@code @Scheduled} method in this application runs on one
     * shared thread (docs/jobs.md §1), and a pass against a receiver that is down is a sequence of
     * connect timeouts: run here, it would hold log-sheet generation and expiry for minutes. The
     * POSTs happen on this class's own thread.
     */
    @Scheduled(fixedDelayString = "${app.alarm.webhook.poll-ms:15000}",
               initialDelayString = "${app.alarm.webhook.initial-delay-ms:20000}")
    public void scheduledNudge() {
        nudge();
    }

    /** Asks the background thread to deliver what is due. Never throws, never blocks. */
    public void nudge() {
        if (!settings.isWebhookEnabled()) {
            return;
        }
        try {
            nudges.execute(() -> {
                try {
                    drain();
                } catch (RuntimeException e) {
                    log.warn("Alarm webhook pass failed: {}", e.getMessage());
                }
            });
        } catch (RejectedExecutionException ignored) {
            // A pass is already waiting, or we are shutting down; the schedule covers both.
        }
    }

    /**
     * Delivers every due event. Blocks while another pass in this JVM runs.
     *
     * @return how many deliveries were attempted
     */
    public int drain() {
        if (!settings.isWebhookEnabled()) {
            return 0;
        }
        lock.lock();
        try {
            long leaseMs = (long) settings.getConnectTimeoutMs() + settings.getRequestTimeoutMs() + 60_000L;
            int attempted = 0;
            for (int batch = 0; batch < MAX_BATCHES_PER_PASS; batch++) {
                long now = System.currentTimeMillis();
                List<Long> ids = requiresNew.execute(status -> {
                    List<Long> due = deliveryRepository.lockDue(now, BATCH_SIZE);
                    if (!due.isEmpty()) {
                        deliveryRepository.lease(due, now, now + leaseMs);
                    }
                    return due;
                });
                if (ids == null || ids.isEmpty()) {
                    break;
                }
                for (Long id : ids) {
                    deliver(id);
                    attempted++;
                }
            }
            return attempted;
        } finally {
            lock.unlock();
        }
    }

    private void deliver(Long deliveryId) {
        AlarmWebhookDelivery delivery = requiresNew.execute(status ->
                deliveryRepository.findById(deliveryId).orElse(null));
        if (delivery == null || delivery.getState() != AlarmWebhookState.PENDING) {
            return;
        }
        Outcome outcome = post(delivery);
        requiresNew.executeWithoutResult(status -> deliveryRepository.findById(deliveryId).ifPresent(row -> {
            long now = System.currentTimeMillis();
            row.setLastStatusCode(outcome.statusCode());
            if (outcome.delivered()) {
                row.setState(AlarmWebhookState.DELIVERED);
                row.setDeliveredAt(now);
                row.setLastError(null);
            } else {
                row.setLastError(outcome.error());
                if (row.getAttempts() >= Math.max(1, settings.getMaxAttempts())) {
                    row.setState(AlarmWebhookState.FAILED);
                    log.error("Alarm webhook event {} ({}) gave up after {} attempts: {}",
                            row.getEventId(), row.getEventType(), row.getAttempts(), outcome.error());
                } else {
                    row.setNextAttemptAt(now + backoffMs(row.getAttempts()));
                    log.warn("Alarm webhook event {} failed (attempt {}): {}",
                            row.getEventId(), row.getAttempts(), outcome.error());
                }
            }
            deliveryRepository.save(row);
        }));
    }

    record Outcome(boolean delivered, Integer statusCode, String error) {}

    Outcome post(AlarmWebhookDelivery delivery) {
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(settings.getWebhookUri())
                    .timeout(Duration.ofMillis(settings.getRequestTimeoutMs()))
                    .header("Content-Type", "application/json; charset=utf-8")
                    .header("X-Event-Id", delivery.getEventId())
                    .header("X-Event-Type", delivery.getEventType())
                    .header("User-Agent", "logsheet-alarm-webhook/1")
                    .POST(HttpRequest.BodyPublishers.ofString(delivery.getPayload(), StandardCharsets.UTF_8));
            if (settings.hasApiKey()) {
                request.header("X-API-Key", settings.getWebhookApiKey());
            }
            HttpResponse<InputStream> response = httpClient().send(
                    request.build(), HttpResponse.BodyHandlers.ofInputStream());
            int status = response.statusCode();
            String body = readBounded(response.body());
            if (status >= 200 && status < 300) {
                return new Outcome(true, status, null);
            }
            return new Outcome(false, status, trim("HTTP " + status
                    + (body.isBlank() ? "" : ": " + body)));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Outcome(false, null, "Interrupted");
        } catch (IOException | RuntimeException e) {
            return new Outcome(false, null, trim(e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : ": " + e.getMessage())));
        }
    }

    private HttpClient httpClient() {
        HttpClient current = client;
        if (current == null) {
            synchronized (this) {
                current = client;
                if (current == null) {
                    current = HttpClient.newBuilder()
                            .connectTimeout(Duration.ofMillis(settings.getConnectTimeoutMs()))
                            .followRedirects(HttpClient.Redirect.NEVER)
                            .version(HttpClient.Version.HTTP_1_1)
                            .build();
                    client = current;
                }
            }
        }
        return current;
    }

    /** The first few hundred bytes of the receiver's answer, for the error column — never more. */
    private static String readBounded(InputStream body) throws IOException {
        if (body == null) {
            return "";
        }
        try (InputStream in = body) {
            byte[] bytes = in.readNBytes(MAX_ERROR_BODY);
            return new String(bytes, StandardCharsets.UTF_8).replaceAll("\\s+", " ").trim();
        }
    }

    private static String trim(String text) {
        if (text == null) {
            return null;
        }
        return text.length() <= 1000 ? text : text.substring(0, 1000);
    }

    /** 30 s, 1 min, 2 min, … capped at an hour. */
    static long backoffMs(int attempts) {
        int exponent = Math.max(0, Math.min(attempts - 1, 20));
        return Math.min(30_000L << exponent, MAX_BACKOFF_MS);
    }

    /**
     * Sends a FAILED delivery again from scratch. Only a failed one: a pending one is already
     * queued, and a delivered one was answered with a 2xx — resending it is a duplicate the
     * receiver did not ask for.
     */
    public void retry(Long deliveryId) {
        requiresNew.executeWithoutResult(status -> {
            AlarmWebhookDelivery row = deliveryRepository.findById(deliveryId)
                    .orElseThrow(() -> new IllegalArgumentException("ارسال پیدا نشد."));
            if (row.getState() != AlarmWebhookState.FAILED) {
                throw new IllegalStateException("فقط ارسالی که شکست خورده است دوباره فرستاده می‌شود.");
            }
            row.setState(AlarmWebhookState.PENDING);
            row.setAttempts(0);
            row.setNextAttemptAt(System.currentTimeMillis());
            deliveryRepository.save(row);
        });
        nudge();
    }

    @PreDestroy
    void shutdown() {
        nudges.shutdownNow();
    }
}
