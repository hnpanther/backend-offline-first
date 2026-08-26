package com.hnp.backendofflinefirst.web.advice;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;

/**
 * Carries the current page's filters across a pagination click.
 *
 * <p><b>The bug this exists to remove.</b> {@code fragments/list-toolbar :: pagination} used to
 * build its links from a fixed list of parameter names — {@code q}, {@code status}, {@code asset},
 * {@code classId} — read out of the model. Every page whose filter was named anything else
 * silently lost it on page two: {@code /asset-status-requests} filters by status and asset id,
 * {@code /reports/asset-parameters} by asset, field, from and to. The user saw a filtered first
 * page, clicked «بعدی», and got page two of the <em>unfiltered</em> list — with no indication
 * anything had changed except that the rows no longer matched.
 *
 * <p>The fix is to stop enumerating. This puts the request's <em>own</em> query string into the
 * model with {@code page} and {@code size} removed, and the fragment appends it verbatim. A page
 * that adds a new filter tomorrow keeps it across paging without touching the fragment, which is
 * the property the enumerated version could never have.
 *
 * <p><b>Why {@code page} and {@code size} are dropped.</b> The fragment supplies both itself —
 * {@code page} is the whole point of the link, and {@code size} comes from the model so the
 * per-page selector and the pager agree. Leaving them in would emit each twice, and a duplicated
 * {@code page} is not merely untidy: Spring binds the first occurrence, so the link would always
 * navigate to the page you are already on.
 *
 * <p>Scoped to the {@code web} package: the JSON controllers under {@code controller} render no
 * templates, and a model attribute they never read is pure overhead on every API call.
 *
 * <p><b>{@code basePackages} alone, deliberately.</b> {@code @ControllerAdvice}'s selectors are
 * OR-ed, not AND-ed ({@code HandlerTypePredicate.test} returns on the first match), so adding
 * {@code annotations = Controller.class} to narrow this would have <em>widened</em> it to every
 * {@code @Controller} and {@code @RestController} in the application — the opposite of the intent.
 */
@ControllerAdvice(basePackages = "com.hnp.backendofflinefirst.web")
public class ListFilterAdvice {

    /** Supplied by the pager itself. See the class javadoc. */
    private static final Set<String> PAGER_OWNED = Set.of("page", "size");

    /**
     * The current query string minus the pager's own parameters, each parameter prefixed with
     * {@code &} so it can be appended to a link that already carries one.
     *
     * <p>Empty when there is nothing to carry, which is the common case.
     *
     * <p>Values are URL-encoded here rather than trusted: they come from the query string, and
     * an unencoded {@code &} in a search term would otherwise split into a parameter of its own.
     * Thymeleaf still HTML-escapes the result on output, so both layers apply.
     */
    @ModelAttribute("filterQuery")
    public String filterQuery(HttpServletRequest request) {
        if (request == null) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, String[]> e : request.getParameterMap().entrySet()) {
            String name = e.getKey();
            if (name == null || name.isBlank() || PAGER_OWNED.contains(name)) {
                continue;
            }
            for (String value : e.getValue()) {
                if (value == null || value.isEmpty()) {
                    // An empty filter is the same as no filter everywhere in this panel, and
                    // dragging `status=` along makes every link longer for nothing.
                    continue;
                }
                out.append('&')
                        .append(UriUtils.encodeQueryParam(name, StandardCharsets.UTF_8))
                        .append('=')
                        .append(UriUtils.encodeQueryParam(value, StandardCharsets.UTF_8));
            }
        }
        return out.toString();
    }
}
