package com.hnp.backendofflinefirst.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hnp.backendofflinefirst.domain.AlarmWebhookState;
import com.hnp.backendofflinefirst.domain.EntrySeverityEvaluator;
import com.hnp.backendofflinefirst.domain.FieldDefinitionSnapshot;
import com.hnp.backendofflinefirst.domain.FieldValidationSupport;
import com.hnp.backendofflinefirst.domain.GenerationMode;
import com.hnp.backendofflinefirst.domain.LogSheetStatus;
import com.hnp.backendofflinefirst.entity.Alarm;
import com.hnp.backendofflinefirst.entity.AlarmWebhookDelivery;
import com.hnp.backendofflinefirst.entity.AssetClass;
import com.hnp.backendofflinefirst.entity.AssetEntry;
import com.hnp.backendofflinefirst.entity.FieldDefinition;
import com.hnp.backendofflinefirst.entity.Location;
import com.hnp.backendofflinefirst.entity.LogSheet;
import com.hnp.backendofflinefirst.entity.LogSheetEntry;
import com.hnp.backendofflinefirst.entity.OperationalUnit;
import com.hnp.backendofflinefirst.entity.SubFunction;
import com.hnp.backendofflinefirst.entity.User;
import com.hnp.backendofflinefirst.repository.AlarmRepository;
import com.hnp.backendofflinefirst.repository.AlarmWebhookDeliveryRepository;
import com.hnp.backendofflinefirst.repository.AssetClassRepository;
import com.hnp.backendofflinefirst.repository.AssetEntryRepository;
import com.hnp.backendofflinefirst.repository.FieldDefinitionRepository;
import com.hnp.backendofflinefirst.repository.LocationRepository;
import com.hnp.backendofflinefirst.repository.LogSheetEntryRepository;
import com.hnp.backendofflinefirst.repository.LogSheetRepository;
import com.hnp.backendofflinefirst.repository.OperationalUnitRepository;
import com.hnp.backendofflinefirst.repository.UserRepository;
import com.hnp.backendofflinefirst.service.AssetHierarchyService;
import com.hnp.backendofflinefirst.service.LogSheetFieldDefinitionsService;
import com.hnp.backendofflinefirst.service.alarm.AlarmEvaluationProcessor;
import com.hnp.backendofflinefirst.service.alarm.AlarmQueue;
import com.hnp.backendofflinefirst.service.alarm.AlarmRuleService;
import com.hnp.backendofflinefirst.service.alarm.AlarmRuleService.RuleForm;
import com.hnp.backendofflinefirst.service.alarm.AlarmWebhookDispatcher;
import com.hnp.backendofflinefirst.support.AbstractPostgresIntegrationTest;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The alarm webhook against a real HTTP receiver: a JDK {@code HttpServer} on a loopback port,
 * started before the Spring context so its address can be the configured URL.
 *
 * <p>Its own Spring context (the webhook is configuration, read once at startup), with the
 * after-commit evaluation switched ON — so one test here proves the whole chain runs by itself:
 * a round commits, its alarm is evaluated on a background thread, and the event reaches the
 * receiver, with no test code in between.
 */
class AlarmWebhookIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String KEY = "test-webhook-key-123";
    private static final long DAY = 86_400_000L;
    private static final ObjectMapper JSON = new ObjectMapper();

    private record Received(String method, String apiKey, String eventId, String eventType,
                            String contentType, String body) {}

    private static final List<Received> RECEIVED = new CopyOnWriteArrayList<>();
    private static volatile int nextStatus = 200;
    private static volatile long delayMs = 0;
    private static final HttpServer SERVER;

    static {
        try {
            SERVER = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        SERVER.createContext("/hook", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            RECEIVED.add(new Received(exchange.getRequestMethod(),
                    exchange.getRequestHeaders().getFirst("X-API-Key"),
                    exchange.getRequestHeaders().getFirst("X-Event-Id"),
                    exchange.getRequestHeaders().getFirst("X-Event-Type"),
                    exchange.getRequestHeaders().getFirst("Content-Type"),
                    new String(body, StandardCharsets.UTF_8)));
            if (delayMs > 0) {
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            int status = nextStatus;
            byte[] response = (status >= 300 ? "receiver says no" : "ok").getBytes(StandardCharsets.UTF_8);
            try {
                exchange.sendResponseHeaders(status, response.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(response);
                }
            } catch (IOException ignored) {
                // The client gave up (the timeout test); nothing to answer.
            } finally {
                exchange.close();
            }
        });
        SERVER.setExecutor(Executors.newCachedThreadPool());
        SERVER.start();
    }

    @DynamicPropertySource
    static void webhook(DynamicPropertyRegistry registry) {
        registry.add("app.alarm.webhook.url",
                () -> "http://127.0.0.1:" + SERVER.getAddress().getPort() + "/hook");
        registry.add("app.alarm.webhook.api-key", () -> KEY);
        registry.add("app.alarm.webhook.max-attempts", () -> "2");
        registry.add("app.alarm.webhook.request-timeout-ms", () -> "1500");
        registry.add("app.alarm.evaluation.after-commit", () -> "true");
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired PlatformTransactionManager transactionManager;
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
    @Autowired AlarmRuleService ruleService;
    @Autowired AlarmQueue alarmQueue;
    @Autowired AlarmEvaluationProcessor processor;
    @Autowired AlarmWebhookDispatcher dispatcher;
    @Autowired AlarmRepository alarmRepository;
    @Autowired AlarmWebhookDeliveryRepository deliveryRepository;

    private final List<Long> sheetIds = new ArrayList<>();
    private Long parentUnit;
    private Long unit;
    private Long classId;
    private Long tempField;
    private Long asset;
    private String assetCode;
    private Long locationId;
    private Long subFunctionId;
    private User supervisor;
    private User parentSupervisor;
    private List<FieldDefinitionSnapshot> snapshot;
    private long t0;

    @BeforeEach
    void setUp() {
        RECEIVED.clear();
        nextStatus = 200;
        delayMs = 0;
        t0 = System.currentTimeMillis() - 30 * DAY;
        seed();
    }

    @AfterEach
    void tearDown() {
        // Let any background pass finish before rows disappear under it.
        processor.drain();
        dispatcher.drain();
        jdbc.update("DELETE FROM alarms WHERE asset_id = ?", asset);
        jdbc.query("SELECT alarm_rule_id FROM alarm_rule_fields WHERE field_definition_id = ?", rs -> {
            long ruleId = rs.getLong(1);
            jdbc.update("DELETE FROM alarms WHERE alarm_rule_id = ?", ruleId);
            jdbc.update("DELETE FROM alarm_rules WHERE id = ?", ruleId);
        }, tempField);
        for (Long sheetId : sheetIds) {
            jdbc.update("DELETE FROM alarm_evaluation_requests WHERE log_sheet_id = ?", sheetId);
            jdbc.update("DELETE FROM log_sheet_entries WHERE log_sheet_id = ?", sheetId);
            jdbc.update("DELETE FROM log_sheets WHERE id = ?", sheetId);
        }
        jdbc.update("DELETE FROM asset_entries WHERE id = ?", asset);
        jdbc.update("DELETE FROM field_definitions WHERE class_id = ?", classId);
        jdbc.update("DELETE FROM asset_classes WHERE id = ?", classId);
        jdbc.update("DELETE FROM sub_functions WHERE id = ?", subFunctionId);
        jdbc.update("DELETE FROM locations WHERE id = ?", locationId);
        for (User u : List.of(supervisor, parentSupervisor)) {
            jdbc.update("DELETE FROM unit_supervisors WHERE user_id = ?", u.getId());
            jdbc.update("DELETE FROM users WHERE id = ?", u.getId());
        }
        jdbc.update("DELETE FROM operational_units WHERE id = ?", unit);
        jdbc.update("DELETE FROM operational_units WHERE id = ?", parentUnit);
        delayMs = 0;
        nextStatus = 200;
    }

    @Test
    void aRaisedAlarmIsPostedWithTheKeyAndEverythingTheReceiverNeeds() throws Exception {
        rule("DANGER");
        Long sheet = round(1, 120);
        Alarm alarm = liveAlarm();
        AlarmWebhookDelivery queued = onlyDelivery(alarm);
        assertThat(queued.getState()).isEqualTo(AlarmWebhookState.PENDING);

        dispatcher.drain();

        assertThat(RECEIVED).hasSize(1);
        Received request = RECEIVED.get(0);
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.apiKey()).isEqualTo(KEY);
        assertThat(request.eventType()).isEqualTo("ALARM_RAISED");
        assertThat(request.eventId()).isEqualTo(queued.getEventId());
        assertThat(request.contentType()).startsWith("application/json");

        JsonNode body = JSON.readTree(request.body());
        assertThat(body.get("eventId").asText()).isEqualTo(queued.getEventId());
        assertThat(body.get("alarm").get("id").asLong()).isEqualTo(alarm.getId());
        assertThat(body.get("alarm").get("severity").asText()).isEqualTo("DANGER");
        assertThat(body.get("alarm").get("expectedAction").asText()).isEqualTo("اعزام مکانیک");
        assertThat(body.get("alarm").get("panelPath").asText()).isEqualTo("/alarms/" + alarm.getId());
        assertThat(body.get("alarm").get("rule").get("name").asText()).isEqualTo("دمای پمپ");
        assertThat(body.get("alarm").get("rule").get("scope").asText()).isEqualTo("FIELDS");
        assertThat(body.get("field").get("key").asText()).isEqualTo("temp");
        assertThat(body.get("asset").get("code").asText()).isEqualTo(assetCode);
        assertThat(body.get("logSheet").get("id").asLong()).isEqualTo(sheet);
        assertThat(body.get("readings").get(0).get("value").asText()).isEqualTo("120");

        JsonNode units = body.get("operationalUnits");
        JsonNode responsible = unitWithRelation(units, "RESPONSIBLE");
        assertThat(responsible.get("id").asLong()).isEqualTo(unit);
        assertThat(responsible.get("supervisors").get(0).get("personnelCode").asText())
                .isEqualTo(supervisor.getPersonnelCode());
        assertThat(responsible.get("supervisors").get(0).get("phoneNumber").asText())
                .isEqualTo(supervisor.getPhoneNumber());
        assertThat(responsible.get("supervisors").get(0).has("id"))
                .as("no internal user id leaves the system").isFalse();
        JsonNode parent = unitWithRelation(units, "PARENT_OF_RESPONSIBLE");
        assertThat(parent.get("id").asLong()).isEqualTo(parentUnit);
        assertThat(parent.get("supervisors").get(0).get("username").asText())
                .isEqualTo(parentSupervisor.getUsername());

        AlarmWebhookDelivery delivered = deliveryRepository.findById(queued.getId()).orElseThrow();
        assertThat(delivered.getState()).isEqualTo(AlarmWebhookState.DELIVERED);
        assertThat(delivered.getAttempts()).isEqualTo(1);
        assertThat(delivered.getDeliveredAt()).isNotNull();
        assertThat(delivered.getPayload()).doesNotContain(KEY);
    }

    @Test
    void aReceiverErrorIsRetriedThenLeftFailedAndCanBeResent() {
        rule("DANGER");
        round(1, 120);
        Long id = onlyDelivery(liveAlarm()).getId();
        nextStatus = 500;

        dispatcher.drain();
        AlarmWebhookDelivery first = deliveryRepository.findById(id).orElseThrow();
        assertThat(first.getState()).isEqualTo(AlarmWebhookState.PENDING);
        assertThat(first.getAttempts()).isEqualTo(1);
        assertThat(first.getLastStatusCode()).isEqualTo(500);
        assertThat(first.getLastError()).contains("HTTP 500").contains("receiver says no");
        assertThat(first.getNextAttemptAt()).isGreaterThan(System.currentTimeMillis());

        jdbc.update("UPDATE alarm_webhook_deliveries SET next_attempt_at = 0 WHERE id = ?", id);
        dispatcher.drain();
        AlarmWebhookDelivery gaveUp = deliveryRepository.findById(id).orElseThrow();
        assertThat(gaveUp.getState()).as("max-attempts is 2 in this context").isEqualTo(AlarmWebhookState.FAILED);
        assertThat(gaveUp.getAttempts()).isEqualTo(2);

        jdbc.update("UPDATE alarm_webhook_deliveries SET next_attempt_at = 0 WHERE id = ?", id);
        dispatcher.drain();
        assertThat(RECEIVED).as("a FAILED delivery is not retried on its own").hasSize(2);

        nextStatus = 200;
        dispatcher.retry(id);
        dispatcher.drain();
        assertThat(deliveryRepository.findById(id).orElseThrow().getState()).isEqualTo(AlarmWebhookState.DELIVERED);
    }

    @Test
    void aHungReceiverTimesOutInsteadOfHoldingTheSender() {
        rule("DANGER");
        round(1, 120);
        Long id = onlyDelivery(liveAlarm()).getId();
        delayMs = 4_000;

        long started = System.currentTimeMillis();
        dispatcher.drain();
        long took = System.currentTimeMillis() - started;

        assertThat(took).as("request-timeout-ms is 1500").isLessThan(3_500);
        AlarmWebhookDelivery row = deliveryRepository.findById(id).orElseThrow();
        assertThat(row.getState()).isEqualTo(AlarmWebhookState.PENDING);
        assertThat(row.getLastError()).containsIgnoringCase("timeout");
    }

    /**
     * Every {@code @Scheduled} method in this application shares ONE thread (docs/jobs.md §1). A
     * scheduled webhook pass that did the POSTs itself would hold log-sheet generation and expiry
     * for as long as a dead receiver took to time out — so the schedule may only hand the work to
     * the webhook's own thread, and must return at once.
     */
    @Test
    void theScheduledPassNeverHoldsTheSharedSchedulerThread() throws Exception {
        rule("DANGER");
        round(1, 120);
        Long id = onlyDelivery(liveAlarm()).getId();
        delayMs = 4_000;

        long started = System.currentTimeMillis();
        dispatcher.scheduledNudge();
        long took = System.currentTimeMillis() - started;

        assertThat(took).as("the scheduler thread is handed back immediately").isLessThan(500);
        // The work still happens, on the webhook thread.
        long deadline = System.currentTimeMillis() + 10_000;
        while (RECEIVED.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertThat(RECEIVED).hasSize(1);
        delayMs = 0;
        dispatcher.drain(); // waits for the background pass to finish before cleanup
        assertThat(deliveryRepository.findById(id)).isPresent();
    }

    @Test
    void anEscalationIsPostedAsItsOwnEvent() throws Exception {
        rule("WARNING");
        round(1, 85);
        dispatcher.drain();
        round(2, 120);
        dispatcher.drain();

        assertThat(RECEIVED).extracting(Received::eventType)
                .containsExactly("ALARM_RAISED", "ALARM_ESCALATED");
        JsonNode escalated = JSON.readTree(RECEIVED.get(1).body());
        assertThat(escalated.get("alarm").get("severity").asText()).isEqualTo("DANGER");
        assertThat(escalated.get("alarm").get("occurrenceCount").asInt()).isEqualTo(2);
        assertThat(RECEIVED.get(0).eventId()).isNotEqualTo(RECEIVED.get(1).eventId());
    }

    @Test
    void theWholeChainRunsByItselfOnceTheRoundCommits() throws Exception {
        rule("DANGER");
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        Long sheetId = tx.execute(status -> {
            Long id = saveRound(1, 120);
            // Exactly what a completion path does inside its own transaction.
            alarmQueue.request(id, "COMPLETED");
            return id;
        });

        long deadline = System.currentTimeMillis() + 20_000;
        while (RECEIVED.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
        }
        assertThat(RECEIVED).as("evaluated and delivered after commit with no test code in between")
                .hasSize(1);
        JsonNode body = JSON.readTree(RECEIVED.get(0).body());
        assertThat(body.get("logSheet").get("id").asLong()).isEqualTo(sheetId);
    }

    // ── Plumbing ─────────────────────────────────────────────────────────────

    private static JsonNode unitWithRelation(JsonNode units, String relation) {
        for (JsonNode unit : units) {
            if (relation.equals(unit.get("relation").asText())) {
                return unit;
            }
        }
        throw new AssertionError("no unit with relation " + relation + " in " + units);
    }

    private Alarm liveAlarm() {
        return alarmRepository.findLive(asset, tempField).orElseThrow();
    }

    private AlarmWebhookDelivery onlyDelivery(Alarm alarm) {
        List<AlarmWebhookDelivery> rows = deliveryRepository.findByAlarmIdOrderByIdAsc(alarm.getId());
        assertThat(rows).hasSize(1);
        return rows.get(0);
    }

    private void rule(String trigger) {
        ruleService.create(new RuleForm("دمای پمپ", "FIELDS", List.of(tempField), trigger, 1, 1, null,
                "HIGH", "اعزام مکانیک", true), supervisor.getId());
    }

    /** A finished round, queued and evaluated synchronously — no after-commit nudge outside a transaction. */
    private Long round(int dayNumber, Object temp) {
        Long sheetId = saveRound(dayNumber, temp);
        alarmQueue.request(sheetId, "COMPLETED");
        processor.drain();
        return sheetId;
    }

    private Long saveRound(int dayNumber, Object temp) {
        long completedAt = t0 + dayNumber * DAY;
        LogSheet sheet = new LogSheet();
        sheet.setTemplateName("راند وب‌هوک");
        sheet.setScopeSummary("webhook test");
        sheet.setOperationalUnitId(unit);
        sheet.setStatus(LogSheetStatus.SUBMITTED);
        sheet.setOrigin(GenerationMode.MANUAL);
        sheet.setFieldDefinitionsSnapshot(snapshot);
        sheet.setCompletedAt(completedAt);
        sheet.setSubmittedAt(completedAt + 1_000L);
        sheet.setSyncStatus("SYNCED");
        sheet.setCreatedAt(completedAt - DAY / 2);
        sheet.setUpdatedAt(completedAt);
        Long sheetId = sheetRepository.save(sheet).getId();
        sheetIds.add(sheetId);

        LogSheetEntry entry = new LogSheetEntry();
        entry.setLogSheetId(sheetId);
        entry.setAssetId(asset);
        entry.setAssetName("پمپ وب‌هوک");
        entry.setClassId(classId);
        Map<String, Object> values = new HashMap<>();
        values.put("temp", temp);
        entry.setFormData(values);
        entry.setCreatedAt(completedAt - 1_000L);
        entry.setUpdatedAt(completedAt - 500L);
        EntrySeverityEvaluator.apply(entry, FieldDefinitionSnapshot.toFieldDefinitions(snapshot));
        entryRepository.save(entry);
        return sheetId;
    }

    private void seed() {
        long now = System.currentTimeMillis();
        String suffix = now + UUID.randomUUID().toString().substring(0, 6);

        OperationalUnit parent = new OperationalUnit();
        parent.setCode("WHP-" + suffix);
        parent.setName("واحد مادر وب‌هوک");
        parent.setCreatedAt(now);
        parent.setUpdatedAt(now);
        parentUnit = unitRepository.save(parent).getId();
        OperationalUnit child = new OperationalUnit();
        child.setCode("WHU-" + suffix);
        child.setName("واحد وب‌هوک");
        child.setParentId(parentUnit);
        child.setCreatedAt(now);
        child.setUpdatedAt(now);
        unit = unitRepository.save(child).getId();

        supervisor = saveUser("wh-sup-" + suffix, "سرپرست وب‌هوک", "09120001234");
        parentSupervisor = saveUser("wh-psup-" + suffix, "سرپرست مادر", "09120005678");
        jdbc.update("INSERT INTO unit_supervisors (unit_id, user_id) VALUES (?, ?)", unit, supervisor.getId());
        jdbc.update("INSERT INTO unit_supervisors (unit_id, user_id) VALUES (?, ?)", parentUnit, parentSupervisor.getId());

        Location location = new Location();
        location.setCode("WHLOC-" + suffix);
        location.setName("Webhook Hall");
        location.setCreatedAt(now);
        location.setUpdatedAt(now);
        locationId = locationRepository.save(location).getId();
        SubFunction subFunction = new SubFunction();
        subFunction.setCode("WHSF-" + suffix);
        subFunction.setName("WHSF-" + suffix);
        subFunction.setTag("NFC-WHSF-" + suffix);
        subFunction.setCreatedAt(now);
        subFunction.setUpdatedAt(now);
        hierarchyService.applySubFunctionParent(subFunction, AssetHierarchyService.SCOPE_LOCATION, locationId);
        subFunctionId = hierarchyService.saveSubFunction(subFunction).getId();

        AssetClass assetClass = new AssetClass();
        assetClass.setName("پمپ وب‌هوک " + suffix);
        assetClass.setCreatedAt(now);
        assetClass.setUpdatedAt(now);
        classId = classRepository.save(assetClass).getId();

        Map<String, Object> warning = new HashMap<>();
        warning.put(FieldValidationSupport.KEY_MIN, 0d);
        warning.put(FieldValidationSupport.KEY_MAX, 80d);
        Map<String, Object> danger = new HashMap<>();
        danger.put(FieldValidationSupport.KEY_MIN, 0d);
        danger.put(FieldValidationSupport.KEY_MAX, 100d);
        Map<String, Object> validation = new HashMap<>();
        validation.put(FieldValidationSupport.KEY_WARNING, warning);
        validation.put(FieldValidationSupport.KEY_DANGER, danger);
        FieldDefinition field = new FieldDefinition();
        field.setClassId(classId);
        field.setKey("temp");
        field.setLabel("دما");
        field.setDataType("number");
        field.setUnit("°C");
        field.setRequired(false);
        field.setOrder(1);
        field.setVersion(1);
        field.setValidation(validation);
        field.setCreatedAt(now);
        field.setUpdatedAt(now);
        tempField = fieldRepository.save(field).getId();

        assetCode = "WHA-" + suffix;
        AssetEntry entry = new AssetEntry();
        entry.setAssetCode(assetCode);
        entry.setAssetName("پمپ وب‌هوک");
        entry.setClassId(classId);
        entry.setSubFunctionId(subFunctionId);
        entry.setNfcTagId("NFC-" + assetCode);
        entry.setCreatedAt(now);
        entry.setUpdatedAt(now);
        asset = assetRepository.save(entry).getId();

        snapshot = fieldDefinitionsService.captureSnapshot(classId);
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
}
