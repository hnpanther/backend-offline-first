package com.hnp.backendofflinefirst.logging;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LogSanitizerTest {

    /** A real JWT shape, so the assertions below are about the value that actually leaked. */
    private static final String JWT =
            "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJhZG1pbiJ9.7Hh1Qk3v_signature";

    @Test
    void masksJsonPasswordFields() {
        String raw = "{\"username\":\"admin\",\"password\":\"secret123\",\"passwordHash\":\"abc\"}";
        String sanitized = LogSanitizer.sanitize(raw);
        assertThat(sanitized).contains("\"password\":\"***\"");
        assertThat(sanitized).contains("\"passwordHash\":\"***\"");
        assertThat(sanitized).doesNotContain("secret123");
    }

    @Test
    void masksKeyValueSecrets() {
        String raw = "password=abc123, name=test";
        assertThat(LogSanitizer.sanitize(raw)).isEqualTo("password=***, name=test");
    }

    @Test
    void leavesNonSensitiveDataUntouched() {
        String raw = "{\"code\":\"LOC-1\",\"name\":\"Hall A\"}";
        assertThat(LogSanitizer.sanitize(raw)).isEqualTo(raw);
    }

    /**
     * The one that mattered.
     *
     * <p>The old pattern anchored on the opening quote, so {@code "token"} matched and
     * {@code "accessToken"} did not — and {@code accessToken} is exactly what the login
     * response and {@code JwtService.JwtToken} call the field. Every mobile login wrote a
     * replayable credential to {@code app.log}.
     */
    @Test
    void masksTheAccessTokenInARealLoginResponse() {
        String raw = "{\"status\":200,\"body\":{\"username\":\"admin\",\"fullName\":\"مدیر سیستم\","
                + "\"accessToken\":\"" + JWT + "\",\"tokenType\":\"Bearer\",\"expiresAt\":1755600000000}}";

        String sanitized = LogSanitizer.sanitize(raw);

        assertThat(sanitized).doesNotContain(JWT);
        assertThat(sanitized).contains("\"accessToken\":\"***\"");
        // Still readable as a log line — the non-secret fields survive.
        assertThat(sanitized).contains("\"username\":\"admin\"").contains("\"expiresAt\":1755600000000");
    }

    /**
     * Any name <em>containing</em> a credential word, because an exact-name list is what failed.
     * These are the names this codebase actually uses plus the ones a future field is likely to
     * be called.
     */
    @Test
    void masksEveryCredentialShapedFieldName() {
        for (String field : new String[]{
                "accessToken", "refreshToken", "token", "tokenType",
                "password", "passwordHash", "newPassword", "confirmPassword",
                "secret", "secretHash", "clientSecret",
                "credential", "userCredentials",
                "apiKey", "presentedKey", "authorization", "jwt"}) {
            String raw = "{\"" + field + "\":\"" + JWT + "\"}";

            assertThat(LogSanitizer.sanitize(raw))
                    .as("field %s must be masked", field)
                    .doesNotContain(JWT)
                    .contains("\"" + field + "\":\"***\"");
        }
    }

    /**
     * {@code key} is deliberately not a credential word.
     *
     * <p>Making it one would mask {@code fieldKey} and the bare {@code key} carried by every
     * parameter definition and every recorded value — i.e. the readings this application exists
     * to record, and every row of the integration API's logs. Under-masking a credential is a
     * leak; over-masking these makes the logs useless, and both are failures.
     */
    @Test
    void doesNotMaskTheParameterKeysThatCarryReadings() {
        String raw = "{\"key\":\"Temperature\",\"fieldKey\":\"Pic\",\"label\":\"دما\",\"value\":\"14\"}";

        assertThat(LogSanitizer.sanitize(raw)).isEqualTo(raw);
    }

    @Test
    void masksTheJwtTokenRecordAsSerialisedByTheAspect() {
        // JwtService.JwtToken reaches LoggingAspect as an argument of ApiSessionService.register.
        String raw = "{\"accessToken\":\"" + JWT + "\",\"jti\":\"a-b-c\",\"issuedAt\":1,\"expiresAt\":2}";

        String sanitized = LogSanitizer.sanitize(raw);

        assertThat(sanitized).doesNotContain(JWT);
        // jti must survive: it is how an administrator ties a log line to an api_sessions row.
        assertThat(sanitized).contains("\"jti\":\"a-b-c\"");
    }

    @Test
    void masksAnAuthorizationHeaderRenderedAsKeyValue() {
        assertThat(LogSanitizer.sanitize("authorization=Bearer " + JWT)).doesNotContain(JWT);
    }

    @Test
    void aFieldNameThatMerelyEndsInAWordBoundaryIsNotConfusedForOne() {
        // `\b` on the key=value pattern: "notapassword=x" is still a password-ish name and is
        // masked, but a value that happens to contain the word is not touched.
        assertThat(LogSanitizer.sanitize("{\"note\":\"the password policy changed\"}"))
                .isEqualTo("{\"note\":\"the password policy changed\"}");
    }

    @Test
    void handlesNullAndBlank() {
        assertThat(LogSanitizer.sanitize(null)).isNull();
        assertThat(LogSanitizer.sanitize("")).isEmpty();
        assertThat(LogSanitizer.sanitize("   ")).isEqualTo("   ");
    }
}
