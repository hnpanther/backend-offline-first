package com.hnp.backendofflinefirst.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hnp.backendofflinefirst.domain.AssignmentType;
import com.hnp.backendofflinefirst.domain.GenerationMode;
import com.hnp.backendofflinefirst.domain.LogSheetActionType;
import com.hnp.backendofflinefirst.domain.LogSheetEntrySource;
import com.hnp.backendofflinefirst.domain.LogSheetStatus;
import com.hnp.backendofflinefirst.entity.AssetClass;
import com.hnp.backendofflinefirst.entity.AssetEntry;
import com.hnp.backendofflinefirst.entity.FieldDefinition;
import com.hnp.backendofflinefirst.entity.Location;
import com.hnp.backendofflinefirst.entity.LogSheet;
import com.hnp.backendofflinefirst.entity.LogSheetEntry;
import com.hnp.backendofflinefirst.entity.LogSheetTemplate;
import com.hnp.backendofflinefirst.entity.OperationalUnit;
import com.hnp.backendofflinefirst.entity.SubFunction;
import com.hnp.backendofflinefirst.entity.UnitOperator;
import com.hnp.backendofflinefirst.entity.User;
import com.hnp.backendofflinefirst.entity.UserRole;
import com.hnp.backendofflinefirst.repository.AssetClassRepository;
import com.hnp.backendofflinefirst.repository.AssetEntryRepository;
import com.hnp.backendofflinefirst.repository.AssetStatusChangeRequestRepository;
import com.hnp.backendofflinefirst.repository.FieldDefinitionRepository;
import com.hnp.backendofflinefirst.repository.LocationRepository;
import com.hnp.backendofflinefirst.repository.LogSheetActionLogRepository;
import com.hnp.backendofflinefirst.repository.LogSheetEntryRepository;
import com.hnp.backendofflinefirst.repository.LogSheetRepository;
import com.hnp.backendofflinefirst.repository.LogSheetTemplateRepository;
import com.hnp.backendofflinefirst.repository.LogSheetVoidSubmissionRepository;
import com.hnp.backendofflinefirst.repository.OperationalUnitRepository;
import com.hnp.backendofflinefirst.repository.RoleRepository;
import com.hnp.backendofflinefirst.repository.SubFunctionRepository;
import com.hnp.backendofflinefirst.repository.UnitOperatorRepository;
import com.hnp.backendofflinefirst.repository.UserRepository;
import com.hnp.backendofflinefirst.repository.UserRoleRepository;
import com.hnp.backendofflinefirst.service.AssetHierarchyService;
import com.hnp.backendofflinefirst.service.LogSheetGenerationService;
import com.hnp.backendofflinefirst.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

/**
 * {@code POST /api/log-sheets/progress} — partial values from a round still being walked.
 *
 * <p><b>The gap it closes.</b> A tablet used to push completions and nothing else, so a round was
 * invisible to the server for its whole duration: an operator could fill twenty assets in the
 * first hour, be online throughout, and a supervisor looking at the sheet saw no data at all. If
 * the sheet then changed hands, the next operator started from an empty form.
 *
 * <p><b>What these tests are really guarding.</b> Progress writes into the same
 * {@code log_sheet_entries} rows a completion writes into, which is what makes a handover carry
 * the previous operator's work — and is also what makes it dangerous. So the tests below are
 * mostly about what progress must <em>not</em> do:
 *
 * <ul>
 *   <li>never complete a round, or write a {@code COMPLETE}/{@code SUBMIT} action row;</li>
 *   <li>never raise an asset status change request — a round in progress proposes nothing;</li>
 *   <li>never record a void submission when refused, because nothing was lost;</li>
 *   <li>never accept a push from somebody who no longer holds the sheet.</li>
 * </ul>
 */
@Transactional
class LogSheetProgressSyncIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired WebApplicationContext context;
    @Autowired ObjectMapper objectMapper;
    @Autowired UserRepository userRepository;
    @Autowired RoleRepository roleRepository;
    @Autowired UserRoleRepository userRoleRepository;
    @Autowired UnitOperatorRepository unitOperatorRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired OperationalUnitRepository operationalUnitRepository;
    @Autowired LocationRepository locationRepository;
    @Autowired SubFunctionRepository subFunctionRepository;
    @Autowired AssetClassRepository assetClassRepository;
    @Autowired AssetEntryRepository assetEntryRepository;
    @Autowired FieldDefinitionRepository fieldDefinitionRepository;
    @Autowired LogSheetTemplateRepository templateRepository;
    @Autowired LogSheetRepository logSheetRepository;
    @Autowired LogSheetEntryRepository logSheetEntryRepository;
    @Autowired LogSheetActionLogRepository actionLogRepository;
    @Autowired LogSheetVoidSubmissionRepository voidSubmissionRepository;
    @Autowired AssetStatusChangeRequestRepository statusRequestRepository;
    @Autowired AssetHierarchyService hierarchyService;
    @Autowired LogSheetGenerationService generationService;

    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    // -----------------------------------------------------------------------
    // The happy path
    // -----------------------------------------------------------------------

    @Test
    void storesPartialValuesAndStampsTheSheetWithoutCompletingIt() throws Exception {
        Fixture f = seed();

        JsonNode result = push(f, entry(f.assetId(), Map.of("temp", 42)));

        assertThat(result.get("outcome").asText()).isEqualTo("SAVED");
        assertThat(result.get("savedAt").asLong()).isPositive();

        LogSheet sheet = logSheetRepository.findById(f.sheetId()).orElseThrow();
        // The round is under way, not finished. Every one of these being untouched is the point.
        assertThat(sheet.getStatus()).isEqualTo(LogSheetStatus.IN_PROGRESS);
        assertThat(sheet.getCompletedAt()).isNull();
        assertThat(sheet.getSubmittedAt()).isNull();
        assertThat(sheet.getCompletedByUserId()).isNull();
        // ...and it is now visible: who reported, when, and from where.
        assertThat(sheet.getDraftSavedAt()).isNotNull();
        assertThat(sheet.getDraftSavedByUserId()).isEqualTo(f.operatorId());
        assertThat(sheet.getDraftSource()).isEqualTo("MOBILE");
        assertThat(sheet.getStartedAt()).isNotNull();

        LogSheetEntry entry = entryFor(f);
        assertThat(entry.getFormData()).containsEntry("temp", 42);
        assertThat(entry.getFilledByUserId()).isEqualTo(f.operatorId());
        assertThat(entry.getEntrySource()).isEqualTo(LogSheetEntrySource.PWA_NFC);
        // The has-a-reading test the progress column and the data-quality report both key off.
        assertThat(entry.getMaxSeverity()).isNotNull();
    }

    @Test
    void movesAnAssignedSheetToInProgressAndRecordsStartOnlyOnce() throws Exception {
        Fixture f = seed();
        assertThat(logSheetRepository.findById(f.sheetId()).orElseThrow().getStatus())
                .isEqualTo(LogSheetStatus.ASSIGNED);

        push(f, entry(f.assetId(), Map.of("temp", 10)));
        Long firstStartedAt = logSheetRepository.findById(f.sheetId()).orElseThrow().getStartedAt();

        push(f, entry(f.assetId(), Map.of("temp", 11)));

        LogSheet sheet = logSheetRepository.findById(f.sheetId()).orElseThrow();
        assertThat(sheet.getStatus()).isEqualTo(LogSheetStatus.IN_PROGRESS);
        // startedAt means "when this round was first worked on", not "when it was last touched".
        assertThat(sheet.getStartedAt()).isEqualTo(firstStartedAt);
        // Exactly one START row, however many times the round reports. A row per push would bury
        // the transitions that matter under hundreds that do not — a round reports on a timer for
        // as long as somebody is walking it.
        assertThat(actionLogRepository.findByLogSheetIdOrderByActionAtAsc(f.sheetId()))
                .filteredOn(a -> a.getAction() == LogSheetActionType.START)
                .hasSize(1);
    }

    @Test
    void writesNoCompletionOrSubmissionInTheActionLog() throws Exception {
        Fixture f = seed();

        push(f, entry(f.assetId(), Map.of("temp", 42)));

        assertThat(actionLogRepository.findByLogSheetIdOrderByActionAtAsc(f.sheetId()))
                .extracting(a -> a.getAction().name())
                .doesNotContain("COMPLETE", "SUBMIT", "EXPIRE", "SUPERSEDE");
    }

    @Test
    void raisesNoAssetStatusRequest() throws Exception {
        // Completing a round *proposes* an asset's new state. A round in progress proposes
        // nothing — the operator may still change the value before they submit, and a supervisor
        // asked to decide on a reading that was later corrected is being asked the wrong question.
        Fixture f = seed();
        long before = statusRequestRepository.count();

        push(f, entry(f.assetId(), Map.of("temp", 42)));

        assertThat(statusRequestRepository.count()).isEqualTo(before);
    }

    @Test
    void isSafeToResendTheSameValues() throws Exception {
        // No clientActionId, on purpose: progress is meant to be re-sent, and a unique action key
        // would answer the second push DUPLICATE and stop the supervisor's view advancing.
        Fixture f = seed();

        assertThat(push(f, entry(f.assetId(), Map.of("temp", 42))).get("outcome").asText())
                .isEqualTo("SAVED");
        assertThat(push(f, entry(f.assetId(), Map.of("temp", 42))).get("outcome").asText())
                .isEqualTo("SAVED");

        assertThat(entryFor(f).getFormData()).containsEntry("temp", 42);
    }

    @Test
    void anEmptyPayloadIsAcceptedButStampsNothing() throws Exception {
        Fixture f = seed();

        JsonNode result = pushRaw(f, "[]");

        assertThat(result.get("outcome").asText()).isEqualTo("NO_CHANGE");
        LogSheet sheet = logSheetRepository.findById(f.sheetId()).orElseThrow();
        // A "last seen" stamp moved by an empty payload would read to a supervisor as fresh work
        // having arrived.
        assertThat(sheet.getDraftSavedAt()).isNull();
        assertThat(sheet.getStatus()).isEqualTo(LogSheetStatus.ASSIGNED);
    }

    // -----------------------------------------------------------------------
    // Handover — the reason this exists
    // -----------------------------------------------------------------------

    @Test
    void thePartialWorkSurvivesAReassignmentAndTheSecondOperatorInheritsIt() throws Exception {
        Fixture f = seed();
        push(f, entry(f.assetId(), Map.of("temp", 42)));

        // The supervisor moves the round to somebody else. Before progress sync, the first
        // operator's readings existed only on their tablet and this asset would have come back
        // empty for the next person.
        User second = createOperator(f.unitId(), "prog-op2-" + System.nanoTime(), "op12345");
        LogSheet sheet = logSheetRepository.findById(f.sheetId()).orElseThrow();
        sheet.setAssigneeUserId(second.getId());
        logSheetRepository.saveAndFlush(sheet);

        LogSheetEntry entry = entryFor(f);
        assertThat(entry.getFormData()).containsEntry("temp", 42);
        // And it still names who took the reading, which is what lets the second operator tell
        // their own rows from the ones they inherited.
        assertThat(entry.getFilledByUserId()).isEqualTo(f.operatorId());
    }

    @Test
    void aSecondOperatorsProgressOverwritesAndTheFirstReadingIsKept() throws Exception {
        Fixture f = seed();
        push(f, entry(f.assetId(), Map.of("temp", 42)));

        User second = createOperator(f.unitId(), "prog-op3-" + System.nanoTime(), "op12345");
        LogSheet sheet = logSheetRepository.findById(f.sheetId()).orElseThrow();
        sheet.setAssigneeUserId(second.getId());
        logSheetRepository.saveAndFlush(sheet);
        String secondToken = loginToken(second.getUsername(), "op12345");

        Fixture asSecond = new Fixture(f.sheetId(), f.assetId(), f.unitId(), second.getId(), secondToken);
        push(asSecond, entry(f.assetId(), Map.of("temp", 77)));

        LogSheetEntry entry = entryFor(f);
        assertThat(entry.getFormData()).containsEntry("temp", 77);
        assertThat(entry.getFilledByUserId()).isEqualTo(second.getId());
        // V4: the reading the second operator replaced is not gone.
        assertThat(revisionsFor(entry)).hasSize(1);
    }

    // -----------------------------------------------------------------------
    // Refusals — none of which may cost the operator their work
    // -----------------------------------------------------------------------

    @Test
    void refusesAPushFromSomebodyWhoNoLongerHoldsTheSheetAndRecordsNoVoidSubmission() throws Exception {
        Fixture f = seed();
        LogSheet sheet = logSheetRepository.findById(f.sheetId()).orElseThrow();
        sheet.setAssigneeUserId(createOperator(f.unitId(), "prog-taker-" + System.nanoTime(), "op12345").getId());
        logSheetRepository.saveAndFlush(sheet);
        long voidsBefore = voidSubmissionRepository.count();

        JsonNode result = push(f, entry(f.assetId(), Map.of("temp", 42)));

        assertThat(result.get("outcome").asText()).isEqualTo("SUPERSEDED");
        assertThat(entryFor(f).getFormData()).isNullOrEmpty();
        // A refused progress push is not a lost submission: the work is still on the device and
        // still deliverable. Writing a void row here would fill the supervisor's review queue
        // with rounds nobody ever tried to finish.
        assertThat(voidSubmissionRepository.count()).isEqualTo(voidsBefore);
    }

    @Test
    void refusesAPushOnACancelledRound() throws Exception {
        Fixture f = seed();
        setStatus(f.sheetId(), LogSheetStatus.CANCELLED);

        assertThat(push(f, entry(f.assetId(), Map.of("temp", 42))).get("outcome").asText())
                .isEqualTo("CANCELLED");
    }

    @Test
    void refusesAPushOnAnAlreadyCompletedRound() throws Exception {
        Fixture f = seed();
        setStatus(f.sheetId(), LogSheetStatus.SUBMITTED);

        assertThat(push(f, entry(f.assetId(), Map.of("temp", 42))).get("outcome").asText())
                .isEqualTo("SUPERSEDED");
    }

    @Test
    void refusesAPushAfterTheDeadlineHasPassed() throws Exception {
        // Unlike a completion — which is judged on the device's completedAt, so on-time work
        // delivered late is still accepted — a progress report is about a round still being
        // walked. There is no earlier moment it could belong to.
        Fixture f = seed();
        LogSheet sheet = logSheetRepository.findById(f.sheetId()).orElseThrow();
        sheet.setDueAt(System.currentTimeMillis() - 60_000L);
        logSheetRepository.saveAndFlush(sheet);

        assertThat(push(f, entry(f.assetId(), Map.of("temp", 42))).get("outcome").asText())
                .isEqualTo("EXPIRED");
        assertThat(entryFor(f).getFormData()).isNullOrEmpty();
    }

    @Test
    void refusesAnAssetThatIsNotOnTheSheet() throws Exception {
        Fixture f = seed();

        JsonNode result = push(f, entry(999_999L, Map.of("temp", 42)));

        assertThat(result.get("outcome").asText()).isEqualTo("ERROR");
        assertThat(result.get("error").asText()).contains("999999");
    }

    // -----------------------------------------------------------------------
    // Validation: the answers that are there, not the ones that are missing
    // -----------------------------------------------------------------------

    @Test
    void acceptsAPartialRoundWithARequiredFieldStillUnanswered() throws Exception {
        // The whole difference from the submit path. A progress push happens at asset seven of
        // forty; judging it against "every required field is present" would refuse every push
        // until the last one.
        Fixture f = seedWithRequiredField();

        assertThat(push(f, entry(f.assetId(), Map.of("temp", 42))).get("outcome").asText())
                .isEqualTo("SAVED");
    }

    @Test
    void stillRefusesAnAnswerTheFinalSubmitWouldRefuseToo() throws Exception {
        // A malformed value must not reach form_data early, or the supervisor's live view shows a
        // reading the operator's own submission then throws out.
        Fixture f = seed();

        JsonNode result = push(f, entry(f.assetId(), Map.of("temp", "not-a-number")));

        assertThat(result.get("outcome").asText()).isEqualTo("VALIDATION_ERROR");
        assertThat(entryFor(f).getFormData()).isNullOrEmpty();
    }

    // -----------------------------------------------------------------------
    // Access
    // -----------------------------------------------------------------------

    @Test
    void refusesWithoutAToken() throws Exception {
        Fixture f = seed();
        mockMvc.perform(post("/api/log-sheets/progress")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(f, entry(f.assetId(), Map.of("temp", 42)))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void theOperatorRoleHoldsTheNewPermission() throws Exception {
        // V5 grants it to every role that already holds POST:/api/log-sheets/batch, derived from
        // the existing grant rather than a hard-coded list of role codes — so a duplicated role
        // gets it too. If that INSERT ever stops running, this is the test that says so.
        Fixture f = seed();
        mockMvc.perform(post("/api/log-sheets/progress")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + f.token())
                        .content(body(f, entry(f.assetId(), Map.of("temp", 42)))))
                .andExpect(status().isOk());
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private record Fixture(Long sheetId, Long assetId, Long unitId, Long operatorId, String token) {}

    private JsonNode push(Fixture f, String entriesJson) throws Exception {
        return pushRaw(f, "[" + entriesJson + "]");
    }

    private JsonNode pushRaw(Fixture f, String entriesArrayJson) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/log-sheets/progress")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + f.token())
                        .content(bodyRaw(f, entriesArrayJson)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.isArray()).isTrue();
        assertThat(body).hasSize(1);
        return body.get(0);
    }

    private String body(Fixture f, String entriesJson) throws Exception {
        return bodyRaw(f, "[" + entriesJson + "]");
    }

    private String bodyRaw(Fixture f, String entriesArrayJson) throws Exception {
        return """
                {"logSheets":[{"serverId":%d,"localId":"local-1","operatorName":"Op","entries":%s}]}
                """.formatted(f.sheetId(), entriesArrayJson);
    }

    private String entry(Long assetId, Map<String, Object> formData) throws Exception {
        return """
                {"assetId":%d,"formData":%s}
                """.formatted(assetId, objectMapper.writeValueAsString(formData));
    }

    private LogSheetEntry entryFor(Fixture f) {
        return logSheetEntryRepository.findByLogSheetId(f.sheetId()).stream()
                .filter(e -> f.assetId().equals(e.getAssetId()))
                .findFirst()
                .orElseThrow();
    }

    private List<com.hnp.backendofflinefirst.entity.LogSheetEntryRevision> revisionsFor(LogSheetEntry entry) {
        return revisionRepository.findByLogSheetEntryIdOrderByIdAsc(entry.getId());
    }

    @Autowired com.hnp.backendofflinefirst.repository.LogSheetEntryRevisionRepository revisionRepository;

    private void setStatus(Long sheetId, LogSheetStatus status) {
        LogSheet sheet = logSheetRepository.findById(sheetId).orElseThrow();
        sheet.setStatus(status);
        logSheetRepository.saveAndFlush(sheet);
    }

    private String loginToken(String username, String password) throws Exception {
        MvcResult login = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", username,
                                "password", password))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(login.getResponse().getContentAsString())
                .get("accessToken").asText();
    }

    private User createOperator(Long unitId, String username, String rawPassword) {
        long now = System.currentTimeMillis();
        User user = new User();
        user.setUsername(username);
        user.setPersonnelCode("PC-" + UUID.randomUUID());
        user.setFullName("Progress Operator");
        user.setPasswordHash(passwordEncoder.encode(rawPassword));
        user.setActive(true);
        user.setCreatedAt(now);
        user.setUpdatedAt(now);
        user = userRepository.saveAndFlush(user);

        UserRole userRole = new UserRole();
        userRole.setUserId(user.getId());
        userRole.setRoleId(roleRepository.findByCode("OPERATOR").orElseThrow().getId());
        userRoleRepository.saveAndFlush(userRole);

        UnitOperator link = new UnitOperator();
        link.setUnitId(unitId);
        link.setUserId(user.getId());
        unitOperatorRepository.saveAndFlush(link);
        return user;
    }

    private Fixture seed() throws Exception {
        return seed(false);
    }

    private Fixture seedWithRequiredField() throws Exception {
        return seed(true);
    }

    /** A generated sheet, ASSIGNED to an operator, one asset, a numeric and a status field. */
    private Fixture seed(boolean withRequiredField) throws Exception {
        long now = System.currentTimeMillis();
        long nano = System.nanoTime();

        OperationalUnit unit = new OperationalUnit();
        unit.setCode("PRG-BU-" + nano);
        unit.setName("Progress Unit");
        unit.setCreatedAt(now);
        unit.setUpdatedAt(now);
        unit = operationalUnitRepository.saveAndFlush(unit);

        Location location = new Location();
        location.setCode("PRG-LOC-" + nano);
        location.setName("Progress Hall");
        location.setCreatedAt(now);
        location.setUpdatedAt(now);
        location = locationRepository.saveAndFlush(location);

        SubFunction subFunction = new SubFunction();
        subFunction.setCode("PRG-SF-" + nano);
        subFunction.setName("Progress Sub");
        subFunction.setTag("NFC-PRG-" + nano);
        subFunction.setCreatedAt(now);
        subFunction.setUpdatedAt(now);
        hierarchyService.applySubFunctionParent(
                subFunction, AssetHierarchyService.SCOPE_LOCATION, location.getId());
        subFunction = hierarchyService.saveSubFunction(subFunction);

        AssetClass assetClass = new AssetClass();
        assetClass.setName("Progress Pump " + nano);
        assetClass.setCreatedAt(now);
        assetClass.setUpdatedAt(now);
        assetClass = assetClassRepository.saveAndFlush(assetClass);

        saveField(assetClass.getId(), "temp", "Temperature", "number", 1, false);
        saveField(assetClass.getId(), "note", "Note", "text", 2, withRequiredField);

        AssetEntry asset = new AssetEntry();
        asset.setAssetCode("PRG-A1-" + nano);
        asset.setAssetName("Pump");
        asset.setClassId(assetClass.getId());
        asset.setSubFunctionId(subFunction.getId());
        asset.setCreatedAt(now);
        asset.setUpdatedAt(now);
        asset = assetEntryRepository.saveAndFlush(asset);

        LogSheetTemplate template = new LogSheetTemplate();
        template.setName("Progress Template " + nano);
        template.setScopeType(AssetHierarchyService.SCOPE_LOCATION);
        template.setScopeId(location.getId());
        template.setClassId(assetClass.getId());
        template.setOperationalUnitId(unit.getId());
        template.setGenerationMode(GenerationMode.MANUAL);
        template.setScheduleActive(false);
        template.setActive(true);
        template.setCreatedAt(now);
        template.setUpdatedAt(now);
        template = templateRepository.saveAndFlush(template);

        LogSheet sheet = generationService.generateFromTemplate(
                template, GenerationMode.MANUAL, null, now);

        User operator = createOperator(unit.getId(), "prog-op-" + nano, "op12345");
        sheet.setAssigneeUserId(operator.getId());
        sheet.setAssignmentType(AssignmentType.SUPERVISOR_ASSIGNED);
        sheet.setAssignedAt(now);
        sheet.setDueAt(now + 3_600_000L);
        sheet.setStatus(LogSheetStatus.ASSIGNED);
        logSheetRepository.saveAndFlush(sheet);

        LogSheetEntry entry = logSheetEntryRepository.findByLogSheetId(sheet.getId()).get(0);
        return new Fixture(sheet.getId(), entry.getAssetId(), unit.getId(), operator.getId(),
                loginToken(operator.getUsername(), "op12345"));
    }

    private void saveField(Long classId, String key, String label, String dataType, int order,
                           boolean required) {
        long now = System.currentTimeMillis();
        FieldDefinition def = new FieldDefinition();
        def.setClassId(classId);
        def.setKey(key);
        def.setLabel(label);
        def.setDataType(dataType);
        def.setRequired(required);
        def.setOrder(order);
        def.setCreatedAt(now);
        def.setUpdatedAt(now);
        fieldDefinitionRepository.saveAndFlush(def);
    }
}
