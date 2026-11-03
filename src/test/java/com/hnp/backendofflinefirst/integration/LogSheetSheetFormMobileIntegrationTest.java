package com.hnp.backendofflinefirst.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hnp.backendofflinefirst.domain.ActionSource;
import com.hnp.backendofflinefirst.domain.AssignmentType;
import com.hnp.backendofflinefirst.domain.GenerationMode;
import com.hnp.backendofflinefirst.domain.LogSheetStatus;
import com.hnp.backendofflinefirst.entity.AssetClass;
import com.hnp.backendofflinefirst.entity.AssetEntry;
import com.hnp.backendofflinefirst.entity.FieldDefinition;
import com.hnp.backendofflinefirst.entity.Location;
import com.hnp.backendofflinefirst.entity.LogSheet;
import com.hnp.backendofflinefirst.entity.LogSheetField;
import com.hnp.backendofflinefirst.entity.LogSheetTemplate;
import com.hnp.backendofflinefirst.entity.OperationalUnit;
import com.hnp.backendofflinefirst.entity.SubFunction;
import com.hnp.backendofflinefirst.entity.UnitOperator;
import com.hnp.backendofflinefirst.entity.User;
import com.hnp.backendofflinefirst.entity.UserRole;
import com.hnp.backendofflinefirst.repository.AssetClassRepository;
import com.hnp.backendofflinefirst.repository.AssetEntryRepository;
import com.hnp.backendofflinefirst.repository.FieldDefinitionRepository;
import com.hnp.backendofflinefirst.repository.LocationRepository;
import com.hnp.backendofflinefirst.repository.LogSheetEntryRepository;
import com.hnp.backendofflinefirst.repository.LogSheetFieldRepository;
import com.hnp.backendofflinefirst.repository.LogSheetRepository;
import com.hnp.backendofflinefirst.repository.LogSheetRevisionRepository;
import com.hnp.backendofflinefirst.repository.LogSheetTemplateRepository;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The round's own questions on the mobile contract.
 *
 * <p><b>Downward the contract did not change at all</b>, and the first test says so: the bundle
 * serialises the whole {@code LogSheet}, so the frozen questions and the current answers already
 * travel to the device. Upward it grew one additive field, {@code sheetFormData}, on the submit
 * payload and on the progress push.
 *
 * <p>The two paths differ in exactly one way and it is the one worth guarding: the submit judges
 * the answers in full, the progress push judges only the shape of what is there. A round being
 * walked is partial by definition, so applying the full check to a push would refuse every one
 * of them until the last.
 */
@Transactional
class LogSheetSheetFormMobileIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired WebApplicationContext context;
    @Autowired ObjectMapper objectMapper;
    @Autowired LogSheetRepository logSheetRepository;
    @Autowired LogSheetEntryRepository logSheetEntryRepository;
    @Autowired LogSheetFieldRepository logSheetFieldRepository;
    @Autowired LogSheetRevisionRepository logSheetRevisionRepository;
    @Autowired LogSheetTemplateRepository templateRepository;
    @Autowired OperationalUnitRepository operationalUnitRepository;
    @Autowired LocationRepository locationRepository;
    @Autowired SubFunctionRepository subFunctionRepository;
    @Autowired AssetClassRepository assetClassRepository;
    @Autowired AssetEntryRepository assetEntryRepository;
    @Autowired FieldDefinitionRepository fieldDefinitionRepository;
    @Autowired UserRepository userRepository;
    @Autowired UserRoleRepository userRoleRepository;
    @Autowired RoleRepository roleRepository;
    @Autowired UnitOperatorRepository unitOperatorRepository;
    @Autowired AssetHierarchyService hierarchyService;
    @Autowired LogSheetGenerationService generationService;
    @Autowired PasswordEncoder passwordEncoder;

    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void theBundleAlreadyCarriesTheQuestionsAndTheAnswersWithNoContractChange() throws Exception {
        Fixture f = seed();

        String body = mockMvc.perform(get("/api/log-sheets/{id}/bundle", f.sheetId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + f.token()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode sheet = objectMapper.readTree(body).get("sheet");
        // Both columns ride on the serialised entity. This is the whole of "downward there is
        // nothing to do", and it is worth a test rather than a sentence.
        assertThat(sheet.get("sheetFieldDefinitionsSnapshot")).isNotNull();
        assertThat(sheet.get("sheetFieldDefinitionsSnapshot").get(0).get("key").asText())
                .isEqualTo("note");
        assertThat(sheet.has("sheetFormData")).isTrue();
    }

    @Test
    void aSubmitCarryingTheAnswersStoresThemAndStampsTheDevice() throws Exception {
        Fixture f = seed();

        JsonNode result = submit(f, "\"sheetFormData\":{\"note\":\"از دستگاه\"},");
        assertThat(result.get("outcome").asText()).isEqualTo("SUBMITTED");

        LogSheet saved = logSheetRepository.findById(f.sheetId()).orElseThrow();
        assertThat(saved.getSheetFormData()).containsEntry("note", "از دستگاه");
        assertThat(saved.getSheetFormSource()).isEqualTo("MOBILE");
        assertThat(saved.getSheetFormFilledByUserId()).isEqualTo(f.operatorId());
        // A first fill replaced nothing.
        assertThat(logSheetRevisionRepository.findByLogSheetIdOrderByIdAsc(f.sheetId())).isEmpty();
    }

    @Test
    void anOlderClientThatSendsNothingLeavesTheAnswersAlone() throws Exception {
        Fixture f = seed();
        // What the web panel stored before the tablet synced.
        LogSheet sheet = logSheetRepository.findById(f.sheetId()).orElseThrow();
        sheet.setSheetFormData(Map.of("note", "از پنل"));
        sheet.setSheetFormUpdatedAt(1_000L);
        logSheetRepository.saveAndFlush(sheet);

        // No `sheetFormData` key at all — an older build knows nothing about the round-level form.
        assertThat(submit(f, "").get("outcome").asText()).isEqualTo("SUBMITTED");

        LogSheet after = logSheetRepository.findById(f.sheetId()).orElseThrow();
        assertThat(after.getSheetFormData()).containsEntry("note", "از پنل");
        assertThat(after.getSheetFormUpdatedAt()).isEqualTo(1_000L);
    }

    @Test
    void aMalformedAnswerRefusesTheSubmitAsSomethingTheOperatorCanFix() throws Exception {
        Fixture f = seedWithNumberQuestion();

        JsonNode result = submit(f, "\"sheetFormData\":{\"pressure\":\"خیلی زیاد\"},");

        // VALIDATION_ERROR, not ERROR: the app offers «correct and resubmit» for this outcome
        // alone, because it is the only rejection the operator can act on themselves.
        assertThat(result.get("outcome").asText()).isEqualTo("VALIDATION_ERROR");
        LogSheet after = logSheetRepository.findById(f.sheetId()).orElseThrow();
        assertThat(after.getSheetFormData()).isNull();
        assertThat(after.getStatus()).isNotEqualTo(LogSheetStatus.SUBMITTED);
    }

    @Test
    void aProgressPushStoresTheAnswersWithoutFinishingTheRound() throws Exception {
        Fixture f = seed();

        JsonNode result = push(f, "\"sheetFormData\":{\"note\":\"در حال قدم زدن\"}");
        assertThat(result.get("outcome").asText()).isEqualTo("SAVED");

        LogSheet after = logSheetRepository.findById(f.sheetId()).orElseThrow();
        assertThat(after.getSheetFormData()).containsEntry("note", "در حال قدم زدن");
        assertThat(after.getSheetFormSource()).isEqualTo("MOBILE");
        assertThat(after.getStatus()).isEqualTo(LogSheetStatus.IN_PROGRESS);
    }

    @Test
    void aCorrectionFromTheFieldKeepsWhatItReplaced() throws Exception {
        Fixture f = seed();
        push(f, "\"sheetFormData\":{\"note\":\"اول\"}");

        push(f, "\"sheetFormData\":{\"note\":\"دوم\"}");

        var history = logSheetRevisionRepository.findByLogSheetIdOrderByIdAsc(f.sheetId());
        assertThat(history).hasSize(1);
        assertThat(history.get(0).getFormData()).containsEntry("note", "اول");
        assertThat(history.get(0).getSupersededSource()).isEqualTo(ActionSource.MOBILE);
        assertThat(logSheetRepository.findById(f.sheetId()).orElseThrow().getSheetFormData())
                .containsEntry("note", "دوم");
    }

    @Test
    void repeatingThePushUnchangedWritesNoHistory() throws Exception {
        Fixture f = seed();
        push(f, "\"sheetFormData\":{\"note\":\"ثابت\"}");
        Long stamp = logSheetRepository.findById(f.sheetId()).orElseThrow().getSheetFormUpdatedAt();

        // A tablet pushes on a timer. Without the unchanged-check the history would fill with
        // rows nobody made a decision in.
        push(f, "\"sheetFormData\":{\"note\":\"ثابت\"}");

        assertThat(logSheetRevisionRepository.findByLogSheetIdOrderByIdAsc(f.sheetId())).isEmpty();
        assertThat(logSheetRepository.findById(f.sheetId()).orElseThrow().getSheetFormUpdatedAt())
                .isEqualTo(stamp);
    }

    @Test
    void aProgressPushAcceptsABlankAnswerThatTheSubmitWouldStillAccept() throws Exception {
        Fixture f = seedWithNumberQuestion();

        // Shape only: a blank is not an error on a round still being walked, and nothing is
        // required in this release, so the submit accepts it too. The two validators are kept
        // separate so that switching `required` on does not need this to be rediscovered.
        assertThat(push(f, "\"sheetFormData\":{\"pressure\":\"\"}").get("outcome").asText())
                .isEqualTo("SAVED");
        // But a malformed one is refused on both paths.
        assertThat(push(f, "\"sheetFormData\":{\"pressure\":\"خیلی\"}").get("outcome").asText())
                .isEqualTo("VALIDATION_ERROR");
    }

    // ---------------------------------------------------------------- helpers

    private record Fixture(Long sheetId, Long assetId, Long classId, Long operatorId, String token) {}

    private JsonNode submit(Fixture f, String sheetFormFragment) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/log-sheets/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + f.token())
                        .content("""
                                {"logSheets":[{"serverId":%d,"localId":"l1","clientActionId":"%s",
                                  "completedAt":%d, %s
                                  "entries":[{"assetId":%d,"classId":%d,"formData":{"temp":"41"}}]}]}
                                """.formatted(f.sheetId(), UUID.randomUUID(),
                                System.currentTimeMillis() - 1000, sheetFormFragment,
                                f.assetId(), f.classId())))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString()).get(0);
    }

    private JsonNode push(Fixture f, String sheetFormFragment) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/log-sheets/progress")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + f.token())
                        .content("""
                                {"logSheets":[{"serverId":%d,"localId":"p1","entries":[], %s}]}
                                """.formatted(f.sheetId(), sheetFormFragment)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString()).get(0);
    }

    private Fixture seed() {
        return seed("note", "text");
    }

    private Fixture seedWithNumberQuestion() {
        return seed("pressure", "number");
    }

    private Fixture seed(String questionKey, String questionType) {
        long now = System.currentTimeMillis();
        long nano = System.nanoTime();

        OperationalUnit unit = new OperationalUnit();
        unit.setCode("SFM-BU-" + nano);
        unit.setName("Sheet Form Mobile Unit");
        unit.setCreatedAt(now);
        unit.setUpdatedAt(now);
        unit = operationalUnitRepository.saveAndFlush(unit);

        Location location = new Location();
        location.setCode("SFM-LOC-" + nano);
        location.setName("Hall");
        location.setCreatedAt(now);
        location.setUpdatedAt(now);
        location = locationRepository.saveAndFlush(location);

        SubFunction subFunction = new SubFunction();
        subFunction.setCode("SFM-SF-" + nano);
        subFunction.setName("Sub");
        subFunction.setTag("NFC-SFM-" + nano);
        subFunction.setCreatedAt(now);
        subFunction.setUpdatedAt(now);
        hierarchyService.applySubFunctionParent(
                subFunction, AssetHierarchyService.SCOPE_LOCATION, location.getId());
        subFunction = hierarchyService.saveSubFunction(subFunction);

        AssetClass assetClass = new AssetClass();
        assetClass.setName("SFM Pump " + nano);
        assetClass.setCreatedAt(now);
        assetClass.setUpdatedAt(now);
        assetClass = assetClassRepository.saveAndFlush(assetClass);

        FieldDefinition def = new FieldDefinition();
        def.setClassId(assetClass.getId());
        def.setKey("temp");
        def.setLabel("دما");
        def.setDataType("number");
        def.setRequired(false);
        def.setOrder(1);
        def.setCreatedAt(now);
        def.setUpdatedAt(now);
        fieldDefinitionRepository.saveAndFlush(def);

        AssetEntry asset = new AssetEntry();
        asset.setAssetCode("SFM-A1-" + nano);
        asset.setAssetName("Pump");
        asset.setClassId(assetClass.getId());
        asset.setSubFunctionId(subFunction.getId());
        asset.setCreatedAt(now);
        asset.setUpdatedAt(now);
        asset = assetEntryRepository.saveAndFlush(asset);

        LogSheetTemplate template = new LogSheetTemplate();
        template.setName("SFM Template " + nano);
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

        // The round-level question, owned by the template and frozen onto the sheet below.
        LogSheetField question = new LogSheetField();
        question.setTemplateId(template.getId());
        question.setKey(questionKey);
        question.setLabel("پرسش");
        question.setDataType(questionType);
        question.setRequired(false);
        question.setOrder(1);
        question.setVersion(1);
        question.setDeleted(false);
        question.setCreatedAt(now);
        question.setUpdatedAt(now);
        logSheetFieldRepository.saveAndFlush(question);

        LogSheet sheet = generationService.generateFromTemplate(
                template, GenerationMode.MANUAL, null, now);

        User operator = operator(unit.getId(), "sfm-op-" + nano);
        sheet.setAssigneeUserId(operator.getId());
        sheet.setAssignmentType(AssignmentType.SUPERVISOR_ASSIGNED);
        sheet.setAssignedAt(now);
        sheet.setDueAt(now + 3_600_000L);
        sheet.setStatus(LogSheetStatus.IN_PROGRESS);
        logSheetRepository.saveAndFlush(sheet);

        return new Fixture(sheet.getId(), asset.getId(), assetClass.getId(), operator.getId(),
                loginToken(operator.getUsername()));
    }

    private User operator(Long unitId, String username) {
        long now = System.currentTimeMillis();
        User u = new User();
        u.setUsername(username);
        u.setPersonnelCode("PC-" + UUID.randomUUID());
        u.setFullName(username);
        u.setPasswordHash(passwordEncoder.encode("pw123456"));
        u.setActive(true);
        u.setCreatedAt(now);
        u.setUpdatedAt(now);
        u = userRepository.saveAndFlush(u);

        UserRole ur = new UserRole();
        ur.setUserId(u.getId());
        ur.setRoleId(roleRepository.findByCode("OPERATOR").orElseThrow().getId());
        userRoleRepository.saveAndFlush(ur);

        UnitOperator link = new UnitOperator();
        link.setUnitId(unitId);
        link.setUserId(u.getId());
        unitOperatorRepository.saveAndFlush(link);
        return u;
    }

    private String loginToken(String username) {
        try {
            MvcResult login = mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    Map.of("username", username, "password", "pw123456"))))
                    .andExpect(status().isOk())
                    .andReturn();
            return objectMapper.readTree(login.getResponse().getContentAsString())
                    .get("accessToken").asText();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
