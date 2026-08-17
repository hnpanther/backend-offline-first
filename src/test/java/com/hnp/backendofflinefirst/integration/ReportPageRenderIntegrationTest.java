package com.hnp.backendofflinefirst.integration;

import com.hnp.backendofflinefirst.support.AbstractPostgresIntegrationTest;
import com.hnp.backendofflinefirst.support.WithAppUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The report pages actually render.
 *
 * <p>A Thymeleaf expression is checked at render time, not at compile time: a mistyped accessor
 * on a paging record — `totalPages()` against `totalPages` — passes every unit test and then
 * throws a 500 the first time somebody opens the page. The service-level tests around these
 * reports say the numbers are right; these say the page comes back at all.
 *
 * <p>Kept deliberately shallow. It asks for the page, checks it is a page, and checks the pager
 * survived — nothing about the figures, which are pinned where they are computed.
 */
class ReportPageRenderIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired WebApplicationContext context;

    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    @WithAppUser(authorities = "GET:/reports")
    void dataQualityRendersWithItsPager() throws Exception {
        mockMvc.perform(get("/reports/data-quality"))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("silentPage", "pageSize"))
                // The page-size control the operator changes, and the default it lands on.
                .andExpect(content().string(containsString("name=\"size\"")))
                .andExpect(content().string(containsString("name=\"page\"")));
    }

    @Test
    @WithAppUser(authorities = "GET:/reports")
    void dataQualityAcceptsAnExplicitPageAndSize() throws Exception {
        mockMvc.perform(get("/reports/data-quality").param("page", "0").param("size", "250"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("pageSize", 250));
    }

    @Test
    @WithAppUser(authorities = "GET:/reports")
    void dataQualityCapsAnAbsurdPageSizeRatherThanRendering100000Rows() throws Exception {
        mockMvc.perform(get("/reports/data-quality").param("size", "100000"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("pageSize", 250));
    }

    @Test
    @WithAppUser(authorities = "GET:/reports")
    void aPageBeyondTheEndStillRenders() throws Exception {
        // Reachable by editing the URL, and by a stale bookmark after the data shrinks.
        mockMvc.perform(get("/reports/data-quality").param("page", "9999"))
                .andExpect(status().isOk());
    }

    @Test
    @WithAppUser(authorities = "GET:/reports")
    void dataQualityPagesItsTwoListsIndependently() throws Exception {
        // Two pagers on one page. Sharing a page number would move the section the operator was
        // not looking at, so each carries its own — and both have to survive being set.
        mockMvc.perform(get("/reports/data-quality").param("page", "1").param("nfcPage", "2"))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("silentPage", "nfcFaultPage"));
    }

    @Test
    @WithAppUser(authorities = "GET:/reports")
    void theAssetInventoryOffersItsPageSize() throws Exception {
        mockMvc.perform(get("/reports").param("size", "250"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("pageSize", 250))
                .andExpect(content().string(containsString("name=\"size\"")));
    }

    @Test
    @WithAppUser(authorities = "GET:/reports")
    void theAssetParametersPageOffersItsPageSize() throws Exception {
        mockMvc.perform(get("/reports/asset-parameters"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("name=\"size\"")));
    }

    @Test
    @WithAppUser(authorities = "GET:/asset-entries")
    void aMasterDataListOffersThePageSizeToo() throws Exception {
        // The shared toolbar's hidden `size` field became the visible control, so every list page
        // gained it at once — the parameter already worked on all of them.
        mockMvc.perform(get("/asset-entries"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("name=\"size\"")));
    }

    @Test
    @WithAppUser(authorities = "GET:/reports")
    void exceptionsStillRendersAfterItsPageSizesChanged() throws Exception {
        mockMvc.perform(get("/reports/exceptions"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("name=\"size\"")));
    }

    @Test
    void reportsAreNotReadableWithoutThePermission() throws Exception {
        // The pages gained parameters; they must not have gained a way in.
        mockMvc.perform(get("/reports/data-quality"))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    @WithAppUser(authorities = "GET:/asset-entries")
    void theReportPermissionIsNotSatisfiedByAnUnrelatedOne() throws Exception {
        // The panel answers a refusal with a redirect and a flash message rather than a bare 403
        // — `WebAccessDeniedHandler`. What matters here is that the report is not rendered.
        mockMvc.perform(get("/reports/data-quality"))
                .andExpect(status().is3xxRedirection())
                .andExpect(model().attributeDoesNotExist("silentPage"));
    }
}
