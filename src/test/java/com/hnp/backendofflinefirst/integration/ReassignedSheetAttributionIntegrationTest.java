package com.hnp.backendofflinefirst.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hnp.backendofflinefirst.domain.AssignmentType;
import com.hnp.backendofflinefirst.domain.GenerationMode;
import com.hnp.backendofflinefirst.domain.LogSheetEntrySource;
import com.hnp.backendofflinefirst.domain.LogSheetStatus;
import com.hnp.backendofflinefirst.entity.AssetClass;
import com.hnp.backendofflinefirst.entity.AssetEntry;
import com.hnp.backendofflinefirst.entity.FieldDefinition;
import com.hnp.backendofflinefirst.entity.Location;
import com.hnp.backendofflinefirst.entity.LogSheet;
import com.hnp.backendofflinefirst.entity.LogSheetEntry;
import com.hnp.backendofflinefirst.entity.OperationalUnit;
import com.hnp.backendofflinefirst.entity.SubFunction;
import com.hnp.backendofflinefirst.entity.User;
import com.hnp.backendofflinefirst.entity.UserAuthType;
import com.hnp.backendofflinefirst.domain.FieldDefinitionSnapshot;
import com.hnp.backendofflinefirst.repository.AssetClassRepository;
import com.hnp.backendofflinefirst.repository.AssetEntryRepository;
import com.hnp.backendofflinefirst.repository.FieldDefinitionRepository;
import com.hnp.backendofflinefirst.repository.LocationRepository;
import com.hnp.backendofflinefirst.repository.LogSheetEntryRepository;
import com.hnp.backendofflinefirst.repository.LogSheetRepository;
import com.hnp.backendofflinefirst.repository.OperationalUnitRepository;
import com.hnp.backendofflinefirst.repository.RoleRepository;
import com.hnp.backendofflinefirst.repository.SubFunctionRepository;
import com.hnp.backendofflinefirst.service.AssetHierarchyService;
import com.hnp.backendofflinefirst.service.UserService;
import com.hnp.backendofflinefirst.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Reopen → reassign: operator 2 sees operator 1's readings, and every asset keeps naming
 * whoever actually recorded it until somebody changes the value.
 *
 * <h2>What this pins, and why it is not the "leak" it looks like</h2>
 *
 * <p>Handing a reopened sheet to a second operator so they can redo part of it is the intended
 * workflow — operator 2 is <em>supposed</em> to see what is already there, or they cannot tell
 * what still needs doing. What must not happen is the sheet quietly re-crediting all of it to
 * whoever submits last.
 *
 * <p>It does not, because both write paths re-stamp {@code filled_by_user_id} and
 * {@code entry_source} only when the value actually changed. That guard is unit-tested; this
 * test proves the whole round trip through real HTTP: submit, reopen, reassign, partial
 * resubmit — and that the bundle operator 2 receives now carries a <b>name</b> for each
 * pre-filled row, which is what makes the distinction visible on a tablet rather than only in
 * the database.
 */
@Transactional
class ReassignedSheetAttributionIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String PASSWORD = "op-secret-12345";

    @Autowired WebApplicationContext context;
    @Autowired ObjectMapper objectMapper;
    @Autowired UserService userService;
    @Autowired RoleRepository roleRepository;
    @Autowired OperationalUnitRepository operationalUnitRepository;
    @Autowired LocationRepository locationRepository;
    @Autowired SubFunctionRepository subFunctionRepository;
    @Autowired AssetClassRepository assetClassRepository;
    @Autowired AssetEntryRepository assetEntryRepository;
    @Autowired FieldDefinitionRepository fieldDefinitionRepository;
    @Autowired LogSheetRepository logSheetRepository;
    @Autowired LogSheetEntryRepository logSheetEntryRepository;
    @Autowired AssetHierarchyService hierarchyService;
    @Autowired com.hnp.backendofflinefirst.repository.UnitOperatorRepository unitOperatorRepository;

    MockMvc mockMvc;

    private User operatorOne;
    private User operatorTwo;
    private LogSheet sheet;
    private Long assetAId;
    private Long assetBId;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        seed();
    }

    private String login(User user) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", user.getUsername(), "password", PASSWORD))))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("accessToken").asText();
    }

    /** One mobile submit carrying the given per-asset values. */
    private void submit(User actor, Map<Long, Object> valuesByAsset) throws Exception {
        String token = login(actor);
        List<Map<String, Object>> entries = valuesByAsset.entrySet().stream()
                .map(e -> Map.<String, Object>of(
                        "assetId", e.getKey(),
                        "formData", Map.of("temp", e.getValue())))
                .toList();
        Map<String, Object> dto = Map.of(
                "serverId", sheet.getId(),
                "localId", "local-" + System.nanoTime(),
                "clientActionId", "action-" + System.nanoTime(),
                "completedAt", System.currentTimeMillis(),
                "entries", entries);

        mockMvc.perform(post("/api/log-sheets/batch")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("logSheets", List.of(dto)))))
                .andExpect(status().isOk());
    }

    private LogSheetEntry entryFor(Long assetId) {
        return logSheetEntryRepository.findByLogSheetId(sheet.getId()).stream()
                .filter(e -> assetId.equals(e.getAssetId()))
                .findFirst().orElseThrow();
    }

    // ── The scenario ─────────────────────────────────────────────────────────

    @Test
    void operatorTwoSeesOperatorOnesValuesButTheyStayCreditedToOperatorOne() throws Exception {
        // Operator 1 fills both assets and submits.
        submit(operatorOne, Map.of(assetAId, "10", assetBId, "20"));
        assertThat(entryFor(assetAId).getFilledByUserId()).isEqualTo(operatorOne.getId());
        assertThat(entryFor(assetBId).getFilledByUserId()).isEqualTo(operatorOne.getId());

        reopenAndReassignToOperatorTwo();

        // Operator 2 resends the whole sheet — as the PWA always does — changing only asset B.
        submit(operatorTwo, Map.of(assetAId, "10", assetBId, "99"));

        assertThat(entryFor(assetAId).getFilledByUserId())
                .as("asset A was never touched by operator 2 and must still name operator 1")
                .isEqualTo(operatorOne.getId());
        assertThat(entryFor(assetBId).getFilledByUserId())
                .as("asset B was genuinely re-recorded by operator 2")
                .isEqualTo(operatorTwo.getId());

        // And the values themselves are the corrected ones.
        assertThat(entryFor(assetAId).getFormData()).containsEntry("temp", "10");
        assertThat(entryFor(assetBId).getFormData()).containsEntry("temp", "99");
    }

    @Test
    void theBundleOperatorTwoReceivesNamesWhoFilledEachAsset() throws Exception {
        submit(operatorOne, Map.of(assetAId, "10", assetBId, "20"));
        reopenAndReassignToOperatorTwo();

        String token = login(operatorTwo);
        MvcResult result = mockMvc.perform(get("/api/log-sheets/" + sheet.getId() + "/bundle")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk()).andReturn();

        JsonNode entries = objectMapper.readTree(result.getResponse().getContentAsString()).get("entries");
        assertThat(entries).isNotEmpty();
        for (JsonNode entry : entries) {
            assertThat(entry.get("filledByName").asText())
                    .as("every pre-filled row must name its author, or the tablet cannot show it")
                    .isEqualTo(operatorOne.getFullName());
        }
    }

    @Test
    void theEntrySourceIsAlsoPreservedForUntouchedAssets() throws Exception {
        submit(operatorOne, Map.of(assetAId, "10", assetBId, "20"));
        LogSheetEntry before = entryFor(assetAId);
        LogSheetEntrySource originalSource = before.getEntrySource();
        assertThat(originalSource).isNotNull();

        reopenAndReassignToOperatorTwo();
        submit(operatorTwo, Map.of(assetAId, "10", assetBId, "99"));

        assertThat(entryFor(assetAId).getEntrySource())
                .as("how asset A was captured is operator 1's fact, not operator 2's")
                .isEqualTo(originalSource);
    }

    /** A name is resolved for an entry nobody has filled — it must simply be absent. */
    @Test
    void anUnfilledEntryCarriesNoFillerName() throws Exception {
        String token = login(operatorOne);
        MvcResult result = mockMvc.perform(get("/api/log-sheets/" + sheet.getId() + "/bundle")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk()).andReturn();

        for (JsonNode entry : objectMapper.readTree(result.getResponse().getContentAsString()).get("entries")) {
            assertThat(entry.get("filledByName").isNull())
                    .as("nothing has been filled yet")
                    .isTrue();
        }
    }

    // ── Fixture ──────────────────────────────────────────────────────────────

    private void reopenAndReassignToOperatorTwo() {
        LogSheet current = logSheetRepository.findById(sheet.getId()).orElseThrow();
        assertThat(current.getStatus()).isEqualTo(LogSheetStatus.SUBMITTED);
        // Reopen, then hand to operator 2 — the two steps a supervisor performs.
        current.setStatus(LogSheetStatus.IN_PROGRESS);
        current.setSubmittedAt(null);
        current.setCompletedAt(null);
        current.setCompletedByUserId(null);
        current.setDueAt(System.currentTimeMillis() + 3_600_000L);
        current.setAssigneeUserId(operatorTwo.getId());
        current.setAssignmentType(AssignmentType.SUPERVISOR_ASSIGNED);
        logSheetRepository.save(current);
    }

    private void seed() {
        long now = System.currentTimeMillis();
        String suffix = String.valueOf(System.nanoTime());
        Long operatorRoleId = roleRepository.findByCode("OPERATOR").orElseThrow().getId();

        OperationalUnit unit = new OperationalUnit();
        unit.setCode("RU-" + suffix);
        unit.setName("واحد بازتخصیص");
        unit.setCreatedAt(now);
        unit.setUpdatedAt(now);
        unit = operationalUnitRepository.save(unit);

        operatorOne = userService.create("reassign-op1-" + suffix, "اپراتور اول", "PC1-" + suffix,
                null, null, null, null, null, null, PASSWORD, UserAuthType.LOCAL, true,
                List.of(operatorRoleId));
        operatorTwo = userService.create("reassign-op2-" + suffix, "اپراتور دوم", "PC2-" + suffix,
                null, null, null, null, null, null, PASSWORD, UserAuthType.LOCAL, true,
                List.of(operatorRoleId));
        linkOperator(operatorOne.getId(), unit.getId());
        linkOperator(operatorTwo.getId(), unit.getId());

        Location location = new Location();
        location.setCode("RLOC-" + suffix);
        location.setName("Reassign Hall");
        location.setCreatedAt(now);
        location.setUpdatedAt(now);
        location = locationRepository.save(location);

        AssetClass assetClass = new AssetClass();
        assetClass.setName("پمپ بازتخصیص " + suffix);
        assetClass.setCreatedAt(now);
        assetClass.setUpdatedAt(now);
        assetClass = assetClassRepository.save(assetClass);

        FieldDefinition temp = new FieldDefinition();
        temp.setClassId(assetClass.getId());
        temp.setKey("temp");
        temp.setLabel("دما");
        temp.setDataType("number");
        temp.setRequired(false);
        temp.setOrder(1);
        temp.setVersion(1);
        temp.setCreatedAt(now);
        temp.setUpdatedAt(now);
        temp = fieldDefinitionRepository.save(temp);

        assetAId = saveAsset(location.getId(), assetClass.getId(), "A", suffix, now);
        assetBId = saveAsset(location.getId(), assetClass.getId(), "B", suffix, now);

        sheet = new LogSheet();
        sheet.setTemplateName("راند بازتخصیص");
        sheet.setOperationalUnitId(unit.getId());
        sheet.setStatus(LogSheetStatus.IN_PROGRESS);
        sheet.setOrigin(GenerationMode.MANUAL);
        sheet.setAssigneeUserId(operatorOne.getId());
        sheet.setAssignmentType(AssignmentType.SUPERVISOR_ASSIGNED);
        sheet.setAssignedAt(now);
        sheet.setDueAt(now + 3_600_000L);
        sheet.setFieldDefinitionsSnapshot(List.of(FieldDefinitionSnapshot.from(temp)));
        sheet.setCreatedAt(now);
        sheet.setUpdatedAt(now);
        sheet = logSheetRepository.save(sheet);

        for (Long assetId : List.of(assetAId, assetBId)) {
            LogSheetEntry entry = new LogSheetEntry();
            entry.setLogSheetId(sheet.getId());
            entry.setAssetId(assetId);
            entry.setAssetName("Asset " + assetId);
            entry.setClassId(assetClass.getId());
            entry.setFormData(new java.util.HashMap<>());
            entry.setCreatedAt(now);
            entry.setUpdatedAt(now);
            logSheetEntryRepository.save(entry);
        }
    }

    private void linkOperator(Long userId, Long unitId) {
        var link = new com.hnp.backendofflinefirst.entity.UnitOperator();
        link.setUserId(userId);
        link.setUnitId(unitId);
        unitOperatorRepository.save(link);
    }

    private Long saveAsset(Long locationId, Long classId, String tag, String suffix, long now) {
        SubFunction subFunction = new SubFunction();
        subFunction.setCode("RSF-" + tag + "-" + suffix);
        subFunction.setName("Sub " + tag);
        subFunction.setTag("NFC-R-" + tag + "-" + suffix);
        subFunction.setCreatedAt(now);
        subFunction.setUpdatedAt(now);
        hierarchyService.applySubFunctionParent(
                subFunction, AssetHierarchyService.SCOPE_LOCATION, locationId);
        subFunction = hierarchyService.saveSubFunction(subFunction);

        AssetEntry asset = new AssetEntry();
        asset.setAssetCode("RAST-" + tag + "-" + suffix);
        asset.setAssetName("Reassign Asset " + tag);
        asset.setClassId(classId);
        asset.setSubFunctionId(subFunction.getId());
        asset.setCreatedAt(now);
        asset.setUpdatedAt(now);
        return assetEntryRepository.save(asset).getId();
    }
}
