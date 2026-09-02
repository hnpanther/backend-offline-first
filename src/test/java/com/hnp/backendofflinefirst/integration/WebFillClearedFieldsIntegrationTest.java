package com.hnp.backendofflinefirst.integration;

import com.hnp.backendofflinefirst.domain.AssignmentType;
import com.hnp.backendofflinefirst.domain.GenerationMode;
import com.hnp.backendofflinefirst.domain.LogSheetStatus;
import com.hnp.backendofflinefirst.entity.*;
import com.hnp.backendofflinefirst.repository.*;
import com.hnp.backendofflinefirst.service.AssetHierarchyService;
import com.hnp.backendofflinefirst.service.LogSheetGenerationService;
import com.hnp.backendofflinefirst.support.AbstractPostgresIntegrationTest;
import com.hnp.backendofflinefirst.support.WithAppUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Clearing every field on one asset, from the web fill dialog.
 *
 * <h2>The defect these were written against</h2>
 *
 * <p>A form does not post a {@code <select multiple>} with nothing selected, and an unticked
 * checkbox posts only its hidden {@code false}. For almost every asset that does not matter: an
 * empty text input still posts {@code ""}, so the entry's key is present in the request regardless
 * and {@code applyWebEntryValues} sees it. But on an asset whose fields are <em>all</em>
 * multiselects, clearing all of them left the request carrying <b>no parameters for that entry at
 * all</b>. The server cannot tell that from "this asset was not submitted": {@code parseEntryValues}
 * produced no map for it, {@code applyWebEntryValues} skipped it — and the dialog reported success
 * having written nothing.
 *
 * <p>The operator sees «ذخیره شد», closes the dialog, and the old readings are still stored. There
 * is nothing on screen to suggest otherwise; the summary that comes back is rendered from what is
 * in the database, which is the value they just tried to remove.
 *
 * <h2>The fix these pin</h2>
 *
 * <p>Absence is no longer the signal. Each dialog posts {@code fd_present_<entryId>=1}, so the
 * server is <em>told</em> that the asset was submitted and can distinguish the two states it could
 * previously only guess between:
 *
 * <table>
 *   <tr><th>Request</th><th>Means</th></tr>
 *   <tr><td>marker + field parameters</td><td>submitted, these are the values</td></tr>
 *   <tr><td>marker, no field parameters</td><td>submitted, everything cleared</td></tr>
 *   <tr><td>no marker</td><td><b>not submitted</b> — leave this asset alone</td></tr>
 * </table>
 *
 * <p>That last row is not a leftover, and it is tested as hard as the other two: a dialog save
 * names one asset and every other asset on the sheet depends on being skipped.
 */
class WebFillClearedFieldsIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired WebApplicationContext context;
    @Autowired LogSheetEntryRepository logSheetEntryRepository;
    @Autowired LogSheetEntryRevisionRepository revisionRepository;
    @Autowired LogSheetRepository logSheetRepository;
    @Autowired UserRepository userRepository;
    @Autowired RoleRepository roleRepository;
    @Autowired UserRoleRepository userRoleRepository;
    @Autowired UnitOperatorRepository unitOperatorRepository;
    @Autowired OperationalUnitRepository operationalUnitRepository;
    @Autowired LocationRepository locationRepository;
    @Autowired SubFunctionRepository subFunctionRepository;
    @Autowired AssetClassRepository assetClassRepository;
    @Autowired AssetEntryRepository assetEntryRepository;
    @Autowired FieldDefinitionRepository fieldDefinitionRepository;
    @Autowired LogSheetTemplateRepository templateRepository;
    @Autowired AssetHierarchyService hierarchyService;
    @Autowired LogSheetGenerationService generationService;

    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    private static final String FILL = "CAP:LOGSHEET_COMPLETE_WEB_ANY";
    private static final String COMPLETE = "POST:/log-sheets/{id}/complete";

    // ── the defect ──────────────────────────────────────────────────────────────────────────

    @Test
    @WithAppUser(roles = "ADMIN", authorities = {FILL, COMPLETE})
    void clearingEveryFieldOnAnAssetActuallyClearsThem() throws Exception {
        // The exact request a browser sends when both multiselects are deselected: the marker,
        // and nothing else. Before the marker existed this request was indistinguishable from a
        // save that did not mention this asset, and it wrote nothing.
        Fixture f = seedAllMultiselect();
        saveShiftsAndModes(f);
        assertThat(entry(f.firstEntryId()).getFormData()).containsKeys("shifts", "modes");

        clear(f.sheetId(), f.firstEntryId());

        assertThat(entry(f.firstEntryId()).getFormData())
                .as("both multiselects were deselected, so nothing should be stored")
                .isEmpty();
    }

    @Test
    @WithAppUser(roles = "ADMIN", authorities = {FILL, COMPLETE})
    void theSummaryThatComesBackSaysTheParametersAreUnanswered() throws Exception {
        // The half the operator actually sees. The card's summary is re-rendered from what is
        // stored, so a save that wrote nothing would answer with the old values still in place —
        // which is precisely why the defect was invisible.
        Fixture f = seedAllMultiselect();
        saveShiftsAndModes(f);

        mockMvc.perform(post("/log-sheets/{id}/entries/{entryId}/draft", f.sheetId(), f.firstEntryId())
                        .param("fd_present_" + f.firstEntryId(), "1")
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("ثبت نشده")))
                .andExpect(content().string(not(containsString("AUTO"))));
    }

    @Test
    @WithAppUser(roles = "ADMIN", authorities = {FILL, COMPLETE})
    void clearingKeepsWhatItReplaced() throws Exception {
        // A clear destroys a reading somebody took, so it is exactly the case the revision table
        // exists for. Storing `{}` without a revision would make this the one way to lose a
        // value with no trace — worse than the defect it replaces.
        Fixture f = seedAllMultiselect();
        saveShiftsAndModes(f);

        clear(f.sheetId(), f.firstEntryId());

        List<LogSheetEntryRevision> history =
                revisionRepository.findByLogSheetEntryIdOrderByIdAsc(f.firstEntryId());
        assertThat(history).hasSize(1);
        assertThat(history.get(0).getFormData())
                .containsEntry("shifts", List.of("A", "B"))
                .containsEntry("modes", "AUTO");
    }

    @Test
    @WithAppUser(roles = "ADMIN", authorities = {FILL, COMPLETE})
    void clearingAnAssetThatWasAlreadyEmptyWritesNoHistory() throws Exception {
        // The counterweight to the test above. Confirming an empty dialog replaces nothing, and a
        // revision row would claim a correction that never happened.
        Fixture f = seedAllMultiselect();

        clear(f.sheetId(), f.firstEntryId());

        assertThat(revisionRepository.findByLogSheetEntryIdOrderByIdAsc(f.firstEntryId())).isEmpty();
        assertThat(entry(f.firstEntryId()).getFormData()).isEmpty();
    }

    // ── the path that already worked, which must keep working ───────────────────────────────

    @Test
    @WithAppUser(roles = "ADMIN", authorities = {FILL, COMPLETE})
    void clearingOneMultiselectAmongOtherFieldsStillWorks() throws Exception {
        // This never failed: the text input posts `""`, so the entry was present in the request
        // and the absent multiselect key was correctly read as a removal. Asserted because the
        // marker must not change how a partially-cleared asset behaves.
        Fixture f = seedMixed();
        mockMvc.perform(post("/log-sheets/{id}/entries/{entryId}/draft", f.sheetId(), f.firstEntryId())
                        .param("fd_present_" + f.firstEntryId(), "1")
                        .param("fd_" + f.firstEntryId() + "_shifts", "A")
                        .param("fd_" + f.firstEntryId() + "_note", "before")
                        .with(csrf()))
                .andExpect(status().isOk());

        mockMvc.perform(post("/log-sheets/{id}/entries/{entryId}/draft", f.sheetId(), f.firstEntryId())
                        .param("fd_present_" + f.firstEntryId(), "1")
                        .param("fd_" + f.firstEntryId() + "_note", "after")
                        .with(csrf()))
                .andExpect(status().isOk());

        Map<String, Object> stored = entry(f.firstEntryId()).getFormData();
        assertThat(stored).containsEntry("note", "after");
        assertThat(stored).doesNotContainKey("shifts");
    }

    @Test
    @WithAppUser(roles = "ADMIN", authorities = {FILL, COMPLETE})
    void aNormalSaveIsUnaffectedByTheMarkerTravellingWithIt() throws Exception {
        // The marker rides on every dialog save, not only on the empty ones. It must be invisible
        // in the ordinary case — never stored as a field, never counted as an answer.
        Fixture f = seedMixed();

        mockMvc.perform(post("/log-sheets/{id}/entries/{entryId}/draft", f.sheetId(), f.firstEntryId())
                        .param("fd_present_" + f.firstEntryId(), "1")
                        .param("fd_" + f.firstEntryId() + "_note", "42")
                        .with(csrf()))
                .andExpect(status().isOk());

        Map<String, Object> stored = entry(f.firstEntryId()).getFormData();
        assertThat(stored).containsEntry("note", "42");
        assertThat(stored).hasSize(1);
        assertThat(stored.keySet())
                .as("the marker is a protocol detail and must never reach form_data")
                .noneMatch(key -> key.contains("present"));
    }

    // ── absence still means "not submitted", which everything else depends on ────────────────

    @Test
    @WithAppUser(roles = "ADMIN", authorities = {FILL, COMPLETE})
    void anAssetWithNoMarkerIsLeftAloneRatherThanCleared() throws Exception {
        // The rule the fix rests on, from the other side. If a missing marker were read as
        // "cleared", one dialog save would blank every other asset on the sheet.
        Fixture f = seedAllMultiselect();
        saveShiftsAndModes(f);
        LogSheetEntry before = entry(f.firstEntryId());

        mockMvc.perform(post("/log-sheets/{id}/entries/{entryId}/draft", f.sheetId(), f.firstEntryId())
                        .with(csrf()))
                .andExpect(status().isOk());

        LogSheetEntry after = entry(f.firstEntryId());
        assertThat(after.getFormData()).containsKeys("shifts", "modes");
        assertThat(after.getUpdatedAt()).isEqualTo(before.getUpdatedAt());
        assertThat(revisionRepository.findByLogSheetEntryIdOrderByIdAsc(f.firstEntryId())).isEmpty();
    }

    @Test
    @WithAppUser(roles = "ADMIN", authorities = {FILL, COMPLETE})
    void clearingOneAssetLeavesEveryOtherAssetOnTheSheetAlone() throws Exception {
        Fixture f = seedAllMultiselect();
        saveShiftsAndModes(f);
        mockMvc.perform(post("/log-sheets/{id}/entries/{entryId}/draft", f.sheetId(), f.secondEntryId())
                        .param("fd_present_" + f.secondEntryId(), "1")
                        .param("fd_" + f.secondEntryId() + "_shifts", "B")
                        .with(csrf()))
                .andExpect(status().isOk());
        LogSheetEntry otherBefore = entry(f.secondEntryId());

        clear(f.sheetId(), f.firstEntryId());

        LogSheetEntry otherAfter = entry(f.secondEntryId());
        assertThat(otherAfter.getFormData()).containsEntry("shifts", "B");
        assertThat(otherAfter.getUpdatedAt())
                .as("clearing one asset must not re-stamp another")
                .isEqualTo(otherBefore.getUpdatedAt());
    }

    @Test
    @WithAppUser(roles = "ADMIN", authorities = {FILL, COMPLETE})
    void aMarkerSmuggledForAnotherAssetClearsNothing() throws Exception {
        // Same rule as the existing dialog tests: the path decides what is written, not the body.
        // The marker is a parameter like any other and must not become a way to blank an asset
        // the request does not name.
        Fixture f = seedAllMultiselect();
        mockMvc.perform(post("/log-sheets/{id}/entries/{entryId}/draft", f.sheetId(), f.secondEntryId())
                        .param("fd_present_" + f.secondEntryId(), "1")
                        .param("fd_" + f.secondEntryId() + "_shifts", "B")
                        .with(csrf()))
                .andExpect(status().isOk());

        mockMvc.perform(post("/log-sheets/{id}/entries/{entryId}/draft", f.sheetId(), f.firstEntryId())
                        .param("fd_present_" + f.firstEntryId(), "1")
                        .param("fd_present_" + f.secondEntryId(), "1")
                        .with(csrf()))
                .andExpect(status().isOk());

        assertThat(entry(f.secondEntryId()).getFormData())
                .as("the asset named only in the body must keep its reading")
                .containsEntry("shifts", "B");
    }

    // ── the parser is shared, so the other two web endpoints get this too ────────────────────

    @Test
    @WithAppUser(roles = "ADMIN", authorities = {FILL, COMPLETE})
    void theMarkerIsReadByTheFullPageDraftEndpointAsWell() throws Exception {
        // The fix is in `parseEntryValues`, not in the dialog controller, so `/draft` and
        // `/complete` read it identically. Asserted so a later refactor cannot quietly move the
        // handling somewhere only one of the three passes through.
        Fixture f = seedAllMultiselect();
        saveShiftsAndModes(f);

        mockMvc.perform(post("/log-sheets/{id}/draft", f.sheetId())
                        .param("fd_present_" + f.firstEntryId(), "1")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection());

        assertThat(entry(f.firstEntryId()).getFormData()).isEmpty();
    }

    @Test
    @WithAppUser(roles = "ADMIN", authorities = {FILL, COMPLETE})
    void aClearedAssetIsCompletedEmptyRatherThanCarryingTheStaleReadingThrough() throws Exception {
        // The end-to-end consequence, and the reason this mattered beyond one lost edit. The
        // clear used to do nothing, so «تأیید نهایی» sealed the sheet on the value the operator
        // believed they had removed — and a completed sheet is the record.
        //
        // The sheet still completes, and that is correct: an asset with no readings is an
        // unfilled asset, which `validateFilledEntry` allows through by design. What changed is
        // what gets sealed into it.
        Fixture f = seedAllMultiselect();
        saveShiftsAndModes(f);

        clear(f.sheetId(), f.firstEntryId());
        mockMvc.perform(post("/log-sheets/{id}/complete", f.sheetId()).with(csrf()))
                .andExpect(status().is3xxRedirection());

        assertThat(logSheetRepository.findById(f.sheetId()).orElseThrow().getStatus())
                .isEqualTo(LogSheetStatus.SUBMITTED);
        assertThat(entry(f.firstEntryId()).getFormData())
                .as("the completed sheet must record the clear, not the reading it replaced")
                .isEmpty();
    }

    @Test
    @WithAppUser(roles = "ADMIN", authorities = {FILL, COMPLETE})
    void clearingARequiredFieldOnAnAssetStillHoldingOtherAnswersIsRefusedAtSubmission() throws Exception {
        // The other side of the rule above. Emptying the whole asset means "not filled" and
        // passes; emptying only the required parameter, while the asset still carries an answer,
        // is an incomplete reading and must be refused. Asserted because the clear now really
        // happens, so validation is judging what is actually stored for the first time.
        Fixture f = seed(true, true);
        mockMvc.perform(post("/log-sheets/{id}/entries/{entryId}/draft", f.sheetId(), f.firstEntryId())
                        .param("fd_present_" + f.firstEntryId(), "1")
                        .param("fd_" + f.firstEntryId() + "_shifts", "A")
                        .param("fd_" + f.firstEntryId() + "_note", "still here")
                        .with(csrf()))
                .andExpect(status().isOk());

        // The multiselect deselected, the text field left as it was.
        mockMvc.perform(post("/log-sheets/{id}/entries/{entryId}/draft", f.sheetId(), f.firstEntryId())
                        .param("fd_present_" + f.firstEntryId(), "1")
                        .param("fd_" + f.firstEntryId() + "_note", "still here")
                        .with(csrf()))
                .andExpect(status().isOk());

        mockMvc.perform(post("/log-sheets/{id}/complete", f.sheetId()).with(csrf()))
                .andExpect(status().is3xxRedirection());
        assertThat(logSheetRepository.findById(f.sheetId()).orElseThrow().getStatus())
                .as("a required parameter is unanswered on an otherwise filled asset")
                .isNotEqualTo(LogSheetStatus.SUBMITTED);
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────

    /** The request a dialog sends when every field on the asset has been emptied. */
    private void clear(Long sheetId, Long entryId) throws Exception {
        mockMvc.perform(post("/log-sheets/{id}/entries/{entryId}/draft", sheetId, entryId)
                        .param("fd_present_" + entryId, "1")
                        .with(csrf()))
                .andExpect(status().isOk());
    }

    /**
      * Fills the first asset the way the dialog would, with <b>two</b> options on one multiselect
      * and one on the other. Both matter: {@code parseFieldValue} stores a single selected option
      * as a scalar and several as a list, so a clear has to survive either shape.
      */
    private void saveShiftsAndModes(Fixture f) throws Exception {
        mockMvc.perform(post("/log-sheets/{id}/entries/{entryId}/draft", f.sheetId(), f.firstEntryId())
                        .param("fd_present_" + f.firstEntryId(), "1")
                        .param("fd_" + f.firstEntryId() + "_shifts", "A", "B")
                        .param("fd_" + f.firstEntryId() + "_modes", "AUTO")
                        .with(csrf()))
                .andExpect(status().isOk());
    }

    private LogSheetEntry entry(Long entryId) {
        return logSheetEntryRepository.findById(entryId).orElseThrow();
    }

    private record Fixture(Long sheetId, Long firstEntryId, Long secondEntryId) {}

    /** Two multiselects and nothing else — the shape that produced an empty request. */
    private Fixture seedAllMultiselect() {
        return seed(false, false);
    }

    /** A multiselect beside a text field — the shape that always worked. */
    private Fixture seedMixed() {
        return seed(true, false);
    }

    private Fixture seed(boolean withTextField, boolean required) {
        long now = System.currentTimeMillis();
        long nano = System.nanoTime();

        OperationalUnit unit = new OperationalUnit();
        unit.setCode("CLR-BU-" + nano);
        unit.setName("Clear Unit");
        unit.setCreatedAt(now);
        unit.setUpdatedAt(now);
        unit = operationalUnitRepository.saveAndFlush(unit);

        Location location = new Location();
        location.setCode("CLR-LOC-" + nano);
        location.setName("Clear Hall");
        location.setCreatedAt(now);
        location.setUpdatedAt(now);
        location = locationRepository.saveAndFlush(location);

        AssetClass assetClass = new AssetClass();
        assetClass.setName("Clear Pump " + nano);
        assetClass.setCreatedAt(now);
        assetClass.setUpdatedAt(now);
        assetClass = assetClassRepository.saveAndFlush(assetClass);

        multiselect(assetClass.getId(), "shifts", "Shifts", List.of("A", "B"), required, 1, now);
        if (!required) {
            multiselect(assetClass.getId(), "modes", "Modes", List.of("AUTO", "MANUAL"), false, 2, now);
        }
        if (withTextField) {
            FieldDefinition text = new FieldDefinition();
            text.setClassId(assetClass.getId());
            text.setKey("note");
            text.setLabel("Note");
            text.setDataType("text");
            text.setRequired(false);
            text.setOrder(3);
            text.setCreatedAt(now);
            text.setUpdatedAt(now);
            fieldDefinitionRepository.saveAndFlush(text);
        }

        // One sub-function per asset: `ux_asset_entries_active_sub_function` allows a single
        // active asset per position. See docs/hierarchy.md.
        for (int i = 1; i <= 2; i++) {
            SubFunction subFunction = new SubFunction();
            subFunction.setCode("CLR-SF" + i + "-" + nano);
            subFunction.setName("Clear Sub " + i);
            subFunction.setTag("NFC-CLR" + i + "-" + nano);
            subFunction.setCreatedAt(now);
            subFunction.setUpdatedAt(now);
            hierarchyService.applySubFunctionParent(
                    subFunction, AssetHierarchyService.SCOPE_LOCATION, location.getId());
            subFunction = hierarchyService.saveSubFunction(subFunction);

            AssetEntry asset = new AssetEntry();
            asset.setAssetCode("CLR-A" + i + "-" + nano);
            asset.setAssetName("Pump " + i);
            asset.setClassId(assetClass.getId());
            asset.setSubFunctionId(subFunction.getId());
            asset.setCreatedAt(now);
            asset.setUpdatedAt(now);
            assetEntryRepository.saveAndFlush(asset);
        }

        LogSheetTemplate template = new LogSheetTemplate();
        template.setName("Clear Template " + nano);
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
        assertThat(entries).as("the fixture needs two assets on the sheet").hasSize(2);
        return new Fixture(sheet.getId(), entries.get(0).getId(), entries.get(1).getId());
    }

    private void multiselect(Long classId, String key, String label, List<String> options,
                             boolean required, int order, long now) {
        FieldDefinition def = new FieldDefinition();
        def.setClassId(classId);
        def.setKey(key);
        def.setLabel(label);
        def.setDataType("multiselect");
        def.setRequired(required);
        def.setValidation(Map.of("options", options));
        def.setOrder(order);
        def.setCreatedAt(now);
        def.setUpdatedAt(now);
        fieldDefinitionRepository.saveAndFlush(def);
    }

    private User operator(Long unitId, long nano) {
        long now = System.currentTimeMillis();
        User user = new User();
        user.setUsername("clr-op-" + nano);
        user.setPersonnelCode("PC-" + UUID.randomUUID());
        user.setFullName("Clear Operator");
        user.setPasswordHash("{noop}x");
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
}
