package com.hnp.backendofflinefirst.service.alarm;

import com.hnp.backendofflinefirst.domain.AlarmCloseReason;
import com.hnp.backendofflinefirst.domain.AlarmCondition;
import com.hnp.backendofflinefirst.domain.AlarmCondition.Reading;
import com.hnp.backendofflinefirst.domain.AlarmEventType;
import com.hnp.backendofflinefirst.domain.AlarmRuleScope;
import com.hnp.backendofflinefirst.domain.AlarmState;
import com.hnp.backendofflinefirst.domain.FieldValidationSeverity;
import com.hnp.backendofflinefirst.entity.Alarm;
import com.hnp.backendofflinefirst.entity.AlarmRule;
import com.hnp.backendofflinefirst.entity.FieldDefinition;
import com.hnp.backendofflinefirst.entity.LogSheet;
import com.hnp.backendofflinefirst.entity.LogSheetEntry;
import com.hnp.backendofflinefirst.repository.AlarmRepository;
import com.hnp.backendofflinefirst.repository.LogSheetEntryRepository;
import com.hnp.backendofflinefirst.repository.LogSheetRepository;
import com.hnp.backendofflinefirst.service.LogSheetFieldDefinitionsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Re-judges the alarms of every asset on one round.
 *
 * <h2>Which rule governs a parameter (V18)</h2>
 *
 * <p>The {@code FIELDS} rule that lists it — <b>active or not</b>: a specific rule switched off
 * silences its parameters, it does not hand them back to the default. Otherwise the single
 * {@code ALL_FIELDS} default rule, when it is active. Parameters are matched by definition id, which
 * survives flattening into a sub-class, against the round's own resolved definitions (the frozen
 * schema with the asset's band override): a parameter the round never asked about cannot raise an
 * alarm from it.
 *
 * <h2>A recomputation, never an increment</h2>
 *
 * <p>Nothing here adds one to a counter. Each evaluation reads the asset's finished readings and
 * decides what the alarm should look like now — so a round arriving out of order, a duplicated or
 * retried request, or a void and a restore all come out right with no special case.
 *
 * <h2>Only what can change is looked at</h2>
 *
 * <p>A default rule covers every parameter, and a round is 47 assets × 22 parameters (roadmap §20).
 * Asking the history of all of them on every completion would be a thousand queries per round. So
 * a pair (asset, parameter) is evaluated only when something about it can actually change:
 * <ul>
 *   <li><b>it has a live alarm</b> — any change to its readings may grow, escalate, clear or
 *       withdraw it (one query finds all of a round's live alarms);</li>
 *   <li><b>completed or restored, and this round's reading breaches</b> — a reading that does not
 *       breach can only break a run, never make one, wherever in time it lands;</li>
 *   <li><b>voided, and this round's reading was normal</b> — taking a normal reading out of the
 *       middle can join two runs into one long enough. Taking a breach out never raises.</li>
 * </ul>
 * This round's reading is judged from the stored verdict on its own entry, with no query. Breaches
 * are the minority of readings, so a round with nothing wrong costs two queries whatever its size.
 *
 * <h2>The unit is fixed when an alarm is raised</h2>
 *
 * <p>An asset read by two units' rounds would otherwise move between their queues every time the
 * other unit walked it — out of the queue of the supervisor who acknowledged it.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AlarmEvaluationService {

    /** Readings examined to raise: enough to find a run of 20 behind a long stretch of unanswered rounds. */
    static final int RAISE_HISTORY_LIMIT = 200;

    /**
     * Readings examined for a live alarm. An alarm open for years on a daily round exceeds it;
     * then the oldest are not counted, and withdrawal is never concluded from a truncated list.
     */
    static final int LIFETIME_HISTORY_LIMIT = 1000;

    private final LogSheetRepository logSheetRepository;
    private final LogSheetEntryRepository entryRepository;
    private final AlarmRuleService ruleService;
    private final AlarmRepository alarmRepository;
    private final LogSheetFieldDefinitionsService fieldDefinitionsService;
    private final AlarmReadingHistory history;
    private final AlarmLifecycle lifecycle;
    private final AlarmWebhookOutbox webhookOutbox;

    /** Who governs which parameter, for one evaluation. */
    record Governance(Map<Long, AlarmRule> specificByField, AlarmRule defaultRule) {

        static Governance of(List<AlarmRule> rules) {
            Map<Long, AlarmRule> specific = new HashMap<>();
            AlarmRule fallback = null;
            for (AlarmRule rule : rules) {
                if (rule.getScope() == AlarmRuleScope.ALL_FIELDS) {
                    if (rule.isActive()) {
                        fallback = rule;
                    }
                } else {
                    for (Long fieldId : rule.getFieldDefinitionIds()) {
                        specific.put(fieldId, rule);
                    }
                }
            }
            return new Governance(specific, fallback);
        }

        /** The active rule governing this parameter, or null when none does. */
        AlarmRule ruleFor(FieldDefinition definition) {
            AlarmRule specific = specificByField.get(definition.getId());
            if (specific != null) {
                return specific.isActive() ? specific : null; // listed and switched off: silenced
            }
            if (defaultRule != null && AlarmRuleService.ALARMABLE_TYPES.contains(definition.getDataType())) {
                return defaultRule;
            }
            return null;
        }

        boolean isEmpty() {
            return specificByField.isEmpty() && defaultRule == null;
        }
    }

    /** As {@link #evaluateSheet(Long, String)} for a completed round. */
    @Transactional
    public void evaluateSheet(Long logSheetId) {
        evaluateSheet(logSheetId, "COMPLETED");
    }

    /**
     * Re-judges what this round can have changed.
     *
     * @param reason {@code COMPLETED}, {@code RESTORED} or {@code VOIDED} — decides which
     *               readings of this round can still change an alarm (class comment)
     */
    @Transactional
    public void evaluateSheet(Long logSheetId, String reason) {
        LogSheet sheet = logSheetRepository.findById(logSheetId).orElse(null);
        if (sheet == null) {
            return;
        }
        List<LogSheetEntry> entries = entryRepository.findByLogSheetId(logSheetId);
        Set<Long> assetIds = new LinkedHashSet<>();
        for (LogSheetEntry entry : entries) {
            if (entry.getAssetId() != null) {
                assetIds.add(entry.getAssetId());
            }
        }
        if (assetIds.isEmpty()) {
            return;
        }

        Map<String, Alarm> liveByPair = new HashMap<>();
        for (Alarm alarm : alarmRepository.findLiveForAssets(assetIds)) {
            liveByPair.put(pair(alarm.getAssetId(), alarm.getFieldDefinitionId()), alarm);
        }
        Governance governance = Governance.of(ruleService.all());
        if (governance.isEmpty() && liveByPair.isEmpty()) {
            return; // no rule and nothing live: nothing this round can change
        }

        boolean voided = "VOIDED".equals(reason);
        Map<Long, LogSheet> sheetCache = new HashMap<>();
        sheetCache.put(sheet.getId(), sheet);
        Set<String> done = new LinkedHashSet<>();
        long now = System.currentTimeMillis();

        for (LogSheetEntry entry : entries) {
            if (entry.getAssetId() == null) {
                continue;
            }
            for (FieldDefinition definition : fieldDefinitionsService.resolveForEntry(sheet, entry)) {
                if (definition == null || definition.getId() == null || definition.isDeleted()) {
                    continue;
                }
                String key = pair(entry.getAssetId(), definition.getId());
                if (!done.add(key)) {
                    continue;
                }
                Alarm live = liveByPair.get(key);
                if (live != null) {
                    reconcile(live, logSheetId, now);
                    continue;
                }
                AlarmRule rule = governance.ruleFor(definition);
                if (rule == null) {
                    continue;
                }
                FieldValidationSeverity reading = thisRoundsReading(entry, definition.getKey(), sheetCache);
                boolean canRaise = voided
                        ? reading != null && !AlarmCondition.breaches(reading, rule.getTriggerSeverity())
                        : AlarmCondition.breaches(reading, rule.getTriggerSeverity());
                if (canRaise) {
                    raiseIfMet(rule, entry, definition, now);
                }
            }
        }
    }

    private FieldValidationSeverity thisRoundsReading(LogSheetEntry entry, String key,
                                                      Map<Long, LogSheet> sheetCache) {
        Object raw = entry.getFormData() == null ? null : entry.getFormData().get(key);
        return AlarmReadingHistory.isAnswered(raw) ? history.severityOf(entry, key, sheetCache) : null;
    }

    private static String pair(Long assetId, Long fieldDefinitionId) {
        return assetId + ":" + fieldDefinitionId;
    }

    private void raiseIfMet(AlarmRule rule, LogSheetEntry entry, FieldDefinition definition, long now) {
        Long assetId = entry.getAssetId();
        Long watermark = alarmRepository.consumedWatermark(assetId, definition.getId());
        List<Reading> readings = history.readings(assetId, definition.getKey(),
                watermark == null ? Long.MIN_VALUE : watermark, RAISE_HISTORY_LIMIT);

        List<Reading> run = AlarmCondition.leadingBreachRun(
                readings, rule.getTriggerSeverity(), rule.getWindowDays());
        Reading metBy = AlarmCondition.conditionMetBy(run, rule.getConsecutiveReadings());
        if (metBy == null) {
            return;
        }
        Reading newest = run.get(0);
        Reading oldest = run.get(run.size() - 1);

        Alarm alarm = new Alarm();
        alarm.setAlarmRuleId(rule.getId());
        alarm.setAssetId(assetId);
        alarm.setFieldDefinitionId(definition.getId());
        alarm.setFieldKey(definition.getKey());
        alarm.setFieldLabel(definition.getLabel());
        alarm.setFieldUnit(definition.getUnit());
        alarm.setAssetName(entry.getAssetName());
        alarm.setOperationalUnitId(newest.unitId());
        alarm.setLogSheetId(newest.logSheetId());
        alarm.setState(AlarmState.OPEN);
        alarm.setSeverity(AlarmCondition.worst(run));
        alarm.setPriority(rule.getPriority());
        alarm.setTriggerSeverity(rule.getTriggerSeverity());
        alarm.setConsecutiveReadings(rule.getConsecutiveReadings());
        alarm.setClearAfterReadings(rule.getClearAfterReadings());
        alarm.setExpectedAction(rule.getExpectedAction());
        alarm.setOccurrenceCount(run.size());
        alarm.setConditionMetAt(metBy.readAt());
        alarm.setFirstReadingAt(oldest.readAt());
        alarm.setLastReadingAt(newest.readAt());
        alarm.setLastValue(newest.value());
        alarm.setCreatedAt(now);
        alarm.setUpdatedAt(now);
        // Flushed here so a lost race with another evaluator surfaces as the unique-index
        // violation inside THIS transaction, which is then retried from the queue and finds the
        // winner's alarm live.
        alarm = alarmRepository.saveAndFlush(alarm);

        lifecycle.record(alarm, AlarmEventType.RAISED, null, newest.logSheetId(), newest.severity(),
                newest.value(), run.size() + " قرائت پیاپی — " + rule.getName(), now);
        webhookOutbox.enqueue(alarm, AlarmWebhookOutbox.EVENT_RAISED, run, now);
        log.info("Alarm {} raised: rule {}, asset {}, field {}, {} x {}", alarm.getId(), rule.getId(),
                assetId, definition.getId(), run.size(), alarm.getSeverity());
    }

    private void reconcile(Alarm alarm, Long triggeringSheetId, long now) {
        List<Reading> readings = history.readings(alarm.getAssetId(), alarm.getFieldKey(),
                alarm.getFirstReadingAt() - 1, LIFETIME_HISTORY_LIMIT);
        boolean truncated = readings.size() >= LIFETIME_HISTORY_LIMIT;
        FieldValidationSeverity trigger = alarm.getTriggerSeverity();
        List<Reading> breaching = AlarmCondition.breaching(readings, trigger);

        if (breaching.isEmpty()) {
            if (!truncated) {
                lifecycle.close(alarm, AlarmCloseReason.EVIDENCE_WITHDRAWN, null, null, now);
                alarmRepository.save(alarm);
            }
            return;
        }

        Reading newest = breaching.get(0);
        Reading fromTrigger = readings.stream()
                .filter(r -> Objects.equals(r.logSheetId(), triggeringSheetId))
                .findFirst().orElse(null);
        if (fromTrigger != null && AlarmCondition.breaches(fromTrigger.severity(), trigger)
                && breaching.size() > alarm.getOccurrenceCount()) {
            lifecycle.record(alarm, AlarmEventType.OCCURRED, null, fromTrigger.logSheetId(),
                    fromTrigger.severity(), fromTrigger.value(), null, now);
        }

        alarm.setOccurrenceCount(breaching.size());
        alarm.setFirstReadingAt(breaching.get(breaching.size() - 1).readAt());
        alarm.setLastReadingAt(newest.readAt());
        alarm.setLastValue(newest.value());
        alarm.setLogSheetId(newest.logSheetId());

        FieldValidationSeverity worst = AlarmCondition.worst(breaching);
        if (worst.ordinal() > alarm.getSeverity().ordinal()) {
            alarm.setSeverity(worst);
            if (alarm.getState() == AlarmState.ACKNOWLEDGED) {
                // An acknowledgement of a warning is not an acknowledgement of a danger.
                alarm.setState(AlarmState.OPEN);
                alarm.setAcknowledgedAt(null);
                alarm.setAcknowledgedByUserId(null);
                alarm.setAckComment(null);
            }
            lifecycle.record(alarm, AlarmEventType.ESCALATED, null, newest.logSheetId(), worst,
                    newest.value(), null, now);
            webhookOutbox.enqueue(alarm, AlarmWebhookOutbox.EVENT_ESCALATED, breaching, now);
        } else if (worst.ordinal() < alarm.getSeverity().ordinal()) {
            alarm.setSeverity(worst);
            lifecycle.record(alarm, AlarmEventType.DEESCALATED, null, null, worst, null, null, now);
        }

        List<Reading> normals = AlarmCondition.leadingNormalRun(readings, trigger);
        Reading clearedBy = AlarmCondition.clearedBy(normals, alarm.getClearAfterReadings());
        if (clearedBy != null) {
            if (alarm.getReturnedToNormalAt() == null) {
                alarm.setReturnedToNormalAt(clearedBy.readAt());
                lifecycle.record(alarm, AlarmEventType.RETURNED_TO_NORMAL, null, clearedBy.logSheetId(),
                        clearedBy.severity(), clearedBy.value(), null, now);
            }
            if (alarm.getState() == AlarmState.ACKNOWLEDGED) {
                lifecycle.close(alarm, AlarmCloseReason.RETURNED_TO_NORMAL, null, null, now);
            }
        } else if (alarm.getReturnedToNormalAt() != null) {
            alarm.setReturnedToNormalAt(null);
            lifecycle.record(alarm, AlarmEventType.REACTIVATED, null, newest.logSheetId(),
                    newest.severity(), newest.value(), null, now);
        }

        alarm.setUpdatedAt(now);
        alarmRepository.save(alarm);
    }
}
