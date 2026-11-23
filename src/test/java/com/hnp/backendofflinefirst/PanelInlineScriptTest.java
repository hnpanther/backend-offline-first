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
 * Stage 2 of roadmap §15. {@code script-src 'self'} refuses an inline handler and an inline
 * script. A new template that puts one back fails here, before a browser is opened.
 *
 * <p>A {@code <script type="application/json">} island is not a script: the browser does not
 * execute it, and CSP does not apply {@code script-src} to it. Thymeleaf may still inline a
 * value into that island.
 */
class PanelInlineScriptTest {

    private static final Pattern HANDLER = Pattern.compile(
            "\\bon(click|change|submit|input|keyup|keydown|load|focus|blur)\\s*=",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SCRIPT = Pattern.compile("<script\\b([^>]*)>", Pattern.CASE_INSENSITIVE);

    @Test
    void templatesHaveNoInlineHandlersOrExecutableScripts() throws IOException {
        Path root = Path.of("src/main/resources/templates");
        List<String> problems = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            files.filter(p -> p.toString().endsWith(".html")).forEach(p -> {
                String text;
                try {
                    text = Files.readString(p, StandardCharsets.UTF_8);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
                // A comment that names the tag is documentation, not a script the browser runs.
                String source = text.replaceAll("(?s)<!--.*?-->", "");
                String relative = root.relativize(p).toString().replace('\\', '/');
                if (HANDLER.matcher(source).find()) {
                    problems.add(relative + " still has an HTML event handler");
                }
                Matcher scripts = SCRIPT.matcher(source);
                while (scripts.find()) {
                    String attrs = scripts.group(1);
                    boolean external = attrs.contains("th:src") || attrs.contains(" src=");
                    boolean json = attrs.toLowerCase().contains("application/json");
                    if (!external && !json) {
                        problems.add(relative + " has an inline <script>");
                    }
                }
            });
        }
        assertThat(problems).isEmpty();
    }
}
