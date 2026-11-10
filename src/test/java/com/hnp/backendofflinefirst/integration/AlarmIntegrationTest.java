package com.hnp.backendofflinefirst.integration;

import com.hnp.backendofflinefirst.domain.ActionSource;
import com.hnp.backendofflinefirst.domain.AlarmCloseReason;
import com.hnp.backendofflinefirst.domain.AlarmEventType;
import com.hnp.backendofflinefirst.domain.AlarmState;
import com.hnp.backendofflinefirst.domain.EntrySeverityEvaluator;
import com.hnp.backendofflinefirst.domain.FieldDefinitionSnapshot;
import com.hnp.backendofflinefirst.domain.FieldValidationSeverity;
import com.hnp.backendofflinefirst.domain.FieldValidationSupport;
import com.hnp.backendofflinefirst.domain.GenerationMode;
import com.hnp.backendofflinefirst.domain.LogSheetStatus;
import com.hnp.backendofflinefirst.entity.Alarm;
import com.hnp.backendofflinefirst.entity.AlarmEvent;
import com.hnp.backendofflinefirst.entity.AssetClass;
import com.hnp.backendofflinefirst.entity.AssetEntry;
import com.hnp.backendofflinefirst.entity.FieldDefinition;
import com.hnp.backendofflinefirst.entity.Location;
import com.hnp.backendofflinefirst.entity.LogSheet;
import com.hnp.backendofflinefirst.entity.LogSheetEntry;
import com.hnp.backendofflinefirst.entity.OperationalUnit;
import com.hnp.backendofflinefirst.entity.SubFunction;
import com.hnp.backendofflinefirst.entity.User;
import com.hnp.backendofflinefirst.repository.AlarmEventRepository;
import com.hnp.backendofflinefirst.repository.AlarmRepository;
import com.hnp.backendofflinefirst.repository.AlarmRuleRepository;
import com.hnp.backendofflinefirst.repository.AssetClassRepository;
import com.hnp.backendofflinefirst.repository.AssetEntryRepository;
import com.hnp.backendofflinefirst.repository.FieldDefinitionRepository;
import com.hnp.backendofflinefirst.repository.LocationRepository;
import com.hnp.backendofflinefirst.repository.LogSheetEntryRepository;
import com.hnp.backendofflinefirst.repository.LogSheetRepository;
import com.hnp.backendofflinefirst.repository.OperationalUnitRepository;
import com.hnp.backendofflinefirst.repository.UserRepository;
import com.hnp.backendofflinefirst.security.AppUserDetails;
import com.hnp.backendofflinefirst.service.AssetHierarchyService;
import com.hnp.backendofflinefirst.service.LogSheetAssignmentService;
import com.hnp.backendofflinefirst.service.LogSheetFieldDefinitionsService;
import com.hnp.backendofflinefirst.service.LogSheetService;
import com.hnp.backendofflinefirst.service.alarm.AlarmEvaluationProcessor;
import com.hnp.backendofflinefirst.service.alarm.AlarmQueue;
import com.hnp.backendofflinefirst.service.alarm.AlarmRuleService;
import com.hnp.backendofflinefirst.service.alarm.AlarmRuleService.RuleForm;
import com.hnp.backendofflinefirst.service.alarm.AlarmService;
import com.hnp.backendofflinefirst.support.AbstractPostgresIntegrationTest;
import com.hnp.backendofflinefirst.support.TestPrincipals;
import com.hnp.backendofflinefirst.support.WithAppUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Alarms, end to end against PostgreSQL: rounds are written the way the completion paths write
 * them (entries stamped by {@code EntrySeverityEvaluator}, a request queued), and the queue is
 * drained the way production drains it.
 *
 * <p><b>Not {@code @Transactional}.</b> Evaluation runs in its own {@code REQUIRES_NEW}
 * transactions — exactly so that it can never fail a round's — and those cannot see a test
 * transaction that has not committed. Everything here is committed and deleted in
 * {@link #tearDown()}, alarms first (gotcha #186: a fixture row is a row in every later test).
 *
 * <p>The temperature parameter's bands: 50 is fine, 85 is a warning, 120 is a danger.
 */
class AlarmIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final long DAY = 86_400_000L;

    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired OperationalUnitRepository unitRepository;
    @Autowired UserRepository userRepository;
    @Autowired LocationRepository locationRepository;
    @Autowired AssetHierarchyService hierarchyService;
    @Autowired AssetClassRepository classRepository;
    @Autowired FieldDefinitionRepository fieldRepository;
    @Autowired AssetEntryRepository assetRepository;
    @Autowired LogSheetRepository sheetRepository;
    @Autowired LogSheetEntryRepository entryRepository;
    @Autowired LogSheetFieldDefinitionsService fieldDefinitionsService;
    @Autowired LogSheetAssignmentService assignmentService;
    @Autowired LogSheetService logSheetService;
    @Autowired AlarmRuleService ruleService;
    @Autowired AlarmRuleRepository ruleRepository;
    @Autowired AlarmService alarmService;
    @Autowired AlarmQueue alarmQueue;
    @Autowired AlarmEvaluationProcessor processor;
    @Autowired AlarmRepository alarmRepository;
    @Autowired AlarmEventRepository eventRepository;

    private final List<Long> sheetIds = new ArrayList<>();
    private final List<Long> ruleIds = new ArrayList<>();
    private Fixture f;
    private long t0;

    private record Fixture(Long parentUnit, Long unit, Long otherUnit, Long classId,
                           Long tempField, Long stateField, Long pumpA, Long pumpB,
                           String pumpACode, User supervisor, User parentSupervisor,
                           User otherSupervisor, Long locationId, List<Long> subFunctions,
                           List<FieldDefinitionSnapshot> snapshot) {}

    @BeforeEach
    void setUp() {
        t0 = System.currentTimeMillis() - 60 * DAY;
        f = seed();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        jdbc.update("DELETE FROM alarms WHERE asset_id IN (?, ?)", f.pumpA(), f.pumpB());
        List<Long> rules = new ArrayList<>(ruleIds);
        jdbc.query("SELECT DISTINCT alarm_rule_id FROM alarm_rule_fields WHERE field_definition_id IN "
                        + "(SELECT id FROM field_definitions WHERE class_id = ?)",
                rs -> { rules.add(rs.getLong(1)); }, f.classId());
        // A default rule is this class's too: no other test creates one, and leaving it would
        // govern every parameter of every later test.
        jdbc.query("SELECT id FROM alarm_rules WHERE scope = 'ALL_FIELDS'", rs -> { rules.add(rs.getLong(1)); });
        for (Long ruleId : rules) {
            jdbc.update("DELETE FROM alarms WHERE alarm_rule_id = ?", ruleId);
            jdbc.update("DELETE FROM alarm_rules WHERE id = ?", ruleId);
        }
        for (Long sheetId : sheetIds) {
            jdbc.update("DELETE FROM alarm_evaluation_requests WHERE log_sheet_id = ?", sheetId);
            jdbc.update("DELETE FROM log_sheet_action_log WHERE log_sheet_id = ?", sheetId);
            jdbc.update("DELETE FROM log_sheet_entry_revisions WHERE log_sheet_id = ?", sheetId);
            jdbc.update("DELETE FROM log_sheet_entries WHERE log_sheet_id = ?", sheetId);
            jdbc.update("DELETE FROM log_sheets WHERE id = ?", sheetId);
        }
        jdbc.update("DELETE FROM asset_entries WHERE id IN (?, ?)", f.pumpA(), f.pumpB());
        jdbc.update("DELETE FROM field_definitions WHERE class_id = ?", f.classId());
        jdbc.update("DELETE FROM asset_classes WHERE id = ?", f.classId());
        for (Long sub : f.subFunctions()) {
            jdbc.update("DELETE FROM sub_functions WHERE id = ?", sub);
        }
        jdbc.update("DELETE FROM locations WHERE id = ?", f.locationId());
        for (User u : List.of(f.supervisor(), f.parentSupervisor(), f.otherSupervisor())) {
            jdbc.update("DELETE FROM unit_supervisors WHERE user_id = ?", u.getId());
            jdbc.update("DELETE FROM users WHERE id = ?", u.getId());
        }
        jdbc.update("DELETE FROM operational_units WHERE id IN (?, ?)", f.unit(), f.otherUnit());
        jdbc.update("DELETE FROM operational_units WHERE id = ?", f.parentUnit());
    }

    // ── Raising ──────────────────────────────────────────────────────────────

    @Test
    void threeWarningsInARowRaiseOnTheThirdAndNotBefore() {
        Long rule = rule(f.tempField(), "WARNING", 3, 1, null);

        round(f.pumpA(), 1, 85);
        round(f.pumpA(), 2, 85);
        assertThat(live(rule, f.pumpA())).as("two of three").isNull();

        round(f.pumpA(), 3, 85);
        Alarm alarm = live(rule, f.pumpA());
        assertThat(alarm).isNotNull();
        assertThat(alarm.getState()).isEqualTo(AlarmState.OPEN);
        assertThat(alarm.getSeverity()).isEqualTo(FieldValidationSeverity.WARNING);
        assertThat(alarm.getOccurrenceCount()).isEqualTo(3);
        assertThat(alarm.getConditionMetAt()).isEqualTo(day(3));
        assertThat(alarm.getFirstReadingAt()).isEqualTo(day(1));
        assertThat(alarm.getLastValue()).isEqualTo("85");
        assertThat(alarm.getOperationalUnitId()).isEqualTo(f.unit());
        assertThat(alarm.getExpectedAction()).isEqualTo("اعزام مکانیک");
        assertThat(events(alarm)).containsExactly(AlarmEventType.RAISED);
    }

    @Test
    void aNormalReadingResetsTheRun() {
        Long rule = rule(f.tempField(), "WARNING", 3, 1, null);

        round(f.pumpA(), 1, 85);
        round(f.pumpA(), 2, 85);
        round(f.pumpA(), 3, 50);
        round(f.pumpA(), 4, 85);
        round(f.pumpA(), 5, 85);
        assertThat(live(rule, f.pumpA())).isNull();

        round(f.pumpA(), 6, 85);
        Alarm alarm = live(rule, f.pumpA());
        assertThat(alarm).isNotNull();
        assertThat(alarm.getFirstReadingAt()).isEqualTo(day(4));
        assertThat(alarm.getOccurrenceCount()).isEqualTo(3);
    }

    @Test
    void anUnansweredReadingNeitherCountsNorResets() {
        Long rule = rule(f.tempField(), "WARNING", 3, 1, null);

        round(f.pumpA(), 1, 85);
        round(f.pumpA(), 2, 85);
        round(f.pumpA(), 3, (Object) null); // on the round, nobody read it
        assertThat(live(rule, f.pumpA())).isNull();

        round(f.pumpA(), 4, 85);
        assertThat(live(rule, f.pumpA()).getOccurrenceCount()).isEqualTo(3);
    }

    @Test
    void theWindowStopsARunFromReachingBackTooFar() {
        Long rule = rule(f.tempField(), "WARNING", 3, 1, 7);

        round(f.pumpA(), 1, 85);
        round(f.pumpA(), 11, 85);
        round(f.pumpA(), 12, 85);
        assertThat(live(rule, f.pumpA())).as("day 1 is outside 7 days of day 12").isNull();

        round(f.pumpA(), 13, 85);
        assertThat(live(rule, f.pumpA())).isNotNull();
    }

    @Test
    void aChoiceFieldRaisesOnADangerOption() {
        Long rule = rule(f.stateField(), "DANGER", 1, 1, null);

        round(f.pumpA(), 1, Map.of("state", "LEAK"));

        Alarm alarm = live(rule, f.pumpA());
        assertThat(alarm).isNotNull();
        assertThat(alarm.getSeverity()).isEqualTo(FieldValidationSeverity.DANGER);
        assertThat(alarm.getLastValue()).isEqualTo("LEAK");
    }

    @Test
    void anAmbiguousStoredVerdictIsResolvedByTheEvaluatorAndNotGuessed() {
        // One round, a danger on `state` and a warning on `temp`. breached_fields lists the
        // danger first and does not say where dangers end — so «was temp a danger» must be
        // answered by the evaluator, and the answer is no.
        Long tempDanger = rule(f.tempField(), "DANGER", 1, 1, null);
        Long stateDanger = rule(f.stateField(), "DANGER", 1, 1, null);

        Map<String, Object> values = new LinkedHashMap<>();
        values.put("state", "LEAK");
        values.put("temp", 85);
        round(f.pumpA(), 1, values);

        assertThat(live(tempDanger, f.pumpA())).isNull();
        assertThat(live(stateDanger, f.pumpA())).isNotNull();
    }

    @Test
    void anAlarmBelongsToOneAssetOnly() {
        Long rule = rule(f.tempField(), "DANGER", 1, 1, null);

        round(f.pumpA(), 1, 120);

        assertThat(live(rule, f.pumpA())).isNotNull();
        assertThat(live(rule, f.pumpB())).isNull();
    }

    // ── One alarm per condition ──────────────────────────────────────────────

    @Test
    void repeatedBreachesUpdateTheSameAlarm() {
        Long rule = rule(f.tempField(), "WARNING", 1, 1, null);

        round(f.pumpA(), 1, 85);
        Long first = live(rule, f.pumpA()).getId();
        round(f.pumpA(), 2, 86);

        Alarm alarm = live(rule, f.pumpA());
        assertThat(alarm.getId()).isEqualTo(first);
        assertThat(alarm.getOccurrenceCount()).isEqualTo(2);
        assertThat(alarm.getLastValue()).isEqualTo("86");
        assertThat(alarmCount(rule)).isEqualTo(1);
        assertThat(events(alarm)).containsExactly(AlarmEventType.RAISED, AlarmEventType.OCCURRED);
    }

    @Test
    void evaluatingTheSameRoundTwiceChangesNothing() {
        Long rule = rule(f.tempField(), "WARNING", 1, 1, null);
        Long sheet = round(f.pumpA(), 1, 85);
        Alarm before = live(rule, f.pumpA());

        alarmQueue.request(sheet, "COMPLETED");
        processor.drain();

        Alarm after = live(rule, f.pumpA());
        assertThat(after.getId()).isEqualTo(before.getId());
        assertThat(after.getOccurrenceCount()).isEqualTo(1);
        assertThat(events(after)).containsExactly(AlarmEventType.RAISED);
    }

    // ── Escalation and clearing ──────────────────────────────────────────────

    @Test
    void aDangerReadingEscalatesAndReopensAnAcknowledgedWarning() {
        Long rule = rule(f.tempField(), "WARNING", 1, 1, null);
        round(f.pumpA(), 1, 85);
        asAdmin();
        alarmService.acknowledge(live(rule, f.pumpA()).getId(), f.supervisor().getId(), "دیدم");

        round(f.pumpA(), 2, 120);

        Alarm alarm = live(rule, f.pumpA());
        assertThat(alarm.getSeverity()).isEqualTo(FieldValidationSeverity.DANGER);
        assertThat(alarm.getState())
                .as("an acknowledgement of a warning is not an acknowledgement of a danger")
                .isEqualTo(AlarmState.OPEN);
        assertThat(alarm.getAcknowledgedAt()).isNull();
        assertThat(events(alarm)).contains(AlarmEventType.ESCALATED);
    }

    @Test
    void anAcknowledgedAlarmClosesWhenItReturnsToNormal() {
        Long rule = rule(f.tempField(), "WARNING", 1, 1, null);
        round(f.pumpA(), 1, 85);
        Long id = live(rule, f.pumpA()).getId();
        asAdmin();
        alarmService.acknowledge(id, f.supervisor().getId(), null);

        round(f.pumpA(), 2, 50);

        Alarm alarm = alarmRepository.findById(id).orElseThrow();
        assertThat(alarm.getState()).isEqualTo(AlarmState.CLOSED);
        assertThat(alarm.getCloseReason()).isEqualTo(AlarmCloseReason.RETURNED_TO_NORMAL);
        assertThat(alarm.getReturnedToNormalAt()).isEqualTo(day(2));
    }

    @Test
    void anUnacknowledgedAlarmStaysOpenWhenItClearsAndTheAcknowledgementClosesIt() {
        // A breach nobody saw must not disappear on its own.
        Long rule = rule(f.tempField(), "WARNING", 1, 1, null);
        round(f.pumpA(), 1, 85);
        round(f.pumpA(), 2, 50);

        Alarm alarm = live(rule, f.pumpA());
        assertThat(alarm.getState()).isEqualTo(AlarmState.OPEN);
        assertThat(alarm.getReturnedToNormalAt()).isEqualTo(day(2));

        asAdmin();
        Alarm acknowledged = alarmService.acknowledge(alarm.getId(), f.supervisor().getId(), "دیدم");
        assertThat(acknowledged.getState()).isEqualTo(AlarmState.CLOSED);
        assertThat(acknowledged.getCloseReason()).isEqualTo(AlarmCloseReason.RETURNED_TO_NORMAL);
    }

    @Test
    void aBreachAfterClearingReactivatesTheSameAlarm() {
        Long rule = rule(f.tempField(), "WARNING", 1, 1, null);
        round(f.pumpA(), 1, 85);
        round(f.pumpA(), 2, 50);
        round(f.pumpA(), 3, 85);

        Alarm alarm = live(rule, f.pumpA());
        assertThat(alarm.getReturnedToNormalAt()).isNull();
        assertThat(alarm.getOccurrenceCount()).isEqualTo(2);
        assertThat(events(alarm)).contains(AlarmEventType.RETURNED_TO_NORMAL, AlarmEventType.REACTIVATED);
        assertThat(alarmCount(rule)).isEqualTo(1);
    }

    @Test
    void clearAfterTwoNeedsTwoNormalReadingsInARow() {
        Long rule = rule(f.tempField(), "WARNING", 1, 2, null);
        round(f.pumpA(), 1, 85);
        Long id = live(rule, f.pumpA()).getId();
        asAdmin();
        alarmService.acknowledge(id, f.supervisor().getId(), null);

        round(f.pumpA(), 2, 50);
        assertThat(alarmRepository.findById(id).orElseThrow().getState()).isEqualTo(AlarmState.ACKNOWLEDGED);

        round(f.pumpA(), 3, 50);
        assertThat(alarmRepository.findById(id).orElseThrow().getState()).isEqualTo(AlarmState.CLOSED);
    }

    // ── Order of arrival, voids, restores ────────────────────────────────────

    @Test
    void aLateRoundIsJudgedInThePlaceItWasTaken() {
        Long rule = rule(f.tempField(), "WARNING", 3, 1, null);
        round(f.pumpA(), 1, 85);
        round(f.pumpA(), 3, 85);
        assertThat(live(rule, f.pumpA())).isNull();

        // Day 2's round was walked offline and delivered last.
        round(f.pumpA(), 2, 85);

        Alarm alarm = live(rule, f.pumpA());
        assertThat(alarm).isNotNull();
        assertThat(alarm.getConditionMetAt()).as("three in a row first held on day 3").isEqualTo(day(3));
        assertThat(alarm.getFirstReadingAt()).isEqualTo(day(1));
    }

    @Test
    void aLateNormalReadingOlderThanTheBreachDoesNotClearIt() {
        Long rule = rule(f.tempField(), "WARNING", 1, 1, null);
        round(f.pumpA(), 2, 85);
        Long id = live(rule, f.pumpA()).getId();
        asAdmin();
        alarmService.acknowledge(id, f.supervisor().getId(), null);

        round(f.pumpA(), 1, 50); // Monday's fine reading, delivered after Tuesday's breach

        assertThat(alarmRepository.findById(id).orElseThrow().getState()).isEqualTo(AlarmState.ACKNOWLEDGED);
    }

    @Test
    void voidingTheOnlyEvidenceWithdrawsTheAlarmAndRestoringRaisesItAgain() {
        Long rule = rule(f.tempField(), "DANGER", 1, 1, null);
        Long sheet = round(f.pumpA(), 1, 120);
        Long first = live(rule, f.pumpA()).getId();

        assignmentService.voidSubmitted(sheet, f.supervisor().getId(), ActionSource.WEB);
        processor.drain();

        Alarm withdrawn = alarmRepository.findById(first).orElseThrow();
        assertThat(withdrawn.getState()).isEqualTo(AlarmState.CLOSED);
        assertThat(withdrawn.getCloseReason()).isEqualTo(AlarmCloseReason.EVIDENCE_WITHDRAWN);

        assignmentService.restoreVoided(sheet, f.supervisor().getId(), ActionSource.WEB);
        processor.drain();

        Alarm again = live(rule, f.pumpA());
        assertThat(again).as("withdrawn evidence was not consumed, so it may raise again").isNotNull();
        assertThat(again.getId()).isNotEqualTo(first);
    }

    // ── Closing ──────────────────────────────────────────────────────────────

    @Test
    void closingAnAlarmConsumesItsReadings() {
        Long rule = rule(f.tempField(), "WARNING", 2, 1, null);
        round(f.pumpA(), 1, 85);
        round(f.pumpA(), 2, 85);
        Long id = live(rule, f.pumpA()).getId();
        asAdmin();
        alarmService.close(id, f.supervisor().getId(), "دستور کار صادر شد");

        round(f.pumpA(), 3, 85);
        assertThat(live(rule, f.pumpA()))
                .as("one new warning is not two; the closed alarm's readings are spent")
                .isNull();

        round(f.pumpA(), 4, 85);
        Alarm second = live(rule, f.pumpA());
        assertThat(second).isNotNull();
        assertThat(second.getId()).isNotEqualTo(id);
        assertThat(second.getFirstReadingAt()).isEqualTo(day(3));
    }

    @Test
    void closingNeedsAReasonInWords() {
        Long rule = rule(f.tempField(), "DANGER", 1, 1, null);
        round(f.pumpA(), 1, 120);
        asAdmin();

        assertThatThrownBy(() -> alarmService.close(live(rule, f.pumpA()).getId(), f.supervisor().getId(), "  "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(live(rule, f.pumpA()).getState()).isEqualTo(AlarmState.OPEN);
    }

    @Test
    void switchingARuleOffClosesItsLiveAlarmsAndRaisesNoMore() {
        Long rule = rule(f.tempField(), "DANGER", 1, 1, null);
        round(f.pumpA(), 1, 120);
        Long id = live(rule, f.pumpA()).getId();

        switchOff(rule, List.of(f.tempField()), "DANGER", 1);

        Alarm alarm = alarmRepository.findById(id).orElseThrow();
        assertThat(alarm.getCloseReason()).isEqualTo(AlarmCloseReason.RULE_DISABLED);
        round(f.pumpA(), 2, 120);
        assertThat(live(rule, f.pumpA())).isNull();
    }

    // ── Rules ────────────────────────────────────────────────────────────────

    @Test
    void aRuleIsRefusedWhenItCouldNeverMeanAnything() {
        Long textField = saveField(f.classId(), "note", "یادداشت", "text", null);

        assertThatThrownBy(() -> rule(textField, "DANGER", 1, 1, null))
                .as("a text parameter has no severity").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ruleService.create(new RuleForm(null, "FIELDS", List.of(f.tempField()),
                "DANGER", 1, 1, null, "HIGH", "  ", true), f.supervisor().getId()))
                .as("an alarm with no defined response").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ruleService.create(new RuleForm(null, "FIELDS", List.of(),
                "DANGER", 1, 1, null, "HIGH", "اقدام", true), f.supervisor().getId()))
                .as("a specific rule with no parameter").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> rule(f.tempField(), "DANGER", 0, 1, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> rule(f.tempField(), "OK", 1, 1, null))
                .isInstanceOf(IllegalArgumentException.class);

        rule(f.tempField(), "DANGER", 1, 1, null);
        assertThatThrownBy(() -> rule(f.tempField(), "WARNING", 1, 1, null))
                .as("one rule per parameter").isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aRuleThatRaisedAnAlarmCannotBeDeletedOnlySwitchedOff() {
        Long unused = rule(f.stateField(), "DANGER", 1, 1, null);
        ruleService.delete(unused);
        ruleIds.remove(unused);

        Long used = rule(f.tempField(), "DANGER", 1, 1, null);
        round(f.pumpA(), 1, 120);
        assertThatThrownBy(() -> ruleService.delete(used)).isInstanceOf(IllegalStateException.class);
    }

    // ── Scope ────────────────────────────────────────────────────────────────

    @Test
    void anAlarmIsSeenByItsUnitsSupervisorsAndTheParentUnitsButNotAnotherUnits() {
        Long rule = rule(f.tempField(), "DANGER", 1, 1, null);
        round(f.pumpA(), 1, 120);
        Long id = live(rule, f.pumpA()).getId();

        as(f.supervisor(), "SUPERVISOR");
        assertThat(alarmService.countVisible(AlarmState.OPEN)).isGreaterThanOrEqualTo(1);
        assertThat(alarmService.requireVisible(id).getId()).isEqualTo(id);

        as(f.parentSupervisor(), "SUPERVISOR");
        assertThat(alarmService.requireVisible(id).getId()).isEqualTo(id);

        as(f.otherSupervisor(), "SUPERVISOR");
        assertThat(alarmService.findVisible(List.of(AlarmState.OPEN), null,
                org.springframework.data.domain.PageRequest.of(0, 200)).getContent())
                .extracting(Alarm::getId).doesNotContain(id);
        assertThat(alarmService.dashboardPanel().openCount()).isZero();
        assertThatThrownBy(() -> alarmService.acknowledge(id, f.otherSupervisor().getId(), null))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(alarmRepository.findById(id).orElseThrow().getState()).isEqualTo(AlarmState.OPEN);
    }

    // ── The real completion path ─────────────────────────────────────────────

    @Test
    @WithAppUser(roles = "ADMIN", authorities = {"GET:/log-sheets/{id}/fill", "POST:/log-sheets/{id}/complete"})
    void completingARoundOnTheWebQueuesItsEvaluation() {
        Long rule = rule(f.tempField(), "DANGER", 1, 1, null);
        long now = System.currentTimeMillis();
        LogSheet sheet = baseSheet(now);
        sheet.setStatus(LogSheetStatus.IN_PROGRESS);
        // WithAppUser's principal has id 1; the web completion requires the assignee.
        sheet.setAssigneeUserId(1L);
        sheet.setCompletedAt(null);
        sheet.setSubmittedAt(null);
        Long sheetId = sheetRepository.save(sheet).getId();
        sheetIds.add(sheetId);
        LogSheetEntry entry = newEntry(sheetId, f.pumpA(), null, now);
        Long entryId = entryRepository.save(entry).getId();

        logSheetService.completeFromWeb(sheetId, Map.of(String.valueOf(entryId), Map.of("temp", 120)));

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM alarm_evaluation_requests WHERE log_sheet_id = ?", Long.class, sheetId))
                .as("the completion wrote its request and nothing else about alarms")
                .isEqualTo(1L);
        assertThat(live(rule, f.pumpA())).as("nothing is evaluated inside the round's transaction").isNull();

        processor.drain();
        assertThat(live(rule, f.pumpA())).isNotNull();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM alarm_evaluation_requests WHERE log_sheet_id = ?", Long.class, sheetId))
                .isZero();
    }

    // ── The panel ────────────────────────────────────────────────────────────

    @Test
    void theDashboardShowsEachSupervisorTheCountsOfTheirOwnAlarmsAsLinksIntoTheFilteredQueue() throws Exception {
        Long rule = rule(f.tempField(), "DANGER", 1, 1, null);
        round(f.pumpA(), 1, 120);
        assertThat(live(rule, f.pumpA())).isNotNull();
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

        String html = mvc.perform(get("/").with(user(principal(f.supervisor(), "SUPERVISOR",
                        "GET:/", "GET:/alarms"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(tile(html, "/alarms?state=OPEN")).as("one undecided alarm in this unit").contains(">1<");
        assertThat(tile(html, "/alarms?state=ACKNOWLEDGED")).contains(">0<");
        assertThat(tile(html, "/alarms?state=LIVE&amp;severity=DANGER")).contains(">1<");
        assertThat(html).as("counts only — the list is on /alarms").doesNotContain(f.pumpACode());

        String otherHtml = mvc.perform(get("/").with(user(principal(f.otherSupervisor(), "SUPERVISOR",
                        "GET:/", "GET:/alarms"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(tile(otherHtml, "/alarms?state=OPEN")).as("another unit's alarm is not counted").contains(">0<");

        String noPermission = mvc.perform(get("/").with(user(principal(f.supervisor(), "SUPERVISOR", "GET:/"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(noPermission).doesNotContain("/alarms?state=OPEN");
    }

    /** The markup of one dashboard alarm tile: from its link to the end of the anchor. */
    private static String tile(String html, String href) {
        int start = html.indexOf("href=\"" + href + "\"");
        assertThat(start).as("a tile linking to %s", href).isGreaterThanOrEqualTo(0);
        return html.substring(start, html.indexOf("</a>", start));
    }

    // ── One rule, many parameters; one default for all (V18) ────────────────

    @Test
    void theDefaultRuleRaisesOnAnyParameterThatNoSpecificRuleLists() {
        Long fallback = defaultRule("DANGER", 1);

        round(f.pumpA(), 1, Map.of("temp", 120, "state", "LEAK"));

        Alarm temp = liveOn(f.pumpA(), f.tempField());
        Alarm state = liveOn(f.pumpA(), f.stateField());
        assertThat(temp).isNotNull();
        assertThat(state).isNotNull();
        assertThat(temp.getAlarmRuleId()).isEqualTo(fallback);
        assertThat(state.getAlarmRuleId()).isEqualTo(fallback);
        assertThat(temp.getId()).as("one alarm per parameter, even under one rule").isNotEqualTo(state.getId());
    }

    @Test
    void aSpecificRuleOverridesTheDefaultForExactlyItsParameters() {
        defaultRule("DANGER", 1);
        Long specific = rule(f.tempField(), "DANGER", 3, 1, null);

        round(f.pumpA(), 1, Map.of("temp", 120, "state", "LEAK"));

        assertThat(liveOn(f.pumpA(), f.tempField()))
                .as("temp follows its own rule — three dangers, not one").isNull();
        assertThat(liveOn(f.pumpA(), f.stateField())).as("state still follows the default").isNotNull();

        round(f.pumpA(), 2, 120);
        round(f.pumpA(), 3, 120);
        Alarm temp = liveOn(f.pumpA(), f.tempField());
        assertThat(temp).isNotNull();
        assertThat(temp.getAlarmRuleId()).isEqualTo(specific);
    }

    @Test
    void aSwitchedOffSpecificRuleSilencesItsParametersInsteadOfHandingThemToTheDefault() {
        defaultRule("DANGER", 1);
        Long specific = rule(f.tempField(), "DANGER", 1, 1, null);
        switchOff(specific, List.of(f.tempField()), "DANGER", 1);

        round(f.pumpA(), 1, 120);

        assertThat(liveOn(f.pumpA(), f.tempField())).isNull();
    }

    @Test
    void oneRuleCoversSeveralParametersEachWithItsOwnAlarm() {
        Long both = specificRule(List.of(f.tempField(), f.stateField()), "DANGER", 1, 1, null);

        round(f.pumpA(), 1, Map.of("temp", 120, "state", "LEAK"));

        assertThat(liveOn(f.pumpA(), f.tempField()).getAlarmRuleId()).isEqualTo(both);
        assertThat(liveOn(f.pumpA(), f.stateField()).getAlarmRuleId()).isEqualTo(both);
        assertThat(alarmCount(both)).isEqualTo(2);
    }

    /**
     * The rule's list is {@code alarm_rule_fields} rows written by difference. Asserted against the
     * table, not the object the service hands back: the object is what the service believes, the
     * table is what the evaluation will read.
     */
    @Test
    void editingARuleRewritesItsParametersByDifferenceAndKeepsOneSinceSwitchedOff() {
        Long id = rule(f.tempField(), "DANGER", 1, 1, null);

        // Keep temp, add state: a delete-everything-then-insert would meet temp's own primary key.
        ruleService.update(id, new RuleForm(null, null, List.of(f.tempField(), f.stateField()), "DANGER", 1, 1,
                null, "HIGH", "اعزام مکانیک", true), f.supervisor().getId());
        assertThat(linkedFields(id)).containsExactlyInAnyOrder(f.tempField(), f.stateField());

        // Drop temp, keep state.
        ruleService.update(id, new RuleForm(null, null, List.of(f.stateField()), "DANGER", 1, 1,
                null, "HIGH", "اعزام مکانیک", true), f.supervisor().getId());
        assertThat(linkedFields(id)).containsExactly(f.stateField());
        assertThat(ruleService.ruleByField()).doesNotContainKey(f.tempField());

        // A parameter switched off after it was listed does not make the rule uneditable...
        jdbc.update("UPDATE field_definitions SET deleted = true WHERE id = ?", f.stateField());
        ruleService.update(id, new RuleForm("پمپ — وضعیت", null, List.of(f.stateField()), "WARNING", 2, 1,
                null, "HIGH", "اعزام مکانیک", true), f.supervisor().getId());
        assertThat(ruleService.all().stream().filter(r -> r.getId().equals(id)).findFirst().orElseThrow())
                .satisfies(r -> {
                    assertThat(r.getTriggerSeverity()).isEqualTo(FieldValidationSeverity.WARNING);
                    assertThat(r.getFieldDefinitionIds()).containsExactly(f.stateField());
                });
        // ...but one cannot be ADDED to a rule.
        Long off = saveField(f.classId(), "vib", "ارتعاش", "number", null);
        jdbc.update("UPDATE field_definitions SET deleted = true WHERE id = ?", off);
        assertThatThrownBy(() -> ruleService.update(id, new RuleForm(null, null, List.of(f.stateField(), off),
                "WARNING", 2, 1, null, "HIGH", "اعزام مکانیک", true), f.supervisor().getId()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("ارتعاش");
        assertThat(linkedFields(id)).as("the refused save changed nothing").containsExactly(f.stateField());

        // Deleting the rule takes its rows with it.
        ruleService.delete(id);
        ruleIds.remove(id);
        assertThat(linkedFields(id)).isEmpty();
    }

    /**
     * Putting a parameter into a rule, or taking one out, is an administrator's decision about what
     * raises an alarm, and lands in audit_log. {@code RepositoryAuditAspect} watches {@code save} and
     * {@code delete} only — a {@code saveAll} would have written the rows and no trace of them.
     */
    @Test
    void puttingAParameterIntoARuleOrTakingItOutIsAudited() throws InterruptedException {
        Long id = rule(f.tempField(), "DANGER", 1, 1, null);
        ruleService.update(id, new RuleForm(null, null, List.of(f.stateField()), "DANGER", 1, 1,
                null, "HIGH", "اعزام مکانیک", true), f.supervisor().getId());

        assertThat(awaitLinkAudit(id, "CREATE", 2)).as("temp, then state, put in").isEqualTo(2);
        assertThat(awaitLinkAudit(id, "DELETE", 1)).as("temp taken out").isEqualTo(1);
    }

    @Test
    void aRuleReadStraightFromItsRepositoryHasNoParametersAndTheServiceAttachesThem() {
        Long id = specificRule(List.of(f.tempField(), f.stateField()), "DANGER", 1, 1, null);

        // Documented on AlarmRuleRepository: the list is not mapped. This pins that a caller who
        // forgets cannot be silently right in one place and wrong in another.
        assertThat(ruleRepository.findById(id).orElseThrow().getFieldDefinitionIds()).isEmpty();
        assertThat(ruleService.all().stream().filter(r -> r.getId().equals(id)).findFirst().orElseThrow()
                .getFieldDefinitionIds()).containsExactlyInAnyOrder(f.tempField(), f.stateField());
    }

    @Test
    void aParameterBelongsToOneRuleOnlyAndThereIsOneDefault() {
        Long first = rule(f.tempField(), "DANGER", 1, 1, null);
        String firstName = ruleService.all().stream().filter(r -> r.getId().equals(first)).findFirst()
                .orElseThrow().getName();

        assertThatThrownBy(() -> specificRule(List.of(f.tempField(), f.stateField()), "DANGER", 1, 1, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(firstName);

        // A specific rule may not be left with nothing...
        assertThatThrownBy(() -> ruleService.update(first, new RuleForm(null, null, List.of(), "DANGER", 1, 1,
                null, "HIGH", "اعزام مکانیک", true), f.supervisor().getId()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(ruleService.all().stream().filter(r -> r.getId().equals(first)).findFirst()
                .orElseThrow().getFieldDefinitionIds()).as("the refused update changed nothing").contains(f.tempField());

        // ...but once temp is taken out of the first rule, another rule may have it.
        ruleService.update(first, new RuleForm(null, null, List.of(f.stateField()), "DANGER", 1, 1, null,
                "HIGH", "اعزام مکانیک", true), f.supervisor().getId());
        Long second = rule(f.tempField(), "WARNING", 2, 1, null);
        assertThat(ruleService.ruleByField().get(f.tempField()).getId()).isEqualTo(second);
        assertThat(ruleService.ruleByField().get(f.stateField()).getId()).isEqualTo(first);

        defaultRule("DANGER", 3);
        assertThatThrownBy(() -> defaultRule("WARNING", 1))
                .as("one default rule").isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anAlarmKeepsItsIdentityWhenItsParameterMovesUnderAnotherRule() {
        defaultRule("DANGER", 1);
        round(f.pumpA(), 1, 120);
        Alarm before = liveOn(f.pumpA(), f.tempField());

        rule(f.tempField(), "WARNING", 3, 1, null);
        round(f.pumpA(), 2, 120);

        Alarm after = liveOn(f.pumpA(), f.tempField());
        assertThat(after.getId()).as("the same condition, the same alarm").isEqualTo(before.getId());
        assertThat(after.getOccurrenceCount()).isEqualTo(2);
        assertThat(after.getConsecutiveReadings()).as("frozen at raise").isEqualTo(1);
    }

    @Test
    void voidingANormalReadingThatSplitTwoRunsCanRaiseTheAlarm() {
        Long rule = rule(f.tempField(), "WARNING", 3, 1, null);
        round(f.pumpA(), 1, 85);
        round(f.pumpA(), 2, 85);
        Long normal = round(f.pumpA(), 3, 50);
        round(f.pumpA(), 4, 85);
        assertThat(live(rule, f.pumpA())).as("runs of two and one").isNull();

        assignmentService.voidSubmitted(normal, f.supervisor().getId(), ActionSource.WEB);
        processor.drain();

        Alarm alarm = live(rule, f.pumpA());
        assertThat(alarm).as("with day 3 withdrawn, days 1, 2 and 4 are three in a row").isNotNull();
        assertThat(alarm.getOccurrenceCount()).isEqualTo(3);
    }

    @Test
    void theRulesPageSavesARuleWithSeveralParametersAndShowsEachParametersClass() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        AppUserDetails admin = principal(f.supervisor(), "ADMIN", "GET:/alarm-rules", "POST:/alarm-rules",
                "POST:/alarm-rules/{id}", "POST:/alarm-rules/{id}/delete");
        String className = classRepository.findById(f.classId()).orElseThrow().getName();

        String page = mvc.perform(get("/alarm-rules").with(user(admin)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(page).contains("قاعده عمومی").contains(className).contains("data-picker-search")
                .contains("value=\"" + f.tempField() + "\"").contains("value=\"" + f.stateField() + "\"");

        mvc.perform(post("/alarm-rules").with(user(admin)).with(csrf())
                        .param("scope", "FIELDS").param("name", "پمپ‌ها")
                        .param("fieldDefinitionIds", String.valueOf(f.tempField()), String.valueOf(f.stateField()))
                        .param("triggerSeverity", "DANGER").param("consecutiveReadings", "3")
                        .param("clearAfterReadings", "1").param("priority", "HIGH")
                        .param("expectedAction", "اعزام مکانیک"))
                .andExpect(status().is3xxRedirection());

        var saved = ruleService.all().stream().filter(r -> "پمپ‌ها".equals(r.getName())).findFirst().orElseThrow();
        ruleIds.add(saved.getId());
        assertThat(saved.getFieldDefinitionIds()).containsExactlyInAnyOrder(f.tempField(), f.stateField());
        assertThat(saved.getConsecutiveReadings()).isEqualTo(3);

        String after = mvc.perform(get("/alarm-rules").with(user(admin)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(after).as("each parameter chip names its class").contains(className + " — دما");
    }

    @Test
    void theQueueAndTheDetailRenderAndAcknowledgingOverHttpWorks() throws Exception {
        Long rule = rule(f.tempField(), "DANGER", 1, 1, null);
        round(f.pumpA(), 1, 120);
        Long id = live(rule, f.pumpA()).getId();
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        AppUserDetails supervisor = principal(f.supervisor(), "SUPERVISOR",
                "GET:/alarms", "POST:/alarms/{id}/acknowledge", "POST:/alarms/{id}/close");

        assertThat(mvc.perform(get("/alarms").with(user(supervisor)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
                .contains(f.pumpACode()).contains("تعیین تکلیف نشده");
        assertThat(mvc.perform(get("/alarms/" + id).with(user(supervisor)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
                .contains("اعزام مکانیک").contains("ایجاد آلارم");

        mvc.perform(post("/alarms/" + id + "/acknowledge").with(user(supervisor)).with(csrf())
                        .param("comment", "مکانیک اعزام شد").param("returnTo", "/alarms"))
                .andExpect(status().is3xxRedirection());
        assertThat(alarmRepository.findById(id).orElseThrow().getState()).isEqualTo(AlarmState.ACKNOWLEDGED);

        // An off-site returnTo is ignored rather than followed.
        mvc.perform(post("/alarms/" + id + "/close").with(user(supervisor)).with(csrf())
                        .param("comment", "تعمیر انجام شد").param("returnTo", "https://evil.example/"))
                .andExpect(status().is3xxRedirection())
                .andExpect(result -> assertThat(result.getResponse().getRedirectedUrl()).isEqualTo("/alarms/" + id));
        assertThat(alarmRepository.findById(id).orElseThrow().getCloseReason()).isEqualTo(AlarmCloseReason.RESOLVED);
    }

    @Test
    void anAlarmIsRefusedToAPersonWithoutThePermission() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

        mvc.perform(get("/alarms").with(user(principal(f.supervisor(), "SUPERVISOR", "GET:/"))))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isIn(302, 403));
        mvc.perform(get("/alarm-rules").with(user(principal(f.supervisor(), "SUPERVISOR", "GET:/alarms"))))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isIn(302, 403));
    }

    // ── Plumbing ─────────────────────────────────────────────────────────────

    private long day(int n) {
        return t0 + n * DAY;
    }

    private Long rule(Long fieldId, String trigger, int consecutive, int clearAfter, Integer windowDays) {
        return specificRule(List.of(fieldId), trigger, consecutive, clearAfter, windowDays);
    }

    private Long specificRule(List<Long> fieldIds, String trigger, int consecutive, int clearAfter,
                              Integer windowDays) {
        Long id = ruleService.create(new RuleForm(null, "FIELDS", fieldIds, trigger, consecutive, clearAfter,
                windowDays, "HIGH", "اعزام مکانیک", true), f.supervisor().getId()).getId();
        ruleIds.add(id);
        return id;
    }

    private Long defaultRule(String trigger, int consecutive) {
        Long id = ruleService.create(new RuleForm(null, "ALL_FIELDS", null, trigger, consecutive, 1,
                null, "MEDIUM", "بررسی پارامتر", true), f.supervisor().getId()).getId();
        ruleIds.add(id);
        return id;
    }

    private void switchOff(Long ruleId, List<Long> fieldIds, String trigger, int consecutive) {
        ruleService.update(ruleId, new RuleForm(null, null, fieldIds, trigger, consecutive, 1, null,
                "HIGH", "اعزام مکانیک", false), f.supervisor().getId());
    }

    /** A finished round for one asset with one temperature value, queued and drained. */
    private Long round(Long assetId, int dayNumber, Object temp) {
        Map<String, Object> values = new HashMap<>();
        if (temp != null) {
            values.put("temp", temp);
        }
        return round(assetId, dayNumber, values);
    }

    private Long round(Long assetId, int dayNumber, Map<String, Object> values) {
        long completedAt = day(dayNumber);
        LogSheet sheet = baseSheet(completedAt);
        Long sheetId = sheetRepository.save(sheet).getId();
        sheetIds.add(sheetId);
        entryRepository.save(newEntry(sheetId, assetId, values.isEmpty() ? null : new LinkedHashMap<>(values),
                completedAt));
        alarmQueue.request(sheetId, "COMPLETED");
        processor.drain();
        return sheetId;
    }

    private LogSheet baseSheet(long completedAt) {
        LogSheet sheet = new LogSheet();
        sheet.setTemplateName("راند آلارم");
        sheet.setScopeSummary("alarm test");
        sheet.setOperationalUnitId(f.unit());
        sheet.setStatus(LogSheetStatus.SUBMITTED);
        sheet.setOrigin(GenerationMode.MANUAL);
        sheet.setFieldDefinitionsSnapshot(f.snapshot());
        sheet.setCompletedAt(completedAt);
        sheet.setSubmittedAt(completedAt + 1_000L);
        sheet.setSyncStatus("SYNCED");
        sheet.setCreatedAt(completedAt - DAY / 2);
        sheet.setUpdatedAt(completedAt);
        return sheet;
    }

    /** Stamped by the evaluator, exactly as both completion paths stamp it (gotcha #186). */
    private LogSheetEntry newEntry(Long sheetId, Long assetId, Map<String, Object> values, long at) {
        LogSheetEntry entry = new LogSheetEntry();
        entry.setLogSheetId(sheetId);
        entry.setAssetId(assetId);
        entry.setAssetName(assetId.equals(f.pumpA()) ? "پمپ آ" : "پمپ ب");
        entry.setClassId(f.classId());
        entry.setFormData(values);
        entry.setCreatedAt(at - 1_000L);
        entry.setUpdatedAt(at - 500L);
        EntrySeverityEvaluator.apply(entry, FieldDefinitionSnapshot.toFieldDefinitions(f.snapshot()));
        return entry;
    }

    /** The live alarm this rule raised on this asset (one rule may now cover several parameters). */
    private Alarm live(Long ruleId, Long assetId) {
        List<Alarm> mine = alarmRepository.findLiveForAssets(List.of(assetId)).stream()
                .filter(a -> a.getAlarmRuleId().equals(ruleId)).toList();
        assertThat(mine).as("at most one live alarm per (rule, asset) in these single-parameter tests")
                .hasSizeLessThanOrEqualTo(1);
        return mine.isEmpty() ? null : mine.get(0);
    }

    private Alarm liveOn(Long assetId, Long fieldId) {
        return alarmRepository.findLive(assetId, fieldId).orElse(null);
    }

    private long alarmCount(Long ruleId) {
        return jdbc.queryForObject("SELECT count(*) FROM alarms WHERE alarm_rule_id = ?", Long.class, ruleId);
    }

    private List<AlarmEventType> events(Alarm alarm) {
        return eventRepository.findByAlarmIdOrderByOccurredAtAscIdAsc(alarm.getId()).stream()
                .map(AlarmEvent::getEventType).toList();
    }

    private void asAdmin() {
        as(f.supervisor(), "ADMIN");
    }

    private void as(User user, String role) {
        AppUserDetails principal = principal(user, role);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    private static AppUserDetails principal(User user, String role, String... authorities) {
        return TestPrincipals.of(user, Set.of(role), List.of(authorities));
    }

    private Fixture seed() {
        long now = System.currentTimeMillis();
        String suffix = now + UUID.randomUUID().toString().substring(0, 6);

        OperationalUnit parent = saveUnit("ALP-" + suffix, "واحد مادر", null);
        OperationalUnit unit = saveUnit("ALU-" + suffix, "واحد آلارم", parent.getId());
        OperationalUnit other = saveUnit("ALO-" + suffix, "واحد دیگر", null);

        User supervisor = saveUser("al-sup-" + suffix, "سرپرست واحد", "0912000" + suffix.substring(suffix.length() - 4));
        User parentSupervisor = saveUser("al-psup-" + suffix, "سرپرست مادر", null);
        User otherSupervisor = saveUser("al-osup-" + suffix, "سرپرست دیگر", null);
        jdbc.update("INSERT INTO unit_supervisors (unit_id, user_id) VALUES (?, ?)", unit.getId(), supervisor.getId());
        jdbc.update("INSERT INTO unit_supervisors (unit_id, user_id) VALUES (?, ?)", parent.getId(), parentSupervisor.getId());
        jdbc.update("INSERT INTO unit_supervisors (unit_id, user_id) VALUES (?, ?)", other.getId(), otherSupervisor.getId());

        Location location = new Location();
        location.setCode("ALLOC-" + suffix);
        location.setName("Alarm Hall");
        location.setCreatedAt(now);
        location.setUpdatedAt(now);
        Long locationId = locationRepository.save(location).getId();
        Long subA = saveSubFunction("ALSA-" + suffix, locationId, now);
        Long subB = saveSubFunction("ALSB-" + suffix, locationId, now);

        AssetClass assetClass = new AssetClass();
        assetClass.setName("پمپ آلارم " + suffix);
        assetClass.setCreatedAt(now);
        assetClass.setUpdatedAt(now);
        Long classId = classRepository.save(assetClass).getId();

        Map<String, Object> warning = new HashMap<>();
        warning.put(FieldValidationSupport.KEY_MIN, 0d);
        warning.put(FieldValidationSupport.KEY_MAX, 80d);
        Map<String, Object> danger = new HashMap<>();
        danger.put(FieldValidationSupport.KEY_MIN, 0d);
        danger.put(FieldValidationSupport.KEY_MAX, 100d);
        Map<String, Object> tempValidation = new HashMap<>();
        tempValidation.put(FieldValidationSupport.KEY_WARNING, warning);
        tempValidation.put(FieldValidationSupport.KEY_DANGER, danger);
        Long tempField = saveField(classId, "temp", "دما", "number", tempValidation);

        Map<String, Object> stateValidation = new HashMap<>();
        stateValidation.put(FieldValidationSupport.KEY_OPTIONS, List.of("OK", "LEAK"));
        stateValidation.put(FieldValidationSupport.KEY_DANGER_OPTIONS, List.of("LEAK"));
        Long stateField = saveField(classId, "state", "وضعیت", "select", stateValidation);

        String codeA = "ALPA-" + suffix;
        Long pumpA = saveAsset(codeA, "پمپ آ", classId, subA, now);
        Long pumpB = saveAsset("ALPB-" + suffix, "پمپ ب", classId, subB, now);

        List<FieldDefinitionSnapshot> snapshot = fieldDefinitionsService.captureSnapshot(classId);

        return new Fixture(parent.getId(), unit.getId(), other.getId(), classId, tempField, stateField,
                pumpA, pumpB, codeA, supervisor, parentSupervisor, otherSupervisor, locationId,
                List.of(subA, subB), snapshot);
    }

    private OperationalUnit saveUnit(String code, String name, Long parentId) {
        long now = System.currentTimeMillis();
        OperationalUnit unit = new OperationalUnit();
        unit.setCode(code);
        unit.setName(name);
        unit.setParentId(parentId);
        unit.setCreatedAt(now);
        unit.setUpdatedAt(now);
        return unitRepository.save(unit);
    }

    private User saveUser(String username, String fullName, String phone) {
        long now = System.currentTimeMillis();
        User user = new User();
        user.setUsername(username);
        user.setFullName(fullName);
        user.setPersonnelCode("PC-" + username);
        user.setPhoneNumber(phone);
        user.setPasswordHash(passwordEncoder.encode("not-used"));
        user.setActive(true);
        user.setCreatedAt(now);
        user.setUpdatedAt(now);
        return userRepository.save(user);
    }

    private Long saveSubFunction(String code, Long locationId, long now) {
        SubFunction subFunction = new SubFunction();
        subFunction.setCode(code);
        subFunction.setName(code);
        subFunction.setTag("NFC-" + code);
        subFunction.setCreatedAt(now);
        subFunction.setUpdatedAt(now);
        hierarchyService.applySubFunctionParent(subFunction, AssetHierarchyService.SCOPE_LOCATION, locationId);
        return hierarchyService.saveSubFunction(subFunction).getId();
    }

    /** Audit rows are written on {@code auditExecutor}; waits up to five seconds for {@code expected}. */
    private long awaitLinkAudit(Long ruleId, String action, long expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        long found;
        do {
            found = jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE entity_type = 'alarm_rule_fields' "
                    + "AND action = ? AND entity_id LIKE ?", Long.class, action, "alarmRuleId=" + ruleId + ",%");
            if (found >= expected) {
                return found;
            }
            Thread.sleep(50);
        } while (System.currentTimeMillis() < deadline);
        return found;
    }

    /** What the table says a rule lists — what the evaluation will read. */
    private List<Long> linkedFields(Long ruleId) {
        return jdbc.queryForList("SELECT field_definition_id FROM alarm_rule_fields WHERE alarm_rule_id = ? "
                + "ORDER BY field_definition_id", Long.class, ruleId);
    }

    private Long saveField(Long classId, String key, String label, String dataType, Map<String, Object> validation) {
        long now = System.currentTimeMillis();
        FieldDefinition field = new FieldDefinition();
        field.setClassId(classId);
        field.setKey(key);
        field.setLabel(label);
        field.setDataType(dataType);
        field.setRequired(false);
        field.setOrder(1);
        field.setVersion(1);
        field.setValidation(validation);
        field.setCreatedAt(now);
        field.setUpdatedAt(now);
        return fieldRepository.save(field).getId();
    }

    private Long saveAsset(String code, String name, Long classId, Long subFunctionId, long now) {
        AssetEntry asset = new AssetEntry();
        asset.setAssetCode(code);
        asset.setAssetName(name);
        asset.setClassId(classId);
        asset.setSubFunctionId(subFunctionId);
        asset.setNfcTagId("NFC-" + code);
        asset.setCreatedAt(now);
        asset.setUpdatedAt(now);
        return assetRepository.save(asset).getId();
    }
}
