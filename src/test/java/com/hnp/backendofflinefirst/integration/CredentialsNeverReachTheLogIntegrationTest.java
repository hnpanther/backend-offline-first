package com.hnp.backendofflinefirst.integration;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hnp.backendofflinefirst.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.hnp.backendofflinefirst.entity.UserAuthType;
import com.hnp.backendofflinefirst.repository.RoleRepository;
import com.hnp.backendofflinefirst.service.UserService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * No credential this application issues ever reaches a log line.
 *
 * <h2>Why this test attaches a real appender instead of asserting on a string</h2>
 *
 * <p>{@code LogSanitizerTest} proves the masking function is correct in isolation, and it
 * passed happily while the leak was live — because the leak was not in the function, it was in
 * <em>what reached it</em>. {@code LoggingAspect} serialised the whole login response body, the
 * sanitizer's field list did not contain {@code accessToken}, and the JWT went to
 * {@code app.log} in clear text, replayable until the session expired.
 *
 * <p>So this test captures what the loggers actually emit during a real login and a real
 * authenticated request, and asserts the token is not in any of it. It is the only arrangement
 * that can fail if either half of the fix is undone — the sanitizer's pattern <b>or</b> the
 * aspect's {@code compactOutput} flag.
 *
 * <p>The application's own loggers are captured at TRACE so the assertion covers the DEBUG
 * service and repository lines too, not just the INFO request boundary. Which loggers, and why
 * not the root one, is explained on {@link #WATCHED_LOGGERS}.
 *
 * <h2>Why these tests log in as an OPERATOR and not as admin</h2>
 *
 * <p>Because as {@code admin} the leak is invisible, for a reason that has nothing to do with
 * security. {@code LoginResponse} serialises {@code permissions} <em>before</em>
 * {@code accessToken}, and an administrator holds ~123 of them; the line blows past
 * {@code LoggingAspect.MAX_JSON_LENGTH} (4,000 characters) and is cut off before the token is
 * ever reached. Verified live: an admin login produced a 5,627-character JWT and a truncated
 * log line with no token in it.
 *
 * <p>An operator holds about eleven permissions, so the whole response — token included — fits
 * comfortably inside the limit and was written to {@code app.log} in full. Operators are also
 * precisely who uses this API: a plant's tablets do not log in as admin.
 *
 * <p>A first version of this test used {@code admin} and passed against the broken code. That
 * is the trap worth remembering: truncation is not a security control, and a test that happens
 * to exercise the truncating case proves nothing about the case that leaks.
 */
class CredentialsNeverReachTheLogIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String OPERATOR_PASSWORD = "op-secret-12345";

    @Autowired WebApplicationContext context;
    @Autowired ObjectMapper objectMapper;
    @Autowired UserService userService;
    @Autowired RoleRepository roleRepository;

    MockMvc mockMvc;

    private String operatorUsername;
    private Long operatorId;

    /**
     * The loggers to watch, and <b>not</b> the root logger.
     *
     * <p>{@code logback-spring.xml} declares {@code com.hnp.backendofflinefirst} — and the
     * {@code business} / {@code audittrail} loggers under it — with {@code additivity="false"},
     * because each writes to its own file. That means this application's own log events
     * <em>never propagate to the root logger's appenders</em>.
     *
     * <p>An earlier version of this test attached to the root logger and passed every
     * assertion — while capturing nothing but Spring and Hibernate chatter. It would have gone
     * on passing with the credential leak fully open. The only reason that was caught is
     * {@link #theRequestBoundaryIsStillLogged()}, which asserts something <em>must</em> be
     * there; without a positive assertion, a capture that collects nothing is indistinguishable
     * from a system that leaks nothing.
     */
    private static final List<String> WATCHED_LOGGERS = List.of(
            "com.hnp.backendofflinefirst",
            "com.hnp.backendofflinefirst.business",
            "com.hnp.backendofflinefirst.audittrail");

    private final List<Logger> watched = new ArrayList<>();
    private final Map<Logger, Level> previousLevels = new LinkedHashMap<>();
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        seedOperator();
        appender = new ListAppender<>();
        appender.start();
        for (String name : WATCHED_LOGGERS) {
            Logger logger = (Logger) LoggerFactory.getLogger(name);
            previousLevels.put(logger, logger.getLevel());
            // TRACE so the DEBUG service and repository lines are captured too — those go
            // through the aspect's *full* serialisation path, which is where a payload would
            // leak even after the request boundary was made compact.
            logger.setLevel(Level.TRACE);
            logger.addAppender(appender);
            watched.add(logger);
        }
    }

    private void seedOperator() {
        String suffix = String.valueOf(System.nanoTime());
        operatorUsername = "log-leak-op-" + suffix;
        Long operatorRoleId = roleRepository.findByCode("OPERATOR").orElseThrow().getId();
        var user = userService.create(operatorUsername, "اپراتور آزمون", "PC-" + suffix, null,
                null, null, null, null, null,
                OPERATOR_PASSWORD, UserAuthType.LOCAL, true, List.of(operatorRoleId));
        operatorId = user.getId();
    }

    @AfterEach
    void tearDown() {
        if (operatorId != null) {
            // Through the service, which is @Transactional — this test deliberately is not, so a
            // bare repository delete has no EntityManager to remove through. The operator is
            // freshly created and has no app activity, so the service's delete guards all pass.
            try {
                userService.delete(operatorId);
            } catch (RuntimeException ignored) {
                // Cleanup must never mask the assertion that already failed.
            }
            operatorId = null;
        }
        for (Logger logger : watched) {
            logger.detachAppender(appender);
            logger.setLevel(previousLevels.get(logger));
        }
        watched.clear();
        previousLevels.clear();
        appender.stop();
    }

    /** Everything the loggers emitted, as one blob to search. */
    private String captured() {
        StringBuilder out = new StringBuilder();
        for (ILoggingEvent event : List.copyOf(appender.list)) {
            out.append(event.getFormattedMessage()).append('\n');
        }
        return out.toString();
    }

    @Test
    void aMobileLoginNeverWritesItsJwtToTheLog() throws Exception {
        MvcResult login = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", operatorUsername, "password", OPERATOR_PASSWORD))))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode body = objectMapper.readTree(login.getResponse().getContentAsString());
        String token = body.get("accessToken").asText();
        assertThat(token).as("the test is meaningless if no token was issued").isNotBlank();

        String logs = captured();

        assertThat(logs)
                .as("the whole token must not appear anywhere in the logs")
                .doesNotContain(token);
        // The signature alone is enough to forge nothing, but the payload segment identifies the
        // user and the header segment is constant — assert on the two that carry meaning.
        String[] segments = token.split("\\.");
        assertThat(logs).as("the JWT payload segment must not appear either")
                .doesNotContain(segments[1]);
        assertThat(logs).as("the JWT signature must not appear either")
                .doesNotContain(segments[2]);
    }

    @Test
    void theLoginPasswordNeverReachesTheLog() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", operatorUsername, "password", OPERATOR_PASSWORD))))
                .andExpect(status().isOk());

        assertThat(captured()).doesNotContain(OPERATOR_PASSWORD);
    }

    @Test
    void aFailedLoginDoesNotLogTheAttemptedPassword() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", operatorUsername, "password", "wrong-password-attempt"))))
                .andExpect(status().isUnauthorized());

        assertThat(captured()).doesNotContain("wrong-password-attempt");
    }

    /**
     * The bearer token is also not echoed back on the requests that carry it.
     *
     * <p>Separate from the login case: the credential arrives in a header here rather than in a
     * response body, and a future change that starts logging headers would reopen the leak from
     * the other direction.
     */
    @Test
    void anAuthenticatedRequestDoesNotEchoItsBearerToken() throws Exception {
        MvcResult login = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", operatorUsername, "password", OPERATOR_PASSWORD))))
                .andExpect(status().isOk()).andReturn();
        String token = objectMapper.readTree(login.getResponse().getContentAsString())
                .get("accessToken").asText();

        appender.list.clear();

        mockMvc.perform(get("/api/bootstrap").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());

        assertThat(captured()).doesNotContain(token);
    }

    /**
     * The integration API key is the other credential this system issues, and it travels in a
     * header on every request an integration makes — the highest-frequency credential of all.
     */
    @Test
    void anIntegrationApiKeyNeverReachesTheLog() throws Exception {
        var issued = context.getBean(com.hnp.backendofflinefirst.service.ApiKeyService.class)
                .create("LogLeakProbe " + java.util.UUID.randomUUID(), null, null, null);
        try {
            appender.list.clear();

            mockMvc.perform(get("/integration/v1/log-sheets")
                            .header("X-API-Key", issued.apiKey())
                            .param("from", "2020-01-01").param("to", "2100-01-01"))
                    .andExpect(status().isOk());

            // The usage row is written on auditExecutor; wait for it so the assertion covers
            // that thread's log lines too, and so cleanup below sees the row it must remove.
            Thread.sleep(500);

            String logs = captured();
            assertThat(logs).doesNotContain(issued.apiKey());
            // The public half may legitimately appear — it is what makes a usage row readable.
            // The secret half must not.
            assertThat(logs).doesNotContain(
                    issued.apiKey().substring(issued.apiKey().lastIndexOf('_') + 1));
        } finally {
            // Usage rows first: the request above wrote one asynchronously, and it holds a
            // foreign key to the row being deleted.
            var usageRepo = context.getBean(
                    com.hnp.backendofflinefirst.repository.ApiKeyUsageRepository.class);
            usageRepo.deleteAll(usageRepo.findAll().stream()
                    .filter(u -> issued.key().getId().equals(u.getApiKeyId()))
                    .toList());
            context.getBean(com.hnp.backendofflinefirst.repository.ApiKeyRepository.class)
                    .deleteById(issued.key().getId());
        }
    }

    /**
     * The request boundary still logs something useful.
     *
     * <p>Without this, "no token in the logs" would also pass if somebody turned the aspect off
     * entirely, which would be a different and equally unwelcome regression.
     */
    @Test
    void theRequestBoundaryIsStillLogged() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", operatorUsername, "password", OPERATOR_PASSWORD))))
                .andExpect(status().isOk());

        assertThat(captured())
                .contains("[API]")
                .contains("AuthApiController.login");
    }
}
