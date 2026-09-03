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
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every static asset must reach the browser under a content-hashed URL.
 *
 * <h2>Why this is load-bearing</h2>
 *
 * <p>{@code application.properties} serves static resources with a one-year {@code max-age}. That
 * is only safe because the URL carries a hash of the file's own bytes, so a deployment changes
 * the URL and the cached response for the old one is never asked for again.
 *
 * <p>The reverse is a genuinely nasty failure, and the reason the hashing was added in the first
 * place: an asset served at a fixed path with a long cache is answered <b>200 from cache</b>
 * indefinitely. Not a failed request, not a console error — the page simply renders with an old
 * stylesheet, with no evidence anywhere that it did.
 *
 * <p>So the cache header and this test are one change. The header was deliberately withheld until
 * every path could be shown to be rewritten; this is what keeps that true.
 *
 * <h2>The two ways a path escapes hashing</h2>
 *
 * <ol>
 *   <li>A template writes {@code href="/css/app.css"} instead of {@code th:href="@{/css/app.css}"}.
 *       Only the {@code @{...}} form passes through {@code ResourceUrlEncodingFilter}; a literal
 *       path is served as written.</li>
 *   <li>A stylesheet points {@code url(...)} at a file that is not there.
 *       {@code CssLinkResourceTransformer} rewrites the links it can resolve and leaves the rest
 *       alone, so a typo silently produces an unhashed — and now long-cached — URL.</li>
 * </ol>
 */
class StaticAssetsAreVersionedTest {

    private static final Path TEMPLATES = Path.of("src/main/resources/templates");
    private static final Path STATIC = Path.of("src/main/resources/static");

    /** A literal {@code src}/{@code href} pointing into a directory this application serves. */
    private static final Pattern LITERAL_ASSET_PATH = Pattern.compile(
            "(?<!th:)\\b(?:src|href)\\s*=\\s*\"(/(?:css|js|fonts|vendor|webjars)/[^\"]*|/favicon\\.[^\"]*)\"");

    /** A {@code url(...)} in a stylesheet, minus the data URIs, which have nothing to resolve. */
    private static final Pattern CSS_URL = Pattern.compile("url\\(\\s*['\"]?(?!data:)([^)'\"]+)['\"]?\\s*\\)");

    @Test
    void templatesReferenceAssetsThroughThymeleafSoTheyGetHashed() throws IOException {
        List<String> literals = new ArrayList<>();

        for (Path file : filesUnder(TEMPLATES, ".html")) {
            String source = stripComments(Files.readString(file, StandardCharsets.UTF_8));
            Matcher matcher = LITERAL_ASSET_PATH.matcher(source);
            while (matcher.find()) {
                literals.add(file + " → " + matcher.group());
            }
        }

        assertThat(literals)
                .as("A literal asset path skips URL rewriting, so it is served unhashed — and then cached for a year")
                .isEmpty();
    }

    @Test
    void everyStylesheetUrlResolvesToAFileThatExists() throws IOException {
        List<String> unresolvable = new ArrayList<>();

        for (Path file : filesUnder(STATIC, ".css")) {
            String source = Files.readString(file, StandardCharsets.UTF_8)
                    .replaceAll("(?s)/\\*.*?\\*/", " ");
            Matcher matcher = CSS_URL.matcher(source);
            while (matcher.find()) {
                String reference = matcher.group(1).trim().split("[?#]")[0];
                if (reference.isEmpty() || reference.startsWith("http")) {
                    continue;   // remote assets are a different rule — UiAssetsStayLocalTest owns it
                }
                Path target = reference.startsWith("/")
                        ? STATIC.resolve(reference.substring(1))
                        : file.getParent().resolve(reference).normalize();
                if (!Files.exists(target)) {
                    unresolvable.add(file + " → " + reference);
                }
            }
        }

        assertThat(unresolvable)
                .as("CssLinkResourceTransformer can only rewrite a link it can resolve; the rest stay unhashed")
                .isEmpty();
    }

    private static String stripComments(String html) {
        return html.replaceAll("(?s)<!--.*?-->", " ");
    }

    private static List<Path> filesUnder(Path root, String suffix) throws IOException {
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(suffix))
                    .sorted()
                    .toList();
        }
    }
}
