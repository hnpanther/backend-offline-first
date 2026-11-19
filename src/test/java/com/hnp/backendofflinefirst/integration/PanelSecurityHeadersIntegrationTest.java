package com.hnp.backendofflinefirst.integration;

import com.hnp.backendofflinefirst.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * Stage 1 of roadmap §15 «Content-Security-Policy»: the panel sends the headers that cannot break
 * a page, enforced, and the full policy report-only. What this pins is the line between the two —
 * an enforced {@code script-src} would switch off the inline handlers 35 templates still carry.
 */
class PanelSecurityHeadersIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired WebApplicationContext context;
    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    @DisplayName("the panel enforces only frame-ancestors, object-src and base-uri")
    void enforcedPolicyIsTheSafeOne() throws Exception {
        MvcResult r = mockMvc.perform(get("/login")).andReturn();
        String enforced = r.getResponse().getHeader("Content-Security-Policy");
        assertThat(enforced).isEqualTo("frame-ancestors 'none'; object-src 'none'; base-uri 'self'");
        assertThat(enforced).as("an enforced script-src would break every inline handler")
                .doesNotContain("script-src").doesNotContain("default-src");
        assertThat(r.getResponse().getHeader("Referrer-Policy")).isEqualTo("same-origin");
        assertThat(r.getResponse().getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(r.getResponse().getHeader("X-Frame-Options")).isEqualTo("DENY");
    }

    @Test
    @DisplayName("the target policy is sent report-only, so the browser blocks nothing it names")
    void fullPolicyIsReportOnly() throws Exception {
        MvcResult r = mockMvc.perform(get("/login")).andReturn();
        String reportOnly = r.getResponse().getHeader("Content-Security-Policy-Report-Only");
        assertThat(reportOnly)
                .contains("default-src 'self'")
                .contains("script-src 'self';")
                .contains("connect-src 'self'")
                .contains("form-action 'self'")
                .contains("frame-ancestors 'none'");
        assertThat(reportOnly).as("never 'unsafe-inline' for scripts — it would defeat the policy")
                .doesNotContain("script-src 'self' 'unsafe-inline'");
    }

    @Test
    @DisplayName("an authenticated panel redirect carries the headers too, not just the login page")
    void redirectCarriesTheHeaders() throws Exception {
        MvcResult r = mockMvc.perform(get("/log-sheets")).andReturn();
        assertThat(r.getResponse().getHeader("Content-Security-Policy")).contains("frame-ancestors 'none'");
        assertThat(r.getResponse().getHeader("Content-Security-Policy-Report-Only")).isNotNull();
    }

    @Test
    @DisplayName("the mobile API and the integration API keep their own chains, without the panel's policy")
    void apiChainsAreUntouched() throws Exception {
        MvcResult api = mockMvc.perform(get("/api/log-sheets/inbox")).andReturn();
        assertThat(api.getResponse().getHeader("Content-Security-Policy-Report-Only")).isNull();
        MvcResult integration = mockMvc.perform(get("/integration/v1/log-sheets")).andReturn();
        assertThat(integration.getResponse().getHeader("Content-Security-Policy-Report-Only")).isNull();
    }
}
