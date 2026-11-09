package com.hnp.backendofflinefirst.integration;

import com.hnp.backendofflinefirst.domain.GenerationMode;
import com.hnp.backendofflinefirst.domain.LogSheetStatus;
import com.hnp.backendofflinefirst.entity.AssetClass;
import com.hnp.backendofflinefirst.entity.AssetEntry;
import com.hnp.backendofflinefirst.dto.LogSheetBundleDto;
import com.hnp.backendofflinefirst.entity.FieldDefinition;
import com.hnp.backendofflinefirst.entity.Location;
import com.hnp.backendofflinefirst.entity.LogSheet;
import com.hnp.backendofflinefirst.entity.LogSheetField;
import com.hnp.backendofflinefirst.entity.OperationalUnit;
import com.hnp.backendofflinefirst.entity.SubFunction;
import com.hnp.backendofflinefirst.entity.UnitSupervisor;
import com.hnp.backendofflinefirst.repository.AssetClassRepository;
import com.hnp.backendofflinefirst.repository.AssetEntryRepository;
import com.hnp.backendofflinefirst.repository.FieldDefinitionRepository;
import com.hnp.backendofflinefirst.repository.LocationRepository;
import com.hnp.backendofflinefirst.repository.LogSheetFieldRepository;
import com.hnp.backendofflinefirst.repository.LogSheetRepository;
import com.hnp.backendofflinefirst.repository.LogSheetTemplateRepository;
import com.hnp.backendofflinefirst.repository.OperationalUnitRepository;
import com.hnp.backendofflinefirst.repository.SubFunctionRepository;
import com.hnp.backendofflinefirst.repository.UnitSupervisorRepository;
import com.hnp.backendofflinefirst.service.AssetHierarchyService;
import com.hnp.backendofflinefirst.service.CustomLogSheetService;
import com.hnp.backendofflinefirst.support.AbstractPostgresIntegrationTest;
import com.hnp.backendofflinefirst.support.WithAppUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * A <strong>custom</strong> log sheet's own questions — the half of the feature a template
 * cannot cover.
 *
 * <p>A template-raised round inherits its questions from the template and freezes them when it
 * is generated. A custom sheet has no template: a supervisor raises a one-off round, perhaps
 * with no equipment on it at all, and the questions <em>are</em> the form. So it owns them
 * directly, through the {@code log_sheet_fields.log_sheet_id} column V9 created and V10 gave a
 * writer.
 *
 * <p>Owning them directly is what makes the four rules below necessary rather than decorative.
 * A template's questions are safe to edit forever because every round took a copy; these have no
 * copy until the first answer arrives, so each rule closes one way an edit could re-describe
 * work somebody has already done:
 *
 * <ol>
 *   <li><b>A custom sheet only.</b> Pointing this editor at a template-raised round would be a
 *       second owner for one list.</li>
 *   <li><b>Not finished.</b> A terminal round has been delivered.</li>
 *   <li><b>Not yet answered.</b> The first answer freezes the list; after that an edit here
 *       could not reach the round, so it is refused rather than silently ignored.</li>
 *   <li><b>A unit the actor may raise custom rounds for</b>, checked exactly as creating one
 *       is checked.</li>
 * </ol>
 */
@Transactional
@WithAppUser(roles = "ADMIN", authorities = {
        "GET:/log-sheets/{id}",
        "GET:/log-sheets/{id}/fields",
        "POST:/log-sheets/{id}/fields",
        "POST:/log-sheets/{id}/fields/{fieldId}",
        "POST:/log-sheets/{id}/fields/{fieldId}/state",
        "POST:/log-sheets/{id}/fields/{fieldId}/delete",
        "POST:/log-sheets/{id}/complete"})
class LogSheetCustomQuestionsIntegrationTest extends AbstractPostgresIntegrationTest {

    /** {@code WithAppUserSecurityContextFactory} always mints the principal with id 1. */
    private static final long PRINCIPAL_USER_ID = 1L;

    @Autowired WebApplicationContext context;
    @Autowired CustomLogSheetService customLogSheetService;
    @Autowired LogSheetRepository logSheetRepository;
    @Autowired LogSheetFieldRepository logSheetFieldRepository;
    @Autowired LogSheetTemplateRepository templateRepository;
    @Autowired OperationalUnitRepository operationalUnitRepository;
    @Autowired UnitSupervisorRepository unitSupervisorRepository;
    @Autowired LocationRepository locationRepository;
    @Autowired SubFunctionRepository subFunctionRepository;
    @Autowired AssetClassRepository assetClassRepository;
    @Autowired AssetEntryRepository assetEntryRepository;
    @Autowired FieldDefinitionRepository fieldDefinitionRepository;
    @Autowired AssetHierarchyService hierarchyService;
    @Autowired com.hnp.backendofflinefirst.service.LogSheetGenerationService generationService;
    @Autowired com.hnp.backendofflinefirst.service.LogSheetBundleService bundleService;

    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    // =================================================================== the editor

    @Test
    void aQuestionIsStoredAgainstTheSheetRatherThanAgainstAnyTemplate() throws Exception {
        LogSheet sheet = customSheet();

        addQuestion(sheet.getId(), "area_clear", "آیا محوطه پاک بود؟", "checkbox")
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("successMessage"));

        List<LogSheetField> stored = logSheetFieldRepository.findForLogSheet(sheet.getId());
        assertThat(stored).hasSize(1);
        assertThat(stored.get(0).getLogSheetId()).isEqualTo(sheet.getId());
        // The other half of the CHECK constraint: exactly one owner, never both.
        assertThat(stored.get(0).getTemplateId()).isNull();
        assertThat(stored.get(0).getKey()).isEqualTo("area_clear");
    }

    @Test
    void aQuestionMayAskForAPhotoBecauseARoundLevelAnswerHasSomewhereToPutItsBytes()
            throws Exception {
        LogSheet sheet = customSheet();

        // The reason the asset-less round is useful at all: an area inspection's evidence is the
        // picture. V10 made `attachments.asset_id` nullable for exactly this.
        addQuestion(sheet.getId(), "photo", "عکس محوطه", "image")
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("successMessage"));

        assertThat(logSheetFieldRepository.findForLogSheet(sheet.getId()).get(0).getDataType())
                .isEqualTo("image");
    }

    @Test
    void theSameKeyMayBeUsedOnTwoDifferentRounds() throws Exception {
        LogSheet first = customSheet();
        LogSheet second = customSheet();

        addQuestion(first.getId(), "note", "یادداشت", "text")
                .andExpect(flash().attributeExists("successMessage"));
        // Two rounds are two separate forms; uniqueness is per owner, as
        // ux_log_sheet_fields_sheet_key_lower enforces.
        addQuestion(second.getId(), "note", "یادداشت", "text")
                .andExpect(flash().attributeExists("successMessage"));

        assertThat(logSheetFieldRepository.findForLogSheet(first.getId())).hasSize(1);
        assertThat(logSheetFieldRepository.findForLogSheet(second.getId())).hasSize(1);
    }

    @Test
    void oneKeyPerRound() throws Exception {
        LogSheet sheet = customSheet();
        addQuestion(sheet.getId(), "note", "یادداشت", "text")
                .andExpect(flash().attributeExists("successMessage"));

        addQuestion(sheet.getId(), "NOTE", "دوباره", "text")
                .andExpect(flash().attributeExists("errorMessage"));

        assertThat(logSheetFieldRepository.findForLogSheet(sheet.getId())).hasSize(1);
    }

    @Test
    void aKeyThatIsNotAnIdentifierIsRefused() throws Exception {
        LogSheet sheet = customSheet();

        addQuestion(sheet.getId(), "has space", "بد", "text")
                .andExpect(flash().attributeExists("errorMessage"));

        assertThat(logSheetFieldRepository.findForLogSheet(sheet.getId())).isEmpty();
    }

    @Test
    void aQuestionIdFromAnotherRoundIsRefused() throws Exception {
        LogSheet mine = customSheet();
        LogSheet theirs = customSheet();
        addQuestion(theirs.getId(), "note", "یادداشت", "text");
        Long foreignId = logSheetFieldRepository.findForLogSheet(theirs.getId()).get(0).getId();

        // The path id decides access and the field id decides what is written, so holding one
        // round must not be a way into another's questions.
        mockMvc.perform(post("/log-sheets/{id}/fields/{fid}", mine.getId(), foreignId)
                        .param("key", "hijacked")
                        .param("label", "دستکاری")
                        .param("dataType", "text")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("errorMessage"));

        assertThat(logSheetFieldRepository.findById(foreignId).orElseThrow().getKey())
                .isEqualTo("note");
    }

    @Test
    void switchingAQuestionOffAndBackOnIsARoundTrip() throws Exception {
        LogSheet sheet = customSheet();
        addQuestion(sheet.getId(), "note", "یادداشت", "text");
        Long fieldId = logSheetFieldRepository.findForLogSheet(sheet.getId()).get(0).getId();

        setState(sheet.getId(), fieldId, true).andExpect(flash().attributeExists("successMessage"));
        assertThat(logSheetFieldRepository.findById(fieldId).orElseThrow().isDeleted()).isTrue();

        setState(sheet.getId(), fieldId, false).andExpect(flash().attributeExists("successMessage"));
        assertThat(logSheetFieldRepository.findById(fieldId).orElseThrow().isDeleted()).isFalse();
    }

    @Test
    void anUnansweredQuestionCanBeDeletedOutright() throws Exception {
        LogSheet sheet = customSheet();
        addQuestion(sheet.getId(), "typo", "اشتباه تایپی", "text");
        Long fieldId = logSheetFieldRepository.findForLogSheet(sheet.getId()).get(0).getId();

        mockMvc.perform(post("/log-sheets/{id}/fields/{fid}/delete", sheet.getId(), fieldId)
                        .with(csrf()))
                .andExpect(flash().attributeExists("successMessage"));

        assertThat(logSheetFieldRepository.findForLogSheet(sheet.getId())).isEmpty();
    }

    // =================================================================== the four rules

    @Test
    void aTemplateRaisedRoundRefusesEveryWriteBecauseItsQuestionsBelongToItsTemplate()
            throws Exception {
        LogSheet sheet = templateRaisedSheet();

        addQuestion(sheet.getId(), "note", "یادداشت", "text")
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("errorMessage"));

        assertThat(logSheetFieldRepository.findForLogSheet(sheet.getId())).isEmpty();
    }

    @Test
    void aTemplateRaisedRoundStillOpensThePageButWithNothingToPress() throws Exception {
        LogSheet sheet = templateRaisedSheet();

        // Read, not refused: a supervisor looking at the round should be able to see what it
        // asks. The page says where to go instead of leaving the missing buttons unexplained.
        mockMvc.perform(get("/log-sheets/{id}/fields", sheet.getId()))
                .andExpect(status().isOk())
                .andExpect(view().name("log-sheet-questions"))
                .andExpect(model().attribute("canManage", false));
    }

    @Test
    void aFinishedRoundRefusesEveryWrite() throws Exception {
        LogSheet sheet = customSheet();
        sheet.setStatus(LogSheetStatus.SUBMITTED);
        logSheetRepository.saveAndFlush(sheet);

        addQuestion(sheet.getId(), "note", "یادداشت", "text")
                .andExpect(flash().attributeExists("errorMessage"));

        assertThat(logSheetFieldRepository.findForLogSheet(sheet.getId())).isEmpty();
    }

    @Test
    void theFirstAnswerFreezesTheListAndClosesTheEditor() throws Exception {
        LogSheet sheet = claimed(customSheet());
        addQuestion(sheet.getId(), "note", "یادداشت", "text");

        // Before: live, with no snapshot of its own.
        assertThat(logSheetRepository.findById(sheet.getId()).orElseThrow()
                .getSheetFieldDefinitionsSnapshot()).isNull();

        mockMvc.perform(post("/log-sheets/{id}/sheet-form/draft", sheet.getId())
                        .param("sfd_present", "1")
                        .param("sfd_note", "پاسخ")
                        .with(csrf()))
                .andExpect(status().isOk());

        // After: frozen from the very list that answer was validated against.
        LogSheet answered = logSheetRepository.findById(sheet.getId()).orElseThrow();
        assertThat(answered.getSheetFieldDefinitionsSnapshot()).hasSize(1);
        assertThat(answered.getSheetFieldDefinitionsSnapshot().get(0).getKey()).isEqualTo("note");

        // And the editor is closed, because an edit could no longer reach this round.
        addQuestion(sheet.getId(), "second", "دوم", "text")
                .andExpect(flash().attributeExists("errorMessage"));
        assertThat(logSheetFieldRepository.findForLogSheet(sheet.getId())).hasSize(1);

        mockMvc.perform(get("/log-sheets/{id}/fields", sheet.getId()))
                .andExpect(status().isOk())
                .andExpect(model().attribute("canManage", false));
    }

    // =================================================================== the tablet

    /**
     * The reported bug: questions added to a custom asset-less round after it was created were
     * missing on the tablet that picked it up.
     *
     * <p>The tablet read only {@code sheet_field_definitions_snapshot}, which a custom sheet does
     * not have until its first answer — and nobody could give a first answer to questions they
     * were never shown. The bundle now carries the list resolved the way the web page resolves
     * it, without writing the snapshot: the approver may still change the questions.
     */
    @Test
    void theTabletIsSentACustomRoundsQuestionsBeforeAnyoneHasAnsweredThem() throws Exception {
        LogSheet sheet = claimed(customSheet());
        addQuestion(sheet.getId(), "area_clear", "آیا محوطه پاک بود؟", "checkbox");
        addQuestion(sheet.getId(), "note", "یادداشت", "text");
        LogSheet stored = logSheetRepository.findById(sheet.getId()).orElseThrow();
        assertThat(stored.getSheetFieldDefinitionsSnapshot()).as("not frozen yet").isNull();

        LogSheetBundleDto bundle = bundleService.buildFullBundle(stored);

        assertThat(bundle.getContext().getSheetFieldDefinitions())
                .extracting(FieldDefinition::getKey)
                .containsExactlyInAnyOrder("area_clear", "note");
        // Reading a bundle must not freeze the list behind the approver's back.
        logSheetRepository.flush();
        assertThat(logSheetRepository.findById(sheet.getId()).orElseThrow()
                .getSheetFieldDefinitionsSnapshot()).isNull();
    }

    @Test
    void afterTheFirstAnswerTheTabletIsSentTheFrozenCopy() throws Exception {
        LogSheet sheet = claimed(customSheet());
        addQuestion(sheet.getId(), "note", "یادداشت", "text");
        mockMvc.perform(post("/log-sheets/{id}/sheet-form/draft", sheet.getId())
                        .param("sfd_present", "1")
                        .param("sfd_note", "پاسخ")
                        .with(csrf()))
                .andExpect(status().isOk());
        // Switched off after the freeze: the live list loses it, the round must not.
        Long fieldId = logSheetFieldRepository.findForLogSheet(sheet.getId()).get(0).getId();
        logSheetFieldRepository.findById(fieldId).ifPresent(f -> {
            f.setDeleted(true);
            logSheetFieldRepository.saveAndFlush(f);
        });

        LogSheetBundleDto bundle = bundleService.buildFullBundle(
                logSheetRepository.findById(sheet.getId()).orElseThrow());

        assertThat(bundle.getContext().getSheetFieldDefinitions())
                .extracting(FieldDefinition::getKey)
                .containsExactly("note");
    }

    @Test
    void aFrozenRoundKeepsAnsweringFromItsOwnCopyEvenIfTheRowIsSwitchedOff() throws Exception {
        LogSheet sheet = claimed(customSheet());
        addQuestion(sheet.getId(), "note", "یادداشت", "text");
        mockMvc.perform(post("/log-sheets/{id}/sheet-form/draft", sheet.getId())
                        .param("sfd_present", "1")
                        .param("sfd_note", "پاسخ اول")
                        .with(csrf()))
                .andExpect(status().isOk());

        // Switched off directly, bypassing the editor the way a future code path might.
        LogSheetField field = logSheetFieldRepository.findForLogSheet(sheet.getId()).get(0);
        field.setDeleted(true);
        logSheetFieldRepository.saveAndFlush(field);

        // The round still asks it — it reads its own frozen copy, not this row. This is the
        // invariant the freeze exists for, stated as a test rather than as a comment.
        mockMvc.perform(post("/log-sheets/{id}/sheet-form/draft", sheet.getId())
                        .param("sfd_present", "1")
                        .param("sfd_note", "پاسخ دوم")
                        .with(csrf()))
                .andExpect(status().isOk());

        assertThat(logSheetRepository.findById(sheet.getId()).orElseThrow().getSheetFormData())
                .containsEntry("note", "پاسخ دوم");
    }

    @Test
    void anAnsweredQuestionCannotBeDeletedEvenIfTheFreezeIsMissing() throws Exception {
        LogSheet sheet = claimed(customSheet());
        addQuestion(sheet.getId(), "note", "یادداشت", "text");
        Long fieldId = logSheetFieldRepository.findForLogSheet(sheet.getId()).get(0).getId();
        mockMvc.perform(post("/log-sheets/{id}/sheet-form/draft", sheet.getId())
                        .param("sfd_present", "1")
                        .param("sfd_note", "پاسخ")
                        .with(csrf()))
                .andExpect(status().isOk());

        // The freeze column cleared by hand: the second lock has to hold on its own, because it
        // reads the answers themselves rather than a marker that something was answered.
        LogSheet answered = logSheetRepository.findById(sheet.getId()).orElseThrow();
        answered.setSheetFieldDefinitionsSnapshot(null);
        logSheetRepository.saveAndFlush(answered);

        mockMvc.perform(post("/log-sheets/{id}/fields/{fid}/delete", sheet.getId(), fieldId)
                        .with(csrf()))
                .andExpect(flash().attributeExists("errorMessage"));

        assertThat(logSheetFieldRepository.findById(fieldId)).isPresent();
    }

    /**
     * The unit rule, from the only principal that can prove it.
     *
     * <p>An ADMIN holds {@code SCOPE_PLANT_WIDE}, so for them the unit check always passes and a
     * test using one would prove nothing about it. A supervisor is scoped, and the question is
     * whether holding the five permissions is enough on a round in somebody else's unit.
     */
    @Nested
    @Transactional
    @WithAppUser(roles = "SUPERVISOR", authorities = {
            "GET:/log-sheets/{id}",
            "GET:/log-sheets/{id}/fields",
            "POST:/log-sheets/{id}/fields"})
    class AsAUnitScopedSupervisor {

        @Test
        void mayEditTheQuestionsOfARoundInTheirOwnUnit() throws Exception {
            OperationalUnit mine = unit();
            supervise(mine.getId());
            LogSheet sheet = customSheetIn(mine);

            addQuestion(sheet.getId(), "note", "یادداشت", "text")
                    .andExpect(flash().attributeExists("successMessage"));

            assertThat(logSheetFieldRepository.findForLogSheet(sheet.getId())).hasSize(1);
        }

        @Test
        void mayNotEditTheQuestionsOfARoundInAUnitTheyDoNotSupervise() throws Exception {
            // The round is raised while they still supervise its unit — the same actor, the same
            // permissions — and then the supervision moves. That is the real sequence: a
            // supervisor is reassigned and the rounds they raised stay behind.
            OperationalUnit theirs = unit();
            UnitSupervisor link = supervise(theirs.getId());
            LogSheet sheet = customSheetIn(theirs);
            unitSupervisorRepository.delete(link);
            supervise(unit().getId());
            unitSupervisorRepository.flush();

            addQuestion(sheet.getId(), "note", "یادداشت", "text")
                    .andExpect(status().is3xxRedirection())
                    .andExpect(flash().attributeExists("errorMessage"));

            assertThat(logSheetFieldRepository.findForLogSheet(sheet.getId())).isEmpty();
        }
    }

    // =================================================================== helpers

    private UnitSupervisor supervise(Long unitId) {
        UnitSupervisor link = new UnitSupervisor();
        link.setUnitId(unitId);
        link.setUserId(PRINCIPAL_USER_ID);
        return unitSupervisorRepository.saveAndFlush(link);
    }

    private ResultActions addQuestion(Long sheetId, String key, String label, String dataType)
            throws Exception {
        return mockMvc.perform(post("/log-sheets/{id}/fields", sheetId)
                .param("key", key)
                .param("label", label)
                .param("dataType", dataType)
                .param("order", "1")
                .with(csrf()));
    }

    private ResultActions setState(Long sheetId, Long fieldId, boolean disabled) throws Exception {
        return mockMvc.perform(post("/log-sheets/{id}/fields/{fid}/state", sheetId, fieldId)
                .param("disabled", String.valueOf(disabled))
                .with(csrf()));
    }

    /** A sheet the signed-in user may fill on the web: assigned to them, with a live deadline. */
    private LogSheet claimed(LogSheet sheet) {
        sheet.setStatus(LogSheetStatus.IN_PROGRESS);
        sheet.setAssigneeUserId(PRINCIPAL_USER_ID);
        sheet.setDueAt(System.currentTimeMillis() + 3_600_000L);
        return logSheetRepository.saveAndFlush(sheet);
    }

    /**
     * A custom round with <b>no equipment on it at all</b> — the shape this whole feature is for.
     *
     * <p>Created through the service rather than by hand so the {@code withoutAssets} flag, the
     * refusals around it and the PENDING status are the real ones.
     */
    private LogSheet customSheet() {
        return customSheetIn(unit());
    }

    private LogSheet customSheetIn(OperationalUnit unit) {
        long now = System.currentTimeMillis();
        return customLogSheetService.createCustom(
                unit.getId(), "بازدید محوطه " + System.nanoTime(), now + 3_600_000L,
                List.of(), true, PRINCIPAL_USER_ID, now);
    }

    /** A round raised from a template, for the rule that refuses this editor on one. */
    private LogSheet templateRaisedSheet() {
        long now = System.currentTimeMillis();
        long nano = System.nanoTime();
        OperationalUnit unit = unit();

        Location location = new Location();
        location.setCode("CQ-LOC-" + nano);
        location.setName("Hall");
        location.setCreatedAt(now);
        location.setUpdatedAt(now);
        location = locationRepository.saveAndFlush(location);

        SubFunction subFunction = new SubFunction();
        subFunction.setCode("CQ-SF-" + nano);
        subFunction.setName("Sub");
        subFunction.setTag("NFC-CQ-" + nano);
        subFunction.setCreatedAt(now);
        subFunction.setUpdatedAt(now);
        hierarchyService.applySubFunctionParent(
                subFunction, AssetHierarchyService.SCOPE_LOCATION, location.getId());
        subFunction = hierarchyService.saveSubFunction(subFunction);

        AssetClass assetClass = new AssetClass();
        assetClass.setName("CQ Pump " + nano);
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
        asset.setAssetCode("CQ-A1-" + nano);
        asset.setAssetName("Pump");
        asset.setClassId(assetClass.getId());
        asset.setSubFunctionId(subFunction.getId());
        asset.setCreatedAt(now);
        asset.setUpdatedAt(now);
        assetEntryRepository.saveAndFlush(asset);

        com.hnp.backendofflinefirst.entity.LogSheetTemplate template =
                new com.hnp.backendofflinefirst.entity.LogSheetTemplate();
        template.setName("CQ Template " + nano);
        template.setOperationalUnitId(unit.getId());
        template.setClassId(assetClass.getId());
        template.setScopeType(AssetHierarchyService.SCOPE_LOCATION);
        template.setScopeId(location.getId());
        template.setGenerationMode(GenerationMode.MANUAL);
        template.setScheduleActive(false);
        template.setActive(true);
        template.setCreatedAt(now);
        template.setUpdatedAt(now);
        template = templateRepository.saveAndFlush(template);

        return generationService.generateFromTemplate(
                template, GenerationMode.MANUAL, null, now);
    }

    private OperationalUnit unit() {
        long now = System.currentTimeMillis();
        OperationalUnit unit = new OperationalUnit();
        unit.setCode("CQ-BU-" + System.nanoTime());
        unit.setName("Custom Question Unit");
        unit.setCreatedAt(now);
        unit.setUpdatedAt(now);
        return operationalUnitRepository.saveAndFlush(unit);
    }
}
