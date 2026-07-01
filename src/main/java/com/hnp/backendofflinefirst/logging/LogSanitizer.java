package com.hnp.backendofflinefirst.logging;

import java.util.regex.Pattern;

/**
 * Masks secrets before they are written to logs (passwords, tokens, hashes).
 */
public final class LogSanitizer {

    private static final Pattern JSON_SECRET_FIELDS = Pattern.compile(
            "(\"(?:password|passwordHash|newPassword|confirmPassword|token|secret)\"\\s*:\\s*)\"[^\"]*\"",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern KEY_VALUE_SECRET = Pattern.compile(
            "(password|passwordHash|newPassword|confirmPassword|token|secret)\\s*=\\s*[^,\\]\\}\\s]+",
            Pattern.CASE_INSENSITIVE);

    private LogSanitizer() {}

    public static String sanitize(String input) {
        if (input == null || input.isBlank()) {
            return input;
        }
        String masked = JSON_SECRET_FIELDS.matcher(input).replaceAll("$1\"***\"");
        return KEY_VALUE_SECRET.matcher(masked).replaceAll("$1=***");
    }
}
