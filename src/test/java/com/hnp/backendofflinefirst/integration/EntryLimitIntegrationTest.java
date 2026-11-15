package com.hnp.backendofflinefirst.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hnp.backendofflinefirst.domain.AssetFieldResolver;
import com.hnp.backendofflinefirst.domain.AssignmentType;
import com.hnp.backendofflinefirst.domain.FieldSource;
import com.hnp.backendofflinefirst.domain.FieldValidationSupport;
import com.hnp.backendofflinefirst.domain.GenerationMode;
import com.hnp.backendofflinefirst.domain.LogSheetStatus;
import com.hnp.backendofflinefirst.dto.LogSheetBatchRequest;
import com.hnp.backendofflinefirst.dto.LogSheetDto;
import com.hnp.backendofflinefirst.dto.LogSheetEntryDto;
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
import com.hnp.backendofflinefirst.repository.FieldDefinitionRepository;
import com.hnp.backendofflinefirst.repository.LocationRepository;
import com.hnp.backendofflinefirst.repository.LogSheetEntryRepository;
import com.hnp.backendofflinefirst.repository.LogSheetRepository;
import com.hnp.backendofflinefirst.repository.LogSheetTemplateRepository;
import com.hnp.backendofflinefirst.repository.OperationalUnitRepository;
import com.hnp.backendofflinefirst.repository.RoleRepository;
import com.hnp.backendofflinefirst.repository.UnitOperatorRepository;
import com.hnp.backendofflinefirst.repository.UserRepository;
import com.hnp.backendofflinefirst.repository.UserRoleRepository;
import com.hnp.backendofflinefirst.service.AssetFieldOverrideService;
import com.hnp.backendofflinefirst.service.AssetHierarchyService;
import com.hnp.backendofflinefirst.service.LogSheetGenerationService;
import com.hnp.backendofflinefirst.support.AbstractPostgresIntegrationTest;
import com.hnp.backendofflinefirst.support.WithAppUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A number field's entry limit (roadmap §40), end to end — every path a reading can arrive by.
 *
 * <p>The fixture is the case the feature was asked for: a class of lamp panels whose operators
 * count how many lamps are lit. The class says 0–8, whole numbers, and warns above 6; one panel
 * is a 32-lamp board, so its own layer says 0–32 — and also clears the class's warning band,
 * which is how this test pins the frozen-delta bug that dropped an asset's «no band here».
 *
 * <p>What must hold, and what each test checks:
 * <ul>
 *   <li>a value outside the limit is <b>refused</b> on every path — the tablet's final submit and
 *       its progress push with {@code VALIDATION_ERROR} (the one outcome the app answers with
 *       correct-and-resubmit), the panel's dialog with a worded 422 — and nothing is stored;</li>
 *   <li>a value inside it is stored and <b>judged by its bands exactly as before</b>: the limit
 *       never touches {@code max_severity}, so warnings, dangers and alarms are unchanged;</li>
 *   <li>the per-asset limit, and the per-asset removal of a band, reach the round's frozen
 *       snapshot — so the server judges the 32-lamp board by its own numbers;</li>
 *   <li>a round raised before the limit existed keeps accepting what it accepted.</li>
 * </ul>
 */
@Transactional
class EntryLimitIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String PASSWORD = "op12345";
    private static final String FILL = "CAP:LOGSHEET_COMPLETE_WEB_ANY";
    private static final String COMPLETE = "POST:/log-sheets/{id}/complete";

    @Autowired WebApplicationContext context;
    @Autowired ObjectMapper objectMapper;
    @Autowired UserRepository userRepository;
    @Autowired RoleRepository roleRepository;
    @Autowired UserRoleRepository userRoleRepository;
    @Autowired UnitOperatorRepository unitOperatorRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired OperationalUnitRepository operationalUnitRepository;
    @Autowired LocationRepository locationRepository;
    @Autowired AssetClassRepository assetClassRepository;
    @Autowired AssetEntryRepository assetEntryRepository;
    @Autowired FieldDefinitionRepository fieldDefinitionRepository;
    @Autowired LogSheetTemplateRepository templateRepository;
    @Autowired LogSheetRepository logSheetRepository;
    @Autowired LogSheetEntryRepository logSheetEntryRepository;
    @Autowired AssetHierarchyService hierarchyService;
    @Autowired LogSheetGenerationService generationService;
    @Autowired AssetFieldOverrideService fieldOverrideService;

    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    // ── the tablet ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a final submit outside the limit is VALIDATION_ERROR, worded with the limit, and stores nothing")
    void mobileSubmitOutsideTheLimitIsCorrectable() throws Exception {
        Fixture f = seed(true);

        mockMvc.perform(submit(f, Map.of(f.eightLampAsset(), Map.of("lamps", 20))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].outcome").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$[0].error").value(containsString("باید بین 0 و 8 باشد")));

        assertThat(logSheetRepository.findById(f.sheetId()).orElseThrow().getStatus())
                .isEqualTo(LogSheetStatus.IN_PROGRESS);
        assertThat(entry(f, f.eightLampAsset()).getFormData()).isNullOrEmpty();
    }

    @Test
    @DisplayName("a fraction on a whole-number field is refused at submit")
    void mobileSubmitRefusesAFraction() throws Exception {
        Fixture f = seed(true);

        mockMvc.perform(submit(f, Map.of(f.eightLampAsset(), Map.of("lamps", 2.5))))
                .andExpect(jsonPath("$[0].outcome").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$[0].error").value(containsString("باید عدد صحیح باشد")));
    }

    @Test
    @DisplayName("inside the limit: stored and judged by the bands exactly as before")
    void insideTheLimitTheBandsStillJudge() throws Exception {
        Fixture f = seed(true);

        mockMvc.perform(submit(f, Map.of(f.eightLampAsset(), Map.of("lamps", 7))))
                .andExpect(jsonPath("$[0].outcome").value("SUBMITTED"));

        LogSheetEntry stored = entry(f, f.eightLampAsset());
        assertThat(stored.getFormData()).containsEntry("lamps", 7);
        assertThat(stored.getMaxSeverity())
                .as("7 is above the class's warning maximum of 6 — the limit changes no verdict")
                .isEqualTo("WARNING");
    }

    @Test
    @DisplayName("the 32-lamp board is judged by its own limit — and by its own removal of the class band")
    void thePerAssetLayerIsFrozenAndApplied() throws Exception {
        Fixture f = seed(true);

        Map<Long, Map<String, Object>> readings = new LinkedHashMap<>();
        readings.put(f.eightLampAsset(), Map.of("lamps", 3));
        readings.put(f.thirtyTwoLampAsset(), Map.of("lamps", 20));
        mockMvc.perform(submit(f, readings))
                .andExpect(jsonPath("$[0].outcome").value("SUBMITTED"));

        LogSheetEntry board = entry(f, f.thirtyTwoLampAsset());
        assertThat(board.getFormData()).containsEntry("lamps", 20);
        // 20 is far above the class's warning maximum of 6, which this asset cleared. Before the
        // frozen delta carried an explicit null, the clearance never reached the round and this
        // was WARNING — the asset screen said «no band», the stored verdict used the class's.
        assertThat(board.getMaxSeverity()).isEqualTo("OK");
    }

    @Test
    @DisplayName("the round's snapshot carries the per-asset range and the cleared band as an explicit null")
    void theSnapshotCarriesTheAssetsLayer() {
        Fixture f = seed(true);
        LogSheet sheet = logSheetRepository.findById(f.sheetId()).orElseThrow();

        Map<?, ?> boardEntry = (Map<?, ?>) sheet.getFieldOverridesSnapshot()
                .get(String.valueOf(f.thirtyTwoLampAsset()));
        Map<?, ?> lamps = (Map<?, ?>) ((Map<?, ?>) boardEntry.get(AssetFieldResolver.SNAPSHOT_FIELDS)).get("lamps");

        Map<?, ?> range = (Map<?, ?>) lamps.get(FieldValidationSupport.KEY_ENTRY_RANGE);
        assertThat(((Number) range.get("min")).doubleValue()).isEqualTo(0.0);
        assertThat(((Number) range.get("max")).doubleValue()).isEqualTo(32.0);
        assertThat(lamps.containsKey(FieldValidationSupport.KEY_WARNING)).isTrue();
        assertThat(lamps.get(FieldValidationSupport.KEY_WARNING)).isNull();
    }

    @Test
    @DisplayName("a progress push refuses the same value the final submit would")
    void progressPushRefusesOutsideTheLimit() throws Exception {
        Fixture f = seed(true);

        JsonNode result = progress(f, f.eightLampAsset(), Map.of("lamps", 9));

        assertThat(result.get("outcome").asText()).isEqualTo("VALIDATION_ERROR");
        assertThat(result.get("error").asText()).contains("باید بین 0 و 8 باشد");
        assertThat(entry(f, f.eightLampAsset()).getFormData()).isNullOrEmpty();

        assertThat(progress(f, f.eightLampAsset(), Map.of("lamps", 8)).get("outcome").asText())
                .isEqualTo("SAVED");
    }

    @Test
    @DisplayName("a round raised before the limit existed keeps accepting what it accepted")
    void aRoundRaisedBeforeTheLimitIsNotRejudged() throws Exception {
        Fixture f = seed(false);
        // The limit arrives after the round was raised — the definition is frozen per sheet, so
        // this round never hears of it, on the tablet or here.
        FieldDefinition lamps = fieldDefinitionRepository.findByClassId(f.classId()).get(0);
        lamps.setValidation(lampValidation());
        fieldDefinitionRepository.saveAndFlush(lamps);

        mockMvc.perform(submit(f, Map.of(f.eightLampAsset(), Map.of("lamps", 20))))
                .andExpect(jsonPath("$[0].outcome").value("SUBMITTED"));
    }

    // ── the panel ──────────────────────────────────────────────────────────────────────────

    @Test
    @WithAppUser(roles = "ADMIN", authorities = {FILL, COMPLETE})
    @DisplayName("the panel's dialog refuses it in place, in Persian, and stores nothing")
    void webDialogRefusesOutsideTheLimit() throws Exception {
        Fixture f = seed(true);
        Long entryId = entry(f, f.eightLampAsset()).getId();

        mockMvc.perform(post("/log-sheets/{id}/entries/{entryId}/draft", f.sheetId(), entryId)
                        .param("fd_" + entryId + "_lamps", "12")
                        .with(csrf()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().string(containsString("باید بین 0 و 8 باشد")));
        assertThat(entry(f, f.eightLampAsset()).getFormData()).isNullOrEmpty();

        mockMvc.perform(post("/log-sheets/{id}/entries/{entryId}/draft", f.sheetId(), entryId)
                        .param("fd_" + entryId + "_lamps", "5")
                        .with(csrf()))
                .andExpect(status().isOk());
        assertThat(entry(f, f.eightLampAsset()).getFormData()).containsEntry("lamps", "5");
    }

    @Test
    @WithAppUser(roles = "ADMIN", authorities = {FILL, COMPLETE})
    @DisplayName("the panel's completion refuses it too, and the round stays open")
    void webCompletionRefusesOutsideTheLimit() throws Exception {
        Fixture f = seed(true);
        Long entryId = entry(f, f.eightLampAsset()).getId();

        mockMvc.perform(post("/log-sheets/{id}/complete", f.sheetId())
                        .param("fd_" + entryId + "_lamps", "9")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection());

        assertThat(logSheetRepository.findById(f.sheetId()).orElseThrow().getStatus())
                .isEqualTo(LogSheetStatus.IN_PROGRESS);
    }

    @Test
    @WithAppUser(roles = "ADMIN", authorities = {"GET:/log-sheets/{id}/fill", "GET:/log-sheets/{id}", COMPLETE, FILL})
    @DisplayName("the fill page renders each asset's own limit, as data — never a native constraint")
    void theFillPageCarriesEachAssetsLimit() throws Exception {
        // This is the test that would have caught the first version of the input: `th:data-*`
        // goes through Thymeleaf's default attribute processor, which refuses a bean call, so
        // every fill page holding a number field answered 500. Only a real render shows it.
        Fixture f = seed(true);

        String html = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/log-sheets/{id}/fill", f.sheetId()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);

        assertThat(html)
                .contains("data-entry-min=\"0\"")
                .contains("data-entry-max=\"8\"")
                .as("the 32-lamp board's own range, frozen with the round")
                .contains("data-entry-max=\"32\"")
                .contains("data-integer-only=\"true\"")
                .contains("inputmode=\"numeric\"")
                .contains("0–8، فقط عدد صحیح");
        assertThat(html)
                .as("no native constraint: an invalid control in a closed dialog blocks the form silently")
                .doesNotContainPattern("fill-field-number\"[^>]*\\smax=\"");
    }

    // ── helpers ────────────────────────────────────────────────────────────────────────────

    /** Lamps lit: 0–8, whole numbers, a warning above 6. */
    private static Map<String, Object> lampValidation() {
        return FieldValidationSupport.build("number", null, null, 6.0, null, null, null, null,
                null, true, null,
                new FieldValidationSupport.FieldBehaviour(false, null,
                        new FieldValidationSupport.EntryLimit(0.0, 8.0, true)));
    }

    private LogSheetEntry entry(Fixture f, Long assetId) {
        return logSheetEntryRepository.findByLogSheetId(f.sheetId()).stream()
                .filter(e -> assetId.equals(e.getAssetId()))
                .findFirst()
                .orElseThrow();
    }

    private org.springframework.test.web.servlet.RequestBuilder submit(
            Fixture f, Map<Long, Map<String, Object>> readings) throws Exception {
        LogSheetDto dto = new LogSheetDto();
        dto.setServerId(f.sheetId());
        dto.setLocalId("local-" + UUID.randomUUID());
        dto.setCompletedAt(System.currentTimeMillis());
        dto.setClientActionId("client-" + UUID.randomUUID());
        dto.setEntries(readings.entrySet().stream().map(r -> {
            LogSheetEntryDto e = new LogSheetEntryDto();
            e.setAssetId(r.getKey());
            e.setFormData(r.getValue());
            return e;
        }).toList());
        LogSheetBatchRequest request = new LogSheetBatchRequest();
        request.setLogSheets(List.of(dto));
        return post("/api/log-sheets/batch")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(f))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request));
    }

    private JsonNode progress(Fixture f, Long assetId, Map<String, Object> formData) throws Exception {
        String body = """
                {"logSheets":[{"serverId":%d,"localId":"local-1","operatorName":"Op","entries":[{"assetId":%d,"formData":%s}]}]}
                """.formatted(f.sheetId(), assetId, objectMapper.writeValueAsString(formData));
        MvcResult result = mockMvc.perform(post("/api/log-sheets/progress")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(f))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get(0);
    }

    private String token(Fixture f) throws Exception {
        MvcResult login = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", f.operatorUsername(), "password", PASSWORD))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(login.getResponse().getContentAsString()).get("accessToken").asText();
    }

    private record Fixture(Long sheetId, Long classId, Long eightLampAsset, Long thirtyTwoLampAsset,
                           String operatorUsername) {}

    /**
     * Two panels of one class, one sheet over both, assigned and in progress.
     *
     * @param limitBeforeRaising whether the class carries its limit when the sheet is raised
     */
    private Fixture seed(boolean limitBeforeRaising) {
        long now = System.currentTimeMillis();
        long nano = System.nanoTime();

        OperationalUnit unit = new OperationalUnit();
        unit.setCode("LIM-BU-" + nano);
        unit.setName("Limit Unit");
        unit.setCreatedAt(now);
        unit.setUpdatedAt(now);
        unit = operationalUnitRepository.saveAndFlush(unit);

        Location location = new Location();
        location.setCode("LIM-LOC-" + nano);
        location.setName("Limit Hall");
        location.setCreatedAt(now);
        location.setUpdatedAt(now);
        location = locationRepository.saveAndFlush(location);

        AssetClass assetClass = new AssetClass();
        assetClass.setName("Lamp Panel " + nano);
        assetClass.setCreatedAt(now);
        assetClass.setUpdatedAt(now);
        assetClass = assetClassRepository.saveAndFlush(assetClass);

        FieldDefinition lamps = new FieldDefinition();
        lamps.setClassId(assetClass.getId());
        lamps.setKey("lamps");
        lamps.setLabel("Lamps lit");
        lamps.setDataType("number");
        lamps.setRequired(false);
        lamps.setOrder(1);
        lamps.setValidation(limitBeforeRaising
                ? lampValidation()
                : FieldValidationSupport.build("number", null, null, 6.0, null, null, null, null));
        lamps.setCreatedAt(now);
        lamps.setUpdatedAt(now);
        fieldDefinitionRepository.saveAndFlush(lamps);

        Long[] assets = new Long[2];
        for (int i = 0; i < 2; i++) {
            SubFunction subFunction = new SubFunction();
            subFunction.setCode("LIM-SF" + i + "-" + nano);
            subFunction.setName("Limit Sub " + i);
            subFunction.setTag("NFC-LIM" + i + "-" + nano);
            subFunction.setCreatedAt(now);
            subFunction.setUpdatedAt(now);
            hierarchyService.applySubFunctionParent(
                    subFunction, AssetHierarchyService.SCOPE_LOCATION, location.getId());
            subFunction = hierarchyService.saveSubFunction(subFunction);

            AssetEntry asset = new AssetEntry();
            asset.setAssetCode("LIM-A" + i + "-" + nano);
            asset.setAssetName("Panel " + i);
            asset.setClassId(assetClass.getId());
            asset.setSubFunctionId(subFunction.getId());
            asset.setCreatedAt(now);
            asset.setUpdatedAt(now);
            assets[i] = assetEntryRepository.saveAndFlush(asset).getId();
        }

        if (limitBeforeRaising) {
            // Through the service, so the override is validated exactly as the asset screen's is.
            Map<String, Object> boardLayer = new LinkedHashMap<>();
            boardLayer.put(FieldValidationSupport.KEY_ENTRY_RANGE, Map.of("min", "0", "max", "32"));
            boardLayer.put(FieldValidationSupport.KEY_WARNING, null);
            fieldOverrideService.save(assets[1], FieldSource.RANGES_ONLY, Map.of("lamps", boardLayer));
        }

        LogSheetTemplate template = new LogSheetTemplate();
        template.setName("Limit Template " + nano);
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

        LogSheet sheet = generationService.generateFromTemplate(template, GenerationMode.MANUAL, null, now);
        User operator = operator(unit.getId(), nano);
        sheet.setAssigneeUserId(operator.getId());
        sheet.setAssignmentType(AssignmentType.SUPERVISOR_ASSIGNED);
        sheet.setAssignedAt(now);
        sheet.setDueAt(now + 3_600_000L);
        sheet.setStatus(LogSheetStatus.IN_PROGRESS);
        logSheetRepository.saveAndFlush(sheet);

        List<LogSheetEntry> entries = logSheetEntryRepository.findByLogSheetId(sheet.getId()).stream()
                .sorted(Comparator.comparing(LogSheetEntry::getId))
                .toList();
        assertThat(entries).as("the fixture needs both panels on the sheet").hasSize(2);
        return new Fixture(sheet.getId(), assetClass.getId(), assets[0], assets[1], operator.getUsername());
    }

    private User operator(Long unitId, long nano) {
        long now = System.currentTimeMillis();
        User user = new User();
        user.setUsername("lim-op-" + nano);
        user.setPersonnelCode("PC-" + UUID.randomUUID());
        user.setFullName("Limit Operator");
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setActive(true);
        user.setCreatedAt(now);
        user.setUpdatedAt(now);
        user = userRepository.saveAndFlush(user);

        UserRole userRole = new UserRole();
        userRole.setUserId(user.getId());
        userRole.setRoleId(roleRepository.findByCode("OPERATOR").orElseThrow().getId());
        userRoleRepository.save(userRole);

        UnitOperator link = new UnitOperator();
        link.setUnitId(unitId);
        link.setUserId(user.getId());
        unitOperatorRepository.save(link);
        return user;
    }
}
