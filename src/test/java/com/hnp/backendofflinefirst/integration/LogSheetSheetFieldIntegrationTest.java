package com.hnp.backendofflinefirst.integration;

import com.hnp.backendofflinefirst.domain.ActionSource;
import com.hnp.backendofflinefirst.domain.GenerationMode;
import com.hnp.backendofflinefirst.domain.LogSheetStatus;
import com.hnp.backendofflinefirst.entity.AssetClass;
import com.hnp.backendofflinefirst.entity.AssetEntry;
import com.hnp.backendofflinefirst.entity.FieldDefinition;
import com.hnp.backendofflinefirst.entity.Location;
import com.hnp.backendofflinefirst.entity.LogSheet;
import com.hnp.backendofflinefirst.entity.LogSheetField;
import com.hnp.backendofflinefirst.entity.LogSheetTemplate;
import com.hnp.backendofflinefirst.entity.OperationalUnit;
import com.hnp.backendofflinefirst.entity.SubFunction;
import com.hnp.backendofflinefirst.repository.AssetClassRepository;
import com.hnp.backendofflinefirst.repository.AssetEntryRepository;
import com.hnp.backendofflinefirst.repository.FieldDefinitionRepository;
import com.hnp.backendofflinefirst.repository.LocationRepository;
import com.hnp.backendofflinefirst.repository.LogSheetEntryRepository;
import com.hnp.backendofflinefirst.repository.LogSheetFieldRepository;
import com.hnp.backendofflinefirst.repository.LogSheetRepository;
import com.hnp.backendofflinefirst.repository.LogSheetRevisionRepository;
import com.hnp.backendofflinefirst.repository.LogSheetTemplateRepository;
import com.hnp.backendofflinefirst.repository.OperationalUnitRepository;
import com.hnp.backendofflinefirst.repository.SubFunctionRepository;
import com.hnp.backendofflinefirst.service.AssetHierarchyService;
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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A log sheet's <strong>own</strong> questions: the editor, the freeze, the answers and their
 * history.
 *
 * <p>«آیا همه لوله‌ها بازدید شد؟» is asked once per round, not once per pump. Structurally these
 * are field definitions with a different owner, which is what makes the feature small — and what
 * makes the things worth testing the ones where "different owner" has consequences:
 *
 * <ul>
 *   <li>media works the same way it does per asset — V10 made {@code attachments.asset_id}
 *       nullable, so a round-level photo has somewhere to live;</li>
 *   <li>{@code required} is judged on <b>one</b> request — the round-level form's own save —
 *       and on no other, so an unanswered question can never hold up delivered work;</li>
 *   <li>a round freezes the questions, so editing the template cannot re-describe a round that
 *       has already been walked;</li>
 *   <li>the answers carry their own authorship, because the sheet's existing
 *       {@code draft_saved_by_user_id} moves on every per-asset save;</li>
 *   <li>a correction keeps what it replaced, and a save that changes nothing does not pretend
 *       to be one.</li>
 * </ul>
 */
@Transactional
@WithAppUser(roles = "ADMIN", authorities = {
        "GET:/log-sheet-templates/{id}/fields",
        "POST:/log-sheet-templates/{id}/fields",
        "POST:/log-sheet-templates/{id}/fields/{fieldId}",
        "POST:/log-sheet-templates/{id}/fields/{fieldId}/state",
        "POST:/log-sheet-templates/{id}/fields/{fieldId}/delete",
        "GET:/log-sheets/{id}",
        "GET:/log-sheets/{id}/fill",
        "POST:/log-sheets/{id}/complete"})
class LogSheetSheetFieldIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired WebApplicationContext context;
    @Autowired LogSheetRepository logSheetRepository;
    @Autowired LogSheetEntryRepository logSheetEntryRepository;
    @Autowired LogSheetFieldRepository logSheetFieldRepository;
    @Autowired LogSheetRevisionRepository logSheetRevisionRepository;
    @Autowired LogSheetTemplateRepository templateRepository;
    @Autowired OperationalUnitRepository operationalUnitRepository;
    @Autowired LocationRepository locationRepository;
    @Autowired SubFunctionRepository subFunctionRepository;
    @Autowired AssetClassRepository assetClassRepository;
    @Autowired AssetEntryRepository assetEntryRepository;
    @Autowired FieldDefinitionRepository fieldDefinitionRepository;
    @Autowired AssetHierarchyService hierarchyService;
    @Autowired LogSheetGenerationService generationService;

    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    // =================================================================== the editor

    @Test
    void addingAQuestionStoresItAgainstTheTemplateAndNeverAsRequired() throws Exception {
        Fixture f = seed();

        addQuestion(f.templateId(), "pipes_checked", "آیا همه لوله‌ها بازدید شد؟", "checkbox")
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("successMessage"));

        List<LogSheetField> stored = logSheetFieldRepository.findForTemplate(f.templateId());
        assertThat(stored).hasSize(1);
        LogSheetField field = stored.get(0);
        assertThat(field.getTemplateId()).isEqualTo(f.templateId());
        assertThat(field.getLogSheetId()).isNull();
        assertThat(field.getKey()).isEqualTo("pipes_checked");
        assertThat(field.getVersion()).isEqualTo(1);
        assertThat(field.isDeleted()).isFalse();
        // Not required, because the form did not say so — an absent checkbox is false. The
        // editor does offer `required` since V10; what makes that safe is where it is JUDGED,
        // which the two tests below pin down.
        assertThat(field.isRequired()).isFalse();
    }

    @Test
    void anAttachmentTypeIsAcceptedSinceV10GaveARoundLevelAnswerSomewhereToPutItsBytes()
            throws Exception {
        Fixture f = seed();

        // This used to be refused, and the refusal was right while `attachments.asset_id` was
        // NOT NULL: the answer could not have been stored. V10 made that column nullable, so a
        // question about the round itself can now ask for a photograph — which is the case the
        // whole asset-less round exists for, an area inspection whose evidence is the picture.
        addQuestion(f.templateId(), "photo", "عکس", "image")
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("successMessage"));

        List<LogSheetField> stored = logSheetFieldRepository.findForTemplate(f.templateId());
        assertThat(stored).hasSize(1);
        assertThat(stored.get(0).getDataType()).isEqualTo("image");
    }

    @Test
    void aQuestionIsStoredAsRequiredWhenTheEditorSaysSo() throws Exception {
        Fixture f = seed();

        mockMvc.perform(post("/log-sheet-templates/{tid}/fields", f.templateId())
                        .param("key", "pipes_checked")
                        .param("label", "آیا همه لوله‌ها بازدید شد؟")
                        .param("dataType", "checkbox")
                        .param("required", "true")
                        .param("order", "1")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("successMessage"));

        assertThat(logSheetFieldRepository.findForTemplate(f.templateId()).get(0).isRequired())
                .isTrue();
    }

    @Test
    void aKeyThatIsNotAnIdentifierIsRefused() throws Exception {
        Fixture f = seed();

        // The same rule as a class field, from the same validator: the key is the key of every
        // value in the stored JSON, and the PWA uses it verbatim as a form control name.
        addQuestion(f.templateId(), "has space", "بد", "text")
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("errorMessage"));
        assertThat(logSheetFieldRepository.findForTemplate(f.templateId())).isEmpty();
    }

    @Test
    void oneKeyPerTemplate_butTwoTemplatesMayShareAKey() throws Exception {
        Fixture f = seed();
        addQuestion(f.templateId(), "note", "یادداشت", "text").andExpect(status().is3xxRedirection());

        addQuestion(f.templateId(), "note", "دوباره", "text")
                .andExpect(flash().attributeExists("errorMessage"));
        assertThat(logSheetFieldRepository.findForTemplate(f.templateId())).hasSize(1);

        // Two templates may each ask a question called «note», and they must.
        Fixture other = seed();
        addQuestion(other.templateId(), "note", "یادداشت", "text").andExpect(status().is3xxRedirection());
        assertThat(logSheetFieldRepository.findForTemplate(other.templateId())).hasSize(1);
    }

    @Test
    void aFieldOfAnotherTemplateCannotBeEditedThroughThisOnesPath() throws Exception {
        Fixture owner = seed();
        Fixture other = seed();
        addQuestion(owner.templateId(), "note", "یادداشت", "text").andExpect(status().is3xxRedirection());
        Long fieldId = logSheetFieldRepository.findForTemplate(owner.templateId()).get(0).getId();

        // The path id decides access and the field id decides what is written, so the two have
        // to agree — or holding one template is a way into another's questions.
        mockMvc.perform(post("/log-sheet-templates/{tid}/fields/{fid}", other.templateId(), fieldId)
                        .param("key", "hijacked").param("label", "x").param("dataType", "text")
                        .param("order", "1").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("errorMessage"));

        assertThat(logSheetFieldRepository.findById(fieldId).orElseThrow().getKey()).isEqualTo("note");
    }

    @Test
    void switchingAQuestionOffKeepsTheRowAndStopsOfferingItOnNewRounds() throws Exception {
        Fixture f = seed();
        addQuestion(f.templateId(), "note", "یادداشت", "text").andExpect(status().is3xxRedirection());
        Long fieldId = logSheetFieldRepository.findForTemplate(f.templateId()).get(0).getId();

        mockMvc.perform(post("/log-sheet-templates/{tid}/fields/{fid}/state", f.templateId(), fieldId)
                        .param("disabled", "true").with(csrf()))
                .andExpect(status().is3xxRedirection());

        LogSheetField field = logSheetFieldRepository.findById(fieldId).orElseThrow();
        assertThat(field.isDeleted()).isTrue();
        assertThat(field.getVersion()).isEqualTo(2);

        // A round raised from now on does not ask it.
        LogSheet raised = generate(f);
        assertThat(raised.getSheetFieldDefinitionsSnapshot()).isNull();
    }

    @Test
    void aQuestionNobodyAnsweredCanBeDeleted_oneWithAnswersCannot() throws Exception {
        Fixture f = seed();
        addQuestion(f.templateId(), "note", "یادداشت", "text").andExpect(status().is3xxRedirection());
        Long fieldId = logSheetFieldRepository.findForTemplate(f.templateId()).get(0).getId();

        // Answered on a round of this template: deleting the definition now would orphan the
        // value exactly as AGENTS.md #157 describes, so it is refused and «غیرفعال» is offered.
        LogSheet sheet = generate(f);
        saveSheetForm(sheet.getId(), "note", "چیزی").andExpect(status().isOk());

        mockMvc.perform(post("/log-sheet-templates/{tid}/fields/{fid}/delete", f.templateId(), fieldId)
                        .with(csrf()))
                .andExpect(flash().attributeExists("errorMessage"));
        assertThat(logSheetFieldRepository.findById(fieldId)).isPresent();

        // A second question nobody has answered goes.
        addQuestion(f.templateId(), "typo", "اشتباه", "text").andExpect(status().is3xxRedirection());
        Long unansweredId = logSheetFieldRepository.findForTemplate(f.templateId()).stream()
                .filter(row -> "typo".equals(row.getKey())).findFirst().orElseThrow().getId();
        mockMvc.perform(post("/log-sheet-templates/{tid}/fields/{fid}/delete", f.templateId(), unansweredId)
                        .with(csrf()))
                .andExpect(flash().attributeExists("successMessage"));
        assertThat(logSheetFieldRepository.findById(unansweredId)).isEmpty();
    }

    // =================================================================== the freeze

    @Test
    void aRoundFreezesTheQuestionsItWasRaisedWith() throws Exception {
        Fixture f = seed();
        addQuestion(f.templateId(), "note", "یادداشت اول", "text").andExpect(status().is3xxRedirection());

        LogSheet sheet = generate(f);
        assertThat(sheet.getSheetFieldDefinitionsSnapshot()).hasSize(1);
        assertThat(sheet.getSheetFieldDefinitionsSnapshot().get(0).getLabel()).isEqualTo("یادداشت اول");

        // Editing the template afterwards must not re-describe a round already raised — the same
        // rule the per-asset schema has followed since V1.
        Long fieldId = logSheetFieldRepository.findForTemplate(f.templateId()).get(0).getId();
        mockMvc.perform(post("/log-sheet-templates/{tid}/fields/{fid}", f.templateId(), fieldId)
                        .param("key", "note").param("label", "یادداشت دوم").param("dataType", "text")
                        .param("order", "1").with(csrf()))
                .andExpect(status().is3xxRedirection());

        LogSheet reloaded = logSheetRepository.findById(sheet.getId()).orElseThrow();
        assertThat(reloaded.getSheetFieldDefinitionsSnapshot().get(0).getLabel()).isEqualTo("یادداشت اول");
    }

    @Test
    void aTemplateWithNoQuestionsRaisesASheetWithNoRoundLevelForm() {
        LogSheet sheet = generate(seed());
        // Null, not an empty array: "asks nothing" and "raised before the feature existed" are
        // the same thing, and they should look the same in the column.
        assertThat(sheet.getSheetFieldDefinitionsSnapshot()).isNull();
        assertThat(sheet.getSheetFormData()).isNull();
    }

    // =================================================================== answering

    @Test
    void theFillPageShowsTheQuestionsWithTheirOwnParameterNames() throws Exception {
        Fixture f = seed();
        addQuestion(f.templateId(), "note", "یادداشت", "text").andExpect(status().is3xxRedirection());
        LogSheet sheet = claimed(generate(f));

        String html = mockMvc.perform(get("/log-sheets/{id}/fill", sheet.getId()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("پرسش‌های این لاگ شیت");
        // `sfd_`, not `fd_`: parseEntryValues splits a name at the first `_` after index 3, so a
        // round-level field named fd_sheet_note would parse as entry id "sheet" and be dropped
        // without an error anywhere.
        assertThat(html).contains("name=\"sfd_note\"");
        assertThat(html).contains("name=\"sfd_present\"");
    }

    @Test
    void savingTheRoundLevelFormStoresTheAnswersAndTheirAuthorship() throws Exception {
        Fixture f = seed();
        addQuestion(f.templateId(), "note", "یادداشت", "text").andExpect(status().is3xxRedirection());
        LogSheet sheet = claimed(generate(f));

        saveSheetForm(sheet.getId(), "note", "اولین پاسخ")
                .andExpect(status().isOk());

        LogSheet saved = logSheetRepository.findById(sheet.getId()).orElseThrow();
        assertThat(saved.getSheetFormData()).containsEntry("note", "اولین پاسخ");
        assertThat(saved.getSheetFormFilledByUserId()).isNotNull();
        assertThat(saved.getSheetFormUpdatedAt()).isNotNull();
        assertThat(saved.getSheetFormSource()).isEqualTo("WEB");
        // A first fill replaced nothing, so it leaves no history row. That is what keeps the
        // table proportional to corrections rather than to rounds.
        assertThat(logSheetRevisionRepository.findByLogSheetIdOrderByIdAsc(sheet.getId())).isEmpty();
    }

    @Test
    void aSaveThatChangesNothingWritesNoHistoryAndMovesNoAuthorship() throws Exception {
        Fixture f = seed();
        addQuestion(f.templateId(), "note", "یادداشت", "text").andExpect(status().is3xxRedirection());
        LogSheet sheet = claimed(generate(f));
        saveSheetForm(sheet.getId(), "note", "پاسخ").andExpect(status().isOk());
        Long firstStamp = logSheetRepository.findById(sheet.getId()).orElseThrow().getSheetFormUpdatedAt();

        saveSheetForm(sheet.getId(), "note", "پاسخ").andExpect(status().isOk());

        LogSheet after = logSheetRepository.findById(sheet.getId()).orElseThrow();
        assertThat(after.getSheetFormUpdatedAt()).isEqualTo(firstStamp);
        assertThat(logSheetRevisionRepository.findByLogSheetIdOrderByIdAsc(sheet.getId())).isEmpty();
    }

    @Test
    void aCorrectionKeepsWhatItReplaced() throws Exception {
        Fixture f = seed();
        addQuestion(f.templateId(), "note", "یادداشت", "text").andExpect(status().is3xxRedirection());
        LogSheet sheet = claimed(generate(f));
        saveSheetForm(sheet.getId(), "note", "پاسخ اول").andExpect(status().isOk());

        saveSheetForm(sheet.getId(), "note", "پاسخ دوم").andExpect(status().isOk());

        var history = logSheetRevisionRepository.findByLogSheetIdOrderByIdAsc(sheet.getId());
        assertThat(history).hasSize(1);
        // The row holds the OLD value; the current one always lives on the sheet.
        assertThat(history.get(0).getFormData()).containsEntry("note", "پاسخ اول");
        assertThat(history.get(0).getSupersededSource()).isEqualTo(ActionSource.WEB);
        assertThat(history.get(0).getSheetStatus()).isNotNull();
        assertThat(logSheetRepository.findById(sheet.getId()).orElseThrow().getSheetFormData())
                .containsEntry("note", "پاسخ دوم");
    }

    @Test
    void clearingAnAnswerRemovesTheKeyRatherThanStoringABlank() throws Exception {
        Fixture f = seed();
        addQuestion(f.templateId(), "note", "یادداشت", "text").andExpect(status().is3xxRedirection());
        LogSheet sheet = claimed(generate(f));
        saveSheetForm(sheet.getId(), "note", "پاسخ").andExpect(status().isOk());

        // An empty text input posts "", which is what CLEARS a value — the marker is what makes
        // "cleared" distinguishable from "the section was not submitted".
        saveSheetForm(sheet.getId(), "note", "").andExpect(status().isOk());

        LogSheet after = logSheetRepository.findById(sheet.getId()).orElseThrow();
        assertThat(after.getSheetFormData()).isNull();
        assertThat(logSheetRevisionRepository.findByLogSheetIdOrderByIdAsc(sheet.getId())).hasSize(1);
    }

    @Test
    void aKeyTheRoundNeverAskedIsDropped() throws Exception {
        Fixture f = seed();
        addQuestion(f.templateId(), "note", "یادداشت", "text").andExpect(status().is3xxRedirection());
        LogSheet sheet = claimed(generate(f));

        mockMvc.perform(post("/log-sheets/{id}/sheet-form/draft", sheet.getId())
                        .param("sfd_present", "1")
                        .param("sfd_note", "درست")
                        .param("sfd_injected", "نباید ذخیره شود")
                        .with(csrf()))
                .andExpect(status().isOk());

        assertThat(logSheetRepository.findById(sheet.getId()).orElseThrow().getSheetFormData())
                .containsOnlyKeys("note");
    }

    @Test
    void aMalformedAnswerIsRefusedRatherThanStored() throws Exception {
        Fixture f = seed();
        addQuestion(f.templateId(), "pressure", "فشار", "number").andExpect(status().is3xxRedirection());
        LogSheet sheet = claimed(generate(f));

        // 422 with the sentence as the body rather than a redirect: the caller is a fetch on
        // the fill page, and a redirect reaches it as a message about an expired session.
        mockMvc.perform(post("/log-sheets/{id}/sheet-form/draft", sheet.getId())
                        .param("sfd_present", "1")
                        .param("sfd_pressure", "خیلی زیاد")
                        .with(csrf()))
                .andExpect(status().isUnprocessableEntity());

        assertThat(logSheetRepository.findById(sheet.getId()).orElseThrow().getSheetFormData()).isNull();
    }

    @Test
    void savingOneAssetLeavesTheRoundLevelAnswersAlone() throws Exception {
        Fixture f = seed();
        addQuestion(f.templateId(), "note", "یادداشت", "text").andExpect(status().is3xxRedirection());
        LogSheet sheet = claimed(generate(f));
        saveSheetForm(sheet.getId(), "note", "پاسخ").andExpect(status().isOk());
        Long entryId = logSheetEntryRepository.findByLogSheetId(sheet.getId()).get(0).getId();

        // The per-asset dialog carries no `sfd_` parameters at all, and null means "not
        // submitted" rather than "cleared".
        mockMvc.perform(post("/log-sheets/{id}/entries/{entryId}/draft", sheet.getId(), entryId)
                        .param("fd_present_" + entryId, "1")
                        .param("fd_" + entryId + "_temp", "42")
                        .with(csrf()))
                .andExpect(status().isOk());

        assertThat(logSheetRepository.findById(sheet.getId()).orElseThrow().getSheetFormData())
                .containsEntry("note", "پاسخ");
    }

    @Test
    void completingTheRoundStoresTheAnswersThatCameWithIt() throws Exception {
        Fixture f = seed();
        addQuestion(f.templateId(), "note", "یادداشت", "text").andExpect(status().is3xxRedirection());
        LogSheet sheet = claimed(generate(f));

        mockMvc.perform(post("/log-sheets/{id}/complete", sheet.getId())
                        .param("sfd_present", "1")
                        .param("sfd_note", "در پایان دور")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection());

        LogSheet done = logSheetRepository.findById(sheet.getId()).orElseThrow();
        assertThat(done.getStatus()).isEqualTo(LogSheetStatus.SUBMITTED);
        assertThat(done.getSheetFormData()).containsEntry("note", "در پایان دور");
    }

    // ============================================================ where `required` is judged

    @Test
    void aRequiredQuestionRefusesTheRoundLevelFormsOwnSave() throws Exception {
        Fixture f = seed();
        addRequiredQuestion(f.templateId(), "pipes", "آیا لوله‌ها بازدید شد؟", "text");
        LogSheet sheet = claimed(generate(f));

        // The one request a person makes IN ORDER TO answer these questions. Refusing here is
        // help: they are looking at the form, and the fix is one field away. 422 with the
        // sentence as the body, not a redirect — the caller is a fetch that shows it in place.
        mockMvc.perform(post("/log-sheets/{id}/sheet-form/draft", sheet.getId())
                        .param("sfd_present", "1")
                        .param("sfd_pipes", "")
                        .with(csrf()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.emptyString())));

        assertThat(logSheetRepository.findById(sheet.getId()).orElseThrow().getSheetFormData())
                .isNull();
    }

    @Test
    void aRequiredQuestionNeverRefusesTheCompletion() throws Exception {
        Fixture f = seed();
        addRequiredQuestion(f.templateId(), "pipes", "آیا لوله‌ها بازدید شد؟", "text");
        LogSheet sheet = claimed(generate(f));

        // THE RULE. A round's readings are the work; an unanswered round-level question is not
        // a reason to refuse them. If this ever starts failing, an operator somewhere is
        // standing at the equipment with a full queue and no way to deliver it.
        mockMvc.perform(post("/log-sheets/{id}/complete", sheet.getId())
                        .param("sfd_present", "1")
                        .param("sfd_pipes", "")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("successMessage"));

        assertThat(logSheetRepository.findById(sheet.getId()).orElseThrow().getStatus())
                .isEqualTo(LogSheetStatus.SUBMITTED);
    }

    @Test
    void aRequiredQuestionStillRefusesAMalformedAnswerOnEveryPath() throws Exception {
        Fixture f = seed();
        addRequiredQuestion(f.templateId(), "count", "تعداد", "number");
        LogSheet sheet = claimed(generate(f));

        // What the completion DOES check: the shape of what it was given. Not required-ness —
        // a value that is not a number could not be stored or reported at all. An ordinary form
        // post, so the refusal is the usual redirect carrying the sentence.
        mockMvc.perform(post("/log-sheets/{id}/complete", sheet.getId())
                        .param("sfd_present", "1")
                        .param("sfd_count", "چهار")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("errorMessage"));

        assertThat(logSheetRepository.findById(sheet.getId()).orElseThrow().getStatus())
                .isEqualTo(LogSheetStatus.IN_PROGRESS);
    }

    @Test
    void theRoundLevelSaveAcceptsARequiredQuestionOnceItIsAnswered() throws Exception {
        Fixture f = seed();
        addRequiredQuestion(f.templateId(), "pipes", "آیا لوله‌ها بازدید شد؟", "text");
        LogSheet sheet = claimed(generate(f));

        saveSheetForm(sheet.getId(), "pipes", "بله").andExpect(status().isOk());

        assertThat(logSheetRepository.findById(sheet.getId()).orElseThrow().getSheetFormData())
                .containsEntry("pipes", "بله");
    }

    @Test
    void theDetailPageShowsTheAnswersAndWhatACorrectionReplaced() throws Exception {
        Fixture f = seed();
        addQuestion(f.templateId(), "note", "یادداشت", "text").andExpect(status().is3xxRedirection());
        LogSheet sheet = claimed(generate(f));
        saveSheetForm(sheet.getId(), "note", "پاسخ اول").andExpect(status().isOk());
        saveSheetForm(sheet.getId(), "note", "پاسخ دوم").andExpect(status().isOk());

        String html = mockMvc.perform(get("/log-sheets/{id}", sheet.getId()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("پرسش‌های این لاگ شیت");
        assertThat(html).contains("پاسخ دوم");
        assertThat(html).contains("سوابق ویرایش پاسخ پرسش‌ها");
        assertThat(html).contains("پاسخ اول");
    }

    @Test
    void aSheetWithNoQuestionsShowsNoRoundLevelCardAnywhere() throws Exception {
        Fixture f = seed();
        LogSheet sheet = claimed(generate(f));

        String fill = mockMvc.perform(get("/log-sheets/{id}/fill", sheet.getId()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String detail = mockMvc.perform(get("/log-sheets/{id}", sheet.getId()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        // Every page includes the fragment unconditionally, so it has to render nothing at all
        // when the round asks nothing — which is every sheet raised before this feature.
        assertThat(fill).doesNotContain("پرسش‌های این لاگ شیت");
        assertThat(detail).doesNotContain("پرسش‌های این لاگ شیت");
    }

    // =================================================================== helpers

    private record Fixture(Long templateId, Long unitId, Long classId) {}

    private org.springframework.test.web.servlet.ResultActions addQuestion(
            Long templateId, String key, String label, String dataType) throws Exception {
        return mockMvc.perform(post("/log-sheet-templates/{tid}/fields", templateId)
                .param("key", key)
                .param("label", label)
                .param("dataType", dataType)
                .param("order", "1")
                .with(csrf()));
    }

    /** The same, with `required` on — the flag whose enforcement point is the whole point. */
    private void addRequiredQuestion(Long templateId, String key, String label, String dataType)
            throws Exception {
        mockMvc.perform(post("/log-sheet-templates/{tid}/fields", templateId)
                        .param("key", key)
                        .param("label", label)
                        .param("dataType", dataType)
                        .param("required", "true")
                        .param("order", "1")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("successMessage"));
    }

    private org.springframework.test.web.servlet.ResultActions saveSheetForm(
            Long sheetId, String key, String value) throws Exception {
        return mockMvc.perform(post("/log-sheets/{id}/sheet-form/draft", sheetId)
                .param("sfd_present", "1")
                .param("sfd_" + key, value)
                .with(csrf()));
    }

    private LogSheet generate(Fixture f) {
        LogSheetTemplate template = templateRepository.findById(f.templateId()).orElseThrow();
        return generationService.generateFromTemplate(
                template, GenerationMode.MANUAL, null, System.currentTimeMillis());
    }

    /** A sheet the signed-in user may fill on the web: assigned to them, with a live deadline. */
    private LogSheet claimed(LogSheet sheet) {
        sheet.setStatus(LogSheetStatus.IN_PROGRESS);
        sheet.setDueAt(System.currentTimeMillis() + 3_600_000L);
        return logSheetRepository.saveAndFlush(sheet);
    }

    private Fixture seed() {
        long now = System.currentTimeMillis();
        long nano = System.nanoTime();

        OperationalUnit unit = new OperationalUnit();
        unit.setCode("SQ-BU-" + nano);
        unit.setName("Sheet Question Unit");
        unit.setCreatedAt(now);
        unit.setUpdatedAt(now);
        unit = operationalUnitRepository.saveAndFlush(unit);

        Location location = new Location();
        location.setCode("SQ-LOC-" + nano);
        location.setName("Hall");
        location.setCreatedAt(now);
        location.setUpdatedAt(now);
        location = locationRepository.saveAndFlush(location);

        SubFunction subFunction = new SubFunction();
        subFunction.setCode("SQ-SF-" + nano);
        subFunction.setName("Sub");
        subFunction.setTag("NFC-SQ-" + nano);
        subFunction.setCreatedAt(now);
        subFunction.setUpdatedAt(now);
        hierarchyService.applySubFunctionParent(
                subFunction, AssetHierarchyService.SCOPE_LOCATION, location.getId());
        subFunction = hierarchyService.saveSubFunction(subFunction);

        AssetClass assetClass = new AssetClass();
        assetClass.setName("SQ Pump " + nano);
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
        asset.setAssetCode("SQ-A1-" + nano);
        asset.setAssetName("Pump");
        asset.setClassId(assetClass.getId());
        asset.setSubFunctionId(subFunction.getId());
        asset.setCreatedAt(now);
        asset.setUpdatedAt(now);
        assetEntryRepository.saveAndFlush(asset);

        LogSheetTemplate template = new LogSheetTemplate();
        template.setName("SQ Template " + nano);
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

        return new Fixture(template.getId(), unit.getId(), assetClass.getId());
    }
}
