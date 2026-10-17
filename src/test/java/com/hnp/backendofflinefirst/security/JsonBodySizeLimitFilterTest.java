package com.hnp.backendofflinefirst.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The ceiling on a JSON request body.
 *
 * <p>The case that matters most here is the one that must <b>not</b> fire: an attachment. Every
 * photograph, voice note and video in this system is uploaded as {@code multipart/form-data},
 * and nothing posts a file as base64 inside JSON — so the filter must be blind to multipart
 * whatever its size. A regression there would reach an operator as "the photo will not upload",
 * with the cause two layers away from the symptom.
 */
class JsonBodySizeLimitFilterTest {

    private static final long MAX = 1024;          // generous ceiling, authenticated paths
    private static final long ANON_MAX = 64;       // tight ceiling, pre-authentication paths

    private JsonBodySizeLimitFilter filter() {
        return new JsonBodySizeLimitFilter(MAX, ANON_MAX, List.of("/api/auth/login"));
    }

    // -- what must never be touched ---------------------------------------------------------

    @Test
    void aMultipartAttachmentIsNotExaminedAtAllHoweverLargeItIs() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/attachments");
        request.setContentType(MediaType.MULTIPART_FORM_DATA_VALUE + "; boundary=----x");
        request.setContent(new byte[(int) MAX * 50]);   // 50x over the JSON ceiling
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean passed = new AtomicBoolean();

        filter().doFilter(request, response, chain(passed));

        assertThat(passed).as("a multipart upload must pass straight through").isTrue();
        assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
    }

    @Test
    void anOversizedFormPostIsNotExaminedEither() throws Exception {
        // The admin panel posts forms, not JSON. Their limits are Tomcat's, not this filter's.
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/users");
        request.setContentType(MediaType.APPLICATION_FORM_URLENCODED_VALUE);
        request.setContent(new byte[(int) MAX * 10]);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean passed = new AtomicBoolean();

        filter().doFilter(request, response, chain(passed));

        assertThat(passed).isTrue();
    }

    @Test
    void aRequestWithNoContentTypeIsNotExamined() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/log-sheets");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean passed = new AtomicBoolean();

        filter().doFilter(request, response, chain(passed));

        assertThat(passed).isTrue();
    }

    // -- the two ceilings --------------------------------------------------------------------

    @Test
    void aSyncSizedJsonBodyIsAcceptedOnAnAuthenticatedPath() throws Exception {
        // The whole reason there are two numbers: a batch must not be refused by the limit that
        // protects the login endpoint.
        MockHttpServletRequest request = jsonRequest("/api/log-sheets/batch", (int) MAX - 1);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean passed = new AtomicBoolean();

        filter().doFilter(request, response, chain(passed));

        assertThat(passed).isTrue();
    }

    @Test
    void anOversizedJsonBodyIsRefusedBeforeItIsParsed() throws Exception {
        MockHttpServletRequest request = jsonRequest("/api/log-sheets/batch", (int) MAX + 1);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean passed = new AtomicBoolean();

        filter().doFilter(request, response, chain(passed));

        assertThat(passed).as("the chain must not run — nothing may parse the body").isFalse();
        assertThat(response.getStatus()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE.value());
        assertThat(response.getContentAsString(StandardCharsets.UTF_8))
                .contains(JsonBodySizeLimitFilter.TOO_LARGE_MESSAGE);
    }

    /**
     * The measured defect: 5 MB of JSON, no authentication, parsed by Jackson before the
     * credentials were looked at. At the anonymous ceiling the same shape is refused.
     */
    @Test
    void theLoginEndpointGetsTheTightCeiling() throws Exception {
        MockHttpServletRequest request = jsonRequest("/api/auth/login", (int) ANON_MAX + 1);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean passed = new AtomicBoolean();

        filter().doFilter(request, response, chain(passed));

        assertThat(passed).isFalse();
        assertThat(response.getStatus()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE.value());
    }

    @Test
    void anOrdinaryLoginBodyStillPasses() throws Exception {
        MockHttpServletRequest request = jsonRequest("/api/auth/login", (int) ANON_MAX - 1);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean passed = new AtomicBoolean();

        filter().doFilter(request, response, chain(passed));

        assertThat(passed).as("a real login is three short strings").isTrue();
    }

    @Test
    void aBodyBetweenTheTwoCeilingsIsRefusedOnLoginAndAcceptedElsewhere() throws Exception {
        int size = (int) ANON_MAX + 10;   // over the anonymous ceiling, under the general one

        MockHttpServletResponse login = new MockHttpServletResponse();
        AtomicBoolean loginPassed = new AtomicBoolean();
        filter().doFilter(jsonRequest("/api/auth/login", size), login, chain(loginPassed));

        MockHttpServletResponse batch = new MockHttpServletResponse();
        AtomicBoolean batchPassed = new AtomicBoolean();
        filter().doFilter(jsonRequest("/api/log-sheets/batch", size), batch, chain(batchPassed));

        assertThat(loginPassed).isFalse();
        assertThat(batchPassed).isTrue();
    }

    // -- content-type parsing ----------------------------------------------------------------

    // -- 18a: the spellings that used to buy the loose ceiling on a permitAll endpoint ------

    /**
     * Each of these reaches the login handler and is {@code permitAll}, because Spring Security's
     * matcher and the handler mapping both work on the parsed, decoded path. The filter compared
     * the raw URI with {@code equals}, so each one skipped the 64 KB anonymous ceiling and was
     * measured against the 64 MB authenticated one instead — on the one endpoint an
     * unauthenticated caller can make Jackson parse, with nothing bounding how often.
     */
    @Test
    void aPathParameterDoesNotBuyTheLooseCeilingOnLogin() throws Exception {
        assertTightCeilingApplies("/api/auth/login;x=1");
    }

    @Test
    void aTrailingSlashDoesNotBuyItEither() throws Exception {
        assertTightCeilingApplies("/api/auth/login/");
    }

    @Test
    void norDoesADoubledSlash() throws Exception {
        assertTightCeilingApplies("//api/auth/login");
    }

    @Test
    void norDoesAPercentEncodedLetter() throws Exception {
        assertTightCeilingApplies("/api/auth/%6Cogin");
    }

    @Test
    void aMalformedEscapeTakesTheTightCeilingRatherThanTheLooseOne() throws Exception {
        // Unreadable is not a reason to relax: every ambiguity resolves towards the smaller body.
        assertTightCeilingApplies("/api/auth/%zzogin");
    }

    /**
     * The counterweight, and the reason the reduction is a comparison rather than a blanket.
     *
     * <p>A rule that pulled every request into the tight ceiling would pass all five tests above
     * and wedge the sync: the PWA pushes every pending sheet in one unchunked batch, and 64 KB is
     * nothing like enough for it.
     */
    @Test
    void anAuthenticatedPathKeepsItsLooseCeilingWhateverTheSpelling() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/log-sheets/batch;x=1");
        request.setContentType(MediaType.APPLICATION_JSON_VALUE);
        request.setContent(new byte[(int) ANON_MAX + 1]);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean passed = new AtomicBoolean();

        filter().doFilter(request, response, chain(passed));

        assertThat(passed).isTrue();
        assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
    }

    @Test
    void aConfiguredPathIsReducedTheSameWayAsAnIncomingOne() throws Exception {
        // The property may be written with a trailing slash; it still names the same endpoint.
        JsonBodySizeLimitFilter filter =
                new JsonBodySizeLimitFilter(MAX, ANON_MAX, List.of("/api/auth/login/"));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/login");
        request.setContentType(MediaType.APPLICATION_JSON_VALUE);
        request.setContent(new byte[(int) ANON_MAX + 1]);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE.value());
    }

    private void assertTightCeilingApplies(String uri) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", uri);
        request.setContentType(MediaType.APPLICATION_JSON_VALUE);
        // Between the two ceilings: refused only if this request is treated as anonymous.
        request.setContent(new byte[(int) ANON_MAX + 1]);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean passed = new AtomicBoolean();

        filter().doFilter(request, response, chain(passed));

        assertThat(response.getStatus()).as(uri).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE.value());
        assertThat(passed).as(uri).isFalse();
    }

    // -- 18c: a chunked body is counted through the reader as well as the stream -------------

    /**
     * {@code HttpServletRequestWrapper.getReader()} delegates to the wrapped request, so before
     * this was overridden a handler reading the body that way walked straight past the counter.
     * Nothing does today, which is why the gap was latent rather than exploitable — and why it is
     * worth a test, since the next filter-level limit will be copied from this class.
     */
    @Test
    void aChunkedBodyPastTheCeilingIsRefusedThroughTheReaderToo() {
        MockHttpServletRequest request = chunked("/api/log-sheets/batch", (int) MAX + 100);
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThatThrownBy(() -> filter().doFilter(request, response, readsEverythingWithTheReader()))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("exceeded");
    }

    @Test
    void aChunkedBodyUnderTheCeilingIsReadableThroughTheReader() throws Exception {
        MockHttpServletRequest request = chunked("/api/log-sheets/batch", (int) MAX - 1);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter().doFilter(request, response, readsEverythingWithTheReader());

        assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
    }

    /** A body whose length the container does not declare — what {@code Transfer-Encoding} gives. */
    private static MockHttpServletRequest chunked(String uri, int bytes) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", uri) {
            @Override
            public long getContentLengthLong() {
                return -1;
            }

            @Override
            public int getContentLength() {
                return -1;
            }
        };
        request.setContentType(MediaType.APPLICATION_JSON_VALUE);
        request.setContent(new byte[bytes]);
        return request;
    }

    private static FilterChain readsEverythingWithTheReader() {
        return (req, res) -> {
            try (BufferedReader reader = ((HttpServletRequest) req).getReader()) {
                reader.transferTo(Writer.nullWriter());
            }
        };
    }

    @Test
    void jsonIsRecognisedThroughCharsetsCasingAndTheSuffixForm() {
        assertThat(JsonBodySizeLimitFilter.isJson("application/json")).isTrue();
        assertThat(JsonBodySizeLimitFilter.isJson("application/json;charset=UTF-8")).isTrue();
        assertThat(JsonBodySizeLimitFilter.isJson("APPLICATION/JSON")).isTrue();
        assertThat(JsonBodySizeLimitFilter.isJson(" application/merge-patch+json ")).isTrue();

        assertThat(JsonBodySizeLimitFilter.isJson("multipart/form-data; boundary=x")).isFalse();
        assertThat(JsonBodySizeLimitFilter.isJson("application/x-www-form-urlencoded")).isFalse();
        assertThat(JsonBodySizeLimitFilter.isJson("text/html")).isFalse();
        assertThat(JsonBodySizeLimitFilter.isJson(null)).isFalse();
        // A hostile, malformed content type must not throw before the size is checked.
        assertThat(JsonBodySizeLimitFilter.isJson(";;;")).isFalse();
    }

    // -- helpers -----------------------------------------------------------------------------

    private static MockHttpServletRequest jsonRequest(String path, int bytes) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        request.setContentType(MediaType.APPLICATION_JSON_VALUE);
        request.setContent(new byte[bytes]);
        return request;
    }

    private static FilterChain chain(AtomicBoolean reached) {
        return (req, res) -> {
            reached.set(true);
            new MockFilterChain().doFilter(req, res);
        };
    }
}
