package com.hnp.backendofflinefirst.integration;

import com.hnp.backendofflinefirst.domain.AssetSelectionMode;
import com.hnp.backendofflinefirst.domain.GenerationMode;
import com.hnp.backendofflinefirst.domain.LogSheetStatus;
import com.hnp.backendofflinefirst.entity.LogSheet;
import com.hnp.backendofflinefirst.entity.LogSheetField;
import com.hnp.backendofflinefirst.entity.LogSheetTemplate;
import com.hnp.backendofflinefirst.entity.OperationalUnit;
import com.hnp.backendofflinefirst.repository.LogSheetEntryRepository;
import com.hnp.backendofflinefirst.repository.LogSheetFieldRepository;
import com.hnp.backendofflinefirst.repository.LogSheetRepository;
import com.hnp.backendofflinefirst.repository.LogSheetTemplateAssetRepository;
import com.hnp.backendofflinefirst.repository.LogSheetTemplateRepository;
import com.hnp.backendofflinefirst.repository.OperationalUnitRepository;
import com.hnp.backendofflinefirst.service.LogSheetGenerationService;
import com.hnp.backendofflinefirst.support.AbstractPostgresIntegrationTest;
import com.hnp.backendofflinefirst.support.WithAppUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A round with <strong>no equipment in it at all</strong>: an area inspection, a shift handover,
 * a questionnaire. Its whole content is the questions the round asks of itself.
 *
 * <p>{@link AssetSelectionMode#NONE} is why this is a feature rather than an accident. An empty
 * asset list already existed and already meant something else — a scope that resolved to nothing
 * because somebody deactivated the last pump, which is the failure the generator refuses. A
 * template that says {@code NONE} has recorded the intent, and a year later nothing has to guess
 * which of the two it was.
 *
 * <p>The tests below follow one such round from the template form to a submitted sheet, because
 * every step of that path was written on the assumption that a round has assets:
 *
 * <ul>
 *   <li>the form posts no scope, no class and no asset ids;</li>
 *   <li>generation produces a sheet with zero entries instead of refusing;</li>
 *   <li>the round-level answers are the whole of what is filled in;</li>
 *   <li>and the completion goes through, because nothing in that path counts assets.</li>
 * </ul>
 */
@Transactional
@WithAppUser(roles = "ADMIN", authorities = {
        "POST:/log-sheet-templates",
        "POST:/log-sheet-templates/{id}/fields",
        "GET:/log-sheets/{id}",
        "GET:/log-sheets/{id}/fill",
        "POST:/log-sheets/{id}/complete"})
class AssetLessRoundIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired WebApplicationContext context;
    @Autowired OperationalUnitRepository operationalUnitRepository;
    @Autowired LogSheetTemplateRepository templateRepository;
    @Autowired LogSheetTemplateAssetRepository templateAssetRepository;
    @Autowired LogSheetFieldRepository logSheetFieldRepository;
    @Autowired LogSheetRepository logSheetRepository;
    @Autowired LogSheetEntryRepository logSheetEntryRepository;
    @Autowired LogSheetGenerationService generationService;

    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void theFormSavesATemplateWithNoScopeNoClassAndNoAssets() throws Exception {
        OperationalUnit unit = unit();
        String name = "بازدید محوطه " + System.nanoTime();

        // Exactly the shape the browser posts: the scope, class and asset inputs are all
        // disabled in NONE mode, so none of them is sent.
        mockMvc.perform(post("/log-sheet-templates").with(csrf())
                        .param("name", name)
                        .param("operationalUnitId", String.valueOf(unit.getId()))
                        .param("assetSelectionMode", "NONE")
                        .param("active", "true")
                        .param("generationMode", "MANUAL")
                        .param("scheduleActive", "false"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("successMessage"));

        LogSheetTemplate saved = templateRepository.findByNameIgnoreCase(name).orElseThrow();
        assertThat(saved.getAssetSelectionMode()).isEqualTo(AssetSelectionMode.NONE);
        assertThat(saved.getScopeType()).isNull();
        assertThat(saved.getScopeId()).isNull();
        assertThat(saved.getClassId()).isNull();
        // And no frozen asset list either — NONE is not EXPLICIT with an empty set.
        assertThat(templateAssetRepository.findAssetIdsByTemplateId(saved.getId())).isEmpty();
    }

    @Test
    void generationProducesARoundWithNoEntriesRatherThanRefusing() {
        LogSheetTemplate template = assetLessTemplate();
        askRoundLevel(template.getId(), "area_clear", "آیا محوطه پاک بود؟");

        LogSheet sheet = generationService.generateFromTemplate(
                template, GenerationMode.MANUAL, null, System.currentTimeMillis());

        assertThat(sheet.getId()).isNotNull();
        assertThat(logSheetEntryRepository.findByLogSheetId(sheet.getId())).isEmpty();
        // The questions travelled, frozen at generation like any other template's.
        assertThat(sheet.getSheetFieldDefinitionsSnapshot()).hasSize(1);
        assertThat(sheet.getSheetFieldDefinitionsSnapshot().get(0).getKey()).isEqualTo("area_clear");
        // No per-asset schema at all: there is no class to resolve one from.
        assertThat(sheet.getFieldDefinitionsSnapshot()).isNullOrEmpty();
    }

    @Test
    void theRoundLevelAnswersCanBeSavedOnTheirOwn() throws Exception {
        LogSheet sheet = claimed(assetLessRound("area_clear"));

        mockMvc.perform(post("/log-sheets/{id}/sheet-form/draft", sheet.getId())
                        .param("sfd_present", "1")
                        .param("sfd_area_clear", "بله")
                        .with(csrf()))
                .andExpect(status().isOk());

        assertThat(logSheetRepository.findById(sheet.getId()).orElseThrow().getSheetFormData())
                .containsEntry("area_clear", "بله");
    }

    @Test
    void theRoundCanBeCompletedWithNothingButItsOwnAnswers() throws Exception {
        LogSheet sheet = claimed(assetLessRound("area_clear"));

        // Nothing in the completion path counts assets, and this is the test that keeps it that
        // way: a round whose whole content is its questions must be deliverable.
        mockMvc.perform(post("/log-sheets/{id}/complete", sheet.getId())
                        .param("sfd_present", "1")
                        .param("sfd_area_clear", "بله")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("successMessage"));

        LogSheet done = logSheetRepository.findById(sheet.getId()).orElseThrow();
        assertThat(done.getStatus()).isEqualTo(LogSheetStatus.SUBMITTED);
        assertThat(done.getSheetFormData()).containsEntry("area_clear", "بله");
    }

    @Test
    void theFillPageOpensForARoundThatHasNoAssets() throws Exception {
        LogSheet sheet = claimed(assetLessRound("area_clear"));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/log-sheets/{id}/fill", sheet.getId()))
                .andExpect(status().isOk());
    }

    // =================================================================== helpers

    private LogSheet assetLessRound(String questionKey) {
        LogSheetTemplate template = assetLessTemplate();
        askRoundLevel(template.getId(), questionKey, questionKey);
        return generationService.generateFromTemplate(
                template, GenerationMode.MANUAL, null, System.currentTimeMillis());
    }

    /** A sheet the signed-in user may fill on the web: assigned to them, with a live deadline. */
    private LogSheet claimed(LogSheet sheet) {
        sheet.setStatus(LogSheetStatus.IN_PROGRESS);
        sheet.setAssigneeUserId(1L);
        sheet.setDueAt(System.currentTimeMillis() + 3_600_000L);
        return logSheetRepository.saveAndFlush(sheet);
    }

    private LogSheetTemplate assetLessTemplate() {
        long now = System.currentTimeMillis();
        LogSheetTemplate template = new LogSheetTemplate();
        template.setName("AL Template " + System.nanoTime());
        template.setOperationalUnitId(unit().getId());
        template.setAssetSelectionMode(AssetSelectionMode.NONE);
        template.setGenerationMode(GenerationMode.MANUAL);
        template.setScheduleActive(false);
        template.setActive(true);
        template.setCreatedAt(now);
        template.setUpdatedAt(now);
        return templateRepository.saveAndFlush(template);
    }

    private void askRoundLevel(Long templateId, String key, String label) {
        long now = System.currentTimeMillis();
        LogSheetField field = new LogSheetField();
        field.setTemplateId(templateId);
        field.setKey(key);
        field.setLabel(label);
        field.setDataType("text");
        field.setRequired(false);
        field.setOrder(1);
        field.setVersion(1);
        field.setDeleted(false);
        field.setCreatedAt(now);
        field.setUpdatedAt(now);
        logSheetFieldRepository.saveAndFlush(field);
    }

    private OperationalUnit unit() {
        long now = System.currentTimeMillis();
        OperationalUnit unit = new OperationalUnit();
        unit.setCode("AL-BU-" + System.nanoTime());
        unit.setName("Asset-less Unit");
        unit.setCreatedAt(now);
        unit.setUpdatedAt(now);
        return operationalUnitRepository.saveAndFlush(unit);
    }
}
