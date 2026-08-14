package com.hnp.backendofflinefirst.logging;

import com.hnp.backendofflinefirst.security.AppUserDetails;
import com.hnp.backendofflinefirst.security.SecurityUtils;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Puts the authenticated username into MDC, for the whole request.
 * <p>
 * <b>Why this is a second filter rather than two lines in {@link RequestMdcFilter}.</b> That
 * filter runs ahead of Spring Security so security's own log lines carry a correlation id —
 * but at that point {@code SecurityContextHolder} is still empty, so there is no username to
 * record.
 * <p>
 * <b>Why it is registered inside the Spring Security chain</b> (see {@code WebSecurityConfig},
 * {@code addFilterAfter(..., SecurityContextHolderFilter.class)} and after the JWT filter on
 * the API chain) rather than as an ordinary servlet filter. A plain servlet filter ordered
 * after security's chain only wraps what comes <em>downstream</em> of it. The denials worth
 * logging happen <em>inside</em> the chain and never reach downstream at all: a CSRF rejection
 * is thrown by {@code CsrfFilter} and handled by {@code ExceptionTranslationFilter}, so
 * {@code WebAccessDeniedHandler} logged "Access denied" with the user reading
 * {@code anonymous} even though somebody was very much logged in. Sitting immediately after
 * the context is restored puts this filter around those events.
 * <p>
 * It is deliberately <b>not</b> a {@code @Component}: Boot would then also auto-register it as
 * a servlet filter, and since {@code OncePerRequestFilter} keys its guard on the class name,
 * the outer copy would mark the request as filtered and the copy inside the chain would skip.
 * <p>
 * The previous arrangement resolved the user <em>after</em> {@code chain.doFilter} returned
 * and then cleared the MDC in the same {@code finally} — so the value was written after every
 * log line for the request had already been emitted, and erased before anything could read
 * it. It was dead code. The username still appeared in most lines only because
 * {@code LoggingAspect} sets it again per advised method, which left it missing on exactly
 * the lines that matter most in production:
 * <ul>
 *   <li>{@code web/advice/**} — the global exception handlers, explicitly excluded from the
 *       aspect's pointcut, i.e. where errors are reported</li>
 *   <li>{@code WebAccessDeniedHandler} — "access denied", with no record of to whom</li>
 *   <li>{@code JwtAuthenticationFilter} — rejected mobile tokens</li>
 *   <li>anything logged by Spring, Hibernate or Tomcat</li>
 * </ul>
 * The aspect's own {@code enrichUserMdc} stays: async workers (import, audit, scheduler) run
 * with no request and therefore never pass through this filter.
 */
public class UserMdcFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        AppUserDetails user = SecurityUtils.currentUser();
        if (user != null) {
            MDC.put(RequestMdcFilter.MDC_USER, user.getUsername());
        }
        try {
            chain.doFilter(request, response);
        } finally {
            // Remove only this filter's key. RequestMdcFilter wraps this one and clears the
            // rest; removing everything here would strip the correlation id from any line the
            // outer filter still logs on the way out.
            MDC.remove(RequestMdcFilter.MDC_USER);
        }
    }
}
