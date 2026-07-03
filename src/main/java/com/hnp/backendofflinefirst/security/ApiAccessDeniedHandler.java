package com.hnp.backendofflinefirst.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hnp.backendofflinefirst.dto.ApiErrorResponse;
import com.hnp.backendofflinefirst.ui.ErrorTranslator;
import com.hnp.backendofflinefirst.ui.FaMessages;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/** Returns JSON 403 for {@code /api/**}; web clients get a plain 403. */
@Component
@RequiredArgsConstructor
public class ApiAccessDeniedHandler implements AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws java.io.IOException {
        if (isApiRequest(request)) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            String message = ErrorTranslator.toFa(accessDeniedException.getMessage());
            if (message.equals(accessDeniedException.getMessage())) {
                message = FaMessages.apiAccessDenied();
            }
            objectMapper.writeValue(response.getOutputStream(), new ApiErrorResponse(message));
            return;
        }
        response.sendError(HttpServletResponse.SC_FORBIDDEN);
    }

    private static boolean isApiRequest(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return uri != null && uri.startsWith("/api/");
    }
}
