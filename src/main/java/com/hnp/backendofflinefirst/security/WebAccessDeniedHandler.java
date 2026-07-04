package com.hnp.backendofflinefirst.security;

import com.hnp.backendofflinefirst.ui.ErrorTranslator;
import com.hnp.backendofflinefirst.ui.FaMessages;
import com.hnp.backendofflinefirst.ui.WebRedirectSupport;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.support.RequestContextUtils;
import org.springframework.web.servlet.FlashMap;

/**
 * Web panel: redirect back with a Persian flash message instead of a whitelabel 403 page.
 * Stack traces stay in logs only.
 */
@Component
@Slf4j
public class WebAccessDeniedHandler implements AccessDeniedHandler {

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws java.io.IOException {
        log.warn("Access denied on {}: {}", request.getRequestURI(), accessDeniedException.getMessage());

        String message = ErrorTranslator.toFa(accessDeniedException.getMessage());
        if (message.equals(accessDeniedException.getMessage())) {
            message = FaMessages.apiAccessDenied();
        }

        FlashMap flashMap = RequestContextUtils.getOutputFlashMap(request);
        if (flashMap != null) {
            flashMap.put("errorMessage", message);
        }

        String target = request.getContextPath() + WebRedirectSupport.backUrl(request);
        response.sendRedirect(target);
    }
}
