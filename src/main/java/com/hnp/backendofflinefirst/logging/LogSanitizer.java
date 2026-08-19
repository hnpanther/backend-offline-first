package com.hnp.backendofflinefirst.logging;

import java.util.regex.Pattern;

/**
 * Masks secrets before they are written to logs (passwords, tokens, hashes).
 */
public final class LogSanitizer {

    /**
     * Field names whose value is a credential.
     *
     * <p>{@code apiKey} / {@code secretHash} joined the list with the integration API. The
     * plaintext key exists in exactly one response — the one that creates it — and the hash
     * exists on every {@code ApiKey} the admin page loads; both would otherwise be serialised
     * in full by {@code LoggingAspect}. Note {@code secretHash} needs naming in its own right:
     * the alternation matches a <em>whole</em> field name, so {@code secret} does not cover it.
     */
    private static final String SECRET_FIELD_NAMES =
            "password|passwordHash|newPassword|confirmPassword|token|secret|secretHash|apiKey|presentedKey";

    private static final Pattern JSON_SECRET_FIELDS = Pattern.compile(
            "(\"(?:" + SECRET_FIELD_NAMES + ")\"\\s*:\\s*)\"[^\"]*\"",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern KEY_VALUE_SECRET = Pattern.compile(
            "(" + SECRET_FIELD_NAMES + ")\\s*=\\s*[^,\\]\\}\\s]+",
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
