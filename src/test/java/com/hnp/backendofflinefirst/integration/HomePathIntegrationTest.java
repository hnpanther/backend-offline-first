package com.hnp.backendofflinefirst.integration;

import com.hnp.backendofflinefirst.support.AbstractPostgresIntegrationTest;
import com.hnp.backendofflinefirst.support.WithAppUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * "Home" points somewhere the reader may actually open.
 *
 * <p>The navbar brand and the «خانه» step of every breadcrumb were a fixed {@code href="/"}. The
 * dashboard needs {@code GET:/}, which the V1 seed grants {@code ADMIN} and {@code HIGH_USER}
 * only — so for a supervisor, a senior operator or an operator, the logo on every page was a link
 * to an access-denied message.
 *
 * <p>What makes this worth a test rather than a careful edit is that the mistake is invisible to
 * anyone reviewing as an administrator: with {@code GET:/} in hand the link works perfectly, and
 * the sidebar's own dashboard entry has always been gated with
 * {@code sec:authorize="hasAuthority('GET:/')"}, so nothing on screen looks wrong. It only fails
 * for the roles a developer does not log in as.
 */
class HomePathIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired WebApplicationContext context;

    MockMvc mockMvc;

    /** The navbar brand, which every page inherits from {@code fragments/layout.html}. */
    private static final Pattern BRAND = Pattern.compile("<a[^>]*class=\"navbar-brand[^\"]*\"[^>]*>");

    /** The first breadcrumb step — the one that says «خانه». */
    private static final Pattern CRUMB_HOME =
            Pattern.compile("<nav[^>]*enterprise-breadcrumb[^>]*>\\s*<ol>\\s*<li><a[^>]*>", Pattern.DOTALL);

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    @WithAppUser(authorities = {"GET:/log-sheets", "GET:/my-inbox"})
    void aUserWithoutTheDashboardIsSentToTheirInboxInstead() throws Exception {
        String html = render("/log-sheets");

        assertThat(hrefOf(BRAND, html))
                .as("the navbar brand must not point at a page this user cannot open")
                .isEqualTo("/my-inbox");
        assertThat(hrefOf(CRUMB_HOME, html))
                .as("the breadcrumb's home step must agree with the brand")
                .isEqualTo("/my-inbox");
    }

    @Test
    @WithAppUser(authorities = {"GET:/", "GET:/log-sheets", "GET:/my-inbox"})
    void aUserWhoHoldsTheDashboardStillGoesThere() throws Exception {
        String html = render("/log-sheets");

        assertThat(hrefOf(BRAND, html)).isEqualTo("/");
        assertThat(hrefOf(CRUMB_HOME, html)).isEqualTo("/");
    }

    /**
     * The fallback when a role holds neither the dashboard nor the inbox.
     *
     * <p>Not reachable with the seeded roles — every field role holds {@code GET:/my-inbox} — but
     * roles are editable in the panel, so "administrator built a role out of two permissions" is
     * a real state. It must still produce a link to something rather than to the dashboard.
     */
    @Test
    @WithAppUser(authorities = "GET:/log-sheets")
    void aRoleWithNeitherFallsBackToAPageItHolds() throws Exception {
        assertThat(hrefOf(BRAND, render("/log-sheets"))).isEqualTo("/log-sheets");
    }

    private String render(String path) throws Exception {
        return mockMvc.perform(get(path))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    /** The {@code href} of the first element the pattern matches. */
    private static String hrefOf(Pattern pattern, String html) {
        Matcher m = pattern.matcher(html);
        assertThat(m.find()).as("element not found in the rendered page").isTrue();
        Matcher href = Pattern.compile("href=\"([^\"]*)\"").matcher(m.group());
        assertThat(href.find()).as("matched element has no href: %s", m.group()).isTrue();
        return href.group(1);
    }
}
