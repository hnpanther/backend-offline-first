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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The landing page comes back, for each period, and refuses one it does not know.
 *
 * <p>Kept shallow on purpose — {@code DashboardIntegrationTest} pins the numbers. What this adds
 * is the thing neither a unit test nor a service test can see: a Thymeleaf expression is checked
 * at <em>render</em> time, so a mistyped accessor on a record — {@code total().generated} against
 * {@code total().generated()} — compiles, passes every test about the figures, and then throws a
 * 500 on the first page of every login. {@code ddl-auto=validate} proves the entities match the
 * schema; only a render proves the page renders.
 */
class DashboardPageIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired WebApplicationContext context;

    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    @WithAppUser(authorities = "GET:/")
    void theDashboardRendersWithItsPeriodSelectorAndItsUnitTable() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("dashboard"))
                .andExpect(model().attribute("period", "WEEK"))
                // The control that changes the window, and the table the picker decorates.
                .andExpect(content().string(containsString("name=\"period\"")))
                .andExpect(content().string(containsString("data-enterprise-table-key=\"0\"")))
                .andExpect(content().string(containsString("به تفکیک واحد عملیاتی")));
    }

    @Test
    @WithAppUser(authorities = "GET:/")
    void eachPeriodIsAccepted() throws Exception {
        for (String period : new String[]{"DAY", "WEEK", "MONTH"}) {
            mockMvc.perform(get("/").param("period", period))
                    .andExpect(status().isOk())
                    .andExpect(model().attribute("period", period));
        }
    }

    @Test
    @WithAppUser(authorities = "GET:/")
    void aLowercasePeriodFromAHandEditedUrlStillWorks() throws Exception {
        mockMvc.perform(get("/").param("period", "month"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("period", "MONTH"));
    }

    @Test
    @WithAppUser(authorities = "GET:/")
    void anUnknownPeriodIsRefusedRatherThanQuietlyShowingAnotherWindow() throws Exception {
        // Coercing to the default would answer 200 with a window the caller did not ask for,
        // which is how somebody reads the wrong month and acts on it. The redirect goes back to
        // the dashboard with no parameter, so the reader lands on a page that works.
        mockMvc.perform(get("/").param("period", "QUARTER"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"))
                .andExpect(flash().attributeExists("errorMessage"));
    }

    @Test
    @WithAppUser(authorities = "GET:/my-inbox")
    void withoutTheAuthorityThePageIsNotServed() throws Exception {
        // The only access rule this page has. Who holds `GET:/` is V16's business, not the
        // controller's — which is what lets an administrator take it away from the Roles page.
        mockMvc.perform(get("/")).andExpect(status().is3xxRedirection());
    }
}
