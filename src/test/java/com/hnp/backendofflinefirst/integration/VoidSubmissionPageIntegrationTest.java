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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * The voided-submission detail page. The guard that matters: the {@code voidId} must belong to
 * the {@code id} in the path, otherwise any submission would be readable from any sheet URL.
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
        v.setReason("SUPERSEDED");
        v.setPayload(List.of(item));
        return voidSubmissionRepository.saveAndFlush(v);
    }
}
