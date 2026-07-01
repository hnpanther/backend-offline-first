package com.hnp.backendofflinefirst.logging;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LogSanitizerTest {

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
}
