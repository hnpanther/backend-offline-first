package com.hnp.backendofflinefirst.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hnp.backendofflinefirst.domain.AssignmentType;
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
import com.hnp.backendofflinefirst.repository.SubFunctionRepository;
import com.hnp.backendofflinefirst.repository.UnitOperatorRepository;
import com.hnp.backendofflinefirst.repository.UserRepository;
import com.hnp.backendofflinefirst.repository.UserRoleRepository;
import com.hnp.backendofflinefirst.service.AssetHierarchyService;
import com.hnp.backendofflinefirst.service.LogSheetBundleService;
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

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Snapshot + submit-time validation: in-flight sheets keep the schema from generation,
 * even after live field definitions change.
 */
@Transactional
class LogSheetFormDataValidationIntegrationTest extends AbstractPostgresIntegrationTest {

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
    @Autowired AssetHierarchyService hierarchyService;
    @Autowired LogSheetGenerationService generationService;
    @Autowired LogSheetBundleService bundleService;

    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void generatedSheetStoresFieldDefinitionSnapshot() {
        Fixture fixture = seedFixture();

        LogSheet sheet = generationService.generateFromTemplate(
                fixture.template(), GenerationMode.MANUAL, null, System.currentTimeMillis());

        assertThat(sheet.getFieldDefinitionsSnapshot()).isNotNull();
        assertThat(sheet.getFieldDefinitionsSnapshot()).extracting(s -> s.getKey())
                .containsExactly("temp");
        assertThat(sheet.getFieldDefinitionsSnapshot().get(0).isRequired()).isTrue();
    }

    @Test
    void bundleUsesSnapshotAfterLiveDefinitionsChange() {
        Fixture fixture = seedFixture();
        LogSheet sheet = generationService.generateFromTemplate(
                fixture.template(), GenerationMode.MANUAL, null, System.currentTimeMillis());

        mutateLiveDefinitionsToStricterRules(fixture);

        var bundleFields = bundleService.buildFullBundle(sheet).getContext().getFieldDefinitions();
        assertThat(bundleFields).extracting(FieldDefinition::getKey).containsExactly("temp");
        assertThat(bundleFields.get(0).isRequired()).isTrue();
        assertThat(bundleFields.get(0).getValidation()).isNull();
    }

    @Test
    void submitAcceptsDataValidUnderSnapshotDespiteStricterLiveDefinitions() throws Exception {
        Fixture fixture = seedFixture();
        LogSheet sheet = generationService.generateFromTemplate(
                fixture.template(), GenerationMode.MANUAL, null, System.currentTimeMillis());
        User operator = assignOperator(fixture, sheet);

        mutateLiveDefinitionsToStricterRules(fixture);

        LogSheetEntry entry = logSheetEntryRepository.findByLogSheetId(sheet.getId()).get(0);
        LogSheetDto dto = submitDto(sheet.getId(), List.of(
                entryDto(entry.getAssetId(), Map.of("temp", 95))), "snapshot-valid");

        mockMvc.perform(batchSubmit(operator, dto))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].outcome").value("SUBMITTED"));
    }

    @Test
    void submitAllowsCompletelyBlankEntryEvenWithRequiredFields() throws Exception {
        Fixture fixture = seedFixture();
        LogSheet sheet = generationService.generateFromTemplate(
                fixture.template(), GenerationMode.MANUAL, null, System.currentTimeMillis());
        User operator = assignOperator(fixture, sheet);

        LogSheetEntry entry = logSheetEntryRepository.findByLogSheetId(sheet.getId()).get(0);
        LogSheetDto dto = submitDto(sheet.getId(), List.of(
                entryDto(entry.getAssetId(), Map.of())), "blank-ok");

        mockMvc.perform(batchSubmit(operator, dto))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].outcome").value("SUBMITTED"));
    }

    @Test
    void submitRejectsMissingRequiredFieldWhenEntryHasOtherData() throws Exception {
        Fixture fixture = seedFixture();
        LogSheet sheet = generationService.generateFromTemplate(
                fixture.template(), GenerationMode.MANUAL, null, System.currentTimeMillis());
        User operator = assignOperator(fixture, sheet);

        LogSheetEntry entry = logSheetEntryRepository.findByLogSheetId(sheet.getId()).get(0);
        LogSheetDto dto = submitDto(sheet.getId(), List.of(
                entryDto(entry.getAssetId(), Map.of("note", "started"))), "missing-required");

        mockMvc.perform(batchSubmit(operator, dto))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].outcome").value("ERROR"))
                .andExpect(jsonPath("$[0].error").value(containsString("temp")));

        assertThat(logSheetRepository.findById(sheet.getId()).orElseThrow().getStatus())
                .isEqualTo(LogSheetStatus.IN_PROGRESS);
    }

    @Test
    void submitAllowsDangerRangePerSnapshot() throws Exception {
        Fixture fixture = seedFixture();
        FieldDefinition temp = fieldDefinitionRepository.findByClassId(fixture.assetClass().getId()).stream()
                .filter(fd -> "temp".equals(fd.getKey()))
                .findFirst()
                .orElseThrow();
        temp.setValidation(FieldValidationSupport.build("number", null, 20.0, 80.0, 10.0, 90.0));
        fieldDefinitionRepository.save(temp);

        LogSheet sheet = generationService.generateFromTemplate(
                fixture.template(), GenerationMode.MANUAL, null, System.currentTimeMillis());
        User operator = assignOperator(fixture, sheet);

        LogSheetEntry entry = logSheetEntryRepository.findByLogSheetId(sheet.getId()).get(0);
        LogSheetDto dto = submitDto(sheet.getId(), List.of(
                entryDto(entry.getAssetId(), Map.of("temp", 95))), "danger-range-ok");

        mockMvc.perform(batchSubmit(operator, dto))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].outcome").value("SUBMITTED"));
    }

    @Test
    void newlyGeneratedSheetUsesUpdatedDefinitions() {
        Fixture fixture = seedFixture();
        generationService.generateFromTemplate(
                fixture.template(), GenerationMode.MANUAL, null, System.currentTimeMillis());

        mutateLiveDefinitionsToStricterRules(fixture);

        LogSheet newer = generationService.generateFromTemplate(
                fixture.template(), GenerationMode.MANUAL, null, System.currentTimeMillis());

        assertThat(newer.getFieldDefinitionsSnapshot()).extracting(s -> s.getKey())
                .containsExactlyInAnyOrder("temp", "pressure");
        assertThat(newer.getFieldDefinitionsSnapshot().stream()
                .filter(s -> "temp".equals(s.getKey()))
                .findFirst().orElseThrow().getValidation())
                .containsKey(FieldValidationSupport.KEY_DANGER);
    }

    private void mutateLiveDefinitionsToStricterRules(Fixture fixture) {
        FieldDefinition temp = fieldDefinitionRepository.findByClassId(fixture.assetClass().getId()).stream()
                .filter(fd -> "temp".equals(fd.getKey()))
                .findFirst()
                .orElseThrow();
        temp.setRequired(false);
        temp.setValidation(FieldValidationSupport.build("number", null, 20.0, 80.0, 10.0, 90.0));
        fieldDefinitionRepository.save(temp);

        FieldDefinition pressure = new FieldDefinition();
        pressure.setClassId(fixture.assetClass().getId());
        pressure.setKey("pressure");
        pressure.setLabel("Pressure");
        pressure.setDataType("number");
        pressure.setRequired(true);
        pressure.setCreatedAt(System.currentTimeMillis());
        pressure.setUpdatedAt(System.currentTimeMillis());
        fieldDefinitionRepository.save(pressure);
    }

    private User assignOperator(Fixture fixture, LogSheet sheet) {
        User operator = createOperator(fixture.unit().getId(), "form-op-" + System.nanoTime(), "op12345");
        sheet.setAssigneeUserId(operator.getId());
        sheet.setAssignmentType(AssignmentType.SUPERVISOR_ASSIGNED);
        sheet.setAssignedAt(System.currentTimeMillis());
        sheet.setDueAt(System.currentTimeMillis() + 3_600_000L);
        sheet.setStatus(LogSheetStatus.IN_PROGRESS);
        logSheetRepository.save(sheet);
        return operator;
    }

    private LogSheetEntryDto entryDto(Long assetId, Map<String, Object> formData) {
        LogSheetEntryDto dto = new LogSheetEntryDto();
        dto.setAssetId(assetId);
        dto.setFormData(formData);
        return dto;
    }

    private LogSheetDto submitDto(Long sheetId, List<LogSheetEntryDto> entries, String actionId) {
        LogSheetDto dto = new LogSheetDto();
        dto.setServerId(sheetId);
        dto.setLocalId("local-" + actionId);
        dto.setCompletedAt(System.currentTimeMillis());
        dto.setClientActionId("client-action-" + actionId);
        dto.setEntries(entries);
        return dto;
    }

    private org.springframework.test.web.servlet.RequestBuilder batchSubmit(User operator, LogSheetDto dto)
            throws Exception {
        LogSheetBatchRequest request = new LogSheetBatchRequest();
        request.setLogSheets(List.of(dto));
        return post("/api/log-sheets/batch")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + loginToken(operator.getUsername(), "op12345"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request));
    }

    private String loginToken(String username, String password) throws Exception {
        MvcResult login = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", username,
                                "password", password))))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = objectMapper.readTree(login.getResponse().getContentAsString());
        return body.get("accessToken").asText();
    }

    private User createOperator(Long unitId, String username, String rawPassword) {
        long now = System.currentTimeMillis();
        User user = new User();
        user.setUsername(username);
        user.setFullName("Form Validation Operator");
        user.setPasswordHash(passwordEncoder.encode(rawPassword));
        user.setActive(true);
        user.setCreatedAt(now);
        user.setUpdatedAt(now);
        user = userRepository.save(user);

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

    private Fixture seedFixture() {
        long now = System.currentTimeMillis();

        OperationalUnit unit = new OperationalUnit();
        unit.setCode("FORM-BU-" + now);
        unit.setName("Form Validation Unit");
        unit.setCreatedAt(now);
        unit.setUpdatedAt(now);
        unit = operationalUnitRepository.save(unit);

        Location location = new Location();
        location.setCode("FORM-LOC-" + now);
        location.setName("Form Hall");
        location.setCreatedAt(now);
        location.setUpdatedAt(now);
        location = locationRepository.save(location);

        SubFunction subFunction = new SubFunction();
        subFunction.setCode("FORM-SF-" + now);
        subFunction.setName("Form Sub");
        subFunction.setTag("NFC-FORM-1");
        subFunction.setCreatedAt(now);
        subFunction.setUpdatedAt(now);
        hierarchyService.applySubFunctionParent(
                subFunction, AssetHierarchyService.SCOPE_LOCATION, location.getId());
        subFunction = hierarchyService.saveSubFunction(subFunction);

        AssetClass assetClass = new AssetClass();
        assetClass.setName("Form Pump " + now);
        assetClass.setCreatedAt(now);
        assetClass.setUpdatedAt(now);
        assetClass = assetClassRepository.save(assetClass);

        FieldDefinition temp = new FieldDefinition();
        temp.setClassId(assetClass.getId());
        temp.setKey("temp");
        temp.setLabel("Temperature");
        temp.setDataType("number");
        temp.setRequired(true);
        temp.setCreatedAt(now);
        temp.setUpdatedAt(now);
        fieldDefinitionRepository.save(temp);

        AssetEntry asset = new AssetEntry();
        asset.setAssetCode("FORM-A1-" + now);
        asset.setAssetName("Pump");
        asset.setClassId(assetClass.getId());
        asset.setSubFunctionId(subFunction.getId());
        asset.setCreatedAt(now);
        asset.setUpdatedAt(now);
        asset = assetEntryRepository.save(asset);

        LogSheetTemplate template = new LogSheetTemplate();
        template.setName("Form Template " + now);
        template.setScopeType(AssetHierarchyService.SCOPE_LOCATION);
        template.setScopeId(location.getId());
        template.setClassId(assetClass.getId());
        template.setOperationalUnitId(unit.getId());
        template.setGenerationMode(GenerationMode.MANUAL);
        template.setScheduleActive(false);
        template.setActive(true);
        template.setCreatedAt(now);
        template.setUpdatedAt(now);
        template = templateRepository.save(template);

        return new Fixture(unit, location, subFunction, assetClass, asset, template);
    }

    private record Fixture(
            OperationalUnit unit,
            Location location,
            SubFunction subFunction,
            AssetClass assetClass,
            AssetEntry asset,
            LogSheetTemplate template) {}
}
