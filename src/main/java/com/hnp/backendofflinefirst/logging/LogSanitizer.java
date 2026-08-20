package com.hnp.backendofflinefirst.logging;

import java.util.regex.Pattern;

/**
 * Masks secrets before they are written to logs (passwords, tokens, hashes, API keys).
 */
public final class LogSanitizer {

    /**
     * What counts as a credential field, matched on the <b>whole field name</b>.
     *
     * <h2>Why this is a substring rule and not a list of exact names</h2>
     *
     * <p>It used to be a list: {@code password|passwordHash|token|secret|…}. That list looks
     * complete and is not, because the pattern anchors on the opening quote — so
     * {@code "token"} matched and <b>{@code "accessToken"} did not</b>. The login response
     * carries its JWT in a field called {@code accessToken}, and {@code JwtService.JwtToken}
     * names its field the same way, so the full token of every mobile login was written to
     * {@code app.log} in clear text, readable by anyone who could read the file and replayable
     * until the session expired or an administrator revoked it.
     *
     * <p>The lesson is not "add accessToken to the list" — it is that an exact-name list fails
     * silently every time somebody names a field naturally. Matching any name that
     * <em>contains</em> one of these words covers {@code accessToken}, {@code refreshToken},
     * {@code clientSecret}, {@code passwordHash}, {@code newPassword} and whatever the next one
     * is called, without anybody having to remember this file exists.
     *
     * <h2>Why {@code key} is deliberately NOT one of the words</h2>
     *
     * <p>It would swallow {@code fieldKey} and the bare {@code key} of every parameter
     * definition and every recorded value — masking the readings this application exists to
     * record, and turning the integration API's logs into rows of {@code ***}. The two key-ish
     * names that really are credentials are listed explicitly instead.
     *
     * <p>Over-masking is cheap here ({@code tokenType} becomes {@code ***}, and "Bearer" is no
     * loss); under-masking cost a production credential leak.
     */
    private static final String SECRET_FIELD_NAME =
            "[A-Za-z0-9_]*(?:password|token|secret|credential)[A-Za-z0-9_]*"
                    + "|apiKey|presentedKey|authorization|jwt";

    private static final Pattern JSON_SECRET_FIELDS = Pattern.compile(
            "(\"(?:" + SECRET_FIELD_NAME + ")\"\\s*:\\s*)\"[^\"]*\"",
            Pattern.CASE_INSENSITIVE);

    /**
     * The {@code name=value} rendering, which {@code LoggingAspect.argSummary} produces.
     *
     * <p>The value stops at the first whitespace, comma or closing bracket — deliberately, so
     * masking one field cannot swallow the rest of the line. {@code Bearer } is the one prefix
     * that has to be allowed through that rule: without it {@code authorization=Bearer eyJ…}
     * masks the word "Bearer" and leaves the credential standing.
     */
    private static final Pattern KEY_VALUE_SECRET = Pattern.compile(
            "\\b(" + SECRET_FIELD_NAME + ")\\s*=\\s*(?:Bearer\\s+)?[^,\\]\\}\\s]+",
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
