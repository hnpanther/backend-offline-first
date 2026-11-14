package com.hnp.backendofflinefirst.config;

import com.hnp.backendofflinefirst.domain.AlarmWebhookState;
import com.hnp.backendofflinefirst.entity.AlarmWebhookDelivery;
import com.hnp.backendofflinefirst.entity.AlarmWebhookEndpoint;
import com.hnp.backendofflinefirst.entity.AppSetting;
import com.hnp.backendofflinefirst.repository.AlarmWebhookDeliveryRepository;
import com.hnp.backendofflinefirst.repository.AlarmWebhookEndpointRepository;
import com.hnp.backendofflinefirst.repository.AppSettingRepository;
import com.hnp.backendofflinefirst.service.alarm.AlarmSettings;
import com.hnp.backendofflinefirst.service.alarm.AlarmWebhookEndpointService;
import com.hnp.backendofflinefirst.service.alarm.AlarmWebhookTargetPolicy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;

/**
 * Turns the pre-V21 webhook address in configuration into an endpoint — once per database.
 *
 * <p>A migration cannot read the application's configuration, so this runs on the first boot after
 * V21. It records that it ran in {@code app_settings} ({@value #MARKER}), and from then on does
 * nothing but warn while {@code app.alarm.webhook.url} is still set — so deleting the imported
 * endpoint in the panel is final, and a restart does not bring it back.
 *
 * <ul>
 *   <li><b>Address configured:</b> one endpoint, named {@value #IMPORTED_NAME}, with the configured
 *       key and contact-details switch, every event, active, and a freshly generated signing
 *       secret (the receiver can ignore the signature until somebody hands it the secret).
 *       Deliveries queued before V21 and still waiting are attached to it, so nothing queued is
 *       lost.</li>
 *   <li><b>None configured:</b> nothing is created. A delivery cannot have been queued without an
 *       address, but should one be found waiting it is failed with a reason, rather than left
 *       «در صف» for ever with nowhere to go.</li>
 * </ul>
 *
 * <p><b>It never stops the application from starting.</b> A failure is logged and the next boot
 * tries again (the marker is written only on success).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AlarmWebhookLegacyImportRunner implements ApplicationRunner {

    public static final String MARKER = "alarm.webhook.legacy_imported";
    public static final String IMPORTED_NAME = "وب‌هوک پیکربندی سرور";

    private final AlarmSettings settings;
    private final AppSettingRepository appSettingRepository;
    private final AlarmWebhookEndpointRepository endpointRepository;
    private final AlarmWebhookDeliveryRepository deliveryRepository;
    private final AlarmWebhookTargetPolicy targetPolicy;

    @Override
    public void run(ApplicationArguments args) {
        try {
            importOnce();
        } catch (RuntimeException e) {
            log.error("Alarm webhook: importing app.alarm.webhook.url as an endpoint failed ({}); "
                    + "the next start will try again.", e.getMessage());
        }
    }

    /** @return the endpoint created, or null when there was nothing to import or it had already run */
    @Transactional
    public AlarmWebhookEndpoint importOnce() {
        if (appSettingRepository.existsById(MARKER)) {
            if (settings.hasLegacyWebhookUrl()) {
                log.warn("Alarm webhook: app.alarm.webhook.url is set but no longer used — receivers "
                        + "are managed in the panel (وب‌هوک‌های آلارم). Remove the setting.");
            }
            return null;
        }
        long now = System.currentTimeMillis();
        AlarmWebhookEndpoint imported = settings.hasLegacyWebhookUrl() ? importConfigured(now) : null;
        for (AlarmWebhookDelivery waiting : deliveryRepository.findByEndpointIdIsNullAndState(AlarmWebhookState.PENDING)) {
            if (imported != null) {
                waiting.setEndpointId(imported.getId());
                waiting.setEndpointName(imported.getName());
            } else {
                waiting.setState(AlarmWebhookState.FAILED);
                waiting.setLastError("No webhook endpoint configured.");
            }
            deliveryRepository.save(waiting);
        }
        AppSetting marker = new AppSetting();
        marker.setSettingKey(MARKER);
        marker.setValue(imported == null ? "nothing" : String.valueOf(imported.getId()));
        marker.setUpdatedAt(now);
        appSettingRepository.save(marker);
        return imported;
    }

    private AlarmWebhookEndpoint importConfigured(long now) {
        URI uri;
        try {
            uri = AlarmWebhookTargetPolicy.parse(settings.getLegacyWebhookUrl());
        } catch (IllegalArgumentException e) {
            // It was not sending before either (AlarmSettings treated a malformed address as off).
            log.error("Alarm webhook: app.alarm.webhook.url is not a valid absolute http(s) URL; "
                    + "nothing imported. Register the receiver in the panel instead.");
            return null;
        }
        String problem = targetPolicy.problemWith(uri);
        if (problem != null) {
            // Imported anyway — the address was the server operator's own choice — but every
            // request to it will be refused until the operator allows it, and they need to know.
            log.warn("Alarm webhook: the imported address will be refused before every request ({}). "
                    + "Add its host to app.alarm.webhook.allowed-hosts if it is intended.", problem);
        }
        AlarmWebhookEndpoint endpoint = new AlarmWebhookEndpoint();
        endpoint.setName(IMPORTED_NAME);
        endpoint.setUrl(uri.toString());
        String key = settings.getLegacyWebhookApiKey();
        endpoint.setApiKey(key == null || key.isBlank() ? null : key.trim());
        endpoint.setSigningSecret(AlarmWebhookEndpointService.newSigningSecret());
        endpoint.setActive(true);
        endpoint.setIncludeContactDetails(settings.isLegacyIncludeContactDetails());
        endpoint.setEventTypes(null);
        endpoint.setCreatedAt(now);
        endpoint.setUpdatedAt(now);
        endpointRepository.save(endpoint);
        log.info("Alarm webhook: imported app.alarm.webhook.url as endpoint {} ({}); manage it in the panel "
                + "and remove the setting.", endpoint.getId(), AlarmWebhookEndpointService.displayTarget(endpoint.getUrl()));
        return endpoint;
    }
}
