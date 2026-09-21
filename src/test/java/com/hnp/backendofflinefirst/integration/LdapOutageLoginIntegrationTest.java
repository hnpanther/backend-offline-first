package com.hnp.backendofflinefirst.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hnp.backendofflinefirst.entity.Role;
import com.hnp.backendofflinefirst.entity.User;
import com.hnp.backendofflinefirst.entity.UserAuthType;
import com.hnp.backendofflinefirst.entity.UserRole;
import com.hnp.backendofflinefirst.repository.RoleRepository;
import com.hnp.backendofflinefirst.repository.UserRepository;
import com.hnp.backendofflinefirst.repository.UserRoleRepository;
import com.hnp.backendofflinefirst.security.LoginAttemptService;
import com.hnp.backendofflinefirst.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Active Directory is enabled and <b>down</b> — the URL names a loopback port nothing listens
 * on — and the whole security chain is real: {@code ProviderManager}, the form login, the API
 * login, the throttle. What must hold:
 *
 * <ul>
 *   <li>a HYBRID account signs in with its local password, on both surfaces, as if nothing
 *       were wrong;</li>
 *   <li>an ACTIVE_DIRECTORY account is told the directory is unreachable — not that its
 *       password is wrong — and is not marched towards a lockout by an outage it cannot
 *       influence;</li>
 *   <li>a HYBRID account whose local password is wrong is told the same, and <em>is</em>
 *       charged the attempt, because a local hash was tested.</li>
 * </ul>
 *
 * <p>Not {@code @Transactional}: the throttle is in-memory and the users are read by the
 * security chain on another thread's connection, so rows are committed and removed by hand.
 */
@TestPropertySource(properties = {
        "app.auth.ldap.enabled=true",
        // tcpmux, port 1: closed on every developer machine and every CI runner.
        "app.auth.ldap.url=ldap://127.0.0.1:1",
        "app.auth.ldap.domain=site.test",
        "app.auth.ldap.timeout-ms=1000",
        "app.auth.ldap.trust-self-signed=false",
        "app.auth.login-attempt.max-attempts=3"
})
class LdapOutageLoginIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String LOCAL_PASSWORD = "local-pass-123";
    private static final String UNREACHABLE_FA = "سرویس اکتیو دایرکتوری در دسترس نیست";

    @Autowired WebApplicationContext context;
    @Autowired UserRepository userRepository;
    @Autowired RoleRepository roleRepository;
    @Autowired UserRoleRepository userRoleRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired LoginAttemptService loginAttemptService;
    @Autowired ObjectMapper objectMapper;

    MockMvc mockMvc;
    final List<User> seeded = new ArrayList<>();

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @AfterEach
    void cleanUp() {
        for (User user : seeded) {
            loginAttemptService.unlock(user.getUsername());
            userRoleRepository.deleteAll(userRoleRepository.findByUserId(user.getId()));
            userRepository.deleteById(user.getId());
        }
        seeded.clear();
    }

    // -- HYBRID keeps working -------------------------------------------------------------

    @Test
    void hybridLocalPasswordSignsInThroughTheApiWhileTheDirectoryIsDown() throws Exception {
        User hybrid = seed(UserAuthType.HYBRID, LOCAL_PASSWORD);

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson(hybrid.getUsername(), LOCAL_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.username").value(hybrid.getUsername()));

        assertThat(loginAttemptService.remainingLockSeconds(hybrid.getUsername())).isZero();
    }

    @Test
    void hybridLocalPasswordSignsInThroughTheWebFormWhileTheDirectoryIsDown() throws Exception {
        User hybrid = seed(UserAuthType.HYBRID, LOCAL_PASSWORD);

        MvcResult result = mockMvc.perform(post("/login")
                        .with(csrf())
                        .param("username", hybrid.getUsername())
                        .param("password", LOCAL_PASSWORD))
                .andExpect(status().is3xxRedirection())
                .andReturn();

        assertThat(result.getResponse().getRedirectedUrl())
                .as("a successful form login leaves the login page")
                .doesNotContain("/login");
    }

    // -- ACTIVE_DIRECTORY is told the truth, and not throttled for it ---------------------

    @Test
    void activeDirectoryAccountIsToldTheDirectoryIsUnreachableNotThatThePasswordIsWrong() throws Exception {
        User adOnly = seed(UserAuthType.ACTIVE_DIRECTORY, null);

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson(adOnly.getUsername(), "whatever")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value(containsString(UNREACHABLE_FA)));
    }

    @Test
    void anOutageDoesNotMarchAnActiveDirectoryAccountTowardsALockout() throws Exception {
        User adOnly = seed(UserAuthType.ACTIVE_DIRECTORY, null);

        // Past max-attempts (3). Each is the directory not having been asked; none may count.
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(loginJson(adOnly.getUsername(), "whatever")))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.message").value(containsString(UNREACHABLE_FA)));
        }

        assertThat(loginAttemptService.remainingLockSeconds(adOnly.getUsername())).isZero();
    }

    @Test
    void theWebLoginPageNamesTheOutage() throws Exception {
        User adOnly = seed(UserAuthType.ACTIVE_DIRECTORY, null);

        MvcResult failed = mockMvc.perform(post("/login")
                        .with(csrf())
                        .param("username", adOnly.getUsername())
                        .param("password", "whatever"))
                .andExpect(redirectedUrl("/login?error"))
                .andReturn();

        // Same session: that is where the failure handler parked the exception.
        MockHttpSession session = (MockHttpSession) failed.getRequest().getSession(false);
        assertThat(session).isNotNull();
        mockMvc.perform(get("/login").param("error", "").session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(UNREACHABLE_FA)));
    }

    // -- HYBRID with the wrong local password: same message, still counted ----------------

    @Test
    void hybridWrongLocalPasswordDuringAnOutageIsToldTheSameAndStillCounted() throws Exception {
        User hybrid = seed(UserAuthType.HYBRID, LOCAL_PASSWORD);

        // max-attempts is 3: the third wrong local password locks the username.
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(loginJson(hybrid.getUsername(), "not-the-local-one")))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.message").value(containsString(UNREACHABLE_FA)));
        }

        assertThat(loginAttemptService.remainingLockSeconds(hybrid.getUsername()))
                .as("a wrong local password is a tested password, outage or not")
                .isPositive();
        // And the lock holds even against the right one, exactly as without an outage.
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson(hybrid.getUsername(), LOCAL_PASSWORD)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value(containsString("قفل شده")));
    }

    // -- helpers ----------------------------------------------------------------------------

    private String loginJson(String username, String password) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "username", username,
                "password", password,
                "deviceLabel", "outage-test"));
    }

    private User seed(UserAuthType authType, String localPassword) {
        long now = System.currentTimeMillis();
        User user = new User();
        user.setUsername("ad-out-" + authType.name().toLowerCase() + "-" + System.nanoTime());
        user.setPersonnelCode("PC-" + java.util.UUID.randomUUID());
        user.setFullName("Outage " + authType.faLabel());
        // What UserService writes for a directory-only account: a hash that never matches.
        user.setPasswordHash(passwordEncoder.encode(
                localPassword != null ? localPassword : "placeholder-" + java.util.UUID.randomUUID()));
        user.setActive(true);
        user.setAuthType(authType);
        user.setCreatedAt(now);
        user.setUpdatedAt(now);
        user = userRepository.save(user);

        // OPERATOR is seeded in V1 with GET:/api/bootstrap, enough for the login to succeed.
        Role operatorRole = roleRepository.findByCode("OPERATOR").orElseThrow();
        UserRole link = new UserRole();
        link.setUserId(user.getId());
        link.setRoleId(operatorRole.getId());
        userRoleRepository.save(link);
        seeded.add(user);
        return user;
    }
}
