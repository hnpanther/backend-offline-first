package com.hnp.backendofflinefirst.security;

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
 * Pulls the Bearer token off a stateless {@code /api/**} request and hands it to
 * {@link ApiTokenAuthenticator}, which decides whether it is anybody.
 *
 * <p>This class deliberately holds no part of that decision. It used to hold half of it — verify,
 * then check the {@code jti} is live — and the half it did not hold was the half that mattered:
 * that the session belongs to the user the token names, and that the authorities come from the
 * database rather than from the token's own claims. Keeping the whole rule in one service makes
 * it testable without a servlet, and leaves nowhere for a condition to be quietly dropped.
 */
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER = "Bearer ";

    private final ApiTokenAuthenticator apiTokenAuthenticator;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (SecurityContextHolder.getContext().getAuthentication() == null) {
            String token = bearerToken(request);
            if (token != null) {
                apiTokenAuthenticator.authenticate(token, System.currentTimeMillis())
                        .ifPresent(auth -> SecurityContextHolder.getContext().setAuthentication(auth));
            }
        }
        filterChain.doFilter(request, response);
    }

    private static String bearerToken(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith(BEARER)) {
            return null;
        }
        String token = header.substring(BEARER.length()).trim();
        return token.isEmpty() ? null : token;
    }
}
