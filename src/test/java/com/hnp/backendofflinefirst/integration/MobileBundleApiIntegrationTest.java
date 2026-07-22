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
import com.hnp.backendofflinefirst.entity.UnitSupervisor;
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
import com.hnp.backendofflinefirst.repository.PermissionRepository;
import com.hnp.backendofflinefirst.repository.RoleRepository;
import com.hnp.backendofflinefirst.repository.SubFunctionRepository;
import com.hnp.backendofflinefirst.repository.UnitOperatorRepository;
import com.hnp.backendofflinefirst.repository.UnitSupervisorRepository;
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

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Transactional
class MobileBundleApiIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired WebApplicationContext context;
    @Autowired ObjectMapper objectMapper;
    @Autowired UserRepository userRepository;
    @Autowired RoleRepository roleRepository;
    @Autowired UserRoleRepository userRoleRepository;
    @Autowired UnitOperatorRepository unitOperatorRepository;
    @Autowired UnitSupervisorRepository unitSupervisorRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired PermissionRepository permissionRepository;
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
    void v2MobilePermissionsAreSeeded() {
        assertThat(permissionRepository.findByCode("GET:/api/bootstrap")).isPresent();
        assertThat(permissionRepository.findByCode("GET:/api/log-sheets/{id}/bundle")).isPresent();
    }

    @Test
    void inboxAssignedReturnsFullBundleAvailableReturnsMetadataOnly() throws Exception {
        SheetFixture fixture = seedSheetFixture(LogSheetStatus.ASSIGNED, adminUserId());
        linkAdminToUnit(fixture.unit().getId());
        seedPendingPoolSheet(fixture.unit());

        String token = loginToken("admin", "admin123");

        mockMvc.perform(get("/api/log-sheets/inbox")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assigned").isArray())
                .andExpect(jsonPath("$.assigned[0].sheet.id").value(fixture.sheet().getId()))
                .andExpect(jsonPath("$.assigned[0].entries").isArray())
                .andExpect(jsonPath("$.assigned[0].entries[0].assetId").value(fixture.asset().getId()))
                .andExpect(jsonPath("$.assigned[0].context.assetEntries").isArray())
                .andExpect(jsonPath("$.assigned[0].context.fieldDefinitions").isArray())
                .andExpect(jsonPath("$.assigned[0].context.locations").isArray())
                .andExpect(jsonPath("$.available[0].id").exists())
                .andExpect(jsonPath("$.available[0].entries").doesNotExist())
                .andExpect(jsonPath("$.available[0].context").doesNotExist());
    }

    @Test
    void bundleEndpointReturnsFullPayload() throws Exception {
        SheetFixture fixture = seedSheetFixture(LogSheetStatus.IN_PROGRESS, adminUserId());
        String token = loginToken("admin", "admin123");

        mockMvc.perform(get("/api/log-sheets/" + fixture.sheet().getId() + "/bundle")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sheet.id").value(fixture.sheet().getId()))
                .andExpect(jsonPath("$.entries[0].nfcTagId").value("NFC-BUNDLE-1"))
                .andExpect(jsonPath("$.context.assetClasses[0].id").value(fixture.assetClass().getId()))
                .andExpect(jsonPath("$.context.scopeDisplayLabel").isNotEmpty());
    }

    @Test
    void claimReturnsFullBundle() throws Exception {
        SheetFixture fixture = seedSheetFixture(LogSheetStatus.PENDING, null);
        User operator = createOperator(fixture.unit().getId(), "bundle-op", "op12345");

        String token = loginToken(operator.getUsername(), "op12345");

        mockMvc.perform(post("/api/log-sheets/" + fixture.sheet().getId() + "/claim")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sheet.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.entries").isArray())
                .andExpect(jsonPath("$.entries[0].assetId").value(fixture.asset().getId()))
                .andExpect(jsonPath("$.context.subFunctions").isArray())
                .andExpect(jsonPath("$.context.fieldDefinitions").isArray());
    }

    @Test
    void bundleReturnsEntryTimestampsWhenStored() throws Exception {
        SheetFixture fixture = seedSheetFixture(LogSheetStatus.IN_PROGRESS, adminUserId());
        LogSheetEntry entry = logSheetEntryRepository.findByLogSheetId(fixture.sheet().getId()).getFirst();
        entry.setCreatedAt(1_700_000_000_000L);
        entry.setUpdatedAt(1_700_000_100_000L);
        logSheetEntryRepository.save(entry);

        String token = loginToken("admin", "admin123");

        mockMvc.perform(get("/api/log-sheets/" + fixture.sheet().getId() + "/bundle")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries[0].createdAt").value(1_700_000_000_000L))
                .andExpect(jsonPath("$.entries[0].updatedAt").value(1_700_000_100_000L));
    }

    @Test
    void batchSubmitPersistsEntryTimestamps() throws Exception {
        SheetFixture fixture = seedSheetFixture(LogSheetStatus.IN_PROGRESS, null);
        User operator = createOperator(fixture.unit().getId(), "ts-op-" + System.nanoTime(), "op12345");

        LogSheet sheet = fixture.sheet();
        sheet.setAssigneeUserId(operator.getId());
        sheet.setAssignmentType(AssignmentType.SUPERVISOR_ASSIGNED);
        sheet.setAssignedAt(System.currentTimeMillis());
        sheet.setDueAt(System.currentTimeMillis() + 3_600_000L);
        logSheetRepository.save(sheet);

        long createdAt = 1_700_000_000_000L;
        long updatedAt = 1_700_000_100_000L;
        long completedAt = System.currentTimeMillis();

        LogSheetEntryDto entryDto = new LogSheetEntryDto();
        entryDto.setAssetId(fixture.asset().getId());
        entryDto.setAssetName(fixture.asset().getAssetName());
        entryDto.setFormData(Map.of("temp", 22));
        entryDto.setCreatedAt(createdAt);
        entryDto.setUpdatedAt(updatedAt);

        LogSheetDto dto = new LogSheetDto();
        dto.setServerId(sheet.getId());
        dto.setLocalId("local-ts-1");
        dto.setCompletedAt(completedAt);
        dto.setClientActionId("client-action-ts-1");
        dto.setEntries(List.of(entryDto));

        LogSheetBatchRequest request = new LogSheetBatchRequest();
        request.setLogSheets(List.of(dto));

        String token = loginToken(operator.getUsername(), "op12345");

        mockMvc.perform(post("/api/log-sheets/batch")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].outcome").value("SUBMITTED"));

        LogSheetEntry persisted = logSheetEntryRepository.findByLogSheetId(sheet.getId()).getFirst();
        assertThat(persisted.getCreatedAt()).isEqualTo(createdAt);
        assertThat(persisted.getUpdatedAt()).isEqualTo(updatedAt);
        assertThat(persisted.getFormData()).containsEntry("temp", 22);

        mockMvc.perform(get("/api/log-sheets/" + sheet.getId() + "/bundle")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries[0].createdAt").value(createdAt))
                .andExpect(jsonPath("$.entries[0].updatedAt").value(updatedAt));
    }

    @Test
    void slimMasterDataNoLongerExposesPlantHierarchy() throws Exception {
        String token = loginToken("admin", "admin123");

        mockMvc.perform(get("/api/master-data")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operationalUnits").isArray())
                .andExpect(jsonPath("$.locations").doesNotExist())
                .andExpect(jsonPath("$.assetEntries").doesNotExist())
                .andExpect(jsonPath("$.logSheetTemplates").doesNotExist());
    }

    private Long adminUserId() {
        return userRepository.findByUsername("admin").orElseThrow().getId();
    }

    private void linkAdminToUnit(Long unitId) {
        UnitSupervisor link = new UnitSupervisor();
        link.setUnitId(unitId);
        link.setUserId(adminUserId());
        unitSupervisorRepository.save(link);
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
        user.setFullName("Bundle Test Operator");
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

    private SheetFixture seedSheetFixture(LogSheetStatus status, Long assigneeUserId) {
        long now = System.currentTimeMillis();

        OperationalUnit unit = new OperationalUnit();
        unit.setCode("BU-" + now);
        unit.setName("Bundle Unit");
        unit.setCreatedAt(now);
        unit.setUpdatedAt(now);
        unit = operationalUnitRepository.save(unit);

        Location location = new Location();
        location.setCode("LOC-B-" + now);
        location.setName("Bundle Hall");
        location.setCreatedAt(now);
        location.setUpdatedAt(now);
        location = locationRepository.save(location);

        SubFunction subFunction = new SubFunction();
        subFunction.setCode("SF-B-" + now);
        subFunction.setName("Bundle Sub");
        subFunction.setTag("NFC-BUNDLE-1");
        subFunction.setCreatedAt(now);
        subFunction.setUpdatedAt(now);
        hierarchyService.applySubFunctionParent(
                subFunction, AssetHierarchyService.SCOPE_LOCATION, location.getId());
        subFunction = hierarchyService.saveSubFunction(subFunction);

        AssetClass assetClass = new AssetClass();
        assetClass.setName("Bundle Pump " + now);
        assetClass.setCreatedAt(now);
        assetClass.setUpdatedAt(now);
        assetClass = assetClassRepository.save(assetClass);
        saveTempField(assetClass.getId(), now);

        AssetEntry asset = new AssetEntry();
        asset.setAssetCode("AST-B-" + now);
        asset.setAssetName("Bundle Asset");
        asset.setClassId(assetClass.getId());
        asset.setSubFunctionId(subFunction.getId());
        asset.setCreatedAt(now);
        asset.setUpdatedAt(now);
        asset = assetEntryRepository.save(asset);

        LogSheetTemplate template = new LogSheetTemplate();
        template.setName("Bundle Template " + now);
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
        sheet.setStatus(status);
        sheet.setOrigin(GenerationMode.MANUAL);
        sheet.setAssigneeUserId(assigneeUserId);
        if (assigneeUserId != null) {
            sheet.setAssignmentType(AssignmentType.SUPERVISOR_ASSIGNED);
            sheet.setAssignedAt(now);
        }
        sheet.setCreatedAt(now);
        sheet.setUpdatedAt(now);
        sheet = logSheetRepository.save(sheet);

        LogSheetEntry entry = new LogSheetEntry();
        entry.setLogSheetId(sheet.getId());
        entry.setAssetId(asset.getId());
        entry.setAssetName(asset.getAssetName());
        entry.setClassId(assetClass.getId());
        entry.setNfcTagId("NFC-BUNDLE-1");
        entry.setSubFunctionCode(subFunction.getCode());
        entry.setSubFunctionTag(subFunction.getTag());
        entry.setFormData(Map.of());
        logSheetEntryRepository.save(entry);

        return new SheetFixture(unit, sheet, asset, assetClass);
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

    private void seedPendingPoolSheet(OperationalUnit unit) {
        long now = System.currentTimeMillis();
        LogSheet pending = new LogSheet();
        pending.setTemplateName("Pool Sheet");
        pending.setOperationalUnitId(unit.getId());
        pending.setStatus(LogSheetStatus.PENDING);
        pending.setOrigin(GenerationMode.MANUAL);
        pending.setCreatedAt(now);
        pending.setUpdatedAt(now);
        logSheetRepository.save(pending);
    }

    private record SheetFixture(
            OperationalUnit unit,
            LogSheet sheet,
            AssetEntry asset,
            AssetClass assetClass) {}
}
