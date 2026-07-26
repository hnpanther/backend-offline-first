package com.hnp.backendofflinefirst.integration;

import com.hnp.backendofflinefirst.entity.Role;
import com.hnp.backendofflinefirst.entity.User;
import com.hnp.backendofflinefirst.entity.UserAuthType;
import com.hnp.backendofflinefirst.entity.UserRole;
import com.hnp.backendofflinefirst.repository.RoleRepository;
import com.hnp.backendofflinefirst.repository.UserRepository;
import com.hnp.backendofflinefirst.repository.UserRoleRepository;
import com.hnp.backendofflinefirst.security.AppUserDetails;
import com.hnp.backendofflinefirst.service.WebSessionService;
import com.hnp.backendofflinefirst.support.AbstractPostgresIntegrationTest;
import com.hnp.backendofflinefirst.support.WithAppUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end checks for web-panel session control: one concurrent session per user
 * (a new form login expires the previous browser), and the admin /web-sessions page
 * lists and expires live sessions. Mirrors {@link ApiSessionIntegrationTest} for the
 * session-based web chain.
 */
class WebSessionConcurrencyIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String PASSWORD = "web-sess-pass-1";

    @Autowired WebApplicationContext context;
    @Autowired UserRepository userRepository;
    @Autowired RoleRepository roleRepository;
    @Autowired UserRoleRepository userRoleRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired WebSessionService webSessionService;
    @Autowired SessionRegistry sessionRegistry;

    MockMvc mockMvc;
    User operator;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        operator = seedOperator();
    }

    @AfterEach
    void cleanUp() {
        // The registry is an application-scoped singleton — drop this test's sessions
        // so they do not leak into other tests sharing the Spring context.
        for (Object principal : sessionRegistry.getAllPrincipals()) {
            if (principal instanceof AppUserDetails details
                    && details.getUsername().equals(operator.getUsername())) {
                sessionRegistry.getAllSessions(principal, true)
                        .forEach(info -> sessionRegistry.removeSessionInformation(info.getSessionId()));
            }
        }
        userRoleRepository.deleteAll(userRoleRepository.findAll().stream()
                .filter(ur -> ur.getUserId().equals(operator.getId()))
                .toList());
        userRepository.deleteById(operator.getId());
    }

    @Test
    void secondLoginExpiresTheFirstBrowserSession() throws Exception {
        MockHttpSession firstBrowser = login();
        mockMvc.perform(get("/my-inbox").session(firstBrowser)).andExpect(status().isOk());

        MockHttpSession secondBrowser = login();

        // The superseded browser is bounced to the login page with the expired notice…
        mockMvc.perform(get("/my-inbox").session(firstBrowser))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?expired"));
        // …while the new browser keeps working.
        mockMvc.perform(get("/my-inbox").session(secondBrowser)).andExpect(status().isOk());
    }

    @Test
    void expiringASessionFromTheServiceLogsThatBrowserOut() throws Exception {
        MockHttpSession browser = login();
        mockMvc.perform(get("/my-inbox").session(browser)).andExpect(status().isOk());

        List<WebSessionService.WebSessionView> rows = webSessionService.listActiveSessions(null);
        WebSessionService.WebSessionView row = rows.stream()
                .filter(r -> r.username().equals(operator.getUsername()))
                .findFirst()
                .orElseThrow();
        assertThat(row.fullName()).isEqualTo("Web Session Operator");
        assertThat(row.loginAt()).isNotNull();
        assertThat(row.ipAddress()).isNotBlank();

        webSessionService.expireByKey(row.key(), 1L);

        mockMvc.perform(get("/my-inbox").session(browser))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?expired"));
    }

    @Test
    @WithAppUser(authorities = {"GET:/web-sessions", "POST:/web-sessions/{key}/expire"})
    void adminPageListsSessionsWithoutLeakingRawSessionIds() throws Exception {
        MockHttpSession browser = login();

        String key = webSessionService.listActiveSessions(null).stream()
                .filter(r -> r.username().equals(operator.getUsername()))
                .findFirst()
                .orElseThrow()
                .key();

        // Rows are addressed by a digest key, never by the raw session id.
        assertThat(key).isNotEqualTo(browser.getId());
        mockMvc.perform(get("/web-sessions"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(operator.getUsername())))
                .andExpect(content().string(containsString(key)));

        mockMvc.perform(post("/web-sessions/" + key + "/expire").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/web-sessions"));

        mockMvc.perform(get("/my-inbox").session(browser))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?expired"));
    }

    /** Form login through the real security chain; returns the resulting servlet session. */
    private MockHttpSession login() throws Exception {
        MvcResult result = mockMvc.perform(post("/login")
                        .param("username", operator.getUsername())
                        .param("password", PASSWORD)
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/my-inbox"))
                .andReturn();
        MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
        assertThat(session).isNotNull();
        return session;
    }

    private User seedOperator() {
        long now = System.currentTimeMillis();
        User user = new User();
        user.setUsername("web-sess-op-" + now);
        user.setFullName("Web Session Operator");
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setActive(true);
        user.setAuthType(UserAuthType.LOCAL);
        user.setCreatedAt(now);
        user.setUpdatedAt(now);
        user = userRepository.save(user);

        // OPERATOR is seeded in V1 with GET:/my-inbox (login redirect for unit-scoped users).
        Role operatorRole = roleRepository.findByCode("OPERATOR").orElseThrow();
        UserRole link = new UserRole();
        link.setUserId(user.getId());
        link.setRoleId(operatorRole.getId());
        userRoleRepository.save(link);
        return user;
    }
}
