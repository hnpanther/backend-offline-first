package com.hnp.backendofflinefirst.web;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The layout contract, checked against every page template at once.
 *
 * <p>A page is composed with
 * {@code th:replace="~{fragments/layout :: layout(~{::title}, ~{::#pageContent})}"}, and that
 * expression keeps <strong>only</strong> the {@code #pageContent} element. Anything after its
 * closing tag is discarded — <em>silently</em>: no exception, no log line, no empty element in
 * the output. The template looks right, the page renders, and the markup is simply not there.
 *
 * <p>It cost two live defects, both reported as "the button does not work":
 *
 * <ul>
 *   <li><b>{@code log-sheet-template-assets-preview.html}</b> — the modal read
 *       «۰ دارایی انتخاب شده» no matter what was ticked, and «انتخاب همه» did nothing, because
 *       the script that counts the boxes never reached the browser.</li>
 *   <li><b>{@code asset-entry-parameters.html}</b> — «افزودن پارامتر تازه» did nothing, for the
 *       same reason. That file even carried a comment warning about this trap, three lines above
 *       a script that was outside the element.</li>
 * </ul>
 *
 * <p>Neither was visible in review: the discarded block sits exactly where a script belongs in
 * an ordinary HTML file, just before {@code </body>}. Nothing but a rule like this one tells
 * the difference, which is why it is a rule and not a convention.
 */
class TemplateLayoutContractTest {

    private static final Path TEMPLATES = Path.of("src/main/resources/templates");

    /** Markup that is lost outright when it sits outside the fragment. */
    private static final List<String> STRUCTURAL = List.of(
            "<script", "<template", "<div", "<form", "<style", "<section", "<table", "<nav");

    @Test
    void nothingSitsOutsidePageContentWhereTheLayoutWouldDiscardIt() throws IOException {
        List<String> offenders = new ArrayList<>();

        try (Stream<Path> files = Files.walk(TEMPLATES)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".html")).toList()) {
                String html = Files.readString(file, StandardCharsets.UTF_8);
                // Only pages composed through the layout fragment; the fragments themselves and
                // standalone documents (error pages, print views) have no such element.
                if (!html.contains("::#pageContent") || !html.contains("id=\"pageContent\"")) {
                    continue;
                }
                int lastClose = html.lastIndexOf("</div>");
                int body = html.indexOf("</body>");
                if (lastClose < 0 || body < 0 || lastClose > body) {
                    continue;
                }
                String tail = stripComments(html.substring(lastClose + "</div>".length(), body));
                for (String tag : STRUCTURAL) {
                    if (tail.contains(tag)) {
                        offenders.add(TEMPLATES.relativize(file) + " → " + tag);
                    }
                }
            }
        }

        assertThat(offenders)
                .as("markup after the last </div> is outside #pageContent and is silently "
                        + "dropped by th:replace — move it inside the element")
                .isEmpty();
    }

    /** The explanatory `<!--/* … *‌/-->` blocks are the one thing that may legitimately sit there. */
    private static String stripComments(String html) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < html.length()) {
            int open = html.indexOf("<!--", i);
            if (open < 0) {
                out.append(html, i, html.length());
                break;
            }
            out.append(html, i, open);
            int close = html.indexOf("-->", open);
            if (close < 0) {
                break;
            }
            i = close + 3;
        }
        return out.toString();
    }
}
