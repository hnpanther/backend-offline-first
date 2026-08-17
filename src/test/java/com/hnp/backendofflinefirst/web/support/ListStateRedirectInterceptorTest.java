package com.hnp.backendofflinefirst.web.support;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.ModelAndView;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Returning an operator to the list they were actually working in.
 *
 * <p>Every admin list keeps its state in the query string. The forms on it POST and the handler
 * finishes with a bare {@code redirect:/asset-entries}, so a filtered, paged list snapped back to
 * page one of everything after each save — which on a registry of a few thousand assets means
 * finding the row again for every single correction.
 *
 * <p>This runs on every web POST in the application, so what it *declines* to do matters more than
 * what it does. Each of the guards below has a case here, and the failure mode throughout is the
 * old behaviour rather than a redirect somewhere unexpected.
 */
class ListStateRedirectInterceptorTest {

    private final ListStateRedirectInterceptor interceptor = new ListStateRedirectInterceptor();

    @Test
    void putsTheOperatorBackOnTheFilteredPageTheySavedFrom() {
        assertThat(redirectAfter("POST", "redirect:/asset-entries",
                "http://panel/asset-entries?q=pump&page=3&size=50"))
                .isEqualTo("redirect:/asset-entries?q=pump&page=3&size=50");
    }

    @Test
    void dropsTheParameterThatWouldReopenTheEditDialog() {
        // Carrying editId through would reopen the dialog on the row just saved, and the page
        // would look as though nothing had been saved at all.
        assertThat(redirectAfter("POST", "redirect:/users",
                "http://panel/users?q=ali&page=2&editId=17"))
                .isEqualTo("redirect:/users?q=ali&page=2");
    }

    @Test
    void leavesTheRedirectAloneWhenEditIdWasTheOnlyState() {
        assertThat(redirectAfter("POST", "redirect:/users", "http://panel/users?editId=17"))
                .isEqualTo("redirect:/users");
    }

    @Test
    void neverOverridesAHandlerThatChoseItsOwnTarget() {
        // Bulk delete already builds its own query string. Appending to it would produce a URL
        // with two copies of the same parameter.
        assertThat(redirectAfter("POST", "redirect:/asset-entries?q=valve&page=1",
                "http://panel/asset-entries?q=pump&page=3"))
                .isEqualTo("redirect:/asset-entries?q=valve&page=1");
    }

    @Test
    void neverSendsTheOperatorSomewhereOtherThanWhereTheHandlerSaid() {
        // The guard that makes this safe to run everywhere: a redirect to a different path is not
        // a return to a list, and the referring page's query has nothing to do with it.
        assertThat(redirectAfter("POST", "redirect:/log-sheets/42",
                "http://panel/log-sheets?q=pump&page=3"))
                .isEqualTo("redirect:/log-sheets/42");
        assertThat(redirectAfter("POST", "redirect:/login",
                "http://panel/users?q=ali"))
                .isEqualTo("redirect:/login");
    }

    @Test
    void ignoresARefererFromAnotherOrigin() {
        assertThat(redirectAfter("POST", "redirect:/asset-entries",
                "http://elsewhere.example/asset-entries?q=pump"))
                .isEqualTo("redirect:/asset-entries");
    }

    @Test
    void doesNothingWithoutAReferer() {
        // A stricter referrer policy or a privacy extension simply restores the old behaviour.
        assertThat(redirectAfter("POST", "redirect:/asset-entries", null))
                .isEqualTo("redirect:/asset-entries");
    }

    @Test
    void doesNothingWhenTheListCarriedNoState() {
        assertThat(redirectAfter("POST", "redirect:/asset-entries", "http://panel/asset-entries"))
                .isEqualTo("redirect:/asset-entries");
    }

    @Test
    void leavesGetRequestsAndOrdinaryViewsUntouched() {
        assertThat(redirectAfter("GET", "redirect:/asset-entries",
                "http://panel/asset-entries?q=pump")).isEqualTo("redirect:/asset-entries");
        assertThat(redirectAfter("POST", "asset-entries",
                "http://panel/asset-entries?q=pump")).isEqualTo("asset-entries");
    }

    @Test
    void leavesAnAbsoluteRedirectAlone() {
        assertThat(redirectAfter("POST", "redirect:https://elsewhere.example/x",
                "http://panel/asset-entries?q=pump"))
                .isEqualTo("redirect:https://elsewhere.example/x");
    }

    @Test
    void keepsAPersianSearchTermExactlyAsTheBrowserEncodedIt() {
        // Re-encoding what arrived percent-encoded would double-encode it, and the filter would
        // come back as literal "%D9%BE..." text that matches nothing.
        String referer = "http://panel/asset-entries?q=%D9%BE%D9%85%D9%BE&page=2";

        assertThat(redirectAfter("POST", "redirect:/asset-entries", referer))
                .isEqualTo("redirect:/asset-entries?q=%D9%BE%D9%85%D9%BE&page=2");
    }

    @Test
    void handlesADeploymentUnderAContextPath() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/panel/asset-entries");
        request.setContextPath("/panel");
        request.setServerName("panel");
        request.addHeader(HttpHeaders.REFERER, "http://panel/panel/asset-entries?q=pump&page=3");
        ModelAndView mv = new ModelAndView("redirect:/asset-entries");

        interceptor.postHandle(request, new MockHttpServletResponse(), new Object(), mv);

        assertThat(mv.getViewName()).isEqualTo("redirect:/asset-entries?q=pump&page=3");
    }

    @Test
    void survivesARefererThatIsNotAUsableUrl() {
        assertThat(redirectAfter("POST", "redirect:/asset-entries", "not a url at all"))
                .isEqualTo("redirect:/asset-entries");
    }

    private String redirectAfter(String method, String viewName, String referer) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, "/whatever");
        request.setServerName("panel");
        if (referer != null) {
            request.addHeader(HttpHeaders.REFERER, referer);
        }
        ModelAndView mv = new ModelAndView(viewName);
        interceptor.postHandle(request, new MockHttpServletResponse(), new Object(), mv);
        return mv.getViewName();
    }
}
