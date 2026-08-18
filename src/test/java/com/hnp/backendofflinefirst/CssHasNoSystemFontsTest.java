package com.hnp.backendofflinefirst;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * No stylesheet or template may name a font that comes from the user's machine.
 *
 * <p>This is here because two Windows 11 machines rendered the same table differently while
 * serving byte-identical CSS — the rule counts matched the repository exactly, the computed
 * colours were right, and the tables still did not look the same. The cause was font resolution,
 * and it is invisible in a diff:
 *
 * <ul>
 *   <li><b>A stack is resolved per glyph, not per element.</b> {@code 'Vazirmatn Persian'} carries
 *       a {@code unicode-range} covering only Persian and Arabic, so every Latin character —
 *       asset codes, ids, tag numbers, chart axes — fell through to the next entry, which was
 *       {@code 'Segoe UI'}. Most of the Latin text in this panel was being drawn by Windows.</li>
 *   <li><b>Bootstrap styles every {@code <code>}, {@code <kbd>}, {@code <pre>} and {@code <samp>}
 *       from {@code --bs-font-monospace}</b>, whose default is a pure system stack
 *       (SFMono-Regular, Menlo, Monaco, Consolas, Liberation Mono, Courier New). Roughly thirty
 *       {@code <code>} elements sit inside the panel's tables.</li>
 *   <li><b>The generic {@code monospace} keyword is a per-browser setting</b>, editable in
 *       chrome://settings/fonts, so two identical installations can disagree.</li>
 * </ul>
 *
 * <p>A reviewer cannot catch this by reading a diff — {@code Tahoma} at the end of a stack looks
 * like courtesy. So the build catches it. The generic keywords {@code sans-serif} and
 * {@code monospace} are allowed as the final entry: they are reachable only if the woff2 we serve
 * fails to load, and there is no way to have no fallback at all.
 */
class CssHasNoSystemFontsTest {

    /**
     * Fonts that are read off the machine rather than served by this application.
     *
     * <p>{@code ui-monospace} and {@code system-ui} are included deliberately: they are not names
     * but instructions to use whatever the operating system prefers, which is the same defect
     * wearing a standards-compliant hat.
     */
    private static final List<String> SYSTEM_FONTS = List.of(
            "consolas", "segoe ui", "tahoma", "arial", "helvetica", "menlo", "monaco",
            "sfmono", "liberation mono", "courier", "cambria", "calibri", "verdana",
            "ui-monospace", "ui-sans-serif", "ui-serif", "system-ui", "-apple-system",
            "blinkmacsystemfont", "roboto", "noto sans", "droid sans");

    /**
     * Legal as the last resort in a stack, never as the first entry.
     *
     * <p>{@code font-family: monospace} is not a fallback, it *is* the choice — and the choice is
     * delegated to whatever the browser profile names in chrome://settings/fonts, which is a
     * per-installation setting. Behind a font we serve it is unreachable; in front of one it is
     * the whole declaration.
     */
    private static final List<String> GENERIC_KEYWORDS =
            List.of("monospace", "sans-serif", "serif", "cursive", "fantasy", "inherit");

    private static final Path STATIC = Path.of("src/main/resources/static");
    private static final Path TEMPLATES = Path.of("src/main/resources/templates");

    /** `font-family: ...;` in CSS and `family: '...'` in the Chart.js options in templates. */
    private static final Pattern DECLARATION =
            Pattern.compile("font-family\\s*:([^;}]*)|family\\s*:\\s*'([^']*)'");

    @Test
    void noStylesheetOrTemplateNamesAFontFromTheUsersMachine() throws IOException {
        List<String> offences = new ArrayList<>();

        for (Path file : sourceFiles()) {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            String withoutComments = stripComments(content);
            Matcher m = DECLARATION.matcher(withoutComments);
            while (m.find()) {
                String stack = m.group(1) != null ? m.group(1) : m.group(2);
                String lower = stack.toLowerCase(Locale.ROOT);
                for (String banned : SYSTEM_FONTS) {
                    if (lower.contains(banned)) {
                        offences.add(file + " → " + stack.strip() + "   (names '" + banned + "')");
                    }
                }
                String first = lower.split(",")[0].replace("'", "").replace("\"", "").strip();
                if (GENERIC_KEYWORDS.contains(first)) {
                    offences.add(file + " → " + stack.strip()
                            + "   (starts at the generic '" + first + "')");
                }
            }
        }

        assertThat(offences)
                .as("""
                    A font stack names a font from the user's machine. Serve it instead, or use \
                    var(--app-font-sans) / var(--app-font-mono). A named system font at the end \
                    of a stack is not a harmless fallback: stacks resolve per glyph, so a face \
                    with a unicode-range hands every character it does not cover to that font, \
                    and machines disagree about what it looks like.""")
                .isEmpty();
    }

    @Test
    void bootstrapsSystemMonospaceStackIsOverridden() throws IOException {
        // Bootstrap's own default reaches every <code> in the panel without any of our selectors
        // being involved, so removing our declarations is not enough — this variable has to be
        // taken over. Assert the override rather than the absence of the default.
        String appCss = Files.readString(STATIC.resolve("css/app.css"), StandardCharsets.UTF_8);

        assertThat(appCss)
                .as("--bs-font-monospace must be redefined, or every <code> falls back to "
                        + "SFMono-Regular/Menlo/Monaco/Consolas/Courier New from the machine")
                .contains("--bs-font-monospace: var(--app-font-mono)");
        assertThat(appCss).contains("--app-font-mono: 'Vazirmatn', monospace");
        assertThat(appCss).contains("--app-font-sans: 'Vazirmatn', sans-serif");
    }

    @Test
    void everyFontWeServeIsAFileInThisRepository() throws IOException {
        // The other half of the claim: no stack names the machine's fonts, and every @font-face
        // points at a local file rather than a CDN. One network call to a font host would make
        // the panel's appearance depend on the plant having internet.
        String vazirCss = Files.readString(STATIC.resolve("css/vazirmatn.css"), StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("url\\(['\"]?([^'\")]+)").matcher(vazirCss);

        int checked = 0;
        while (m.find()) {
            String url = m.group(1);
            assertThat(url).as("a remote font URL").doesNotStartWith("http");
            Path onDisk = STATIC.resolve(url.startsWith("/") ? url.substring(1) : url);
            assertThat(Files.exists(onDisk)).as("%s is served but not in the repository", url).isTrue();
            checked++;
        }
        assertThat(checked).as("the @font-face rules were found and checked").isPositive();
    }

    private static List<Path> sourceFiles() throws IOException {
        try (Stream<Path> css = Files.walk(STATIC); Stream<Path> tpl = Files.walk(TEMPLATES)) {
            return Stream.concat(css, tpl)
                    .filter(Files::isRegularFile)
                    .filter(p -> {
                        String n = p.getFileName().toString();
                        return n.endsWith(".css") || n.endsWith(".html") || n.endsWith(".js");
                    })
                    .toList();
        }
    }

    /** Comments explain *why* a font is banned and would otherwise trip the check on the word. */
    private static String stripComments(String content) {
        return content.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("(?s)<!--.*?-->", " ");
    }
}
