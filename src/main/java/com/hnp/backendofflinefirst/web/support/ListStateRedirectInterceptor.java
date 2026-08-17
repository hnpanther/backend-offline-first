package com.hnp.backendofflinefirst.web.support;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Keeps a list page's filter, page number and page size across an edit.
 *
 * <p>Every admin list page carries its state in the query string — {@code ?q=pump&page=3&size=50}.
 * The forms on it POST to the same controller, which finishes with a bare
 * {@code redirect:/asset-entries}. The filter and the page number are lost, so anybody working
 * through a filtered list is thrown back to page one of everything after each save. On a list of
 * a few thousand assets that turns a five-minute correction into a search each time.
 *
 * <p>Fixing it in the handlers would mean threading three parameters through roughly seventy
 * redirect statements across seventeen controllers, and every one of them a chance to get it
 * subtly wrong. This does it once, and it can only ever put the operator back on the page they
 * submitted from:
 *
 * <ul>
 *   <li>Only on <b>POST</b>, and only when the handler returned a {@code redirect:} view.</li>
 *   <li>Only when the handler produced <b>no query string of its own</b> — a handler that already
 *       builds its own target (bulk delete does) is left completely alone.</li>
 *   <li>Only when the {@code Referer} is <b>same-origin</b> and its path is <b>exactly</b> the
 *       redirect path. A redirect that sends the user somewhere else — to a detail page, back to
 *       the login screen — is not a return to a list and is not touched.</li>
 * </ul>
 *
 * <p>If the browser sends no {@code Referer} — a stricter referrer policy, a privacy extension —
 * nothing happens and the behaviour is exactly what it was before. That is the whole reason this
 * is safe: its failure mode is the old behaviour, not a broken redirect.
 */
@Component
public class ListStateRedirectInterceptor implements HandlerInterceptor {

    private static final String REDIRECT_PREFIX = "redirect:";

    /**
     * Parameters that describe *this* visit rather than the list, and must not come back.
     *
     * <p>{@code editId} is the one that matters: it is what opens the edit dialog, so carrying it
     * through would reopen the dialog on the row that was just saved — the page would look as if
     * the save had not happened.
     */
    private static final Set<String> TRANSIENT_PARAMS = Set.of("editId");

    @Override
    public void postHandle(HttpServletRequest request, HttpServletResponse response,
                           Object handler, ModelAndView modelAndView) {
        if (modelAndView == null || !"POST".equalsIgnoreCase(request.getMethod())) {
            return;
        }
        String viewName = modelAndView.getViewName();
        if (viewName == null || !viewName.startsWith(REDIRECT_PREFIX)) {
            return;
        }
        String target = viewName.substring(REDIRECT_PREFIX.length());
        // A handler that built its own query string has already decided where to land.
        if (target.isEmpty() || target.indexOf('?') >= 0 || !target.startsWith("/")) {
            return;
        }
        String listState = sameListQuery(request, target);
        if (listState == null) {
            return;
        }
        modelAndView.setViewName(REDIRECT_PREFIX + target + "?" + listState);
    }

    /**
     * The referring page's query string, but only when that page is the very list being redirected
     * to, and only the parts worth restoring.
     *
     * @return the query to append, or {@code null} to leave the redirect untouched
     */
    private String sameListQuery(HttpServletRequest request, String target) {
        String referer = request.getHeader(HttpHeaders.REFERER);
        if (referer == null || referer.isBlank()) {
            return null;
        }
        URI uri;
        try {
            uri = new URI(referer);
        } catch (URISyntaxException e) {
            return null;
        }
        // Same origin only: a referer from anywhere else must never steer where this lands.
        if (uri.getHost() != null && !uri.getHost().equalsIgnoreCase(request.getServerName())) {
            return null;
        }
        String path = uri.getPath();
        String contextPath = request.getContextPath();
        if (path != null && !contextPath.isEmpty() && path.startsWith(contextPath)) {
            path = path.substring(contextPath.length());
        }
        if (path == null || !path.equals(target)) {
            return null;
        }
        String query = uri.getRawQuery();
        if (query == null || query.isBlank()) {
            return null;
        }
        // Kept raw: the values were URL-encoded by the browser when it built this URL, and
        // re-encoding a Persian search term that arrived percent-encoded would double-encode it.
        String preserved = Arrays.stream(query.split("&"))
                .filter(param -> !param.isBlank())
                .filter(param -> !TRANSIENT_PARAMS.contains(paramName(param)))
                .collect(Collectors.joining("&"));
        return preserved.isBlank() ? null : preserved;
    }

    private String paramName(String param) {
        int eq = param.indexOf('=');
        return eq < 0 ? param : param.substring(0, eq);
    }
}
