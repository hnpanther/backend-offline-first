package com.hnp.backendofflinefirst.integration;

import com.hnp.backendofflinefirst.support.AbstractPostgresIntegrationTest;
import com.hnp.backendofflinefirst.support.WithAppUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The classes that size a list page must be in the HTML the server sends.
 *
 * <p>{@link com.hnp.backendofflinefirst.FirstPaintNeedsNoScriptTest} reads the template sources.
 * This renders the pages, which is the part source-scanning cannot see: a page is composed of a
 * content template and {@code fragments/layout.html}, and {@code #pageContent} only exists in the
 * output because {@code th:replace="~{fragments/layout :: layout(~{::title}, ~{::#pageContent})}"}
 * puts it there. A template could carry every class and still emit none of them if that wiring
 * broke — and gotcha #4 in AGENTS.md is exactly a case of {@code th:replace} silently discarding
 * markup outside {@code #pageContent}.
 *
 * <p>One page is checked per shape the change had to handle, because they fail differently:
 * a table wrapped in a viewport that the template creates, one wrapped in a
 * {@code .table-responsive} that was already there, and a page holding two tables.
 */
class FirstPaintRenderedMarkupIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired WebApplicationContext context;

    MockMvc mockMvc;

    /** {@code <div id="pageContent" ...>} as it reaches the browser. */
    private static final Pattern PAGE_CONTENT = Pattern.compile("<div id=\"pageContent\"[^>]*>");

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    @WithAppUser(authorities = "GET:/log-sheets")
    void theLogSheetListArrivesAlreadySizedRatherThanWaitingForTheScript() throws Exception {
        // A table the template had to wrap itself: it sat bare inside .card-body.p-0.
        assertFirstPaintIsFinal("/log-sheets", 1);
    }

    @Test
    @WithAppUser(authorities = "GET:/locations")
    void aTableThatAlreadyHadAResponsiveWrapperReusesItAsTheViewport() throws Exception {
        // .table-responsive-modern was already in the markup; the viewport class joins it rather
        // than a second wrapper being introduced, which is what enhanceTables does at runtime.
        String html = assertFirstPaintIsFinal("/locations", 1);
        assertThat(html).contains("table-responsive-modern enterprise-table-viewport");
    }

    @Test
    @WithAppUser(authorities = "GET:/my-inbox")
    void bothTablesOnAPageWithTwoOfThemAreSized() throws Exception {
        assertFirstPaintIsFinal("/my-inbox", 2);
    }

    /**
     * Renders the page and asserts the three geometry decisions are present, then returns the
     * HTML so a caller can look at anything specific to that page.
     */
    private String assertFirstPaintIsFinal(String path, int expectedTables) throws Exception {
        String html = mockMvc.perform(get(path))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        Matcher pageContent = PAGE_CONTENT.matcher(html);
        assertThat(pageContent.find()).as("%s renders a #pageContent", path).isTrue();
        assertThat(pageContent.group())
                .as("%s: #pageContent must already be a list page", path)
                .contains("enterprise-list-page");

        assertThat(countOf("<table class=\"table", html))
                .as("%s renders the tables this assertion was written for", path)
                .isEqualTo(expectedTables);
        assertThat(countOf("enterprise-data-table", html))
                .as("%s: every table must arrive at the enterprise size, not Bootstrap's", path)
                .isEqualTo(expectedTables);
        assertThat(countOf("enterprise-table-viewport", html))
                .as("%s: every table must arrive inside its scroll viewport", path)
                .isEqualTo(expectedTables);

        return html;
    }

    private static int countOf(String needle, String haystack) {
        int total = 0;
        for (int at = haystack.indexOf(needle); at >= 0; at = haystack.indexOf(needle, at + 1)) {
            total++;
        }
        return total;
    }
}
