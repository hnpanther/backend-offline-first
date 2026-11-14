package com.hnp.backendofflinefirst.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The server's copy of the Persian search normaliser runs the <b>same table of cases</b> as the
 * panel ({@code src/test/js/choice-search.test.cjs}) and the PWA ({@code persianSearch.test.ts}):
 * three implementations of «does this match» drift the first time one is fixed, and the symptom is
 * an entry the tablet finds and the panel does not.
 */
class PersianSearchNormalizerTest {

    private static JsonNode cases() throws IOException {
        Path file = Path.of("src", "test", "js", "persian-search-cases.json");
        return new ObjectMapper().readTree(Files.readString(file));
    }

    @Test
    void normalisesEveryCaseTheOtherTwoImplementationsRun() throws IOException {
        JsonNode normalize = cases().get("normalize");
        assertThat(normalize.size()).isGreaterThan(5);
        for (JsonNode c : normalize) {
            String in = c.get("in").isNull() ? null : c.get("in").asText();
            assertThat(PersianSearchNormalizer.normalize(in))
                    .as("normalize(%s)", in)
                    .isEqualTo(c.get("out").asText());
        }
    }

    @Test
    void matchesEveryCaseTheOtherTwoImplementationsRun() throws IOException {
        JsonNode match = cases().get("match");
        assertThat(match.size()).isGreaterThan(5);
        for (JsonNode c : match) {
            assertThat(PersianSearchNormalizer.matches(c.get("option").asText(), c.get("query").asText()))
                    .as("matches(%s, %s)", c.get("option").asText(), c.get("query").asText())
                    .isEqualTo(c.get("match").asBoolean());
        }
    }

    /** JavaScript's \s includes the no-break space; Java's does not unless told — this one is told. */
    @Test
    void collapsesTheWhitespaceJavaScriptCallsWhitespace() {
        assertThat(PersianSearchNormalizer.normalize(" پمپ  ۱۲﻿")).isEqualTo("پمپ 12");
    }

    @Test
    void latinDigitsTouchesDigitsAndNothingElse() {
        assertThat(PersianSearchNormalizer.latinDigits("A-۱۲٣ي")).isEqualTo("A-123ي");
        assertThat(PersianSearchNormalizer.latinDigits(null)).isNull();
    }
}
