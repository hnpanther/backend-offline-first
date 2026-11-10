package com.hnp.backendofflinefirst.service.alarm;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.Locale;

/**
 * Alarm configuration, read once at startup.
 *
 * <h2>The webhook URL is configuration, never data</h2>
 *
 * <p>roadmap §11: an outbound URL an administrator can type into a page is a way to point the
 * server at an internal address and read what comes back, and a way to send plant readings
 * somewhere new without anyone touching the deployment. So it lives in
 * {@code application.properties} / the environment, beside every other {@code app.*} setting, and
 * the panel only <em>shows</em> whether it is set.
 *
 * <p><b>A malformed URL does not stop the application booting.</b> It is logged at ERROR, the
 * webhook is treated as off, and the rules page says «پیکربندی نادرست». An alarm subsystem that
 * can take the whole plant system down over a typo is worse than one that is loudly not sending.
 *
 * <p>The key is never logged, never shown in the panel, never put in an error message. Only
 * whether one is configured is ever exposed.
 */
@Component
@Getter
@Slf4j
public class AlarmSettings {

    @Value("${app.alarm.webhook.url:}")
    private String webhookUrl;

    @Value("${app.alarm.webhook.api-key:}")
    private String webhookApiKey;

    /**
     * Whether the payload carries supervisors' phone numbers. On by default because the stated
     * purpose of the webhook is for the receiver to text or call them; it is a switch because a
     * phone number is personal data crossing a boundary (roadmap §11, «decide what is in the
     * payload»), and a plant whose receiver resolves people itself should be able to say no.
     */
    @Value("${app.alarm.webhook.include-contact-details:true}")
    private boolean includeContactDetails;

    @Value("${app.alarm.webhook.connect-timeout-ms:5000}")
    private int connectTimeoutMs;

    @Value("${app.alarm.webhook.request-timeout-ms:10000}")
    private int requestTimeoutMs;

    /** After this many failed attempts a delivery is left FAILED and visible, not retried. */
    @Value("${app.alarm.webhook.max-attempts:10}")
    private int maxAttempts;

    /**
     * Whether a completed round's alarms are evaluated immediately after its transaction
     * commits, on a background thread. Off only in tests, which drain the queue themselves so
     * that nothing races their assertions; the scheduled pass still covers everything.
     */
    @Value("${app.alarm.evaluation.after-commit:true}")
    private boolean evaluateAfterCommit;

    private URI webhookUri;
    private boolean webhookMisconfigured;

    @PostConstruct
    void parse() {
        webhookUri = null;
        webhookMisconfigured = false;
        if (webhookUrl == null || webhookUrl.isBlank()) {
            log.info("Alarm webhook: not configured (app.alarm.webhook.url is empty); alarms are "
                    + "raised and shown in the panel, and nothing is sent.");
            return;
        }
        try {
            URI uri = URI.create(webhookUrl.trim());
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!(scheme.equals("http") || scheme.equals("https")) || uri.getHost() == null) {
                throw new IllegalArgumentException("not an absolute http(s) URL");
            }
            webhookUri = uri;
            // Scheme, host and port only: a URL can carry credentials in its user-info or query.
            log.info("Alarm webhook: enabled, POST to {}://{}{}", scheme, uri.getHost(),
                    uri.getPort() > 0 ? ":" + uri.getPort() : "");
            if (webhookApiKey == null || webhookApiKey.isBlank()) {
                log.warn("Alarm webhook: app.alarm.webhook.api-key is empty, so requests carry no "
                        + "X-API-Key header and the receiver cannot tell them from anyone else's.");
            }
        } catch (IllegalArgumentException e) {
            webhookMisconfigured = true;
            log.error("Alarm webhook: app.alarm.webhook.url is not a valid absolute http(s) URL "
                    + "({}); the webhook is OFF until it is corrected.", e.getMessage());
        }
    }

    public boolean isWebhookEnabled() {
        return webhookUri != null;
    }

    public boolean hasApiKey() {
        return webhookApiKey != null && !webhookApiKey.isBlank();
    }

    /** For the panel: where the webhook goes, without path, query or credentials. */
    public String webhookTargetForDisplay() {
        if (webhookUri == null) {
            return null;
        }
        return webhookUri.getScheme() + "://" + webhookUri.getHost()
                + (webhookUri.getPort() > 0 ? ":" + webhookUri.getPort() : "");
    }
}
