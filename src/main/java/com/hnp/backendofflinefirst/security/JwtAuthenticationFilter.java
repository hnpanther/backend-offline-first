package com.hnp.backendofflinefirst.security;

import com.hnp.backendofflinefirst.service.ApiSessionService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Validates Bearer JWT on stateless {@code /api/**} requests.
 * <p>
 * A valid signature is not enough: the token's {@code jti} must also match a live row in
 * {@code api_sessions}, which is what makes admin revocation and the one-device-per-user
 * rule take effect on the very next request.
 */
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final ApiSessionService apiSessionService;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (SecurityContextHolder.getContext().getAuthentication() == null) {
            String header = request.getHeader(HttpHeaders.AUTHORIZATION);
            if (header != null && header.startsWith("Bearer ")) {
                String token = header.substring(7).trim();
                if (!token.isEmpty()) {
                    jwtService.verify(token)
                            .filter(verified -> apiSessionService.isSessionActive(
                                    verified.jti(), System.currentTimeMillis()))
                            .ifPresent(verified -> SecurityContextHolder.getContext()
                                    .setAuthentication(verified.authentication()));
                }
            }
        }
        filterChain.doFilter(request, response);
    }
}
