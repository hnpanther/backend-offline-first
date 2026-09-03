package com.hnp.backendofflinefirst.integration;

import com.hnp.backendofflinefirst.support.AbstractPostgresIntegrationTest;
import com.hnp.backendofflinefirst.support.WithAppUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Every page of the panel opens, and opens finished.
 *
 * <h2>Why a sweep rather than a test per page</h2>
 *
 * <p>Most of these pages have a test somewhere that covers what they are *for*. What nothing
 * covered is the thing that breaks when a shared piece changes: the layout fragment, the sidebar,
 * the toolbar fragment, a stylesheet class every page relies on. A change there can leave one
 * page throwing at render time and every targeted test still green, because none of them opens
 * that page.
 *
 * <p>The two failures this is aimed at are silent ones. A Thymeleaf error during rendering is
 * <b>not</b> a broken build — the servlet answers 500 and only a request notices. And
 * {@code th:replace} discarding everything outside {@code #pageContent} (AGENTS.md #4) produces a
 * page that returns 200 with most of its content missing.
 *
 * <h2>What each page is checked for</h2>
 *
 * <ul>
 *   <li><b>200</b> — it rendered at all.</li>
 *   <li><b>{@code #pageContent} present</b> — the layout actually composed the page, rather than
 *       returning a shell.</li>
 *   <li><b>No {@code th:} attribute survives</b> — an unprocessed attribute in the output means
 *       a fragment was emitted as literal markup instead of being evaluated.</li>
 *   <li><b>The navigation shell is there</b> — sidebar and main region, the parts every page
 *       inherits and therefore nobody tests.</li>
 * </ul>
 *
 * <p>Rendered as an unrestricted admin, so a page is never "fine" merely because scope emptied
 * it. Pages are seeded with whatever the suite has left behind rather than fixtures of their own:
 * this asks whether a page renders, not what it renders — the per-page tests own that.
 */
class EveryPanelPageRendersIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired WebApplicationContext context;

    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "/",
            "/api-sessions",
            "/asset-classes",
            "/asset-entries",
            "/asset-status-requests",
            "/audit-logs",
            "/batch-import",
            "/integration-keys",
            "/locations",
            "/log-sheets",
            "/log-sheet-templates",
            "/login-attempts",
            "/main-functions",
            "/my-inbox",
            "/nfc-fault-reports",
            "/operational-units",
            "/plant-systems",
            "/reports",
            "/reports/actions",
            "/reports/asset-history",
            "/reports/asset-parameters",
            "/reports/compliance",
            "/reports/data-quality",
            "/reports/exceptions",
            "/reports/overview",
            "/reports/workforce",
            "/roles",
            "/settings",
            "/sub-functions",
            "/users",
            "/web-sessions",
    })
    @WithAppUser(roles = "ADMIN", authorities = {
            "GET:/", "GET:/api-sessions", "GET:/asset-classes", "GET:/asset-entries",
            "GET:/asset-status-requests", "GET:/audit-logs", "GET:/batch-import",
            "GET:/integration-keys", "GET:/locations", "GET:/log-sheets",
            "GET:/log-sheet-templates", "GET:/login-attempts", "GET:/main-functions",
            "GET:/my-inbox", "GET:/nfc-fault-reports", "GET:/operational-units",
            "GET:/plant-systems", "GET:/reports", "GET:/roles", "GET:/settings",
            "GET:/sub-functions", "GET:/users", "GET:/web-sessions",
    })
    void thePageOpensAndIsFullyRendered(String path) throws Exception {
        String html = mockMvc.perform(get(path))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html)
                .as("%s: the layout did not compose the page's own content", path)
                .contains("id=\"pageContent\"");

        assertThat(markupOnly(html))
                .as("%s: a th: attribute reached the browser, so a fragment was not evaluated", path)
                .doesNotContain("th:text").doesNotContain("th:each")
                .doesNotContain("th:if").doesNotContain("th:replace")
                .doesNotContain("th:href").doesNotContain("th:action");

        // The sidebar is unconditional; the toast stack deliberately is not — it is only emitted
        // when a controller set a flash message, so an empty container never ships.
        assertThat(html)
                .as("%s: the shared navigation shell is missing", path)
                .contains("app-sidebar")
                .contains("app-main");
    }

    /**
     * The response with HTML comments and script bodies removed.
     *
     * <p>Both legitimately survive into the output and both legitimately <em>talk about</em>
     * Thymeleaf: {@code batch-import.html} explains in a comment why a control cannot be toggled
     * because "{@code th:if} never emitted" it, and {@code roles.html} says in a script comment
     * that the duplicate form's {@code th:action} is a placeholder. Searching the raw response
     * flags those as unprocessed attributes, which is a test that fails on its own documentation.
     */
    private static String markupOnly(String html) {
        return html.replaceAll("(?s)<!--.*?-->", " ")
                   .replaceAll("(?si)<script\\b[^>]*>.*?</script\\s*>", " ");
    }
}
