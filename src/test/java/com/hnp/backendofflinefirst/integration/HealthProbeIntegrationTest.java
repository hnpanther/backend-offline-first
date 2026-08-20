package com.hnp.backendofflinefirst.integration;

import com.hnp.backendofflinefirst.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The probes a load balancer reads, and what each of them is allowed to depend on.
 *
 * <p><b>Readiness must include the database; liveness must not.</b> Spring Boot's default
 * readiness group holds only {@code readinessState} — external dependencies are excluded out of
 * the box — so before this was configured, {@code /actuator/health/readiness} stayed green with
 * PostgreSQL unreachable and a load balancer would have gone on routing traffic that could only
 * fail. Every request in this system touches the database; there is no degraded mode worth
 * staying in rotation for.
 *
 * <p>Liveness deliberately keeps the default. A database outage must not get the container
 * killed and restarted: that fixes nothing and throws away the in-memory session registry,
 * login-attempt counters and sweep progress along with it.
 *
 * <p>{@code show-details=always} is set <b>for this test only</b>. Production keeps the default
 * ({@code never}) so no component detail — driver, database name, validation query — reaches an
 * unauthenticated prober. The property is what lets the assertions below name the components
 * rather than guess at a colour.
 */
@TestPropertySource(properties = {
        "management.endpoint.health.show-details=always",
        "management.endpoint.health.group.readiness.show-details=always",
        "management.endpoint.health.group.liveness.show-details=always"
})
class HealthProbeIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired WebApplicationContext context;

    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void readinessIncludesTheDatabase() throws Exception {
        mockMvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components.db").exists())
                .andExpect(jsonPath("$.components.db.status").value("UP"));
    }

    @Test
    void livenessDoesNotIncludeTheDatabase() throws Exception {
        // A database outage must not be able to get this container restarted.
        mockMvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components.db").doesNotExist());
    }

    /** Both probes stay reachable without authentication — that is what a probe is for. */
    @Test
    void bothProbesArePublic() throws Exception {
        mockMvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
        mockMvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
    }

    /** Everything else under /actuator stays admin-only. */
    @Test
    void theFullHealthEndpointIsNotPublic() throws Exception {
        mockMvc.perform(get("/actuator/health")).andExpect(status().is3xxRedirection());
        mockMvc.perform(get("/actuator/metrics")).andExpect(status().is3xxRedirection());
    }

    /**
     * {@code /api/health} is a fixed string and stays one.
     *
     * <p>It answers {@code ok} unconditionally and always has. That is fine for what it is — a
     * "the servlet container is answering" check — and it must not be mistaken for readiness,
     * which is why the readiness probe above exists and is the one a load balancer should read.
     */
    @Test
    void theApiHealthEndpointIsALivenessStyleConstant() throws Exception {
        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("\"db\""))));
    }
}
