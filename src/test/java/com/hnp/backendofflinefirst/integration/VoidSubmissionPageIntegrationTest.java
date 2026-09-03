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
