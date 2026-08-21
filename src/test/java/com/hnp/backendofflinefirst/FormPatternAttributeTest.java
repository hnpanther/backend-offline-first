package com.hnp.backendofflinefirst;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every HTML {@code pattern=} attribute in the templates must actually be enforced by the browser.
 *
 * <h2>The trap</h2>
 *
 * <p>A {@code pattern} attribute is compiled by the browser as {@code ^(?:…)$} <b>with the
 * {@code v} flag</b>. Under {@code v}, characters that are merely unusual under {@code u} become
 * syntax errors — and the HTML spec says that a {@code pattern} which fails to compile is
 * <b>ignored</b>, so the field silently accepts everything.
 *
 * <p>That is not hypothetical. The field-key input shipped as {@code pattern="[A-Za-z0-9_-]+"},
 * which reads as obviously correct and is valid in Java, in {@code grep}, and under the {@code u}
 * flag. Under {@code v} an unescaped {@code -} in that position is
 * {@code "Invalid character in character class"}, so the attribute did nothing: typing
 * {@code Bearing Housing} passed the form and only the server refused it. Confirmed in a real
 * browser — {@code checkValidity()} returned true for every value tested, including Persian text
 * and {@code =temp}.
 *
 * <h2>What this checks</h2>
 *
 * <p>Java's regex engine will not reproduce the {@code v} flag's rules, so this does not try to.
 * It checks the one construct that caused it and would cause it again: an unescaped {@code -}
 * sitting at the end of a character class. Written as {@code \-} it compiles under both flags,
 * which is what the templates now use.
 *
 * <p>The alternative — a browser-driven test — would catch more, and would also mean starting a
 * browser to assert a property of a string. This is the cheap half that covers the failure that
 * actually happened.
 */
class FormPatternAttributeTest {

    private static final Path TEMPLATES = Path.of("src/main/resources/templates");

    /** {@code pattern="…"} in any template. */
    private static final Pattern PATTERN_ATTRIBUTE = Pattern.compile("pattern=\"([^\"]*)\"");

    /**
     * A {@code -} immediately before the closing bracket of a character class, unescaped.
     *
     * <p>{@code [A-Za-z0-9_-]} matches; {@code [A-Za-z0-9_\-]} does not, because the {@code -}
     * is preceded by a backslash.
     */
    private static final Pattern UNESCAPED_TRAILING_HYPHEN =
            Pattern.compile("\\[[^]]*[^\\\\]-]");

    @Test
    void noTemplatePatternUsesAnUnescapedTrailingHyphenInACharacterClass() throws IOException {
        List<String> offenders = new ArrayList<>();

        for (Path template : templates()) {
            String html = Files.readString(template, StandardCharsets.UTF_8);
            Matcher attributes = PATTERN_ATTRIBUTE.matcher(html);
            while (attributes.find()) {
                String value = attributes.group(1);
                if (UNESCAPED_TRAILING_HYPHEN.matcher(value).find()) {
                    offenders.add(TEMPLATES.relativize(template) + ": " + value);
                }
            }
        }

        assertThat(offenders)
                .as("""
                    A pattern attribute is compiled with the regex `v` flag, where an unescaped \
                    `-` at the end of a character class is a syntax error — and a pattern that \
                    does not compile is IGNORED, so the input validates nothing while looking \
                    correct. Write it as \\- instead.""")
                .isEmpty();
    }

    /**
     * The field-key input states the same rule the server enforces.
     *
     * <p>Two copies of a rule drift. This does not merge them — the server's is Java and the
     * form's is an HTML attribute — but it does fail the build when they stop agreeing, which is
     * the part that matters: a form that permits what the server refuses turns a typo into a
     * round trip and an error banner, and a form that refuses what the server permits is a field
     * nobody can fill.
     */
    @Test
    void theFieldKeyInputsCarryTheServersRule() throws IOException {
        String html = Files.readString(TEMPLATES.resolve("field-definitions.html"), StandardCharsets.UTF_8);

        long occurrences = PATTERN_ATTRIBUTE.matcher(html).results()
                .map(r -> r.group(1))
                .filter("[A-Za-z0-9_\\-]+"::equals)
                .count();

        assertThat(occurrences)
                .as("both the add and the edit field-key inputs must carry the identifier rule; "
                    + "the server's is MasterDataUniquenessValidator.VALID_FIELD_KEY")
                .isEqualTo(2);
    }

    private static List<Path> templates() throws IOException {
        try (var paths = Files.walk(TEMPLATES)) {
            return paths.filter(p -> p.toString().endsWith(".html")).toList();
        }
    }
}
