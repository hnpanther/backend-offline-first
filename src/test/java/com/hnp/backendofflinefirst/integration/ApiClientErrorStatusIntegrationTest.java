package com.hnp.backendofflinefirst.integration;

import com.hnp.backendofflinefirst.support.AbstractPostgresIntegrationTest;
import com.hnp.backendofflinefirst.support.WithAppUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A malformed request must be answered 4xx, never 500.
 *
 * <h2>Why this has its own test class</h2>
 *
 * <p>{@code ApiExceptionHandler} catches {@code Exception}, and {@code @ExceptionHandler} on a
 * {@code @RestControllerAdvice} is consulted before Spring's own
 * {@code DefaultHandlerExceptionResolver} — so every binding failure was answered
 * {@code 500 خطای نامشخص}. Found by posting to {@code /api/attachments} without its {@code id}
 * parameter.
 *
 * <p><b>The status is contractual, not cosmetic.</b> The PWA's {@code isPermanentFailure} parks a
 * 4xx and keeps retrying a 5xx. A permanently malformed upload answered 500 is therefore re-sent
 * on every sync pass for the life of the tablet, and the operator is shown «خطای نامشخص» each
 * time. Each case below is a request that can never succeed; each must come back in the range
 * that makes the client stop.
 */
class ApiClientErrorStatusIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired WebApplicationContext context;

    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    /** The case that was found: {@code id} is required and absent. */
    @Test
    @WithAppUser(roles = "OPERATOR", authorities = "POST:/api/attachments")
    void aMissingRequestParameterIsFourHundredNotFiveHundred() throws Exception {
        mockMvc.perform(multipart("/api/attachments")
                        .file(new MockMultipartFile("file", "a.png", "image/png", new byte[]{1, 2, 3}))
                        .param("logSheetId", "1")
                        .param("assetId", "1")
                        .param("fieldKey", "Picture"))
                .andExpect(status().isBadRequest());
    }

    /** The multipart part itself is absent. */
    @Test
    @WithAppUser(roles = "OPERATOR", authorities = "POST:/api/attachments")
    void aMissingMultipartPartIsFourHundred() throws Exception {
        mockMvc.perform(multipart("/api/attachments")
                        .param("id", "11111111-1111-1111-1111-111111111111")
                        .param("logSheetId", "1")
                        .param("assetId", "1")
                        .param("fieldKey", "Picture"))
                .andExpect(status().isBadRequest());
    }

    /**
     * A path variable that is not a number, on an endpoint that really exists — {@code
     * /api/log-sheets/{id}/entries} binds {@code id} to a {@code Long}, so this reaches the
     * binder rather than failing to match a route.
     */
    @Test
    @WithAppUser(roles = "OPERATOR", authorities = "GET:/api/log-sheets/{id}/entries")
    void aTypeMismatchIsFourHundred() throws Exception {
        mockMvc.perform(get("/api/log-sheets/not-a-number/entries"))
                .andExpect(status().isBadRequest());
    }

    /**
     * An unreadable JSON body. Checked on the <b>unauthenticated</b> login endpoint on purpose:
     * it is the one a caller can reach before authenticating, so it is where a 500 would be both
     * most visible and most useless.
     */
    @Test
    void anUnreadableJsonBodyIsFourHundred() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\": "))
                .andExpect(status().isBadRequest());
    }

    @Test
    void theWrongMethodIsFourOhFive() throws Exception {
        mockMvc.perform(get("/api/auth/login"))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    void theWrongContentTypeIsFourFifteen() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("username=admin"))
                .andExpect(status().isUnsupportedMediaType());
    }

    /**
     * And the counterweight: a genuine server fault must still be a 500. Without this the fix
     * could be "turn every error into a 400", which would make the client park faults that
     * really will clear on their own.
     */
    @Test
    @WithAppUser(roles = "ADMIN", authorities = "GET:/api/log-sheets/{id}/entries")
    void aRequestThatBindsCorrectlyIsNotTurnedIntoAClientError() throws Exception {
        // Binds fine, finds nothing. Whatever this answers, it must not be 400 for *binding*.
        mockMvc.perform(get("/api/log-sheets/999999999/entries"))
                .andExpect(result -> {
                    int status = result.getResponse().getStatus();
                    if (status == 400) {
                        throw new AssertionError("a well-formed request was reported as malformed");
                    }
                });
    }
}
