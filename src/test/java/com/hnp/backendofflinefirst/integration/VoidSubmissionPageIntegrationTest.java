package com.hnp.backendofflinefirst.integration;

import com.hnp.backendofflinefirst.domain.GenerationMode;
import com.hnp.backendofflinefirst.domain.LogSheetStatus;
import com.hnp.backendofflinefirst.entity.LogSheet;
import com.hnp.backendofflinefirst.entity.LogSheetVoidSubmission;
import com.hnp.backendofflinefirst.entity.OperationalUnit;
import com.hnp.backendofflinefirst.repository.LogSheetRepository;
import com.hnp.backendofflinefirst.repository.LogSheetVoidSubmissionRepository;
import com.hnp.backendofflinefirst.repository.OperationalUnitRepository;
import com.hnp.backendofflinefirst.support.AbstractPostgresIntegrationTest;
import com.hnp.backendofflinefirst.support.WithAppUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * Voided offline submissions, on both pages that show them.
 *
 * <p>The guard that matters on the dedicated page: the {@code voidId} must belong to the
 * {@code id} in the path, otherwise any submission would be readable from any sheet URL.
 *
 * <p>The sheet page lists the same submissions in summary, and the reason has to read in Persian
 * on both — it is stored as an English sentence written by {@code LogSheetService}.
 */
class VoidSubmissionPageIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired WebApplicationContext context;
    @Autowired LogSheetRepository logSheetRepository;
    @Autowired LogSheetVoidSubmissionRepository voidSubmissionRepository;
    @Autowired OperationalUnitRepository operationalUnitRepository;

    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    @WithAppUser(authorities = "GET:/log-sheets/{id}", roles = "ADMIN")
    void rendersTheSubmittedValuesOfAVoidedSubmission() throws Exception {
        LogSheet sheet = saveSheet("Void page sheet");
        LogSheetVoidSubmission v = saveVoid(sheet.getId(), "MY-SECRET-READING");

        mockMvc.perform(get("/log-sheets/{id}/void-submissions/{voidId}", sheet.getId(), v.getId()))
                .andExpect(status().isOk())
                .andExpect(view().name("log-sheet-void-submission"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("MY-SECRET-READING")));
    }

    @Test
    @WithAppUser(authorities = "GET:/log-sheets/{id}", roles = "ADMIN")
    void aVoidIdBelongingToAnotherSheetIsRejectedAndLeaksNothing() throws Exception {
        LogSheet owner = saveSheet("Owner sheet");
        LogSheet unrelated = saveSheet("Unrelated sheet");
        LogSheetVoidSubmission v = saveVoid(owner.getId(), "MUST-NOT-LEAK");

        mockMvc.perform(get("/log-sheets/{id}/void-submissions/{voidId}", unrelated.getId(), v.getId()))
                .andExpect(status().is3xxRedirection())
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("MUST-NOT-LEAK"))));
    }

    @Test
    @WithAppUser(authorities = "GET:/log-sheets/{id}", roles = "ADMIN")
    void anUnknownVoidIdIsRejected() throws Exception {
        LogSheet sheet = saveSheet("Unknown void sheet");

        mockMvc.perform(get("/log-sheets/{id}/void-submissions/{voidId}", sheet.getId(), 9_999_999L))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    void anonymousCannotOpenTheVoidPage() throws Exception {
        mockMvc.perform(get("/log-sheets/1/void-submissions/1"))
                .andExpect(status().is3xxRedirection());
    }

    /**
     * The sheet's own page shows the void reason in Persian, not the sentence the server stored.
     *
     * <p>{@code LogSheetService} writes a fixed English sentence when it voids a submission, and
     * both pages that show it have to translate it. The dedicated page did;
     * {@code log-sheet-detail.html} printed {@code ${v.reason}} straight through, so a supervisor
     * reading the sheet got English while the page one click further along said the same thing in
     * Persian. Nothing failed — it just read as untranslated, which is why this asserts on the
     * page the reason is *first* seen on rather than only on the detail page.
     *
     * <p>The reason used here is copied from {@code LogSheetService}: a fixture that invents its
     * own wording would pass while the real sentence fell through the translation untouched.
     */
    @Test
    @WithAppUser(authorities = "GET:/log-sheets/{id}", roles = "ADMIN")
    void theSheetPageShowsTheVoidReasonInPersian() throws Exception {
        LogSheet sheet = saveSheet("Void reason sheet");
        saveVoid(sheet.getId(), "READING", "This log sheet was already completed by someone else.");

        String html = mockMvc.perform(get("/log-sheets/{id}", sheet.getId()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(html)
                .as("the voided-sync table should read in Persian")
                .contains("این لاگ‌شیت را پیش‌تر شخص دیگری تکمیل کرده بود.");
        assertThat(html)
                .as("the stored English sentence should not be printed on this page")
                .doesNotContain("This log sheet was already completed by someone else.");
    }

    /**
     * A reason the helper has no translation for is printed as it stands.
     *
     * <p>The counterweight to the test above: "translate it" must not become "hide anything
     * unrecognised". A voided submission is the record of somebody's lost work, and a sentence a
     * future version starts writing is still the truth about why it was discarded.
     */
    @Test
    @WithAppUser(authorities = "GET:/log-sheets/{id}", roles = "ADMIN")
    void anUntranslatedReasonIsStillShown() throws Exception {
        LogSheet sheet = saveSheet("Unknown reason sheet");
        saveVoid(sheet.getId(), "READING", "Some reason no version has translated yet.");

        String html = mockMvc.perform(get("/log-sheets/{id}", sheet.getId()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(html).contains("Some reason no version has translated yet.");
    }

    // ---- the round's own answers (V14, roadmap §34) ----

    /**
     * The review page shows what the departed operator answered at round level, labelled with the
     * round's own wording, and the sheet page says which voids carry such answers.
     *
     * <p>Asserted on the rendered pages, not on the model: the page goes through the layout's
     * {@code th:replace}, and markup that lands outside {@code #pageContent} is silently dropped
     * (AGENTS.md) — a green model and an empty screen is the failure this rules out.
     */
    @Test
    @WithAppUser(authorities = "GET:/log-sheets/{id}", roles = "ADMIN")
    void theRoundsOwnAnswersAreShownOnTheVoidPageAndFlaggedOnTheSheet() throws Exception {
        LogSheet sheet = saveSheet("Void round answers sheet");
        sheet.setSheetFieldDefinitionsSnapshot(List.of(question("area_ok", "وضعیت کلی محوطه")));
        logSheetRepository.saveAndFlush(sheet);
        LogSheetVoidSubmission v = saveVoid(sheet.getId(), "ASSET-READING");
        v.setSheetFormData(Map.of("area_ok", "ROUND-ANSWER-محوطه"));
        voidSubmissionRepository.saveAndFlush(v);

        String page = mockMvc.perform(get("/log-sheets/{id}/void-submissions/{voidId}", sheet.getId(), v.getId()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(page)
                .contains("پرسش‌های این کار")
                .contains("وضعیت کلی محوطه")
                .contains("ROUND-ANSWER-محوطه")
                // The assets are still there beside it.
                .contains("ASSET-READING");

        String sheetPage = mockMvc.perform(get("/log-sheets/{id}", sheet.getId()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(sheetPage).contains("+ پرسش‌های کار");
    }

    /**
     * An asset-less round's void is nothing BUT round answers. Before V14 it was an empty payload,
     * and the page said «داده‌ای در این ارسال وجود ندارد» over work that had been done.
     */
    @Test
    @WithAppUser(authorities = "GET:/log-sheets/{id}", roles = "ADMIN")
    void anAssetLessRoundsVoidIsNotCalledEmpty() throws Exception {
        LogSheet sheet = saveSheet("Void asset-less sheet");
        sheet.setSheetFieldDefinitionsSnapshot(List.of(question("note", "یادداشت")));
        logSheetRepository.saveAndFlush(sheet);
        LogSheetVoidSubmission v = new LogSheetVoidSubmission();
        v.setLogSheetId(sheet.getId());
        v.setCompletedAt(System.currentTimeMillis() - 60_000);
        v.setSyncedAt(System.currentTimeMillis());
        v.setReason("This log sheet is no longer assigned to you.");
        v.setPayload(List.of());
        v.setSheetFormData(Map.of("note", "ONLY-ROUND-ANSWER"));
        v = voidSubmissionRepository.saveAndFlush(v);

        String page = mockMvc.perform(get("/log-sheets/{id}/void-submissions/{voidId}", sheet.getId(), v.getId()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(page)
                .contains("ONLY-ROUND-ANSWER")
                .doesNotContain("داده‌ای در این ارسال وجود ندارد");
    }

    @Test
    @WithAppUser(authorities = "GET:/log-sheets/{id}", roles = "ADMIN")
    void aVoidWithoutRoundAnswersRendersAsItAlwaysDid() throws Exception {
        LogSheet sheet = saveSheet("Void without round answers");
        LogSheetVoidSubmission v = saveVoid(sheet.getId(), "PLAIN-READING");

        String page = mockMvc.perform(get("/log-sheets/{id}/void-submissions/{voidId}", sheet.getId(), v.getId()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(page).contains("PLAIN-READING").doesNotContain("voidRoundTitle");
        String sheetPage = mockMvc.perform(get("/log-sheets/{id}", sheet.getId()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(sheetPage).doesNotContain("+ پرسش‌های کار");
    }

    private static com.hnp.backendofflinefirst.domain.FieldDefinitionSnapshot question(String key, String label) {
        com.hnp.backendofflinefirst.entity.FieldDefinition fd = new com.hnp.backendofflinefirst.entity.FieldDefinition();
        fd.setKey(key);
        fd.setLabel(label);
        fd.setDataType("text");
        fd.setOrder(1);
        fd.setVersion(1);
        return com.hnp.backendofflinefirst.domain.FieldDefinitionSnapshot.from(fd);
    }

    // ---- helpers ----

    private LogSheet saveSheet(String name) {
        long now = System.currentTimeMillis();
        OperationalUnit unit = new OperationalUnit();
        unit.setCode("OU-VOIDPAGE-" + System.nanoTime());
        unit.setName("Void page unit");
        unit.setCreatedAt(now);
        unit.setUpdatedAt(now);
        unit = operationalUnitRepository.saveAndFlush(unit);

        LogSheet sheet = new LogSheet();
        sheet.setTemplateName(name);
        sheet.setOperationalUnitId(unit.getId());
        sheet.setStatus(LogSheetStatus.PENDING);
        sheet.setOrigin(GenerationMode.MANUAL);
        sheet.setCreatedAt(now);
        sheet.setUpdatedAt(now);
        return logSheetRepository.saveAndFlush(sheet);
    }

    private LogSheetVoidSubmission saveVoid(Long sheetId, String markerValue) {
        return saveVoid(sheetId, markerValue, "SUPERSEDED");
    }

    private LogSheetVoidSubmission saveVoid(Long sheetId, String markerValue, String reason) {
        Map<String, Object> formData = new LinkedHashMap<>();
        formData.put("reading", markerValue);
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("assetId", null);
        item.put("assetName", "دارایی آزمایشی");
        item.put("formData", formData);
        item.put("updatedAt", System.currentTimeMillis());

        LogSheetVoidSubmission v = new LogSheetVoidSubmission();
        v.setLogSheetId(sheetId);
        v.setCompletedAt(System.currentTimeMillis() - 60_000);
        v.setSyncedAt(System.currentTimeMillis());
        v.setReason(reason);
        v.setPayload(List.of(item));
        return voidSubmissionRepository.saveAndFlush(v);
    }
}
