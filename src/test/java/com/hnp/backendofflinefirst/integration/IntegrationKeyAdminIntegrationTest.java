package com.hnp.backendofflinefirst.integration;

import com.hnp.backendofflinefirst.entity.ApiKey;
import com.hnp.backendofflinefirst.repository.ApiKeyRepository;
import com.hnp.backendofflinefirst.repository.PermissionRepository;
import com.hnp.backendofflinefirst.repository.RolePermissionRepository;
import com.hnp.backendofflinefirst.repository.RoleRepository;
import com.hnp.backendofflinefirst.service.ApiKeyService;
import com.hnp.backendofflinefirst.support.AbstractPostgresIntegrationTest;
import com.hnp.backendofflinefirst.support.WithAppUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The admin side of integration keys: the seeded permissions, the page, and the lifecycle.
 *
 * <p>The seeding assertions are not ceremony. V1's blanket grant to {@code ADMIN} was a
 * one-time snapshot and does not reach rows a later migration inserts (gotcha #22), so a
 * migration that creates a permission and forgets its {@code role_permissions} row leaves an
 * endpoint that denies everyone — a failure the rest of the suite cannot see, because
 * {@code @PreAuthorize} is satisfied in tests by whatever authority the test principal is
 * handed.
 */
@Transactional
class IntegrationKeyAdminIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final List<String> PERMISSIONS = List.of(
            "GET:/integration-keys",
            "POST:/integration-keys",
            "POST:/integration-keys/{id}/status",
            "POST:/integration-keys/{id}/revoke");

    @Autowired WebApplicationContext context;
    @Autowired PermissionRepository permissionRepository;
    @Autowired RoleRepository roleRepository;
    @Autowired RolePermissionRepository rolePermissionRepository;
    @Autowired ApiKeyRepository apiKeyRepository;
    @Autowired ApiKeyService apiKeyService;

    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void v4SeedsEveryPermissionThePageChecks() {
        for (String code : PERMISSIONS) {
            assertThat(permissionRepository.findByCode(code)).as(code).isPresent();
        }
    }

    @Test
    void adminActuallyHoldsThem() {
        Long adminRoleId = roleRepository.findByCode("ADMIN").orElseThrow().getId();
        List<Long> granted = rolePermissionRepository.findByRoleId(adminRoleId).stream()
                .map(rp -> rp.getPermissionId())
                .toList();

        for (String code : PERMISSIONS) {
            Long permissionId = permissionRepository.findByCode(code).orElseThrow().getId();
            assertThat(granted)
                    .as("ADMIN must hold %s — V1's blanket grant does not cover V4's rows", code)
                    .contains(permissionId);
        }
    }

    /**
     * The integration endpoints must have <b>no</b> permission row.
     *
     * <p>Granting one would say a role can be given this access. It cannot: the
     * {@code /integration/**} chain has no user principal, so an authority on a role is not a
     * thing it can check. A row here would be a promise the code does not keep.
     */
    @Test
    void theIntegrationEndpointsThemselvesAreNotGrantableToAnyRole() {
        assertThat(permissionRepository.findByCode("GET:/integration/v1/log-sheets")).isEmpty();
        assertThat(permissionRepository.findByCode("GET:/integration/v1/log-sheets/{id}")).isEmpty();
        assertThat(permissionRepository.findAll().stream()
                .filter(p -> p.getCode() != null && p.getCode().contains("/integration/"))
                .toList())
                .isEmpty();
    }

    /**
     * {@code @WithAppUser} rather than {@code .with(user(...))}: the layout fragment reads
     * {@code principal.user.fullName}, so a bare {@code UsernamePasswordAuthenticationToken}
     * blows up in Thymeleaf before any assertion about the page is reached.
     */
    @Test
    @WithAppUser(authorities = {"GET:/integration-keys"})
    void thePageRendersForSomebodyHoldingThePermission() throws Exception {
        mockMvc.perform(get("/integration-keys"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("کلیدهای یکپارچه‌سازی")))
                // Proof the markup survived th:replace — anything outside #pageContent is
                // silently discarded, which is how a whole feature ships invisible.
                .andExpect(content().string(org.hamcrest.Matchers.containsString("گزارش استفاده")))
                // The standing explanation of what these keys can and cannot reach has to be on
                // the page, not only in the javadoc. (The one-time key reveal is not asserted
                // here — it renders only on the redirect after a create, and is covered by
                // creatingThroughThePageShowsTheKeyOnceAndStoresOnlyItsHash.)
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/integration/v1/**")));
    }

    /**
     * Denied, which on the web panel means a redirect carrying a Persian flash message rather
     * than a 403 — {@code WebAccessDeniedHandler} exists so an operator never meets a
     * whitelabel error page.
     */
    @Test
    @WithAppUser(authorities = {"GET:/log-sheets"})
    void thePageIsDeniedWithoutThePermission() throws Exception {
        mockMvc.perform(get("/integration-keys"))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    void creatingThroughThePageShowsTheKeyOnceAndStoresOnlyItsHash() throws Exception {
        String clientName = "ERP " + UUID.randomUUID();

        var result = mockMvc.perform(post("/integration-keys").with(csrf())
                        .with(user("admin").authorities(auth("POST:/integration-keys")))
                        .param("clientName", clientName)
                        .param("description", "nightly export"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/integration-keys"))
                .andReturn();

        String presented = (String) result.getFlashMap().get("createdApiKey");
        assertThat(presented).isNotNull().startsWith("lsk_");

        ApiKey stored = apiKeyRepository.findAll().stream()
                .filter(k -> clientName.equals(k.getClientName()))
                .findFirst().orElseThrow();

        // Only the hash survives — the plaintext is nowhere in the row.
        assertThat(stored.getSecretHash()).isNotBlank().doesNotContain(presented);
        assertThat(presented).contains(stored.getKeyId());
        assertThat(stored.getPrefix()).isEqualTo("lsk_" + stored.getKeyId());
        assertThat(presented).startsWith(stored.getPrefix());
        assertThat(stored.isActive()).isTrue();
        assertThat(stored.getRevokedAt()).isNull();
    }

    @Test
    void aSecondLiveKeyForTheSameClientIsRefusedWithAPersianMessage() throws Exception {
        String clientName = "MES " + UUID.randomUUID();
        apiKeyService.create(clientName, null, null, null);

        var result = mockMvc.perform(post("/integration-keys").with(csrf())
                        .with(user("admin").authorities(auth("POST:/integration-keys")))
                        .param("clientName", clientName))
                .andExpect(status().is3xxRedirection())
                .andReturn();

        assertThat((String) result.getFlashMap().get("errorMessage"))
                .as("a raw English message here means ErrorTranslator is missing a mapping")
                .contains("کلید فعال");
        assertThat(result.getFlashMap().get("createdApiKey")).isNull();
    }

    /** Rotation: revoke, then issue again for the same client. The partial index allows it. */
    @Test
    void aClientCanBeReIssuedAfterRevocation() {
        String clientName = "SCADA " + UUID.randomUUID();
        var first = apiKeyService.create(clientName, null, null, null);

        apiKeyService.revoke(first.key().getId(), "rotation", null);
        var second = apiKeyService.create(clientName, null, null, null);

        assertThat(second.key().getId()).isNotEqualTo(first.key().getId());
        assertThat(second.apiKey()).isNotEqualTo(first.apiKey());
        // The retired row survives so its past usage stays attributable.
        assertThat(apiKeyRepository.findById(first.key().getId())).isPresent();
    }

    @Test
    void disableIsReversibleAndRevokeIsNot() throws Exception {
        var issued = apiKeyService.create("PI " + UUID.randomUUID(), null, null, null);
        Long id = issued.key().getId();

        mockMvc.perform(post("/integration-keys/" + id + "/status").with(csrf())
                        .with(user("admin").authorities(auth("POST:/integration-keys/{id}/status")))
                        .param("active", "false"))
                .andExpect(status().is3xxRedirection());
        assertThat(apiKeyRepository.findById(id).orElseThrow().isActive()).isFalse();

        mockMvc.perform(post("/integration-keys/" + id + "/status").with(csrf())
                        .with(user("admin").authorities(auth("POST:/integration-keys/{id}/status")))
                        .param("active", "true"))
                .andExpect(status().is3xxRedirection());
        assertThat(apiKeyRepository.findById(id).orElseThrow().isActive()).isTrue();

        mockMvc.perform(post("/integration-keys/" + id + "/revoke").with(csrf())
                        .with(user("admin").authorities(auth("POST:/integration-keys/{id}/revoke")))
                        .param("reason", "pasted into a public ticket"))
                .andExpect(status().is3xxRedirection());

        ApiKey revoked = apiKeyRepository.findById(id).orElseThrow();
        assertThat(revoked.getRevokedAt()).isNotNull();
        assertThat(revoked.getRevokeReason()).isEqualTo("pasted into a public ticket");
        // Cleared too, so a reader checking only `active` still sees a dead key.
        assertThat(revoked.isActive()).isFalse();

        assertThatThrownBy(() -> apiKeyService.setActive(id, true, null))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void anExpiryInThePastIsRefused() {
        assertThatThrownBy(() -> apiKeyService.create(
                "Historian " + UUID.randomUUID(), null, System.currentTimeMillis() - 1_000L, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("future");
    }

    @Test
    void aKeyNeedsAClientName() {
        assertThatThrownBy(() -> apiKeyService.create("   ", null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Client name is required");
    }

    private static org.springframework.security.core.authority.SimpleGrantedAuthority auth(String code) {
        return new org.springframework.security.core.authority.SimpleGrantedAuthority(code);
    }
}
