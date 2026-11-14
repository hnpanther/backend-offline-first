package com.hnp.backendofflinefirst.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hnp.backendofflinefirst.domain.GenerationMode;
import com.hnp.backendofflinefirst.domain.IntegrationKeyScope;
import com.hnp.backendofflinefirst.domain.LogSheetStatus;
import com.hnp.backendofflinefirst.entity.LogSheet;
import com.hnp.backendofflinefirst.entity.LogSheetCategory;
import com.hnp.backendofflinefirst.entity.OperationalUnit;
import com.hnp.backendofflinefirst.repository.ApiKeyRepository;
import com.hnp.backendofflinefirst.repository.ApiKeyUsageRepository;
import com.hnp.backendofflinefirst.repository.LogSheetRepository;
import com.hnp.backendofflinefirst.repository.OperationalUnitRepository;
import com.hnp.backendofflinefirst.service.ApiKeyScopeService;
import com.hnp.backendofflinefirst.service.ApiKeyService;
import com.hnp.backendofflinefirst.service.LogSheetCategoryService;
import com.hnp.backendofflinefirst.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The integration API's category filter (V19) and what one key may read (V20), through HTTP — the
 * half that breaks is the parsing and the narrowing, and only the wire shows a filter that reached
 * the query as null and returned the whole plant.
 *
 * <p>Four finished rounds: SUBMITTED in category A, APPROVED in B, VOIDED in A, and SUBMITTED with
 * no category. Not {@code @Transactional}: usage rows are written on another thread.
 */
class IntegrationKeyScopeIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String BASE = "/integration/v1/log-sheets";
    private static final String FROM = "2000-01-01";
    private static final String TO = "2100-01-01";

    @Autowired WebApplicationContext context;
    @Autowired ObjectMapper objectMapper;
    @Autowired ApiKeyService apiKeyService;
    @Autowired ApiKeyScopeService apiKeyScopeService;
    @Autowired ApiKeyRepository apiKeyRepository;
    @Autowired ApiKeyUsageRepository apiKeyUsageRepository;
    @Autowired LogSheetCategoryService categoryService;
    @Autowired LogSheetRepository logSheetRepository;
    @Autowired OperationalUnitRepository unitRepository;
    @Autowired JdbcTemplate jdbc;

    private MockMvc mockMvc;
    private final List<Long> keyIds = new ArrayList<>();
    private final List<Long> sheetIds = new ArrayList<>();
    private String tag;
    private OperationalUnit unit;
    private LogSheetCategory categoryA;
    private LogSheetCategory categoryB;
    private Long submittedA;
    private Long approvedB;
    private Long voidedA;
    private Long uncategorised;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        tag = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        long now = System.currentTimeMillis();
        unit = new OperationalUnit();
        unit.setCode("KSC-" + tag);
        unit.setName("واحد محدوده کلید");
        unit.setCreatedAt(now);
        unit.setUpdatedAt(now);
        unit = unitRepository.save(unit);
        categoryA = categoryService.create("KSA_" + tag, "دسته الف", true);
        categoryB = categoryService.create("KSB_" + tag, "دسته ب", true);
        submittedA = sheet(LogSheetStatus.SUBMITTED, categoryA, now);
        approvedB = sheet(LogSheetStatus.APPROVED, categoryB, now);
        voidedA = sheet(LogSheetStatus.VOIDED, categoryA, now);
        uncategorised = sheet(LogSheetStatus.SUBMITTED, null, now);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        for (Long id : keyIds) {
            deleteKey(id);
        }
        for (Long id : sheetIds) {
            jdbc.update("DELETE FROM log_sheets WHERE id = ?", id);
        }
        jdbc.update("DELETE FROM log_sheet_categories WHERE id IN (?, ?)", categoryA.getId(), categoryB.getId());
        jdbc.update("DELETE FROM operational_units WHERE id = ?", unit.getId());
    }

    // ── The category filter ─────────────────────────────────────────────────

    @Test
    void theCategoryFilterTakesCodesCaseInsensitivelyAndCommaSeparated() throws Exception {
        String key = key(IntegrationKeyScope.UNRESTRICTED);
        assertThat(ids(list(key, get(BASE).param("category", categoryA.getCode().toLowerCase()))))
                .containsExactly(submittedA);
        assertThat(ids(list(key, get(BASE).param("category", categoryA.getCode() + "," + categoryB.getCode()))))
                .containsExactlyInAnyOrder(submittedA, approvedB);
        assertThat(ids(list(key, get(BASE).param("category", categoryA.getCode()).param("statuses", "VOIDED"))))
                .containsExactly(voidedA);
    }

    @Test
    void anUnknownCodeMatchesNothingAndAMalformedOneIsRefused() throws Exception {
        String key = key(IntegrationKeyScope.UNRESTRICTED);
        JsonNode empty = list(key, get(BASE).param("category", "NO_SUCH_" + tag));
        assertThat(empty.get("items")).isEmpty();
        assertThat(empty.get("totalElements").asLong()).isZero();

        mockMvc.perform(withRange(get(BASE).header("X-API-Key", key).param("category", "bad code")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_request"));
    }

    @Test
    void theSummaryCarriesTheCategoryTheFilterTakes() throws Exception {
        String key = key(IntegrationKeyScope.UNRESTRICTED);
        JsonNode items = list(key, get(BASE).param("category", categoryA.getCode())).get("items");
        assertThat(items.get(0).get("category").get("code").asText()).isEqualTo(categoryA.getCode());
        assertThat(items.get(0).get("category").get("name").asText()).isEqualTo("دسته الف");
    }

    // ── A key limited to categories ─────────────────────────────────────────

    @Test
    void aCategoryScopedKeySeesOnlyItsCategoriesAndNeverTheUncategorised() throws Exception {
        String key = key(new IntegrationKeyScope(Set.of(categoryA.getId()), null));
        assertThat(ids(list(key, get(BASE)))).containsExactly(submittedA);
        assertThat(ids(list(key, get(BASE).param("statuses", "SUBMITTED,APPROVED,VOIDED"))))
                .containsExactlyInAnyOrder(submittedA, voidedA);
    }

    @Test
    void askingForACategoryOutsideTheScopeAnswersEmptyNotForbidden() throws Exception {
        String key = key(new IntegrationKeyScope(Set.of(categoryA.getId()), null));
        assertThat(list(key, get(BASE).param("category", categoryB.getCode())).get("items")).isEmpty();
    }

    @Test
    void aRoundOutsideTheScopeIsNotFoundLikeOneThatDoesNotExist() throws Exception {
        String key = key(new IntegrationKeyScope(Set.of(categoryA.getId()), null));
        mockMvc.perform(get(BASE + "/" + submittedA).header("X-API-Key", key)).andExpect(status().isOk());
        mockMvc.perform(get(BASE + "/" + approvedB).header("X-API-Key", key))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("not_found"));
        mockMvc.perform(get(BASE + "/" + uncategorised).header("X-API-Key", key)).andExpect(status().isNotFound());
    }

    // ── A key limited to statuses ───────────────────────────────────────────

    @Test
    void aStatusOutsideTheScopeAskedForByNameIsForbiddenAndSaysWhichItMayRead() throws Exception {
        String key = key(new IntegrationKeyScope(null, Set.of(LogSheetStatus.SUBMITTED)));
        mockMvc.perform(withRange(get(BASE).header("X-API-Key", key).param("statuses", "APPROVED")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("forbidden"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("[SUBMITTED]")));
        assertThat(ids(list(key, get(BASE)))).containsExactlyInAnyOrder(submittedA, uncategorised);
        mockMvc.perform(get(BASE + "/" + approvedB).header("X-API-Key", key)).andExpect(status().isNotFound());
    }

    @Test
    void aNarrowedScopeTakesEffectOnTheVeryNextRequest() throws Exception {
        String key = key(IntegrationKeyScope.UNRESTRICTED);
        assertThat(ids(list(key, get(BASE)))).containsExactlyInAnyOrder(submittedA, approvedB, uncategorised);

        apiKeyService.updateScope(keyIds.get(0), new IntegrationKeyScope(Set.of(categoryB.getId()), null), null);

        assertThat(ids(list(key, get(BASE)))).containsExactly(approvedB);
        assertThat(apiKeyScopeService.scopesOf(List.of(apiKeyRepository.findById(keyIds.get(0)).orElseThrow()))
                .get(keyIds.get(0)).categoryIds()).containsExactly(categoryB.getId());
    }

    @Test
    void aCategoryAKeyIsLimitedToCannotBeDeleted() {
        key(new IntegrationKeyScope(Set.of(categoryB.getId()), null));
        LogSheetCategory unusedElsewhere = categoryService.create("KSC_" + tag, "فقط در کلید", true);
        Long keyId = keyIds.get(0);
        apiKeyService.updateScope(keyId, new IntegrationKeyScope(Set.of(unusedElsewhere.getId()), null), null);
        try {
            assertThatThrownBy(() -> categoryService.delete(unusedElsewhere.getId()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("This log sheet category is used by an integration API key.");
        } finally {
            jdbc.update("DELETE FROM api_key_categories WHERE api_key_id = ?", keyId);
            categoryService.delete(unusedElsewhere.getId());
        }
    }

    @Test
    void anIssuedScopeRejectsAStatusTheApiDoesNotExposeOrACategoryThatDoesNotExist() {
        assertThatThrownBy(() -> apiKeyScopeService.parse(List.of(), List.of("IN_PROGRESS")))
                .hasMessage("Invalid API key status scope.");
        assertThatThrownBy(() -> apiKeyScopeService.parse(List.of(Long.MAX_VALUE), List.of()))
                .hasMessage("Log sheet category not found.");
        assertThat(apiKeyScopeService.parse(null, null).isUnrestricted()).isTrue();
    }

    // ── Plumbing ─────────────────────────────────────────────────────────────

    /**
     * Usage rows are written after the response, on another thread, so the last request's row can
     * land after this test's first delete. Retried until the key goes, for a few seconds at most.
     */
    private void deleteKey(Long id) throws InterruptedException {
        for (int attempt = 0; ; attempt++) {
            jdbc.update("DELETE FROM api_key_usage WHERE api_key_id = ?", id);
            jdbc.update("DELETE FROM api_key_categories WHERE api_key_id = ?", id);
            try {
                jdbc.update("DELETE FROM api_keys WHERE id = ?", id);
                return;
            } catch (org.springframework.dao.DataIntegrityViolationException e) {
                if (attempt >= 50) {
                    throw e;
                }
                Thread.sleep(100);
            }
        }
    }

    private String key(IntegrationKeyScope scope) {
        var issued = apiKeyService.create("KS " + tag + " " + keyIds.size(), "key scope test", null, scope, null);
        keyIds.add(issued.key().getId());
        return issued.apiKey();
    }

    private Long sheet(LogSheetStatus status, LogSheetCategory category, long now) {
        LogSheet s = new LogSheet();
        s.setTemplateName("KS " + tag);
        s.setScopeSummary("key scope test");
        s.setOperationalUnitId(unit.getId());
        s.setStatus(status);
        s.setOrigin(GenerationMode.MANUAL);
        s.setCompletedAt(now - 60_000L);
        s.setSubmittedAt(now - 59_000L);
        if (status == LogSheetStatus.APPROVED) {
            s.setApprovedAt(now - 30_000L);
        }
        s.setSyncStatus("SYNCED");
        s.setCreatedAt(now - 120_000L);
        s.setUpdatedAt(now);
        if (category != null) {
            s.setCategoryId(category.getId());
            s.setCategoryName(category.getName());
        }
        Long id = logSheetRepository.save(s).getId();
        sheetIds.add(id);
        return id;
    }

    private static MockHttpServletRequestBuilder withRange(MockHttpServletRequestBuilder request) {
        return request.param("from", FROM).param("to", TO).param("size", "200");
    }

    private JsonNode list(String key, MockHttpServletRequestBuilder request) throws Exception {
        String body = mockMvc.perform(withRange(request.header("X-API-Key", key)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    /** Only this test's rows: the container is shared. */
    private List<Long> ids(JsonNode page) {
        List<Long> mine = List.of(submittedA, approvedB, voidedA, uncategorised);
        List<Long> out = new ArrayList<>();
        for (JsonNode item : page.get("items")) {
            long id = item.get("id").asLong();
            if (mine.contains(id)) {
                out.add(id);
            }
        }
        return out;
    }
}
