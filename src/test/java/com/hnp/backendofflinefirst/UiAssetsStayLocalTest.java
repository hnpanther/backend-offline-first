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
 * The administration panel has to render on an isolated plant network.
 *
 * <p>Dependencies in {@code pom.xml} are packaged into the application and webjars are served by
 * Spring. This test guards the other, easy-to-miss path: a template or stylesheet pointing at a
 * CDN, remote font, remote image or remotely imported script.
 */
class UiAssetsStayLocalTest {

    private static final Path TEMPLATES = Path.of("src/main/resources/templates");
    private static final Path STATIC = Path.of("src/main/resources/static");

    private static final Pattern REMOTE_ASSET = Pattern.compile(
            "(?i)(?:\\b(?:src|href)\\s*=\\s*[\\\"']\\s*https?://"
                    + "|url\\(\\s*[\\\"']?\\s*https?://"
                    + "|@import\\s+(?:url\\()?\\s*[\\\"']?\\s*https?://"
                    + "|\\bimport\\s*\\(\\s*[\\\"']\\s*https?://)");

    @Test
    void templatesStylesAndScriptsDoNotLoadRemoteAssets() throws IOException {
        List<String> remoteReferences = new ArrayList<>();

        for (Path file : uiSources()) {
            String source = Files.readString(file, StandardCharsets.UTF_8)
                    .replaceAll("(?s)/\\*.*?\\*/", " ")
                    .replaceAll("(?s)<!--.*?-->", " ");
            Matcher matcher = REMOTE_ASSET.matcher(source);
            while (matcher.find()) {
                remoteReferences.add(file + " → " + matcher.group());
            }
        }

        assertThat(remoteReferences)
                .as("UI assets must be served by this application; isolated installations have no internet")
                .isEmpty();
    }

    private static List<Path> uiSources() throws IOException {
        try (Stream<Path> templates = Files.walk(TEMPLATES); Stream<Path> staticFiles = Files.walk(STATIC)) {
            return Stream.concat(templates, staticFiles)
                    .filter(Files::isRegularFile)
                    .filter(path -> {
                        String name = path.getFileName().toString();
                        return name.endsWith(".html") || name.endsWith(".css") || name.endsWith(".js");
                    })
                    .toList();
        }
    }
}
