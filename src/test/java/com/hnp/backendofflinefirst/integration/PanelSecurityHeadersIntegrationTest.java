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
 * Roadmap §15 stage 3: the panel enforces {@code script-src 'self'}. Swagger UI is the one
 * path that still needs inline script, and it gets its own policy.
 */
class PanelSecurityHeadersIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired WebApplicationContext context;
    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    @DisplayName("the panel enforces script-src self and does not allow unsafe-inline scripts")
    void enforcedPolicyBlocksInlineScript() throws Exception {
        MvcResult r = mockMvc.perform(get("/login")).andReturn();
        String enforced = r.getResponse().getHeader("Content-Security-Policy");
        assertThat(enforced)
                .contains("default-src 'self'")
                .contains("script-src 'self'")
                .contains("connect-src 'self'")
                .contains("form-action 'self'")
                .contains("frame-ancestors 'none'")
                .contains("style-src 'self' 'unsafe-inline'");
        assertThat(enforced).doesNotContain("script-src 'self' 'unsafe-inline'");
        assertThat(r.getResponse().getHeader("Content-Security-Policy-Report-Only")).isNull();
        assertThat(r.getResponse().getHeader("Referrer-Policy")).isEqualTo("same-origin");
        assertThat(r.getResponse().getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(r.getResponse().getHeader("X-Frame-Options")).isEqualTo("DENY");
    }

    @Test
    @DisplayName("an authenticated panel redirect carries the enforced policy")
    void redirectCarriesTheHeaders() throws Exception {
        MvcResult r = mockMvc.perform(get("/log-sheets")).andReturn();
        assertThat(r.getResponse().getHeader("Content-Security-Policy")).contains("script-src 'self'");
    }

    @Test
    @DisplayName("Swagger UI keeps a relaxed script policy of its own")
    void swaggerKeepsInlineScript() throws Exception {
        MvcResult r = mockMvc.perform(get("/swagger-ui/index.html")).andReturn();
        String csp = r.getResponse().getHeader("Content-Security-Policy");
        assertThat(csp).contains("script-src 'self' 'unsafe-inline' 'unsafe-eval'");
    }

    @Test
    @DisplayName("the mobile API and the integration API keep their own chains, without the panel's policy")
    void apiChainsAreUntouched() throws Exception {
        MvcResult api = mockMvc.perform(get("/api/log-sheets/inbox")).andReturn();
        assertThat(api.getResponse().getHeader("Content-Security-Policy")).isNull();
        MvcResult integration = mockMvc.perform(get("/integration/v1/log-sheets")).andReturn();
        assertThat(integration.getResponse().getHeader("Content-Security-Policy")).isNull();
    }
}
