package com.hnp.backendofflinefirst.web;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class AssetClassesPresentationTest {

    @Test
    void fieldsActionUsesTheTextButtonVariantInsteadOfTheSquareIconButton() throws IOException {
        String template = Files.readString(
                Path.of("src/main/resources/templates/asset-classes.html"),
                StandardCharsets.UTF_8);
        String styles = Files.readString(
                Path.of("src/main/resources/static/css/enterprise.css"),
                StandardCharsets.UTF_8);

        assertThat(template)
                .contains("enterprise-operation-text")
                .contains("enterprise-operation-wide")
                .contains("<span>فیلدها</span>")
                .contains("colspan=\"5\"");
        assertThat(styles)
                .contains(".enterprise-operation-column .enterprise-operation-text")
                .contains("width: auto;")
                .contains("white-space: nowrap;");
    }
}
