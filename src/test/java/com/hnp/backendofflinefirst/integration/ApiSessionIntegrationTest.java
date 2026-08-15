package com.hnp.backendofflinefirst.integration;

import com.hnp.backendofflinefirst.support.TestPrincipals;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hnp.backendofflinefirst.domain.ApiSessionRevokeReason;
import com.hnp.backendofflinefirst.entity.ApiSession;
import com.hnp.backendofflinefirst.entity.Role;
import com.hnp.backendofflinefirst.entity.User;
import com.hnp.backendofflinefirst.entity.UserAuthType;
import com.hnp.backendofflinefirst.entity.UserRole;
import com.hnp.backendofflinefirst.repository.ApiSessionRepository;
import com.hnp.backendofflinefirst.repository.RoleRepository;
import com.hnp.backendofflinefirst.repository.UserRepository;
import com.hnp.backendofflinefirst.repository.UserRoleRepository;
import com.hnp.backendofflinefirst.security.AppUserDetails;
import com.hnp.backendofflinefirst.security.JwtService;
import com.hnp.backendofflinefirst.service.ApiSessionService;
import com.hnp.backendofflinefirst.support.AbstractPostgresIntegrationTest;
import com.hnp.backendofflinefirst.support.WithAppUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end checks for the stateful JWT registry: login records a device, a second
 * login supersedes the first, and admin revocation blocks the very next API call.
 * <p>
 * Deliberately <b>not</b> {@code @Transactional} — revocation must be observable through
 * committed state, exactly as it is for a tablet on the next request.
 */
class ApiSessionIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String PASSWORD = "session-pass-1";

    @Autowired WebApplicationContext context;
    @Autowired ObjectMapper objectMapper;
    @Autowired UserRepository userRepository;
    @Autowired RoleRepository roleRepository;
    @Autowired UserRoleRepository userRoleRepository;
    @Autowired ApiSessionRepository apiSessionRepository;
    @Autowired ApiSessionService apiSessionService;
    @Autowired JwtService jwtService;
    @Autowired PasswordEncoder passwordEncoder;

    MockMvc mockMvc;
    User operator;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        operator = seedOperator();
    }

    @AfterEach
    void cleanUp() {
        apiSessionRepository.deleteAll(apiSessionRepository.findAll().stream()
                .filter(s -> s.getUserId().equals(operator.getId()))
                .toList());
        userRoleRepository.deleteAll(userRoleRepository.findAll().stream()
                .filter(ur -> ur.getUserId().equals(operator.getId()))
                .toList());
        userRepository.deleteById(operator.getId());
    }

    @Test
    void loginRegistersSessionAndTokenAuthenticatesApiCalls() throws Exception {
        String token = login("Tablet A");

        List<ApiSession> sessions = apiSessionRepository.findActiveByUserId(
                operator.getId(), System.currentTimeMillis());
        assertThat(sessions).hasSize(1);
        ApiSession session = sessions.getFirst();
        assertThat(session.getUsername()).isEqualTo(operator.getUsername());
        assertThat(session.getDeviceLabel()).isEqualTo("Tablet A");
        assertThat(session.getJti()).isNotBlank();
        assertThat(session.getExpiresAt()).isGreaterThan(System.currentTimeMillis());

        mockMvc.perform(get("/api/bootstrap").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void secondLoginSupersedesFirstDeviceSoOnlyOneStaysValid() throws Exception {
        String firstToken = login("Tablet A");
        String secondToken = login("Tablet B");

        mockMvc.perform(get("/api/bootstrap").header(HttpHeaders.AUTHORIZATION, "Bearer " + firstToken))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/bootstrap").header(HttpHeaders.AUTHORIZATION, "Bearer " + secondToken))
                .andExpect(status().isOk());

        assertThat(apiSessionRepository.findActiveByUserId(operator.getId(), System.currentTimeMillis()))
                .hasSize(1)
                .allSatisfy(s -> assertThat(s.getDeviceLabel()).isEqualTo("Tablet B"));

        ApiSession superseded = apiSessionRepository.findAll().stream()
                .filter(s -> "Tablet A".equals(s.getDeviceLabel()))
                .findFirst()
                .orElseThrow();
        assertThat(superseded.getRevokeReason()).isEqualTo(ApiSessionRevokeReason.SUPERSEDED);
    }

    @Test
    void adminRevocationBlocksTheNextApiCall() throws Exception {
        String token = login("Tablet A");
        mockMvc.perform(get("/api/bootstrap").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());

        ApiSession session = apiSessionRepository
                .findActiveByUserId(operator.getId(), System.currentTimeMillis()).getFirst();
        apiSessionService.revoke(session.getId(), 1L, System.currentTimeMillis());

        mockMvc.perform(get("/api/bootstrap").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void revokeAllForUserBlocksTheToken() throws Exception {
        String token = login("Tablet A");

        assertThat(apiSessionService.revokeAllForUser(operator.getId(), 1L, System.currentTimeMillis()))
                .isEqualTo(1);

        mockMvc.perform(get("/api/bootstrap").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void signedTokenWithoutRegistryRowIsRejected() throws Exception {
        // A correctly signed token is no longer sufficient on its own.
        AppUserDetails details = TestPrincipals.of(operator, Set.of("OPERATOR"),
                Set.of("GET:/api/bootstrap"));
        JwtService.JwtToken orphan = jwtService.issueToken(details);

        mockMvc.perform(get("/api/bootstrap")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + orphan.accessToken()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void loginRecordsTheClientIpFromForwardedHeader() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Forwarded-For", "203.0.113.9, 10.0.0.1")
                        .content(loginBody("Tablet C")))
                .andExpect(status().isOk());

        assertThat(apiSessionRepository.findActiveByUserId(operator.getId(), System.currentTimeMillis()))
                .singleElement()
                .satisfies(s -> assertThat(s.getIpAddress()).isEqualTo("203.0.113.9"));
    }

    @Test
    @WithAppUser(authorities = {"GET:/api-sessions", "POST:/api-sessions/{id}/revoke"})
    void adminPageRendersSessionRowsForBothActiveAndAllFilters() throws Exception {
        String token = login("Tablet A");
        assertThat(token).isNotBlank();

        mockMvc.perform(get("/api-sessions"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Tablet A")))
                .andExpect(content().string(containsString(operator.getUsername())));

        // Revoked rows are hidden by the default filter and visible under "all".
        ApiSession session = apiSessionRepository
                .findActiveByUserId(operator.getId(), System.currentTimeMillis()).getFirst();
        apiSessionService.revoke(session.getId(), 1L, System.currentTimeMillis());

        mockMvc.perform(get("/api-sessions"))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("Tablet A"))));
        mockMvc.perform(get("/api-sessions").param("activeOnly", "false"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Tablet A")));
    }

    @Test
    @WithAppUser(authorities = "GET:/api-sessions")
    void adminPageSearchMatchesDeviceLabel() throws Exception {
        login("Tablet Zeta");

        mockMvc.perform(get("/api-sessions").param("q", "zeta"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Tablet Zeta")));
        mockMvc.perform(get("/api-sessions").param("q", "no-such-device"))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("Tablet Zeta"))));
    }

    private String login(String deviceLabel) throws Exception {
        String body = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(deviceLabel)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode json = objectMapper.readTree(body);
        String token = json.get("accessToken").asText();
        assertThat(token).isNotBlank();
        return token;
    }

    private String loginBody(String deviceLabel) throws Exception {
        return objectMapper.writeValueAsString(java.util.Map.of(
                "username", operator.getUsername(),
                "password", PASSWORD,
                "deviceLabel", deviceLabel));
    }

    private User seedOperator() {
        long now = System.currentTimeMillis();
        User user = new User();
        user.setUsername("sess-op-" + now);
        user.setPersonnelCode("PC-" + java.util.UUID.randomUUID());
        user.setFullName("Session Operator");
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setActive(true);
        user.setAuthType(UserAuthType.LOCAL);
        user.setCreatedAt(now);
        user.setUpdatedAt(now);
        user = userRepository.save(user);

        // OPERATOR is seeded in V1 with GET:/api/bootstrap.
        Role operatorRole = roleRepository.findByCode("OPERATOR").orElseThrow();
        UserRole link = new UserRole();
        link.setUserId(user.getId());
        link.setRoleId(operatorRole.getId());
        userRoleRepository.save(link);
        return user;
    }
}
