package com.hnp.backendofflinefirst.ui;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class WebRedirectSupportTest {

    private static final String FALLBACK = "/nfc-fault-reports";

    @Test
    void stripsActionSuffixFromLogSheetUri() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/log-sheets/42/takeover");
        request.setServerName("localhost");
        request.setServerPort(8081);

        assertThat(WebRedirectSupport.backUrl(request)).isEqualTo("/log-sheets/42");
    }

    @Test
    void prefersSameOriginReferer() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/log-sheets/42/takeover");
        request.setServerName("localhost");
        request.setServerPort(8081);
        request.addHeader("Referer", "http://localhost:8081/log-sheets/42?tab=1");

        assertThat(WebRedirectSupport.backUrl(request)).isEqualTo("/log-sheets/42?tab=1");
    }

    // --- Issue B-11: returnTo may only point inside this application ------------------------

    @Test
    void anInAppReturnToIsKept() {
        assertThat(WebRedirectSupport.safeReturn("/log-sheets/42?tab=history", FALLBACK))
                .isEqualTo("/log-sheets/42?tab=history");
    }

    @Test
    void aReturnToThatCouldLeaveTheApplicationFallsBack() {
        String[] hostile = {
                "https://evil.example/",
                "//evil.example/path",
                "/\\evil.example",
                "\\\\evil.example",
                "/redirect?to=https://evil.example",
                "javascript:alert(1)",
                "log-sheets/42",
                "",
                null};
        for (String returnTo : hostile) {
            assertThat(WebRedirectSupport.safeReturn(returnTo, FALLBACK))
                    .as(String.valueOf(returnTo))
                    .isEqualTo(FALLBACK);
        }
    }

    @Test
    void redirectToBuildsTheViewName() {
        assertThat(WebRedirectSupport.redirectTo("https://evil.example", FALLBACK))
                .isEqualTo("redirect:" + FALLBACK);
        assertThat(WebRedirectSupport.redirectTo("/alarms/7", FALLBACK)).isEqualTo("redirect:/alarms/7");
    }
}
