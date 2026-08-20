package com.hnp.backendofflinefirst.integration;

import com.hnp.backendofflinefirst.repository.ApiKeyRepository;
import com.hnp.backendofflinefirst.repository.ApiKeyUsageRepository;
import com.hnp.backendofflinefirst.service.ApiKeyService;
import com.hnp.backendofflinefirst.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.UUID;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The integration page limits are configuration, and the configuration actually reaches the
 * endpoint.
 *
 * <p>The unit tests prove {@code PageLimits} computes the right numbers; they cannot prove the
 * controller reads the properties, which is the half that silently does nothing if the wiring
 * is wrong. This runs a real context with non-default values set and checks what the endpoint
 * echoes back — the effective {@code size}, which is the only thing a caller can see.
 */
@TestPropertySource(properties = {
        "app.integration.max-page-size=7",
        "app.integration.default-page-size=3"
})
class IntegrationPageSizeConfigIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String BASE = "/integration/v1/log-sheets";

    @Autowired WebApplicationContext context;
    @Autowired ApiKeyService apiKeyService;
    @Autowired ApiKeyRepository apiKeyRepository;
    @Autowired ApiKeyUsageRepository apiKeyUsageRepository;

    MockMvc mockMvc;
    private String apiKey;
    private Long apiKeyId;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        var issued = apiKeyService.create("PageSizeProbe " + UUID.randomUUID(), null, null, null);
        apiKey = issued.apiKey();
        apiKeyId = issued.key().getId();
    }

    /**
     * Usage rows are written on {@code auditExecutor} <em>after</em> the response is sent, so a
     * teardown that deletes them once races the write that is still in flight and then trips the
     * {@code api_key_usage → api_keys} foreign key. Retried rather than slept on for a fixed
     * time: a sleep long enough to be safe on a loaded machine is a sleep wasted on every run.
     */
    @AfterEach
    void tearDown() {
        for (int attempt = 0; attempt < 40; attempt++) {
            apiKeyUsageRepository.deleteAll(apiKeyUsageRepository.findAll().stream()
                    .filter(u -> apiKeyId.equals(u.getApiKeyId()))
                    .toList());
            try {
                apiKeyRepository.deleteById(apiKeyId);
                return;
            } catch (org.springframework.dao.DataIntegrityViolationException stillReferenced) {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
        throw new IllegalStateException("api_key_usage rows kept arriving; could not clean up");
    }

    @Test
    void theConfiguredDefaultIsAppliedWhenNoSizeIsAskedFor() throws Exception {
        mockMvc.perform(get(BASE).header("X-API-Key", apiKey)
                        .param("from", "2000-01-01").param("to", "2100-01-01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(3));
    }

    @Test
    void theConfiguredMaximumClampsAnOversizedRequest() throws Exception {
        // 200 would be the compiled-in cap; 7 is what this context configures.
        mockMvc.perform(get(BASE).header("X-API-Key", apiKey)
                        .param("from", "2000-01-01").param("to", "2100-01-01")
                        .param("size", "200"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(7));
    }

    @Test
    void aRequestUnderTheMaximumIsHonouredExactly() throws Exception {
        mockMvc.perform(get(BASE).header("X-API-Key", apiKey)
                        .param("from", "2000-01-01").param("to", "2100-01-01")
                        .param("size", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(5));
    }

    @Test
    void aRequestExactlyAtTheMaximumIsHonoured() throws Exception {
        mockMvc.perform(get(BASE).header("X-API-Key", apiKey)
                        .param("from", "2000-01-01").param("to", "2100-01-01")
                        .param("size", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(7));
    }

    @Test
    void aNonsensicalSizeFallsBackToTheConfiguredDefault() throws Exception {
        mockMvc.perform(get(BASE).header("X-API-Key", apiKey)
                        .param("from", "2000-01-01").param("to", "2100-01-01")
                        .param("size", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(3));
    }

    /**
     * The page never carries more rows than the effective size, whatever the caller asked for.
     *
     * <p>Echoing the right number and returning a different count would be the worst of both:
     * a caller that trusts {@code size} to page correctly would skip rows.
     */
    @Test
    void theRowsReturnedNeverExceedTheEffectiveSize() throws Exception {
        mockMvc.perform(get(BASE).header("X-API-Key", apiKey)
                        .param("from", "2000-01-01").param("to", "2100-01-01")
                        .param("statuses", "SUBMITTED,VOIDED,EXPIRED,CANCELLED")
                        .param("size", "999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(7))
                .andExpect(jsonPath("$.items.length()").value(
                        org.hamcrest.Matchers.lessThanOrEqualTo(7)));
    }
}
