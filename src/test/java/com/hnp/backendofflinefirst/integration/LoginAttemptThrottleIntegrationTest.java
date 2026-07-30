package com.hnp.backendofflinefirst.integration;

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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Regression coverage for a bug where a single failed web-form login was recorded as
 * two failures: {@code WebSecurityConfig}'s web chain used to wire the login form with
 * {@code .authenticationProvider(...)}, which lets {@code HttpSecurity}'s builder silently
 * attach the Boot-auto-configured *global* AuthenticationManager as a parent — and that
 * global manager resolves to the same provider bean, so a failed attempt ran
 * {@code additionalAuthenticationChecks()} (and {@code LoginAttemptService.recordFailure})
 * twice. Fixed by wiring the same no-parent {@code AuthenticationManager} bean into both
 * chains (see AGENTS.md gotcha #17). Also covers the web login page showing the specific
 * lockout message, and that the mobile API shares the same throttle state.
 */
class LoginAttemptThrottleIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String PASSWORD = "throttle-test-pass-1";
    private static final String WRONG_PASSWORD = "definitely-wrong";

    @Autowired WebApplicationContext context;
    @Autowired UserRepository userRepository;
    @Autowired RoleRepository roleRepository;
    @Autowired UserRoleRepository userRoleRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired LoginAttemptService loginAttemptService;

    @Value("${app.auth.login-attempt.max-attempts}")
    int maxAttempts;

    MockMvc mockMvc;
    User testUser;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        testUser = seedLocalUser();
    }

    @AfterEach
    void cleanUp() {
        loginAttemptService.unlock(testUser.getUsername());
        userRoleRepository.deleteAll(userRoleRepository.findAll().stream()
                .filter(ur -> ur.getUserId().equals(testUser.getId()))
                .toList());
        userRepository.deleteById(testUser.getId());
    }

    @Test
    void singleFailedWebFormLoginRecordsExactlyOneFailure() throws Exception {
        attemptWebLogin(WRONG_PASSWORD, null);

        LoginAttemptService.Status status = loginAttemptService.snapshot().stream()
                .filter(s -> s.username().equalsIgnoreCase(testUser.getUsername()))
                .findFirst()
                .orElseThrow();

        assertThat(status.failureCount()).isEqualTo(1);
        assertThat(status.locked()).isFalse();
    }

    @Test
    void webFormLockoutShowsSpecificMessageAndBlocksApiToo() throws Exception {
        MockHttpSession session = null;
        // The Nth wrong attempt is the one that reaches the threshold, but the lock check
        // itself runs *before* password verification on each attempt — so the Nth attempt
        // still fails on its own merits (plain "bad credentials"); only the (N+1)th attempt
        // is rejected purely for being locked, without even checking the password.
        for (int i = 0; i < maxAttempts; i++) {
            session = attemptWebLogin(WRONG_PASSWORD, session);
        }
        assertThat(loginAttemptService.isLocked(testUser.getUsername())).isTrue();
        session = attemptWebLogin(PASSWORD, session);

        // The web login page exposes the specific lockout reason as a model attribute (not
        // the generic message) — checked on the model object itself (in-JVM string compare)
        // rather than scanning rendered response bytes, since MockMvc's response decoding for
        // this particular request doesn't reliably preserve non-ASCII content.
        mockMvc.perform(get("/login").session(session))
                .andExpect(status().isOk())
                .andExpect(model().attribute("loginErrorMessage", containsString("قفل شده است")));

        // Same throttle blocks the mobile API too, even with the correct password. The API
        // path returns the message directly in the JSON body, so response content is checked
        // there instead (that response is UTF-8 JSON and decodes correctly).
        MvcResult apiResult = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + testUser.getUsername()
                                + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isUnauthorized())
                .andReturn();
        assertThat(apiResult.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8))
                .contains("قفل شده است");
    }

    private MockHttpSession attemptWebLogin(String password, MockHttpSession session) throws Exception {
        var request = post("/login")
                .param("username", testUser.getUsername())
                .param("password", password)
                .with(csrf());
        if (session != null) {
            request.session(session);
        }
        MvcResult result = mockMvc.perform(request)
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?error"))
                .andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    private User seedLocalUser() {
        long now = System.currentTimeMillis();
        User user = new User();
        user.setUsername("throttle-test-" + now);
        user.setFullName("Throttle Test User");
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setActive(true);
        user.setAuthType(UserAuthType.LOCAL);
        user.setCreatedAt(now);
        user.setUpdatedAt(now);
        user = userRepository.save(user);

        Role operatorRole = roleRepository.findByCode("OPERATOR").orElseThrow();
        UserRole link = new UserRole();
        link.setUserId(user.getId());
        link.setRoleId(operatorRole.getId());
        userRoleRepository.save(link);
        return user;
    }
}
