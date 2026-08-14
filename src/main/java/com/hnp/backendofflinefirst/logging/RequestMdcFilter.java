package com.hnp.backendofflinefirst.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Puts the correlation id and HTTP metadata into SLF4J MDC for every request.
 * <p>
 * <b>Deliberately ordered ahead of Spring Security</b> (which registers its chain at
 * {@code -100}) so that the security filters' own log lines — a rejected JWT, a CSRF failure,
 * a locked account — already carry a correlation id. The price is that no authenticated user
 * exists yet at this point, which is why the username is <em>not</em> set here.
 * {@link UserMdcFilter} runs after the security chain and adds it.
 *
 * @see UserMdcFilter
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RequestMdcFilter extends OncePerRequestFilter {

    public static final String MDC_CORRELATION = "correlationId";
    public static final String MDC_USER = "user";
    public static final String MDC_METHOD = "method";
    public static final String MDC_URI = "uri";
    public static final String MDC_CLIENT = "clientIp";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        String correlationId = request.getHeader("X-Correlation-Id");
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = UUID.randomUUID().toString().substring(0, 12);
        }

        MDC.put(MDC_CORRELATION, correlationId);
        MDC.put(MDC_METHOD, request.getMethod());
        MDC.put(MDC_URI, request.getRequestURI());
        MDC.put(MDC_CLIENT, clientIp(request));
        response.setHeader("X-Correlation-Id", correlationId);

        try {
            chain.doFilter(request, response);
        } finally {
            // Clears every key any layer added, including UserMdcFilter's and the aspect's.
            // Threads are pooled, so a leftover key would reappear on an unrelated request and
            // attribute it to the wrong person.
            MDC.clear();
        }
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
