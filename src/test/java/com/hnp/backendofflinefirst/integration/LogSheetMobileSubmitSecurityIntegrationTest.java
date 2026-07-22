package com.hnp.backendofflinefirst.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hnp.backendofflinefirst.domain.AssignmentType;
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

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end checks for mobile log-sheet submit integrity:
 * foreign assets are rejected, omitted assets are preserved, and entry metadata is server-authoritative.
 */
@Transactional
class LogSheetMobileSubmitSecurityIntegrationTest extends AbstractPostgresIntegrationTest {

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

    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void batchSubmitRejectsForeignAssetEvenWhenItExistsInDatabase() throws Exception {
        MultiAssetFixture fixture = seedThreeAssetSheet();
        User operator = assignOperator(fixture);

        LogSheetEntryDto foreign = entryDto(fixture.foreignAsset().getId(), Map.of("temp", 99));
        LogSheetDto dto = submitDto(fixture.sheet().getId(), List.of(foreign), "foreign-asset");

        mockMvc.perform(batchSubmit(operator, dto))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].outcome").value("ERROR"))
                .andExpect(jsonPath("$[0].error").value(containsString(
                        String.valueOf(fixture.foreignAsset().getId()))));

        LogSheet reloaded = logSheetRepository.findById(fixture.sheet().getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(LogSheetStatus.IN_PROGRESS);
        assertThat(logSheetEntryRepository.findByLogSheetId(fixture.sheet().getId())).hasSize(3);
        assertThat(allEntriesEmpty(fixture.sheet().getId())).isTrue();
    }

    @Test
    void batchSubmitOmitsAssetWithoutRemovingItFromServer() throws Exception {
        MultiAssetFixture fixture = seedThreeAssetSheet();
        User operator = assignOperator(fixture);
        AssetEntry omitted = fixture.sheetAssets().get(2);

        LogSheetEntryDto first = entryDto(fixture.sheetAssets().get(0).getId(), Map.of("temp", 11));
        LogSheetEntryDto second = entryDto(fixture.sheetAssets().get(1).getId(), Map.of("temp", 22));
        LogSheetDto dto = submitDto(fixture.sheet().getId(), List.of(first, second), "omit-third");

        mockMvc.perform(batchSubmit(operator, dto))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].outcome").value("SUBMITTED"));

        List<LogSheetEntry> entries = logSheetEntryRepository.findByLogSheetId(fixture.sheet().getId());
        assertThat(entries).hasSize(3);
        assertThat(findEntry(entries, omitted.getId()).getFormData()).isEmpty();
        assertThat(findEntry(entries, fixture.sheetAssets().get(0).getId()).getFormData()).containsEntry("temp", 11);
        assertThat(findEntry(entries, fixture.sheetAssets().get(1).getId()).getFormData()).containsEntry("temp", 22);
    }

    @Test
    void batchSubmitIgnoresTamperedEntryMetadata() throws Exception {
        MultiAssetFixture fixture = seedThreeAssetSheet();
        User operator = assignOperator(fixture);
        AssetEntry target = fixture.sheetAssets().get(0);
        LogSheetEntry serverEntry = findEntry(
                logSheetEntryRepository.findByLogSheetId(fixture.sheet().getId()), target.getId());

        LogSheetEntryDto tampered = entryDto(target.getId(), Map.of("temp", 42));
        tampered.setAssetName("Tampered asset name");
        tampered.setClassId(999_999L);
        tampered.setNfcTagId("NFC-TAMPERED");
        tampered.setSubFunctionCode("SF-TAMPERED");
        tampered.setSubFunctionTag("TAG-TAMPERED");

        LogSheetDto dto = submitDto(fixture.sheet().getId(), List.of(tampered), "tamper-meta");

        mockMvc.perform(batchSubmit(operator, dto))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].outcome").value("SUBMITTED"));

        LogSheetEntry persisted = findEntry(
                logSheetEntryRepository.findByLogSheetId(fixture.sheet().getId()), target.getId());
        assertThat(persisted.getFormData()).containsEntry("temp", 42);
        assertThat(persisted.getAssetName()).isEqualTo(serverEntry.getAssetName());
        assertThat(persisted.getClassId()).isEqualTo(serverEntry.getClassId());
        assertThat(persisted.getNfcTagId()).isEqualTo(serverEntry.getNfcTagId());
        assertThat(persisted.getSubFunctionCode()).isEqualTo(serverEntry.getSubFunctionCode());
        assertThat(persisted.getSubFunctionTag()).isEqualTo(serverEntry.getSubFunctionTag());
    }

    @Test
    void batchSubmitRejectsForeignAssetAmongValidOnesWithoutMutatingEntries() throws Exception {
        MultiAssetFixture fixture = seedThreeAssetSheet();
        User operator = assignOperator(fixture);

        LogSheetEntryDto valid = entryDto(fixture.sheetAssets().get(0).getId(), Map.of("temp", 5));
        LogSheetEntryDto foreign = entryDto(fixture.foreignAsset().getId(), Map.of("temp", 6));
        LogSheetDto dto = submitDto(fixture.sheet().getId(), List.of(valid, foreign), "mixed-assets");

        mockMvc.perform(batchSubmit(operator, dto))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].outcome").value("ERROR"));

        LogSheet reloaded = logSheetRepository.findById(fixture.sheet().getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(LogSheetStatus.IN_PROGRESS);
        assertThat(allEntriesEmpty(fixture.sheet().getId())).isTrue();
    }

    @Test
    void batchSubmitWithNullEntriesCompletesWithoutChangingEntryRows() throws Exception {
        MultiAssetFixture fixture = seedThreeAssetSheet();
        User operator = assignOperator(fixture);

        LogSheetDto dto = submitDto(fixture.sheet().getId(), null, "null-entries");

        mockMvc.perform(batchSubmit(operator, dto))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].outcome").value("SUBMITTED"));

        assertThat(logSheetRepository.findById(fixture.sheet().getId()).orElseThrow().getStatus())
                .isEqualTo(LogSheetStatus.SUBMITTED);
        assertThat(logSheetEntryRepository.findByLogSheetId(fixture.sheet().getId())).hasSize(3);
        assertThat(allEntriesEmpty(fixture.sheet().getId())).isTrue();
    }

    private boolean allEntriesEmpty(Long sheetId) {
        return logSheetEntryRepository.findByLogSheetId(sheetId).stream()
                .allMatch(entry -> entry.getFormData() == null || entry.getFormData().isEmpty());
    }

    private LogSheetEntry findEntry(List<LogSheetEntry> entries, Long assetId) {
        return entries.stream()
                .filter(entry -> assetId.equals(entry.getAssetId()))
                .findFirst()
                .orElseThrow();
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

    private User assignOperator(MultiAssetFixture fixture) {
        User operator = createOperator(fixture.unit().getId(), "sec-op-" + System.nanoTime(), "op12345");
        LogSheet sheet = fixture.sheet();
        sheet.setAssigneeUserId(operator.getId());
        sheet.setAssignmentType(AssignmentType.SUPERVISOR_ASSIGNED);
        sheet.setAssignedAt(System.currentTimeMillis());
        sheet.setDueAt(System.currentTimeMillis() + 3_600_000L);
        sheet.setStatus(LogSheetStatus.IN_PROGRESS);
        logSheetRepository.save(sheet);
        return operator;
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
        user.setFullName("Submit Security Operator");
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

    private MultiAssetFixture seedThreeAssetSheet() {
        long now = System.currentTimeMillis();

        OperationalUnit unit = new OperationalUnit();
        unit.setCode("SEC-BU-" + now);
        unit.setName("Security Unit");
        unit.setCreatedAt(now);
        unit.setUpdatedAt(now);
        unit = operationalUnitRepository.save(unit);

        Location location = new Location();
        location.setCode("SEC-LOC-" + now);
        location.setName("Security Hall");
        location.setCreatedAt(now);
        location.setUpdatedAt(now);
        location = locationRepository.save(location);

        SubFunction subFunction = new SubFunction();
        subFunction.setCode("SEC-SF-" + now);
        subFunction.setName("Security Sub");
        subFunction.setTag("NFC-SEC-1");
        subFunction.setCreatedAt(now);
        subFunction.setUpdatedAt(now);
        hierarchyService.applySubFunctionParent(
                subFunction, AssetHierarchyService.SCOPE_LOCATION, location.getId());
        subFunction = hierarchyService.saveSubFunction(subFunction);

        AssetClass assetClass = new AssetClass();
        assetClass.setName("Security Pump " + now);
        assetClass.setCreatedAt(now);
        assetClass.setUpdatedAt(now);
        assetClass = assetClassRepository.save(assetClass);
        saveTempField(assetClass.getId(), now);

        AssetEntry asset1 = saveAsset("SEC-A1-" + now, "Asset One", assetClass.getId(), subFunction.getId(), now);
        AssetEntry asset2 = saveAsset("SEC-A2-" + now, "Asset Two", assetClass.getId(), subFunction.getId(), now);
        AssetEntry asset3 = saveAsset("SEC-A3-" + now, "Asset Three", assetClass.getId(), subFunction.getId(), now);
        AssetEntry foreignAsset = saveAsset("SEC-FX-" + now, "Foreign Asset", assetClass.getId(), subFunction.getId(), now);

        LogSheetTemplate template = new LogSheetTemplate();
        template.setName("Security Template " + now);
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

        LogSheet sheet = new LogSheet();
        sheet.setTemplateId(template.getId());
        sheet.setTemplateName(template.getName());
        sheet.setScopeSummary("location:" + location.getId());
        sheet.setOperationalUnitId(unit.getId());
        sheet.setStatus(LogSheetStatus.IN_PROGRESS);
        sheet.setOrigin(GenerationMode.MANUAL);
        sheet.setCreatedAt(now);
        sheet.setUpdatedAt(now);
        sheet = logSheetRepository.save(sheet);

        for (AssetEntry asset : List.of(asset1, asset2, asset3)) {
            LogSheetEntry entry = new LogSheetEntry();
            entry.setLogSheetId(sheet.getId());
            entry.setAssetId(asset.getId());
            entry.setAssetName(asset.getAssetName());
            entry.setClassId(assetClass.getId());
            entry.setNfcTagId(subFunction.getTag());
            entry.setSubFunctionCode(subFunction.getCode());
            entry.setSubFunctionTag(subFunction.getTag());
            entry.setFormData(new HashMap<>());
            logSheetEntryRepository.save(entry);
        }

        return new MultiAssetFixture(unit, sheet, List.of(asset1, asset2, asset3), foreignAsset, assetClass);
    }

    private void saveTempField(Long classId, long now) {
        FieldDefinition temp = new FieldDefinition();
        temp.setClassId(classId);
        temp.setKey("temp");
        temp.setLabel("Temperature");
        temp.setDataType("number");
        temp.setRequired(false);
        temp.setOrder(1);
        temp.setVersion(1);
        temp.setDeleted(false);
        temp.setSynced(false);
        temp.setCreatedAt(now);
        temp.setUpdatedAt(now);
        fieldDefinitionRepository.save(temp);
    }

    private AssetEntry saveAsset(String code, String name, Long classId, Long subFunctionId, long now) {
        AssetEntry asset = new AssetEntry();
        asset.setAssetCode(code);
        asset.setAssetName(name);
        asset.setClassId(classId);
        asset.setSubFunctionId(subFunctionId);
        asset.setCreatedAt(now);
        asset.setUpdatedAt(now);
        return assetEntryRepository.save(asset);
    }

    private record MultiAssetFixture(
            OperationalUnit unit,
            LogSheet sheet,
            List<AssetEntry> sheetAssets,
            AssetEntry foreignAsset,
            AssetClass assetClass) {}
}
