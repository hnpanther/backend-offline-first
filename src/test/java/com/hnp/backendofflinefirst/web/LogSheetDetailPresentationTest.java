package com.hnp.backendofflinefirst.web;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class LogSheetDetailPresentationTest {

    @Test
    void actionHistoryHeaderDoesNotRenderARecordCount() throws IOException {
        String template = Files.readString(
                Path.of("src/main/resources/templates/log-sheet-detail.html"),
                StandardCharsets.UTF_8);

        int historySection = template.indexOf("<section id=\"history\"");
        int historyCard = template.indexOf("<div class=\"card log-detail-history-card\"", historySection);

        assertThat(historySection).isGreaterThanOrEqualTo(0);
        assertThat(historyCard).isGreaterThan(historySection);
        assertThat(template.substring(historySection, historyCard))
                .contains("تاریخچه اکشن‌ها")
                .doesNotContain("#lists.size(history)", "log-detail-count");
    }
}
