package com.hnp.backendofflinefirst.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hnp.backendofflinefirst.dto.integration.IntegrationErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * The 401 for {@code /integration/**}, in JSON and in English.
 *
 * <p>{@link IntegrationApiKeyFilter} already answers every rejection it makes, so in normal
 * operation nothing reaches this class. It exists for the paths the filter cannot cover:
 * a request that somehow arrives at the authorization stage unauthenticated, and a future
 * endpoint on this chain that adds an authorization rule.
 *
 * <p><b>It must never be {@code ApiAuthenticationEntryPoint}.</b> That one falls back to
 * {@code LoginUrlAuthenticationEntryPoint} for anything outside {@code /api/}, so an
 * integration client with a bad key would receive a {@code 302} to an HTML login page — which
 * most HTTP clients follow, leaving the integrator staring at a 200 and a login form and no
 * indication anywhere that their key was refused.
 *
 * <p>It serves as the {@link AccessDeniedHandler} too. On this chain there is no partial
 * authorization to distinguish: a caller is a valid key or it is nothing, so the two answers
 * are the same answer.
 */
@Component
@RequiredArgsConstructor
public class IntegrationAuthenticationEntryPoint implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        write(response);
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        write(response);
    }

    private void write(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), IntegrationErrorResponse.unauthorized());
    }
}
