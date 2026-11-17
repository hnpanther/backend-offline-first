package com.hnp.backendofflinefirst.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hnp.backendofflinefirst.domain.AssignmentType;
import com.hnp.backendofflinefirst.domain.FieldSource;
import com.hnp.backendofflinefirst.domain.FieldValidationSupport;
import com.hnp.backendofflinefirst.domain.GenerationMode;
import com.hnp.backendofflinefirst.domain.LogSheetStatus;
import com.hnp.backendofflinefirst.domain.LookupValues;
import com.hnp.backendofflinefirst.dto.LogSheetBatchRequest;
import com.hnp.backendofflinefirst.dto.LogSheetContextDto;
import com.hnp.backendofflinefirst.dto.LogSheetDto;
import com.hnp.backendofflinefirst.dto.LogSheetEntryDto;
import com.hnp.backendofflinefirst.entity.*;
import com.hnp.backendofflinefirst.repository.*;
import com.hnp.backendofflinefirst.service.AssetFieldOverrideService;
import com.hnp.backendofflinefirst.service.AssetHierarchyService;
import com.hnp.backendofflinefirst.service.LogSheetBundleService;
import com.hnp.backendofflinefirst.service.LogSheetGenerationService;
import com.hnp.backendofflinefirst.service.LookupListService;
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

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A field that draws from a reference list (roadmap §31 Phase 2), end to end against a real
 * database: the editor, every write path, the panel's search, the round's bundle, the device
 * endpoint and its grant, and the delete guards.
 *
 * <p>The fixture is a «staff» list with two declared columns — {@code unit}, which may go to a
 * device, and {@code nationalId}, which may not — three people (one retired), and an unrelated
 * «other» list no round uses. A class carries a single-choice {@code person} and a multi-choice
 * {@code crew}, both drawing from «staff».
 *
 * <p>The properties that carry the feature, in the order the tests take them:
 * <ul>
 *   <li><b>Nothing an operator records is refused for being unknown or retired</b>, and nothing
 *       stored can be forged: a known code takes the list's label, an unknown one is kept and
 *       flagged, a retired one is kept and flagged. Only a malformed value is refused, with the
 *       correctable outcome.</li>
 *   <li><b>A recorded answer never changes after the fact</b> — re-sending it, or renaming the
 *       item, leaves the stored label and verdict as they were.</li>
 *   <li><b>A list leaves the server only for a reason</b> — the panel search for a round that uses
 *       it, the device endpoint for an open round assigned to the caller — with the offline columns
 *       only, every page logged.</li>
 *   <li><b>A delete never loses anything</b>: what an answer names, what a device may hold, and a
 *       list a field draws from are all refused, with «deactivate» as the way out.</li>
 * </ul>
 */
@Transactional
class LookupFieldIntegrationTest extends AbstractPostgresIntegrationTest {

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
    @Autowired LogSheetBundleService bundleService;
    @Autowired LookupListService lookupListService;
    @Autowired LookupItemRepository lookupItemRepository;
    @Autowired LookupDownloadRepository lookupDownloadRepository;
    @Autowired AssetFieldOverrideService fieldOverrideService;

    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    // ── writing answers ────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a known code takes the list's label; a retired one and an unknown one are kept and flagged")
    void submitStampsLabelAndVerdict() throws Exception {
        Fixture f = seed();
        Map<String, Object> answers = new LinkedHashMap<>();
        answers.put("person", Map.of("code", "1001", "label", "FORGED NAME"));
        answers.put("crew", List.of(Map.of("code", "1002"), Map.of("code", "9999", "label", "از دفترچه")));

        mockMvc.perform(submit(f, answers))
                .andExpect(jsonPath("$[0].outcome").value("SUBMITTED"));

        Map<String, Object> stored = entry(f).getFormData();
        assertThat(stored.get("person")).isEqualTo(Map.of("code", "1001", "label", "علی رضایی", "state", "ACTIVE"));
        assertThat(stored.get("crew")).isEqualTo(List.of(
                Map.of("code", "1002", "label", "مریم احمدی", "state", "INACTIVE"),
                Map.of("code", "9999", "label", "از دفترچه", "state", "UNKNOWN")));
        assertThat(entry(f).getMaxSeverity()).as("a lookup has no severity").isEqualTo("OK");
    }

    @Test
    @DisplayName("a malformed answer is refused with the correctable outcome, worded in Persian")
    void malformedIsCorrectable() throws Exception {
        Fixture f = seed();

        mockMvc.perform(submit(f, Map.of("person", Map.of("label", "free text"))))
                .andExpect(jsonPath("$[0].outcome").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$[0].error").value(containsString("انتخاب معتبری از فهرست مرجع نیست")));
        mockMvc.perform(submit(f, Map.of("person", List.of("1001", "1003"))))
                .andExpect(jsonPath("$[0].outcome").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$[0].error").value(containsString("فقط یک انتخاب می‌پذیرد")));
        assertThat(entry(f).getFormData()).isNullOrEmpty();
    }

    @Test
    @DisplayName("a recorded answer keeps its label and verdict after the item is renamed and retired")
    void recordedAnswersDoNotChange() throws Exception {
        Fixture f = seed();
        assertThat(progress(f, Map.of("person", "۱۰۰۱")).get("outcome").asText()).isEqualTo("SAVED");
        assertThat(entry(f).getFormData().get("person"))
                .isEqualTo(Map.of("code", "1001", "label", "علی رضایی", "state", "ACTIVE"));

        LookupItem ali = item(f, "1001");
        lookupListService.updateItem(f.staffId(), ali.getId(), null, "علی رضایی‌نژاد", Map.of(), null, null);
        lookupListService.setItemActive(f.staffId(), ali.getId(), false);

        // The tablet resends its whole state at submit, the stamped value included.
        mockMvc.perform(submit(f, Map.of("person", Map.of("code", "1001", "label", "علی رضایی", "state", "ACTIVE"))))
                .andExpect(jsonPath("$[0].outcome").value("SUBMITTED"));
        assertThat(entry(f).getFormData().get("person"))
                .as("what was recorded at entry, not what the list says today")
                .isEqualTo(Map.of("code", "1001", "label", "علی رضایی", "state", "ACTIVE"));
    }

    // ── the panel ──────────────────────────────────────────────────────────────────────────

    @Test
    @WithAppUser(roles = "ADMIN", authorities = {FILL, COMPLETE})
    @DisplayName("the panel searches only a list this round uses, offers only active items, and saves the code")
    void panelSearchAndSave() throws Exception {
        Fixture f = seed();

        String body = mockMvc.perform(get("/log-sheets/{id}/lookups/{code}", f.sheetId(), "staff").param("q", "علي"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode found = objectMapper.readTree(body);
        assertThat(found).hasSize(1);
        assertThat(found.get(0).get("code").asText()).isEqualTo("1001");
        assertThat(found.get(0).get("detail").asText())
                .as("display columns only — never the national id").isEqualTo("تعمیرات");
        assertThat(body).doesNotContain("0012345678").doesNotContain("1002");

        mockMvc.perform(get("/log-sheets/{id}/lookups/{code}", f.sheetId(), "other").param("q", ""))
                .andExpect(status().isNotFound());

        Long entryId = entry(f).getId();
        mockMvc.perform(post("/log-sheets/{id}/entries/{entryId}/draft", f.sheetId(), entryId)
                        .param("fd_" + entryId + "_person", "۱۰۰۳")
                        .with(csrf()))
                .andExpect(status().isOk());
        assertThat(entry(f).getFormData().get("person"))
                .isEqualTo(Map.of("code", "1003", "label", "رضا کریمی", "state", "ACTIVE"));
    }

    @Test
    @WithAppUser(roles = "ADMIN", authorities = {"GET:/log-sheets/{id}/fill", "GET:/log-sheets/{id}", FILL, COMPLETE})
    @DisplayName("the fill page renders the picker with the recorded answer as a chip that posts its code")
    void fillPageRendersThePicker() throws Exception {
        Fixture f = seed();
        // Recorded through the panel's own dialog: under @WithAppUser the mocked principal wins
        // over a bearer token, so a tablet-style submit here would be refused as somebody else's.
        Long entryId = entry(f).getId();
        mockMvc.perform(post("/log-sheets/{id}/entries/{entryId}/draft", f.sheetId(), entryId)
                        .param("fd_" + entryId + "_person", "1001")
                        .with(csrf()))
                .andExpect(status().isOk());

        String html = mockMvc.perform(get("/log-sheets/{id}/fill", f.sheetId()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(html)
                .contains("lookup-field")
                .contains("/log-sheets/" + f.sheetId() + "/lookups/staff")
                .as("the URL names the list as the field does; the server matches it ignoring case")
                .contains("علی رضایی (1001)")
                .contains("value=\"1001\"");
    }

    // ── the device ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("the bundle names the lists the round needs, with their revision — never their items")
    void bundleDeclaresRequiredLists() {
        Fixture f = seed();
        LogSheetContextDto context = bundleService.buildFullBundle(
                logSheetRepository.findById(f.sheetId()).orElseThrow()).getContext();
        assertThat(context.getRequiredLookups())
                .as("the list's canonical code — the field names it in lower case")
                .extracting(LogSheetContextDto.RequiredLookup::code).containsExactly("STAFF");
        assertThat(context.getRequiredLookups().get(0).revision()).isPositive();
    }

    @Test
    @DisplayName("a device gets the list page by page, offline columns only, every page logged, with a lease")
    void deviceGetsTheListWithAGrant() throws Exception {
        Fixture f = seed();
        String token = token(f);
        long before = System.currentTimeMillis();

        JsonNode first = deviceItems(token, "staff", 0, 0, 2, 200);
        assertThat(first.get("items")).hasSize(2);
        assertThat(first.get("hasMore").asBoolean()).isTrue();
        assertThat(first.get("leaseUntil").asLong()).isBetween(before + 23 * 3_600_000L, before + 25 * 3_600_000L + 60_000);
        assertThat(first.get("columns")).hasSize(1);
        assertThat(first.get("columns").get(0).get("key").asText()).isEqualTo("unit");
        assertThat(first.toString()).doesNotContain("0012345678").doesNotContain("nationalId");

        JsonNode second = deviceItems(token, "staff", first.get("nextSince").asLong(),
                first.get("nextAfterId").asLong(), 2, 200);
        assertThat(second.get("items")).hasSize(1);
        assertThat(second.get("hasMore").asBoolean()).isFalse();
        assertThat(second.get("items").get(0).get("code").asText())
                .as("the half-open cursor: no item twice, none skipped")
                .isNotIn(first.get("items").get(0).get("code").asText(), first.get("items").get(1).get("code").asText());

        boolean retiredIncluded = false;
        for (JsonNode page : List.of(first, second)) {
            for (JsonNode item : page.get("items")) {
                if ("1002".equals(item.get("code").asText())) {
                    retiredIncluded = !item.get("active").asBoolean();
                }
            }
        }
        assertThat(retiredIncluded).as("a deactivation reaches the device as an update").isTrue();
        assertThat(lookupDownloadRepository.countByListId(f.staffId())).isEqualTo(2);
    }

    @Test
    @DisplayName("no open round assigned to the caller uses the list: refused, whether or not it exists")
    void deviceIsRefusedWithoutAGrant() throws Exception {
        Fixture f = seed();
        String token = token(f);

        deviceItems(token, "other", 0, 0, 10, 403);
        deviceItems(token, "no-such-list", 0, 0, 10, 403);

        mockMvc.perform(submit(f, Map.of("person", "1001")))
                .andExpect(jsonPath("$[0].outcome").value("SUBMITTED"));
        // A fresh token: each login replaces the user's previous API session.
        deviceItems(token(f), "staff", 0, 0, 10, 403);
        assertThat(lookupDownloadRepository.countByListId(f.staffId()))
                .as("a refused request is not a download").isZero();
    }

    // ── deleting ───────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("an item an answer names, or a device may hold, is never deleted; a fresh unused one is")
    void itemDeleteGuards() throws Exception {
        Fixture f = seed();
        mockMvc.perform(submit(f, Map.of("person", "1003")));

        LookupItem named = item(f, "1003");
        assertThatThrownBy(() -> lookupListService.deleteItem(f.staffId(), named.getId()))
                .hasMessage("A recorded answer names this lookup item.");

        LookupItem fresh = lookupListService.createItem(f.staffId(), "2001", "تازه", Map.of(), null, null);
        lookupListService.deleteItem(f.staffId(), fresh.getId());
        assertThat(lookupItemRepository.findById(fresh.getId())).isEmpty();

        // Reopen the round so the operator may fetch the list, and let a device fetch it.
        LogSheet sheet = logSheetRepository.findById(f.sheetId()).orElseThrow();
        sheet.setStatus(LogSheetStatus.IN_PROGRESS);
        logSheetRepository.saveAndFlush(sheet);
        LookupItem fetched = lookupListService.createItem(f.staffId(), "2002", "روی دستگاه", Map.of(), null, null);
        deviceItems(token(f), "staff", 0, 0, 100, 200);
        assertThatThrownBy(() -> lookupListService.deleteItem(f.staffId(), fetched.getId()))
                .hasMessage("This lookup item may be on a device.");
        assertThat(lookupItemRepository.findById(fetched.getId())).as("still there").isPresent();
    }

    @Test
    @DisplayName("a list a field draws from cannot be deleted, even empty")
    void listDeleteGuard() {
        Fixture f = seed();
        LookupList empty = lookupListService.createList("empty-" + f.nano(), "خالی", null, null);
        FieldDefinition def = fieldDefinitionRepository.findByClassId(f.classId()).get(0);
        Map<String, Object> v = new LinkedHashMap<>(def.getValidation());
        v.put(FieldValidationSupport.KEY_LOOKUP_LIST, empty.getCode());
        def.setValidation(v);
        fieldDefinitionRepository.saveAndFlush(def);

        assertThatThrownBy(() -> lookupListService.deleteList(empty.getId()))
                .hasMessage("A form field draws from this lookup list.");
    }

    // ── the editors ────────────────────────────────────────────────────────────────────────

    @Test
    @WithAppUser(roles = "ADMIN", authorities = {"POST:/asset-classes/{classId}/fields",
            "POST:/asset-classes/{classId}/fields/{fieldId}"})
    @DisplayName("the class editor stores the list, keeps it through an unrelated edit, and refuses what cannot work")
    void editorStoresAndKeepsTheList() throws Exception {
        Fixture f = seed();

        mockMvc.perform(post("/asset-classes/{classId}/fields", f.classId())
                        .param("key", "inspector").param("label", "بازرس").param("dataType", "lookup")
                        .param("lookupList", "STAFF").param("order", "5")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection());
        FieldDefinition inspector = fieldDefinitionRepository.findByClassId(f.classId()).stream()
                .filter(d -> "inspector".equals(d.getKey())).findFirst().orElseThrow();
        assertThat(FieldValidationSupport.lookupList(inspector.getValidation()))
                .as("stored as the list's own code, whatever case was posted").isEqualTo("STAFF");

        mockMvc.perform(post("/asset-classes/{classId}/fields/{fieldId}", f.classId(), inspector.getId())
                        .param("key", "inspector").param("label", "بازرس شیفت").param("dataType", "lookup")
                        .param("lookupList", "staff").param("order", "5")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection());
        assertThat(FieldValidationSupport.lookupList(
                fieldDefinitionRepository.findById(inspector.getId()).orElseThrow().getValidation()))
                .isEqualTo("STAFF");

        int before = fieldDefinitionRepository.findByClassId(f.classId()).size();
        mockMvc.perform(post("/asset-classes/{classId}/fields", f.classId())
                        .param("key", "nolist").param("label", "x").param("dataType", "lookup").with(csrf()))
                .andExpect(status().is3xxRedirection());
        mockMvc.perform(post("/asset-classes/{classId}/fields", f.classId())
                        .param("key", "badlist").param("label", "x").param("dataType", "lookup")
                        .param("lookupList", "does-not-exist").with(csrf()))
                .andExpect(status().is3xxRedirection());
        mockMvc.perform(post("/asset-classes/{classId}/fields", f.classId())
                        .param("key", "status").param("label", "وضعیت").param("dataType", "lookup")
                        .param("lookupList", "staff").with(csrf()))
                .andExpect(status().is3xxRedirection());
        assertThat(fieldDefinitionRepository.findByClassId(f.classId())).hasSize(before);
    }

    @Test
    @WithAppUser(roles = "ADMIN", authorities = {"POST:/asset-classes/{classId}/fields",
            "POST:/asset-classes/{classId}/fields/{fieldId}", "GET:/log-sheets/{id}/fill",
            "GET:/log-sheets/{id}", FILL, COMPLETE})
    @DisplayName("«use this code» survives an unrelated edit, is cleared by unticking, and reaches the fill page")
    void acceptsUnlistedCodeRoundTrip() throws Exception {
        Fixture f = seed();
        FieldDefinition person = fieldDefinitionRepository.findByClassId(f.classId()).stream()
                .filter(d -> "person".equals(d.getKey())).findFirst().orElseThrow();

        mockMvc.perform(post("/asset-classes/{classId}/fields/{fieldId}", f.classId(), person.getId())
                        .param("key", "person").param("label", "person").param("dataType", "lookup")
                        .param("lookupList", "staff").param("order", "1")
                        .param("_lookupAcceptsUnlistedCode", "on").param("lookupAcceptsUnlistedCode", "true")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection());
        assertThat(FieldValidationSupport.lookupAcceptsUnlistedCode(
                fieldDefinitionRepository.findById(person.getId()).orElseThrow().getValidation())).isTrue();

        // An edit of the label alone, with the box still ticked — the rebuild must keep it.
        mockMvc.perform(post("/asset-classes/{classId}/fields/{fieldId}", f.classId(), person.getId())
                        .param("key", "person").param("label", "بازدیدکننده").param("dataType", "lookup")
                        .param("lookupList", "staff").param("order", "1")
                        .param("_lookupAcceptsUnlistedCode", "on").param("lookupAcceptsUnlistedCode", "true")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection());
        assertThat(FieldValidationSupport.lookupAcceptsUnlistedCode(
                fieldDefinitionRepository.findById(person.getId()).orElseThrow().getValidation())).isTrue();

        // A round raised now freezes it, and the fill page tells the picker.
        LogSheetTemplate template = templateRepository.findAll().stream()
                .filter(t -> f.classId().equals(t.getClassId())).findFirst().orElseThrow();
        LogSheet raised = generationService.generateFromTemplate(template, GenerationMode.MANUAL, null,
                System.currentTimeMillis());
        raised.setStatus(LogSheetStatus.IN_PROGRESS);
        logSheetRepository.saveAndFlush(raised);
        String html = mockMvc.perform(get("/log-sheets/{id}/fill", raised.getId()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(html).contains("data-accept-code=\"true\"");

        // Unticked: the marker posts alone, and the setting is gone.
        mockMvc.perform(post("/asset-classes/{classId}/fields/{fieldId}", f.classId(), person.getId())
                        .param("key", "person").param("label", "بازدیدکننده").param("dataType", "lookup")
                        .param("lookupList", "staff").param("order", "1")
                        .param("_lookupAcceptsUnlistedCode", "on")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection());
        assertThat(FieldValidationSupport.lookupAcceptsUnlistedCode(
                fieldDefinitionRepository.findById(person.getId()).orElseThrow().getValidation())).isFalse();
    }

    @Test
    @DisplayName("an asset cannot add a lookup parameter of its own — the list is the class's to name")
    void assetCannotAddALookup() {
        Fixture f = seed();
        Map<String, Object> added = new LinkedHashMap<>();
        added.put("label", "x");
        added.put("dataType", "lookup");
        assertThatThrownBy(() -> fieldOverrideService.save(f.assetId(), FieldSource.FULL, Map.of("extra", added)))
                .hasMessageContaining("a lookup parameter is defined on the class");
    }

    // ── helpers ────────────────────────────────────────────────────────────────────────────

    private LogSheetEntry entry(Fixture f) {
        return logSheetEntryRepository.findByLogSheetId(f.sheetId()).get(0);
    }

    private LookupItem item(Fixture f, String code) {
        return lookupItemRepository.findByListIdAndCodeIgnoreCase(f.staffId(), code).orElseThrow();
    }

    private JsonNode deviceItems(String token, String code, long since, long afterId, int limit, int expected)
            throws Exception {
        MvcResult result = mockMvc.perform(get("/api/lookups/{code}/items", code)
                        .param("since", String.valueOf(since))
                        .param("afterId", String.valueOf(afterId))
                        .param("limit", String.valueOf(limit))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().is(expected))
                .andReturn();
        return expected == 200 ? objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8)) : null;
    }

    private org.springframework.test.web.servlet.RequestBuilder submit(Fixture f, Map<String, Object> answers)
            throws Exception {
        LogSheetDto dto = new LogSheetDto();
        dto.setServerId(f.sheetId());
        dto.setLocalId("local-" + UUID.randomUUID());
        dto.setCompletedAt(System.currentTimeMillis());
        dto.setClientActionId("client-" + UUID.randomUUID());
        LogSheetEntryDto e = new LogSheetEntryDto();
        e.setAssetId(f.assetId());
        e.setFormData(answers);
        dto.setEntries(List.of(e));
        LogSheetBatchRequest request = new LogSheetBatchRequest();
        request.setLogSheets(List.of(dto));
        return post("/api/log-sheets/batch")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(f))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request));
    }

    private JsonNode progress(Fixture f, Map<String, Object> answers) throws Exception {
        String body = """
                {"logSheets":[{"serverId":%d,"localId":"local-1","operatorName":"Op","entries":[{"assetId":%d,"formData":%s}]}]}
                """.formatted(f.sheetId(), f.assetId(), objectMapper.writeValueAsString(answers));
        MvcResult result = mockMvc.perform(post("/api/log-sheets/progress")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(f))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8)).get(0);
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

    private record Fixture(Long sheetId, Long classId, Long assetId, Long staffId, String operatorUsername, long nano) {}

    private Fixture seed() {
        long now = System.currentTimeMillis();
        long nano = System.nanoTime();

        LookupList staff = lookupListService.findAllForPicker().stream()
                .filter(l -> "staff".equalsIgnoreCase(l.getCode())).findFirst()
                .orElseGet(() -> lookupListService.createList("staff", "پرسنل", null, null));
        if (staff.getColumns() == null || staff.getColumns().isEmpty()) {
            lookupListService.addColumn(staff.getId(), "unit", "واحد", true, true, true);
            lookupListService.addColumn(staff.getId(), "nationalId", "کد ملی", false, false, false);
            lookupListService.createItem(staff.getId(), "1001", "علی رضایی",
                    Map.of("unit", "تعمیرات", "nationalId", "0012345678"), 1, null);
            LookupItem maryam = lookupListService.createItem(staff.getId(), "1002", "مریم احمدی",
                    Map.of("unit", "بهره‌برداری"), 2, null);
            lookupListService.setItemActive(staff.getId(), maryam.getId(), false);
            lookupListService.createItem(staff.getId(), "1003", "رضا کریمی", Map.of(), 3, null);
            lookupListService.createList("other", "دیگر", null, null);
        }

        OperationalUnit unit = new OperationalUnit();
        unit.setCode("LKP-BU-" + nano);
        unit.setName("Lookup Unit");
        unit.setCreatedAt(now);
        unit.setUpdatedAt(now);
        unit = operationalUnitRepository.saveAndFlush(unit);

        Location location = new Location();
        location.setCode("LKP-LOC-" + nano);
        location.setName("Lookup Hall");
        location.setCreatedAt(now);
        location.setUpdatedAt(now);
        location = locationRepository.saveAndFlush(location);

        AssetClass assetClass = new AssetClass();
        assetClass.setName("Permit " + nano);
        assetClass.setCreatedAt(now);
        assetClass.setUpdatedAt(now);
        assetClass = assetClassRepository.saveAndFlush(assetClass);

        for (String[] spec : new String[][]{{"person", "lookup", "1"}, {"crew", "lookupmulti", "2"}}) {
            FieldDefinition def = new FieldDefinition();
            def.setClassId(assetClass.getId());
            def.setKey(spec[0]);
            def.setLabel(spec[0]);
            def.setDataType(spec[1]);
            def.setRequired(false);
            def.setOrder(Integer.parseInt(spec[2]));
            def.setValidation(FieldValidationSupport.build(spec[1], null, null, null, null, null, null, null,
                    null, true, null, new FieldValidationSupport.FieldBehaviour(false, null, null, "staff")));
            def.setCreatedAt(now);
            def.setUpdatedAt(now);
            fieldDefinitionRepository.saveAndFlush(def);
        }

        SubFunction subFunction = new SubFunction();
        subFunction.setCode("LKP-SF-" + nano);
        subFunction.setName("Lookup Sub");
        subFunction.setTag("NFC-LKP-" + nano);
        subFunction.setCreatedAt(now);
        subFunction.setUpdatedAt(now);
        hierarchyService.applySubFunctionParent(subFunction, AssetHierarchyService.SCOPE_LOCATION, location.getId());
        subFunction = hierarchyService.saveSubFunction(subFunction);

        AssetEntry asset = new AssetEntry();
        asset.setAssetCode("LKP-A-" + nano);
        asset.setAssetName("Permit board");
        asset.setClassId(assetClass.getId());
        asset.setSubFunctionId(subFunction.getId());
        asset.setCreatedAt(now);
        asset.setUpdatedAt(now);
        asset = assetEntryRepository.saveAndFlush(asset);

        LogSheetTemplate template = new LogSheetTemplate();
        template.setName("Lookup Template " + nano);
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

        return new Fixture(sheet.getId(), assetClass.getId(), asset.getId(), staff.getId(), operator.getUsername(), nano);
    }

    private User operator(Long unitId, long nano) {
        long now = System.currentTimeMillis();
        User user = new User();
        user.setUsername("lkp-op-" + nano);
        user.setPersonnelCode("PC-" + UUID.randomUUID());
        user.setFullName("Lookup Operator");
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
