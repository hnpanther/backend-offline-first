package com.hnp.backendofflinefirst.security;

import com.hnp.backendofflinefirst.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every endpoint that parses a JSON body before anybody is authenticated must sit under the
 * tight pre-authentication ceiling.
 *
 * <p>{@code JsonBodySizeLimitFilter} gives paths on {@code app.http.anonymous-json-paths} a
 * 64 KB limit and everything else 64 MB — the large one is for an authenticated tablet pushing a
 * long offline backlog. The list is matched exactly and kept by hand, so adding a {@code permitAll}
 * JSON endpoint without adding it there leaves that endpoint parsing 64 MB from anyone on the
 * network. Device enrolment added three such endpoints at once; this is what stops the next one
 * from being forgotten.
 *
 * <p>The filter is registered by hand and MockMvc cannot see it, so the check runs against a
 * filter built from the <b>configured</b> values — the same ones a running server uses.
 */
class AnonymousJsonPathsIntegrationTest extends AbstractPostgresIntegrationTest {

    /** Every POST endpoint that WebSecurityConfig's API chain opens to the unauthenticated. */
    private static final List<String> ANONYMOUS_JSON_ENDPOINTS = List.of(
            "/api/auth/login",
            "/api/auth/challenge",
            "/api/devices/enrollment-requests",
            "/api/devices/enrollment-requests/confirm");

    @Value("${app.http.max-json-body-bytes}") long maxBytes;
    @Value("${app.http.max-anonymous-json-body-bytes}") long maxAnonymousBytes;
    @Value("${app.http.anonymous-json-paths}") String anonymousPaths;

    @Test
    void everyAnonymousJsonEndpointIsOnTheTightList() {
        List<String> configured = Arrays.stream(anonymousPaths.split(",")).map(String::trim).toList();

        assertThat(configured).containsAll(ANONYMOUS_JSON_ENDPOINTS);
    }

    @Test
    void anOversizedAnonymousBodyIsRefusedAtEachOfThem() throws Exception {
        JsonBodySizeLimitFilter filter = new JsonBodySizeLimitFilter(
                maxBytes, maxAnonymousBytes,
                Arrays.stream(anonymousPaths.split(",")).map(String::trim).toList());

        for (String path : ANONYMOUS_JSON_ENDPOINTS) {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
            request.setContentType(MediaType.APPLICATION_JSON_VALUE);
            // Just over the anonymous ceiling, and nowhere near the authenticated one.
            request.setContent(new byte[(int) maxAnonymousBytes + 1]);
            MockHttpServletResponse response = new MockHttpServletResponse();
            AtomicBoolean reachedHandler = new AtomicBoolean();

            filter.doFilter(request, response, (req, res) -> reachedHandler.set(true));

            assertThat(reachedHandler).as("%s must not parse a body over the anonymous ceiling", path).isFalse();
            assertThat(response.getStatus()).as(path).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE.value());
        }
    }
}
