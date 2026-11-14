package com.hnp.backendofflinefirst.service.alarm;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Alarm configuration that belongs to the deployment rather than to the plant, read once at
 * startup: timeouts, the retry ceiling, and whether evaluation runs right after a round commits.
 *
 * <h2>Where the webhook's address went</h2>
 *
 * <p>Until V21 the webhook was one address here ({@code app.alarm.webhook.url} / {@code api-key}).
 * Receivers are now registered in the panel ({@code alarm_webhook_endpoints}), several of them,
 * each with its own key, signing secret and events; where they may point is bounded by
 * {@link AlarmWebhookTargetPolicy}. The three old values are still read — <b>only</b> by
 * {@code AlarmWebhookLegacyImportRunner}, which turns a configured address into an endpoint once,
 * on the first boot after V21, so an upgrade keeps sending where it sent before. After that they
 * do nothing; the startup log says so when they are still set.
 */
@Component
@Getter
public class AlarmSettings {

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

    /** Pre-V21 address — read by the one-time import only. */
    @Value("${app.alarm.webhook.url:}")
    private String legacyWebhookUrl;

    /** Pre-V21 key — read by the one-time import only. Never logged. */
    @Value("${app.alarm.webhook.api-key:}")
    private String legacyWebhookApiKey;

    /** Pre-V21 contact-details switch — read by the one-time import only. */
    @Value("${app.alarm.webhook.include-contact-details:true}")
    private boolean legacyIncludeContactDetails;

    public boolean hasLegacyWebhookUrl() {
        return legacyWebhookUrl != null && !legacyWebhookUrl.isBlank();
    }
}
