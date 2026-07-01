package com.hnp.backendofflinefirst.logging;

import com.hnp.backendofflinefirst.security.AppUserDetails;
import com.hnp.backendofflinefirst.security.SecurityUtils;
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
 * Puts correlation id and HTTP metadata into SLF4J MDC for every request.
 * User name is refreshed after the security chain runs.
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
            refreshUserMdc();
        } finally {
            MDC.clear();
        }
    }

    private void refreshUserMdc() {
        AppUserDetails user = SecurityUtils.currentUser();
        if (user != null) {
            MDC.put(MDC_USER, user.getUsername());
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
