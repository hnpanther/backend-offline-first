package com.hnp.backendofflinefirst.ui;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class WebRedirectSupportTest {

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
}
