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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The web fill page edits one asset at a time, in a dialog that saves itself.
 *
 * <p>The page used to hold every asset's inputs in one form and submit the lot. It now shows each
 * asset's stored readings read-only and opens a dialog to change them, and confirming that dialog
 * posts <em>that asset alone</em> to {@code POST /log-sheets/{id}/entries/{entryId}/draft}.
 *
 * <p>Three things had to stay true through that change, and each is asserted here rather than
 * assumed from the fact that the underlying service was not modified:
 *
 * <ol>
 *   <li><b>History.</b> Replacing a reading somebody already took still writes a revision, and
 *       recording one for the first time still does not. This is the property the request named
 *       explicitly, and it comes from {@code applyWebEntryValues} — the same code the old form
 *       used — but a per-entry map is a new shape for it.</li>
 *   <li><b>Isolation.</b> A save that names one asset must not touch, blank, or re-stamp any
 *       other. The old form posted every asset every time, so nothing depended on this; now
 *       everything does.</li>
 *   <li><b>Scope.</b> The path carries a sheet id and an entry id. If the entry is not checked
 *       against the sheet, the first decides access while the second decides what is written.</li>
 * </ol>
 */
class WebFillEntryDialogIntegrationTest extends AbstractPostgresIntegrationTest {

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

    // ── the value, and the summary that comes back ──────────────────────────────────────────

    @Test
    @WithAppUser(roles = "ADMIN", authorities = {FILL, COMPLETE})
    void savingOneAssetStoresItsValuesAndAnswersWithThatAssetsSummary() throws Exception {
        Fixture f = seed();

        mockMvc.perform(post("/log-sheets/{id}/entries/{entryId}/draft", f.sheetId(), f.firstEntryId())
                        .param("fd_" + f.firstEntryId() + "_temp", "42")
                        .with(csrf()))
                .andExpect(status().isOk())
                // The answer is the read-only summary the card swaps in, not JSON — so it has to
                // carry the value a person will read.
                .andExpect(content().string(containsString("42")))
                .andExpect(content().string(containsString("data-entry-summary")));

        assertThat(entry(f.firstEntryId()).getFormData()).containsEntry("temp", "42");
    }

    @Test
    @WithAppUser(roles = "ADMIN", authorities = {FILL, COMPLETE})
    void theSummaryNamesAParameterNobodyAnsweredRatherThanOmittingIt() throws Exception {
        // `tableAll`, not `table`. "Not measured" and "not a parameter of this class" are the two
        // things the person filling the sheet most needs to tell apart.
        Fixture f = seed();

        mockMvc.perform(post("/log-sheets/{id}/entries/{entryId}/draft", f.sheetId(), f.firstEntryId())
                        .param("fd_" + f.firstEntryId() + "_temp", "")
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Temperature")))
                .andExpect(content().string(containsString("ثبت نشده")));
    }

    // ── history: the property the request named ─────────────────────────────────────────────

    @Test
    @WithAppUser(roles = "ADMIN", authorities = {FILL, COMPLETE})
    void correctingAReadingThroughTheDialogKeepsWhatItReplaced() throws Exception {
        Fixture f = seed();
        save(f.sheetId(), f.firstEntryId(), "temp", "10");

        save(f.sheetId(), f.firstEntryId(), "temp", "99");

        List<LogSheetEntryRevision> history =
                revisionRepository.findByLogSheetEntryIdOrderByIdAsc(f.firstEntryId());
        assertThat(history).hasSize(1);
        assertThat(history.get(0).getFormData()).containsEntry("temp", "10");
        assertThat(entry(f.firstEntryId()).getFormData()).containsEntry("temp", "99");
    }

    @Test
    @WithAppUser(roles = "ADMIN", authorities = {FILL, COMPLETE})
    void recordingAReadingForTheFirstTimeWritesNoHistory() throws Exception {
        // The counterweight. A revision means "this replaced something"; writing one here would
        // put a correction in the record that never happened.
        Fixture f = seed();

        save(f.sheetId(), f.firstEntryId(), "temp", "10");

        assertThat(revisionRepository.findByLogSheetEntryIdOrderByIdAsc(f.firstEntryId())).isEmpty();
    }

    @Test
    @WithAppUser(roles = "ADMIN", authorities = {FILL, COMPLETE})
    void confirmingTheDialogWithoutChangingAnythingWritesNoHistory() throws Exception {
        // Opening a dialog to look at a reading and pressing save is not a correction. Without
        // this the table would grow a row every time somebody checked a value.
        Fixture f = seed();
        save(f.sheetId(), f.firstEntryId(), "temp", "10");

        save(f.sheetId(), f.firstEntryId(), "temp", "10");

        assertThat(revisionRepository.findByLogSheetEntryIdOrderByIdAsc(f.firstEntryId())).isEmpty();
    }

    // ── isolation: everything now depends on this ───────────────────────────────────────────

    @Test
    @WithAppUser(roles = "ADMIN", authorities = {FILL, COMPLETE})
    void savingOneAssetLeavesTheOtherAssetOnTheSheetUntouched() throws Exception {
        Fixture f = seed();
        save(f.sheetId(), f.secondEntryId(), "temp", "second-value");
        LogSheetEntry before = entry(f.secondEntryId());

        save(f.sheetId(), f.firstEntryId(), "temp", "first-value");

        LogSheetEntry after = entry(f.secondEntryId());
        assertThat(after.getFormData()).containsEntry("temp", "second-value");
        assertThat(after.getUpdatedAt())
                .as("an untouched asset must not be re-stamped by another asset's save")
                .isEqualTo(before.getUpdatedAt());
        assertThat(revisionRepository.findByLogSheetEntryIdOrderByIdAsc(f.secondEntryId()))
                .as("nor gain a revision")
                .isEmpty();
    }

    @Test
    @WithAppUser(roles = "ADMIN", authorities = {FILL, COMPLETE})
    void aDialogPostCarryingAnotherAssetsFieldsWritesOnlyTheAssetInThePath() throws Exception {
        // The path decides what is written, not the body. A stale page, or a hand-made request,
        // must not be able to write a second asset by naming it in the parameters.
        Fixture f = seed();

        mockMvc.perform(post("/log-sheets/{id}/entries/{entryId}/draft", f.sheetId(), f.firstEntryId())
                        .param("fd_" + f.firstEntryId() + "_temp", "mine")
                        .param("fd_" + f.secondEntryId() + "_temp", "smuggled")
                        .with(csrf()))
                .andExpect(status().isOk());

        assertThat(entry(f.firstEntryId()).getFormData()).containsEntry("temp", "mine");
        assertThat(entry(f.secondEntryId()).getFormData())
                .as("the asset named only in the body must be untouched")
                .doesNotContainEntry("temp", "smuggled");
    }

    // ── scope ───────────────────────────────────────────────────────────────────────────────

    @Test
    @WithAppUser(roles = "ADMIN", authorities = {FILL, COMPLETE})
    void anEntryBelongingToAnotherSheetIsRefused() throws Exception {
        // A redirect rather than a 4xx, because this is the web chain: WebExceptionHandler turns
        // the refusal into a flash message and sends the browser somewhere. What matters, and
        // what is asserted, is that nothing was written — the status only says it did not render.
        Fixture mine = seed();
        Fixture other = seed();

        mockMvc.perform(post("/log-sheets/{id}/entries/{entryId}/draft",
                        mine.sheetId(), other.firstEntryId())
                        .param("fd_" + other.firstEntryId() + "_temp", "crossed")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection());

        assertThat(entry(other.firstEntryId()).getFormData())
                .as("the other sheet's asset must be untouched")
                .doesNotContainEntry("temp", "crossed");
    }

    @Test
    @WithAppUser(roles = "ADMIN", authorities = {FILL})
    void withoutTheCompletePermissionTheDialogCannotSave() throws Exception {
        Fixture f = seed();

        mockMvc.perform(post("/log-sheets/{id}/entries/{entryId}/draft", f.sheetId(), f.firstEntryId())
                        .param("fd_" + f.firstEntryId() + "_temp", "42")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection());

        assertThat(entry(f.firstEntryId()).getFormData()).doesNotContainEntry("temp", "42");
    }

    // ── the rest of the sheet ───────────────────────────────────────────────────────────────

    @Test
    @WithAppUser(roles = "ADMIN", authorities = {FILL, COMPLETE})
    void aPerAssetSaveDoesNotClearTheSheetsNotes() throws Exception {
        // The dialog does not show the notes field, so it sends none. `applyWebNotes` reads a
        // null as "not submitted" rather than "cleared" — asserted because the opposite reading
        // would silently erase a supervisor's note on every asset save.
        Fixture f = seed();
        LogSheet sheet = logSheetRepository.findById(f.sheetId()).orElseThrow();
        sheet.setNotes("یادداشت سرپرست");
        logSheetRepository.saveAndFlush(sheet);

        save(f.sheetId(), f.firstEntryId(), "temp", "42");

        assertThat(logSheetRepository.findById(f.sheetId()).orElseThrow().getNotes())
                .isEqualTo("یادداشت سرپرست");
    }

    @Test
    @WithAppUser(roles = "ADMIN", authorities = {FILL, COMPLETE})
    void aPerAssetSaveMarksTheSheetAsHavingAWebDraft() throws Exception {
        Fixture f = seed();
        assertThat(logSheetRepository.findById(f.sheetId()).orElseThrow().getDraftSavedAt()).isNull();

        save(f.sheetId(), f.firstEntryId(), "temp", "42");

        LogSheet after = logSheetRepository.findById(f.sheetId()).orElseThrow();
        assertThat(after.getDraftSavedAt()).isNotNull();
        assertThat(after.getDraftSource()).isEqualTo("WEB");
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────

    private void save(Long sheetId, Long entryId, String key, String value) throws Exception {
        mockMvc.perform(post("/log-sheets/{id}/entries/{entryId}/draft", sheetId, entryId)
                        .param("fd_" + entryId + "_" + key, value)
                        .with(csrf()))
                .andExpect(status().isOk());
    }

    private LogSheetEntry entry(Long entryId) {
        return logSheetEntryRepository.findById(entryId).orElseThrow();
    }

    private record Fixture(Long sheetId, Long firstEntryId, Long secondEntryId) {}

    private Fixture seed() {
        long now = System.currentTimeMillis();
        long nano = System.nanoTime();

        OperationalUnit unit = new OperationalUnit();
        unit.setCode("DLG-BU-" + nano);
        unit.setName("Dialog Unit");
        unit.setCreatedAt(now);
        unit.setUpdatedAt(now);
        unit = operationalUnitRepository.saveAndFlush(unit);

        Location location = new Location();
        location.setCode("DLG-LOC-" + nano);
        location.setName("Dialog Hall");
        location.setCreatedAt(now);
        location.setUpdatedAt(now);
        location = locationRepository.saveAndFlush(location);


        AssetClass assetClass = new AssetClass();
        assetClass.setName("Dialog Pump " + nano);
        assetClass.setCreatedAt(now);
        assetClass.setUpdatedAt(now);
        assetClass = assetClassRepository.saveAndFlush(assetClass);

        FieldDefinition def = new FieldDefinition();
        def.setClassId(assetClass.getId());
        def.setKey("temp");
        def.setLabel("Temperature");
        def.setDataType("text");
        def.setRequired(false);
        def.setOrder(1);
        def.setCreatedAt(now);
        def.setUpdatedAt(now);
        fieldDefinitionRepository.saveAndFlush(def);

        // Two assets, because the isolation tests need a second one to leave alone — and one
        // sub-function each, because `ux_asset_entries_active_sub_function` allows only one
        // active asset per sub-function. See docs/hierarchy.md.
        for (int i = 1; i <= 2; i++) {
            SubFunction subFunction = new SubFunction();
            subFunction.setCode("DLG-SF" + i + "-" + nano);
            subFunction.setName("Dialog Sub " + i);
            subFunction.setTag("NFC-DLG" + i + "-" + nano);
            subFunction.setCreatedAt(now);
            subFunction.setUpdatedAt(now);
            hierarchyService.applySubFunctionParent(
                    subFunction, AssetHierarchyService.SCOPE_LOCATION, location.getId());
            subFunction = hierarchyService.saveSubFunction(subFunction);

            AssetEntry asset = new AssetEntry();
            asset.setAssetCode("DLG-A" + i + "-" + nano);
            asset.setAssetName("Pump " + i);
            asset.setClassId(assetClass.getId());
            asset.setSubFunctionId(subFunction.getId());
            asset.setCreatedAt(now);
            asset.setUpdatedAt(now);
            assetEntryRepository.saveAndFlush(asset);
        }

        LogSheetTemplate template = new LogSheetTemplate();
        template.setName("Dialog Template " + nano);
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

    private User operator(Long unitId, long nano) {
        long now = System.currentTimeMillis();
        User user = new User();
        user.setUsername("dlg-op-" + nano);
        user.setPersonnelCode("PC-" + UUID.randomUUID());
        user.setFullName("Dialog Operator");
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
