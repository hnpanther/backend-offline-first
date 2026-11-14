package com.hnp.backendofflinefirst.service.alarm;

import com.hnp.backendofflinefirst.domain.AlarmWebhookState;
import com.hnp.backendofflinefirst.entity.AlarmWebhookDelivery;
import com.hnp.backendofflinefirst.entity.AlarmWebhookEndpoint;
import com.hnp.backendofflinefirst.repository.AlarmWebhookDeliveryRepository;
import com.hnp.backendofflinefirst.repository.AlarmWebhookEndpointRepository;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * POSTs queued alarm events to their endpoints — the drain of the outbox.
 *
 * <h2>The request</h2>
 *
 * <pre>
 * POST {the endpoint's url}
 * Content-Type:        application/json; charset=utf-8
 * X-API-Key:           {the endpoint's API key}        (omitted when it has none)
 * X-Event-Id:          {uuid}                          (the receiver deduplicates on it)
 * X-Event-Type:        ALARM_RAISED | ALARM_ESCALATED | WEBHOOK_TEST
 * X-Webhook-Timestamp: {unix seconds of this attempt}
 * X-Webhook-Signature: sha256={hex HMAC-SHA256 of "timestamp.body" under the signing secret}
 * </pre>
 *
 * <p>The signature is computed per attempt, over the exact bytes sent, so a receiver that checks
 * it and refuses a timestamp more than a few minutes old is protected against a forged body and
 * against a captured request replayed later. The signing secret itself is never sent.
 *
 * <p>Any 2xx is delivered. Anything else — another status, a refused connection, a timeout, an
 * address the target policy refuses — is an attempt that failed: retried with backoff (30 s,
 * 1 min, 2 min … capped at an hour) until {@code app.alarm.webhook.max-attempts}, then left
 * {@code FAILED}, visible on the webhooks page with its last error, and sent again only when an
 * administrator asks.
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
 *   <li><b>The target is checked again before every request</b> ({@link AlarmWebhookTargetPolicy}),
 *       on what the host resolves to at that moment.</li>
 *   <li><b>Nothing a receiver answers is kept but its status code.</b> The address is typed in
 *       the panel (V21), so echoing a response body into {@code last_error} would let whoever
 *       can type an address read whatever answers at it.</li>
 *   <li><b>The key and the secret are never logged</b> and never written into {@code last_error}.</li>
 * </ul>
 */
@Component
@Slf4j
public class AlarmWebhookDispatcher {

    static final int BATCH_SIZE = 25;
    static final int MAX_BATCHES_PER_PASS = 20;
    static final long MAX_BACKOFF_MS = 60 * 60_000L;
    /** How much of a response is read (and discarded) so the connection can be reused. */
    private static final int MAX_DRAINED_BODY = 8 * 1024;

    private final AlarmSettings settings;
    private final AlarmWebhookDeliveryRepository deliveryRepository;
    private final AlarmWebhookEndpointRepository endpointRepository;
    private final AlarmWebhookTargetPolicy targetPolicy;
    private final TransactionTemplate requiresNew;
    private final ReentrantLock lock = new ReentrantLock();
    private final ThreadPoolExecutor nudges;
    private volatile HttpClient client;

    public AlarmWebhookDispatcher(AlarmSettings settings,
                                  AlarmWebhookDeliveryRepository deliveryRepository,
                                  AlarmWebhookEndpointRepository endpointRepository,
                                  AlarmWebhookTargetPolicy targetPolicy,
                                  PlatformTransactionManager transactionManager) {
        this.settings = settings;
        this.deliveryRepository = deliveryRepository;
        this.endpointRepository = endpointRepository;
        this.targetPolicy = targetPolicy;
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
        if (delivery == null || delivery.getState() != AlarmWebhookState.PENDING
                || delivery.getEndpointId() == null) {
            return;
        }
        AlarmWebhookEndpoint endpoint = requiresNew.execute(status ->
                endpointRepository.findById(delivery.getEndpointId()).orElse(null));
        if (endpoint == null || !endpoint.isActive()) {
            // Deleted or switched off between the claim and now. The claim counted an attempt;
            // give it back, so a receiver paused for a week does not come back with its queue
            // already spent.
            requiresNew.executeWithoutResult(status -> deliveryRepository.findById(deliveryId).ifPresent(row -> {
                row.setAttempts(Math.max(0, row.getAttempts() - 1));
                row.setNextAttemptAt(System.currentTimeMillis());
                deliveryRepository.save(row);
            }));
            return;
        }
        Outcome outcome = post(delivery, endpoint);
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

    Outcome post(AlarmWebhookDelivery delivery, AlarmWebhookEndpoint endpoint) {
        try {
            URI target = URI.create(endpoint.getUrl());
            String refused = targetPolicy.problemWith(target);
            if (refused != null) {
                return new Outcome(false, null, refused);
            }
            byte[] body = delivery.getPayload().getBytes(StandardCharsets.UTF_8);
            String timestamp = Long.toString(System.currentTimeMillis() / 1000L);
            HttpRequest.Builder request = HttpRequest.newBuilder(target)
                    .timeout(Duration.ofMillis(settings.getRequestTimeoutMs()))
                    .header("Content-Type", "application/json; charset=utf-8")
                    .header("X-Event-Id", delivery.getEventId())
                    .header("X-Event-Type", delivery.getEventType())
                    .header("X-Webhook-Timestamp", timestamp)
                    .header("X-Webhook-Signature", "sha256=" + signature(endpoint.getSigningSecret(), timestamp, body))
                    .header("User-Agent", "logsheet-alarm-webhook/2")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body));
            if (endpoint.getApiKey() != null && !endpoint.getApiKey().isBlank()) {
                request.header("X-API-Key", endpoint.getApiKey());
            }
            HttpResponse<InputStream> response = httpClient().send(
                    request.build(), HttpResponse.BodyHandlers.ofInputStream());
            int status = response.statusCode();
            drain(response.body());
            if (status >= 200 && status < 300) {
                return new Outcome(true, status, null);
            }
            // The status code only. The body is somebody else's response to a request whose
            // address was typed in a page, and the delivery log is readable in that page.
            return new Outcome(false, status, "HTTP " + status);
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

    /** Reads a bounded amount of the answer and throws it away, so the connection can be reused. */
    private static void drain(InputStream body) throws IOException {
        if (body == null) {
            return;
        }
        try (InputStream in = body) {
            in.readNBytes(MAX_DRAINED_BODY);
        }
    }

    /**
     * Hex HMAC-SHA256 of {@code timestamp + "." + body} under {@code secret} — what a receiver
     * recomputes to check {@code X-Webhook-Signature}. Public so the docs' recipe has a reference.
     */
    public static String signature(String secret, String timestamp, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update(timestamp.getBytes(StandardCharsets.UTF_8));
            mac.update((byte) '.');
            mac.update(body);
            return HexFormat.of().formatHex(mac.doFinal());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is unavailable", e);
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
            // Pending without an endpoint is a row nothing will ever claim — it would sit «در صف»
            // for ever. Its receiver was deleted (or never imported); there is nowhere to send it.
            if (row.getEndpointId() == null || !endpointRepository.existsById(row.getEndpointId())) {
                throw new IllegalStateException("مقصد این ارسال حذف شده است و نمی‌توان آن را دوباره فرستاد.");
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
