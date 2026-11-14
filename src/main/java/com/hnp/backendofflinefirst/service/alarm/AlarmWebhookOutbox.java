package com.hnp.backendofflinefirst.service.alarm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hnp.backendofflinefirst.domain.AlarmCondition.Reading;
import com.hnp.backendofflinefirst.domain.AlarmWebhookEvents;
import com.hnp.backendofflinefirst.domain.AlarmWebhookRuleScope;
import com.hnp.backendofflinefirst.domain.AlarmWebhookState;
import com.hnp.backendofflinefirst.entity.Alarm;
import com.hnp.backendofflinefirst.entity.AlarmRule;
import com.hnp.backendofflinefirst.entity.AlarmWebhookDelivery;
import com.hnp.backendofflinefirst.entity.AlarmWebhookEndpoint;
import com.hnp.backendofflinefirst.entity.AlarmWebhookEndpointRule;
import com.hnp.backendofflinefirst.entity.AssetClass;
import com.hnp.backendofflinefirst.entity.AssetEntry;
import com.hnp.backendofflinefirst.entity.LogSheet;
import com.hnp.backendofflinefirst.entity.OperationalUnit;
import com.hnp.backendofflinefirst.entity.UnitSupervisor;
import com.hnp.backendofflinefirst.entity.User;
import com.hnp.backendofflinefirst.repository.AlarmWebhookDeliveryRepository;
import com.hnp.backendofflinefirst.repository.AlarmWebhookEndpointRepository;
import com.hnp.backendofflinefirst.repository.AlarmWebhookEndpointRuleRepository;
import com.hnp.backendofflinefirst.repository.AssetClassRepository;
import com.hnp.backendofflinefirst.repository.AssetEntryRepository;
import com.hnp.backendofflinefirst.repository.LogSheetEntryRepository;
import com.hnp.backendofflinefirst.repository.LogSheetRepository;
import com.hnp.backendofflinefirst.repository.OperationalUnitRepository;
import com.hnp.backendofflinefirst.repository.UnitSupervisorRepository;
import com.hnp.backendofflinefirst.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Writes the webhook delivery rows for an alarm event — in the transaction that raised it.
 *
 * <h2>One event, one row per receiver (V21)</h2>
 *
 * <p>Every active endpoint subscribed to the event — and, for one that lists rules, to the rule
 * that raised the alarm — gets its own row, sharing the event id: it is
 * one event, and each receiver deduplicates on that id. Each row is retried, failed and resent on
 * its own, so a receiver that is down never holds back one that is up. An endpoint that does not
 * receive contact details gets a payload without phone numbers — built once per variant, never
 * once per endpoint.
 *
 * <h2>Why the payload is built now and stored</h2>
 *
 * <p>The receiver is going to text the supervisors of the unit. «Who are the supervisors» has to
 * be answered as of the moment of the alarm: rebuilding the body at send time would, after a
 * retry an hour later, describe a different set of people than the alarm actually concerned, and
 * a retry would no longer be a retry. So it is serialised once, here, and the exact bytes are
 * what every attempt sends.
 *
 * <h2>Which units, and why three relations</h2>
 *
 * <p>An asset does not belong to an operational unit in this system; responsibility runs through
 * the log sheet (docs/hierarchy.md §5). So «the units this asset relates to» is answered three
 * ways, each labelled, so the receiver can decide whom to bother:
 * <ul>
 *   <li>{@code RESPONSIBLE} — the unit responsible for the round that raised the alarm. The panel
 *       shows the alarm to this unit's supervisors.</li>
 *   <li>{@code PARENT_OF_RESPONSIBLE} — every ancestor of it. A parent unit's supervisor sees
 *       their children's work ({@code OperationalUnitScopeService.expandDownward}), so they see
 *       this alarm in the panel too.</li>
 *   <li>{@code ALSO_COVERS_ASSET} — any other unit whose rounds have covered this asset. They do
 *       not see the alarm, but they read the same machine.</li>
 * </ul>
 *
 * <p>Supervisors are the unit's own, active ones only — a deactivated account is somebody who no
 * longer works here, and texting them is a leak, not a notification.
 */
@Component
@RequiredArgsConstructor
public class AlarmWebhookOutbox {

    public static final String EVENT_RAISED = AlarmWebhookEvents.ALARM_RAISED;
    public static final String EVENT_ESCALATED = AlarmWebhookEvents.ALARM_ESCALATED;
    public static final int MAX_EVIDENCE = 20;
    private static final int MAX_UNIT_DEPTH = 32;

    /**
     * A private mapper, not the application's: the payload is a contract with somebody else, and
     * must not change shape because a {@code spring.jackson.*} setting was tuned for the panel.
     * It serialises maps, lists, strings and numbers only, so it needs no modules.
     */
    private static final ObjectMapper JSON = new ObjectMapper();

    private final AlarmWebhookEndpointRepository endpointRepository;
    private final AlarmWebhookEndpointRuleRepository endpointRuleRepository;
    private final AlarmWebhookDeliveryRepository deliveryRepository;
    private final AssetEntryRepository assetEntryRepository;
    private final AssetClassRepository assetClassRepository;
    private final LogSheetRepository logSheetRepository;
    private final LogSheetEntryRepository entryRepository;
    private final OperationalUnitRepository operationalUnitRepository;
    private final UnitSupervisorRepository unitSupervisorRepository;
    private final UserRepository userRepository;
    private final com.hnp.backendofflinefirst.repository.AlarmRuleRepository ruleRepository;

    /**
     * Queues one event for every active endpoint subscribed to it, or does nothing when there is
     * none.
     *
     * <p>Nothing is queued for a receiver that is off or not subscribed, deliberately: switching
     * one on later must not flood it with a month of alarms that were handled in the panel long
     * ago.
     *
     * @param evidence the breaching readings behind the alarm, newest first
     */
    public void enqueue(Alarm alarm, String eventType, List<Reading> evidence, long now) {
        List<AlarmWebhookEndpoint> subscribed = endpointRepository.findByActiveTrueOrderByIdAsc().stream()
                .filter(endpoint -> AlarmWebhookEvents.subscribes(endpoint.getEventTypes(), eventType))
                .toList();
        // Rule lists are read only for the receivers that have one: an «every alarm» receiver
        // costs no second statement.
        List<AlarmWebhookEndpoint> selective = subscribed.stream()
                .filter(endpoint -> endpoint.getRuleScope() == AlarmWebhookRuleScope.SELECTED)
                .toList();
        Map<Long, Set<Long>> rules = selective.isEmpty() ? Map.of() : rulesOf(selective);
        List<AlarmWebhookEndpoint> receivers = subscribed.stream()
                .filter(endpoint -> AlarmWebhookEndpointService.receives(
                        endpoint, rules.get(endpoint.getId()), alarm.getAlarmRuleId()))
                .toList();
        if (receivers.isEmpty()) {
            return;
        }
        String eventId = UUID.randomUUID().toString();
        Map<Boolean, String> bodies = new HashMap<>(2);
        for (AlarmWebhookEndpoint endpoint : receivers) {
            boolean withContact = endpoint.isIncludeContactDetails();
            String body = bodies.computeIfAbsent(withContact,
                    contact -> serialise(payload(alarm, eventId, eventType, evidence, now, contact)));
            AlarmWebhookDelivery delivery = new AlarmWebhookDelivery();
            delivery.setEventId(eventId);
            delivery.setEventType(eventType);
            delivery.setAlarmId(alarm.getId());
            delivery.setEndpointId(endpoint.getId());
            delivery.setEndpointName(endpoint.getName());
            delivery.setPayload(body);
            delivery.setState(AlarmWebhookState.PENDING);
            delivery.setAttempts(0);
            delivery.setNextAttemptAt(now);
            delivery.setCreatedAt(now);
            deliveryRepository.save(delivery);
        }
    }

    private Map<Long, Set<Long>> rulesOf(List<AlarmWebhookEndpoint> endpoints) {
        Map<Long, Set<Long>> out = new HashMap<>();
        for (AlarmWebhookEndpointRule link : endpointRuleRepository.findByEndpointIdIn(
                endpoints.stream().map(AlarmWebhookEndpoint::getId).toList())) {
            out.computeIfAbsent(link.getEndpointId(), k -> new HashSet<>()).add(link.getAlarmRuleId());
        }
        return out;
    }

    Map<String, Object> payload(Alarm alarm, String eventId, String eventType,
                                List<Reading> evidence, long now, boolean withContact) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("eventId", eventId);
        body.put("eventType", eventType);
        body.put("occurredAt", iso(now));
        body.put("alarm", alarmPart(alarm));
        body.put("field", fieldPart(alarm));
        body.put("asset", assetPart(alarm));
        body.put("logSheet", logSheetPart(alarm.getLogSheetId()));
        body.put("readings", evidencePart(evidence));
        body.put("operationalUnits", unitsPart(alarm, withContact));
        return body;
    }

    private Map<String, Object> alarmPart(Alarm alarm) {
        Map<String, Object> part = new LinkedHashMap<>();
        part.put("id", alarm.getId());
        part.put("state", alarm.getState() == null ? null : alarm.getState().name());
        part.put("severity", alarm.getSeverity() == null ? null : alarm.getSeverity().name());
        part.put("priority", alarm.getPriority() == null ? null : alarm.getPriority().name());
        part.put("triggerSeverity", alarm.getTriggerSeverity() == null ? null : alarm.getTriggerSeverity().name());
        part.put("consecutiveReadings", alarm.getConsecutiveReadings());
        part.put("occurrenceCount", alarm.getOccurrenceCount());
        part.put("conditionMetAt", iso(alarm.getConditionMetAt()));
        part.put("firstReadingAt", iso(alarm.getFirstReadingAt()));
        part.put("lastReadingAt", iso(alarm.getLastReadingAt()));
        part.put("lastValue", alarm.getLastValue());
        part.put("expectedAction", alarm.getExpectedAction());
        part.put("raisedAt", iso(alarm.getCreatedAt()));
        AlarmRule rule = alarm.getAlarmRuleId() == null ? null
                : ruleRepository.findById(alarm.getAlarmRuleId()).orElse(null);
        Map<String, Object> rulePart = new LinkedHashMap<>();
        rulePart.put("id", alarm.getAlarmRuleId());
        rulePart.put("name", rule == null ? null : rule.getName());
        rulePart.put("scope", rule == null || rule.getScope() == null ? null : rule.getScope().name());
        part.put("rule", rulePart);
        // Relative on purpose: this server does not know the address it is reached by, and a
        // guessed absolute URL is a link that works on nobody's network.
        part.put("panelPath", "/alarms/" + alarm.getId());
        return part;
    }

    private static Map<String, Object> fieldPart(Alarm alarm) {
        Map<String, Object> part = new LinkedHashMap<>();
        part.put("definitionId", alarm.getFieldDefinitionId());
        part.put("key", alarm.getFieldKey());
        part.put("label", alarm.getFieldLabel());
        part.put("unit", alarm.getFieldUnit());
        return part;
    }

    private Map<String, Object> assetPart(Alarm alarm) {
        AssetEntry asset = assetEntryRepository.findById(alarm.getAssetId()).orElse(null);
        AssetClass assetClass = asset == null || asset.getClassId() == null
                ? null : assetClassRepository.findById(asset.getClassId()).orElse(null);
        Map<String, Object> part = new LinkedHashMap<>();
        part.put("id", alarm.getAssetId());
        part.put("code", asset == null ? null : asset.getAssetCode());
        // The name frozen on the alarm — the one the reading was taken under.
        part.put("name", alarm.getAssetName() != null ? alarm.getAssetName()
                : asset == null ? null : asset.getAssetName());
        part.put("className", assetClass == null ? null : assetClass.getName());
        part.put("nfcTagId", asset == null ? null : asset.getNfcTagId());
        return part;
    }

    private Map<String, Object> logSheetPart(Long logSheetId) {
        if (logSheetId == null) {
            return null;
        }
        LogSheet sheet = logSheetRepository.findById(logSheetId).orElse(null);
        if (sheet == null) {
            return null;
        }
        Map<String, Object> part = new LinkedHashMap<>();
        part.put("id", sheet.getId());
        part.put("templateId", sheet.getTemplateId());
        part.put("templateName", sheet.getTemplateName());
        part.put("status", sheet.getStatus() == null ? null : sheet.getStatus().name());
        part.put("completedAt", iso(sheet.getCompletedAt()));
        User completedBy = sheet.getCompletedByUserId() == null
                ? null : userRepository.findById(sheet.getCompletedByUserId()).orElse(null);
        part.put("completedBy", completedBy == null ? null : person(completedBy, false));
        return part;
    }

    private static List<Map<String, Object>> evidencePart(List<Reading> evidence) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (evidence == null) {
            return out;
        }
        for (Reading reading : evidence.subList(0, Math.min(evidence.size(), MAX_EVIDENCE))) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("logSheetId", reading.logSheetId());
            row.put("readAt", iso(reading.readAt()));
            row.put("value", reading.value());
            row.put("severity", reading.severity() == null ? null : reading.severity().name());
            out.add(row);
        }
        return out;
    }

    private List<Map<String, Object>> unitsPart(Alarm alarm, boolean withContact) {
        Map<Long, String> relationByUnit = new LinkedHashMap<>();
        Long responsible = alarm.getOperationalUnitId();
        if (responsible != null) {
            relationByUnit.put(responsible, "RESPONSIBLE");
            Set<Long> seen = new HashSet<>();
            seen.add(responsible);
            OperationalUnit current = operationalUnitRepository.findById(responsible).orElse(null);
            for (int depth = 0; current != null && current.getParentId() != null && depth < MAX_UNIT_DEPTH; depth++) {
                Long parentId = current.getParentId();
                if (!seen.add(parentId)) {
                    break; // a cycle in the data must not hang an alarm
                }
                relationByUnit.putIfAbsent(parentId, "PARENT_OF_RESPONSIBLE");
                current = operationalUnitRepository.findById(parentId).orElse(null);
            }
        }
        for (Long unitId : entryRepository.findUnitIdsWhoseRoundsCoveredAsset(alarm.getAssetId())) {
            relationByUnit.putIfAbsent(unitId, "ALSO_COVERS_ASSET");
        }
        if (relationByUnit.isEmpty()) {
            return List.of();
        }

        Map<Long, OperationalUnit> units = operationalUnitRepository.findAllById(relationByUnit.keySet())
                .stream().collect(Collectors.toMap(OperationalUnit::getId, Function.identity()));
        List<UnitSupervisor> links = unitSupervisorRepository.findByUnitIdIn(relationByUnit.keySet());
        Map<Long, User> users = userRepository.findAllById(links.stream()
                        .map(UnitSupervisor::getUserId).filter(Objects::nonNull).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(User::getId, Function.identity()));

        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<Long, String> entry : relationByUnit.entrySet()) {
            OperationalUnit unit = units.get(entry.getKey());
            Map<String, Object> part = new LinkedHashMap<>();
            part.put("id", entry.getKey());
            part.put("code", unit == null ? null : unit.getCode());
            part.put("name", unit == null ? null : unit.getName());
            part.put("parentId", unit == null ? null : unit.getParentId());
            part.put("relation", entry.getValue());
            List<Map<String, Object>> supervisors = new ArrayList<>();
            for (UnitSupervisor link : links) {
                if (!entry.getKey().equals(link.getUnitId())) {
                    continue;
                }
                User user = users.get(link.getUserId());
                if (user != null && user.isActive()) {
                    supervisors.add(person(user, withContact));
                }
            }
            part.put("supervisors", supervisors);
            out.add(part);
        }
        return out;
    }

    /**
     * A person as the receiver needs them. {@code personnelCode} is the join key a plant's HR or
     * messaging system already holds; the internal user id is deliberately absent, for the reason
     * the integration API gives (IntegrationReferences.Person).
     */
    private static Map<String, Object> person(User user, boolean withContact) {
        Map<String, Object> part = new LinkedHashMap<>();
        part.put("username", user.getUsername());
        part.put("fullName", user.getFullName());
        part.put("personnelCode", user.getPersonnelCode());
        if (withContact) {
            part.put("phoneNumber", user.getPhoneNumber());
        }
        return part;
    }

    private static String serialise(Map<String, Object> payload) {
        try {
            return JSON.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            // Maps of strings and numbers cannot fail to serialise; if they somehow did, failing
            // the evaluation (and retrying it) is better than queueing an empty body.
            throw new IllegalStateException("Could not serialise the alarm webhook payload", e);
        }
    }

    static String iso(Long epochMillis) {
        return epochMillis == null ? null : DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(epochMillis));
    }
}
