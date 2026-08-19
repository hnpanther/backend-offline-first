package com.hnp.backendofflinefirst.integration;

import com.hnp.backendofflinefirst.security.IntegrationApiKeyFilter;
import com.hnp.backendofflinefirst.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The integration filter runs on {@code /integration/**} and <b>nowhere else</b>.
 *
 * <h2>Why this test exists, and why it does not use MockMvc</h2>
 *
 * <p>{@link IntegrationApiKeyFilter} is a {@code @Component} extending {@code Filter}, and Boot
 * auto-registers every {@code Filter} bean against {@code /*} in addition to wherever the
 * security configuration places it. Unlike {@code JwtAuthenticationFilter} — which only ever
 * tries to authenticate and then calls {@code doFilter} — this one <em>writes a 401 and
 * returns</em> when there is no {@code X-API-Key} header. Auto-registered, it therefore answered
 * that 401 to every URL in the application: the login page, {@code /api/health}, the CSS.
 * The whole panel was unreachable.
 *
 * <p><b>A MockMvc test cannot see this.</b> {@code webAppContextSetup(...).apply(springSecurity())}
 * builds the Spring Security filter chain and nothing else, so Boot's auto-registered copy is
 * simply not present. Every one of the 1,356 tests passed against the broken build; one live
 * {@code curl http://localhost:8081/login} found it immediately.
 *
 * <p>So this test starts a real servlet container ({@code WebEnvironment.RANDOM_PORT}, from the
 * base class) and goes over HTTP, which is the only arrangement in which the fault is visible.
 * If somebody removes the {@code FilterRegistrationBean} that disables the auto-registration,
 * {@link #theLoginPageIsNotAnsweredByTheIntegrationFilter()} fails.
 */
class IntegrationFilterScopeIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired FilterRegistrationBean<IntegrationApiKeyFilter> integrationFilterRegistration;

    /**
     * The JDK client rather than {@code TestRestTemplate} — which is not on this project's test
     * classpath — and deliberately without redirect following, so a 302 to the login page is
     * observed rather than silently resolved into the 200 it leads to.
     */
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    @Value("${local.server.port}")
    int port;

    private HttpResponse<String> httpGet(String path) throws IOException, InterruptedException {
        return CLIENT.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString(java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test
    void theAutoRegistrationOfTheIntegrationFilterIsDisabled() {
        assertThat(integrationFilterRegistration.isEnabled())
                .as("enabled = true makes this filter answer 401 for every URL in the application")
                .isFalse();
    }

    @Test
    void theLoginPageIsNotAnsweredByTheIntegrationFilter() throws Exception {
        HttpResponse<String> response = httpGet("/login");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body())
                .as("the login form, not an integration API error")
                .doesNotContain("X-API-Key")
                .contains("<form");
    }

    @Test
    void thePublicApiHealthProbeIsNotAnsweredByTheIntegrationFilter() throws Exception {
        HttpResponse<String> response = httpGet("/api/health");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("ok");
    }

    @Test
    void theMobileApiStillAnswersWithItsOwnPersian401AndNotTheIntegrationOne() throws Exception {
        // Two independent chains: /api/** must keep its own unauthenticated answer, in Persian,
        // rather than being taken over by the integration filter's English one.
        HttpResponse<String> response = httpGet("/api/bootstrap");

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.body())
                .doesNotContain("X-API-Key")
                .contains("لطفاً وارد شوید.");
    }

    @Test
    void theIntegrationApiStillRequiresItsKeyOverRealHttp() throws Exception {
        HttpResponse<String> response =
                httpGet("/integration/v1/log-sheets?from=2020-01-01&to=2100-01-01");

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.body()).contains("X-API-Key").contains("unauthorized");
    }

    @Test
    void staticAssetsAreStillServed() throws Exception {
        // The most visible symptom of the fault: a login page with no styling, because every
        // CSS and font request was answered with an integration 401.
        HttpResponse<String> response = httpGet("/css/app.css");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).doesNotContain("X-API-Key");
    }
}
