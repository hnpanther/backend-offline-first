package com.hnp.backendofflinefirst.web;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class OperationalPagesPresentationTest {

    @Test
    void pageSizeControlStaysCompactWithoutChangingItsParameters() throws IOException {
        String toolbar = read("src/main/resources/templates/fragments/list-toolbar.html");
        String parameterReport = read("src/main/resources/templates/reports/asset-parameters.html");

        assertThat(toolbar)
                .contains("name=\"size\"")
                .contains("onchange=\"this.form.submit()\"")
                .contains("enterprise-page-size-field");
        assertThat(parameterReport)
                .contains("name=\"size\"")
                .contains("report-page-size-field")
                .contains("asset-parameters-page");
    }

    @Test
    void importAndAssetReportsExposeOnlyPresentationHooks() throws IOException {
        assertThat(read("src/main/resources/templates/batch-import.html"))
                .contains("batch-import-page", "batch-import-start-card", "batch-import-jobs-card")
                .contains("id=\"importSubmitBtn\"", "id=\"jobsTable\"");
        assertThat(read("src/main/resources/templates/reports/asset-history.html"))
                .contains("asset-report-page asset-history-page")
                .contains("id=\"assetHistoryFilter\"", "id=\"assetHistoryTimeline\"");
    }

    private static String read(String path) throws IOException {
        return Files.readString(Path.of(path), StandardCharsets.UTF_8);
    }
}
