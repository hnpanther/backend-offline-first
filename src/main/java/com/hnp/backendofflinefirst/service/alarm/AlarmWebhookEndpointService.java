package com.hnp.backendofflinefirst.service.alarm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hnp.backendofflinefirst.domain.AlarmWebhookEvents;
import com.hnp.backendofflinefirst.domain.AlarmWebhookRuleScope;
import com.hnp.backendofflinefirst.domain.AlarmWebhookState;
import com.hnp.backendofflinefirst.entity.AlarmWebhookDelivery;
import com.hnp.backendofflinefirst.entity.AlarmWebhookEndpoint;
import com.hnp.backendofflinefirst.entity.AlarmWebhookEndpointRule;
import com.hnp.backendofflinefirst.repository.AlarmRuleRepository;
import com.hnp.backendofflinefirst.repository.AlarmWebhookDeliveryRepository;
import com.hnp.backendofflinefirst.repository.AlarmWebhookEndpointRepository;
import com.hnp.backendofflinefirst.repository.AlarmWebhookEndpointRuleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.net.URI;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The alarm webhook receivers an administrator registers in the panel (V21).
 *
 * <h2>What an administrator decides, per receiver</h2>
 * <ul>
 *   <li>the address — checked by {@link AlarmWebhookTargetPolicy}, here and before every request;</li>
 *   <li>an optional API key, sent as {@code X-API-Key} (the V17 contract);</li>
 *   <li>which alarm events it receives, and whether its payloads carry supervisors' phone numbers;</li>
 *   <li>which alarms: every alarm, or only those of chosen rules (the default rule is one of them),
 *       so a sensitive parameter's rule can go to a second receiver as well as the general one;</li>
 *   <li>whether it is active. An inactive receiver is sent nothing new; what was already queued for
 *       it waits, and goes out if it is switched back on.</li>
 * </ul>
 *
 * <p>The signing secret is generated, never typed: a secret an administrator chooses is a secret
 * somebody can guess. It is returned once — at creation and at each regeneration — for the
 * administrator to give the receiver, and never shown again.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AlarmWebhookEndpointService {

    static final int MAX_NAME = 100;
    static final int MAX_API_KEY = 500;
    /** A header value: printable ASCII, no space. Anything else could split the request's headers. */
    private static final Pattern API_KEY = Pattern.compile("[\\x21-\\x7E]{1," + MAX_API_KEY + "}");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final ObjectMapper JSON = new ObjectMapper();

    private final AlarmWebhookEndpointRepository endpointRepository;
    private final AlarmWebhookDeliveryRepository deliveryRepository;
    private final AlarmWebhookTargetPolicy targetPolicy;
    private final AlarmWebhookDispatcher dispatcher;
    private final AlarmWebhookEndpointRuleRepository endpointRuleRepository;
    private final AlarmRuleRepository ruleRepository;

    /** A receiver as saved, with its signing secret — the one moment the secret exists outside the row. */
    public record WithSecret(AlarmWebhookEndpoint endpoint, String signingSecret) {}

    /**
     * What the add / edit form carries. {@code apiKey} blank on an edit keeps the stored one;
     * {@code ruleIds} is read only when {@code ruleScope} is {@code SELECTED}.
     */
    public record Form(String name, String url, String apiKey, boolean clearApiKey,
                       boolean includeContactDetails, Collection<String> eventTypes, boolean active,
                       AlarmWebhookRuleScope ruleScope, Collection<Long> ruleIds) {

        public Form {
            ruleScope = ruleScope == null ? AlarmWebhookRuleScope.ALL : ruleScope;
        }

        /** A receiver for every alarm — what every endpoint was before rule selection existed. */
        public Form(String name, String url, String apiKey, boolean clearApiKey,
                    boolean includeContactDetails, Collection<String> eventTypes, boolean active) {
            this(name, url, apiKey, clearApiKey, includeContactDetails, eventTypes, active,
                    AlarmWebhookRuleScope.ALL, null);
        }
    }

    /**
     * Whether a receiver gets an alarm raised by {@code alarmRuleId}.
     *
     * @param selectedRuleIds the receiver's list — read only when its scope is SELECTED
     */
    public static boolean receives(AlarmWebhookEndpoint endpoint, Set<Long> selectedRuleIds, Long alarmRuleId) {
        if (endpoint.getRuleScope() != AlarmWebhookRuleScope.SELECTED) {
            return true;
        }
        return alarmRuleId != null && selectedRuleIds != null && selectedRuleIds.contains(alarmRuleId);
    }

    /** Each listed receiver's chosen rules, in one statement — empty for an ALL receiver. */
    @Transactional(readOnly = true)
    public Map<Long, Set<Long>> rulesByEndpoint(Collection<AlarmWebhookEndpoint> endpoints) {
        if (endpoints.isEmpty()) {
            return Map.of();
        }
        Map<Long, Set<Long>> out = new HashMap<>();
        for (AlarmWebhookEndpointRule link : endpointRuleRepository.findByEndpointIdIn(
                endpoints.stream().map(AlarmWebhookEndpoint::getId).toList())) {
            out.computeIfAbsent(link.getEndpointId(), k -> new LinkedHashSet<>()).add(link.getAlarmRuleId());
        }
        return out;
    }

    @Transactional(readOnly = true)
    public List<AlarmWebhookEndpoint> all() {
        return endpointRepository.findAllByOrderByNameAsc();
    }

    @Transactional(readOnly = true)
    public AlarmWebhookEndpoint require(Long id) {
        return endpointRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Webhook endpoint not found."));
    }

    @Transactional
    public WithSecret create(Form form, Long actorUserId) {
        AlarmWebhookEndpoint endpoint = new AlarmWebhookEndpoint();
        apply(endpoint, form, null);
        String secret = newSigningSecret();
        endpoint.setSigningSecret(secret);
        long now = System.currentTimeMillis();
        endpoint.setCreatedAt(now);
        endpoint.setUpdatedAt(now);
        endpoint.setCreatedByUserId(actorUserId);
        endpointRepository.save(endpoint);
        writeRules(endpoint.getId(), selectedRules(form));
        log.info("User {} registered alarm webhook endpoint {} '{}' -> {}", actorUserId, endpoint.getId(),
                endpoint.getName(), displayTarget(endpoint.getUrl()));
        return new WithSecret(endpoint, secret);
    }

    @Transactional
    public AlarmWebhookEndpoint update(Long id, Form form, Long actorUserId) {
        AlarmWebhookEndpoint endpoint = require(id);
        boolean wasActive = endpoint.isActive();
        apply(endpoint, form, endpoint.getId());
        endpoint.setUpdatedAt(System.currentTimeMillis());
        endpointRepository.save(endpoint);
        writeRules(endpoint.getId(), selectedRules(form));
        log.info("User {} updated alarm webhook endpoint {} '{}' -> {} (active={})", actorUserId, id,
                endpoint.getName(), displayTarget(endpoint.getUrl()), endpoint.isActive());
        if (!wasActive && endpoint.isActive()) {
            // Whatever waited while it was off goes out now rather than at the next poll.
            nudgeAfterCommit();
        }
        return endpoint;
    }

    /**
     * Removes a receiver. Its deliveries keep their record (the endpoint's name is frozen on each);
     * the ones still waiting are failed here, in the same transaction, so nothing is left queued
     * for an address that no longer exists — and an administrator can see that they were not sent.
     */
    @Transactional
    public void delete(Long id, Long actorUserId) {
        AlarmWebhookEndpoint endpoint = require(id);
        long now = System.currentTimeMillis();
        for (AlarmWebhookDelivery pending : deliveryRepository.findByEndpointIdAndState(id, AlarmWebhookState.PENDING)) {
            pending.setState(AlarmWebhookState.FAILED);
            pending.setLastError("Endpoint deleted before delivery.");
            pending.setNextAttemptAt(now);
            deliveryRepository.save(pending);
        }
        // Row by row, so the trail shows the list going with it; the key would cascade them unaudited.
        writeRules(id, Set.of());
        endpointRepository.delete(endpoint);
        log.info("User {} deleted alarm webhook endpoint {} '{}'", actorUserId, id, endpoint.getName());
    }

    /** A new signing secret; the old one stops being used by the very next request. */
    @Transactional
    public WithSecret regenerateSecret(Long id, Long actorUserId) {
        AlarmWebhookEndpoint endpoint = require(id);
        String secret = newSigningSecret();
        endpoint.setSigningSecret(secret);
        endpoint.setUpdatedAt(System.currentTimeMillis());
        endpointRepository.save(endpoint);
        log.info("User {} regenerated the signing secret of alarm webhook endpoint {} '{}'",
                actorUserId, id, endpoint.getName());
        return new WithSecret(endpoint, secret);
    }

    /**
     * Queues one {@value AlarmWebhookEvents#WEBHOOK_TEST} request to this receiver — a delivery like
     * any other, so its outcome appears in the log a few seconds later. Refused while the receiver
     * is inactive: a test that is never sent would read as a receiver that never answers.
     */
    @Transactional
    public AlarmWebhookDelivery queueTest(Long id, Long actorUserId) {
        AlarmWebhookEndpoint endpoint = require(id);
        if (!endpoint.isActive()) {
            throw new IllegalStateException("Webhook endpoint is inactive.");
        }
        long now = System.currentTimeMillis();
        String eventId = UUID.randomUUID().toString();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("eventId", eventId);
        body.put("eventType", AlarmWebhookEvents.WEBHOOK_TEST);
        body.put("occurredAt", DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(now)));
        body.put("endpoint", Map.of("name", endpoint.getName()));
        body.put("message", "Test request from the log-sheet panel. No alarm is behind it; acknowledge it with any 2xx.");
        AlarmWebhookDelivery delivery = new AlarmWebhookDelivery();
        delivery.setEventId(eventId);
        delivery.setEventType(AlarmWebhookEvents.WEBHOOK_TEST);
        delivery.setEndpointId(endpoint.getId());
        delivery.setEndpointName(endpoint.getName());
        delivery.setPayload(serialise(body));
        delivery.setState(AlarmWebhookState.PENDING);
        delivery.setAttempts(0);
        delivery.setNextAttemptAt(now);
        delivery.setCreatedAt(now);
        deliveryRepository.save(delivery);
        log.info("User {} queued a test request to alarm webhook endpoint {} '{}'", actorUserId, id, endpoint.getName());
        nudgeAfterCommit();
        return delivery;
    }

    /** For the panel and the log: scheme, host and port — never the path, query or anything secret. */
    public static String displayTarget(String url) {
        try {
            URI uri = URI.create(url);
            return uri.getScheme() + "://" + uri.getHost() + (uri.getPort() > 0 ? ":" + uri.getPort() : "");
        } catch (RuntimeException e) {
            return "?";
        }
    }

    private void apply(AlarmWebhookEndpoint endpoint, Form form, Long selfId) {
        String name = form.name() == null ? "" : form.name().trim();
        if (name.isEmpty()) {
            throw new IllegalArgumentException("Webhook name is required.");
        }
        if (name.length() > MAX_NAME) {
            throw new IllegalArgumentException("Webhook name is too long.");
        }
        endpointRepository.findByNameIgnoreCase(name)
                .filter(other -> !other.getId().equals(selfId))
                .ifPresent(other -> {
                    throw new IllegalArgumentException("Duplicate webhook name.");
                });
        URI target = targetPolicy.validate(form.url());

        String apiKey = form.apiKey() == null ? "" : form.apiKey().trim();
        if (!apiKey.isEmpty() && !API_KEY.matcher(apiKey).matches()) {
            throw new IllegalArgumentException("Webhook API key must be printable ASCII without spaces.");
        }

        endpoint.setName(name);
        endpoint.setUrl(target.toString());
        if (form.clearApiKey()) {
            endpoint.setApiKey(null);
        } else if (!apiKey.isEmpty()) {
            endpoint.setApiKey(apiKey);
        }
        endpoint.setIncludeContactDetails(form.includeContactDetails());
        endpoint.setEventTypes(AlarmWebhookEvents.column(form.eventTypes()));
        endpoint.setActive(form.active());
        endpoint.setRuleScope(form.ruleScope());
        selectedRules(form); // validated before anything is written
    }

    /**
     * The rules a SELECTED form names, checked: at least one, and each a rule that exists. An
     * empty SELECTED list is refused rather than stored — a receiver for nothing is a receiver
     * nobody notices has gone quiet. An ALL form selects nothing.
     */
    private Set<Long> selectedRules(Form form) {
        if (form.ruleScope() != AlarmWebhookRuleScope.SELECTED) {
            return Set.of();
        }
        Set<Long> wanted = new LinkedHashSet<>();
        if (form.ruleIds() != null) {
            form.ruleIds().stream().filter(java.util.Objects::nonNull).forEach(wanted::add);
        }
        if (wanted.isEmpty()) {
            throw new IllegalArgumentException("Select at least one alarm rule for this webhook.");
        }
        if (ruleRepository.findAllById(wanted).size() != wanted.size()) {
            throw new IllegalArgumentException("Alarm rule not found.");
        }
        return wanted;
    }

    /** Makes {@code wanted} the receiver's list — by difference, row by row (AGENTS.md #192, #193). */
    private void writeRules(Long endpointId, Set<Long> wanted) {
        Set<Long> kept = new HashSet<>();
        for (AlarmWebhookEndpointRule link : endpointRuleRepository.findByEndpointId(endpointId)) {
            if (wanted.contains(link.getAlarmRuleId())) {
                kept.add(link.getAlarmRuleId());
            } else {
                endpointRuleRepository.delete(link);
            }
        }
        for (Long ruleId : wanted) {
            if (!kept.contains(ruleId)) {
                endpointRuleRepository.save(new AlarmWebhookEndpointRule(endpointId, ruleId));
            }
        }
    }

    private void nudgeAfterCommit() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    dispatcher.nudge();
                }
            });
        } else {
            dispatcher.nudge();
        }
    }

    /** {@code whsec_} and 32 random bytes — the prefix tells a reader what the value is for. */
    public static String newSigningSecret() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return "whsec_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String serialise(Map<String, Object> body) {
        try {
            return JSON.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialise the webhook test payload", e);
        }
    }
}
