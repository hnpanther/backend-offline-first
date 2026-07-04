package com.hnp.backendofflinefirst.web.advice;

import com.hnp.backendofflinefirst.ui.ErrorTranslator;
import com.hnp.backendofflinefirst.ui.FaMessages;
import com.hnp.backendofflinefirst.ui.WebRedirectSupport;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Converts service-layer exceptions from web controllers into flash messages + redirect.
 * Expected authorization failures are logged at WARN without a stack trace in the response.
 */
@ControllerAdvice(basePackages = "com.hnp.backendofflinefirst.web")
@Slf4j
public class WebExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public String badRequest(IllegalArgumentException e, HttpServletRequest request, RedirectAttributes ra) {
        log.warn("Bad request on {}: {}", request.getRequestURI(), e.getMessage());
        ra.addFlashAttribute("errorMessage", ErrorTranslator.toFa(e.getMessage()));
        return redirectBack(request);
    }

    @ExceptionHandler(IllegalStateException.class)
    public String conflict(IllegalStateException e, HttpServletRequest request, RedirectAttributes ra) {
        log.warn("Conflict on {}: {}", request.getRequestURI(), e.getMessage());
        ra.addFlashAttribute("errorMessage", ErrorTranslator.toFa(e.getMessage()));
        return redirectBack(request);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public String forbidden(AccessDeniedException e, HttpServletRequest request, RedirectAttributes ra) {
        log.warn("Access denied on {}: {}", request.getRequestURI(), e.getMessage());
        ra.addFlashAttribute("errorMessage", ErrorTranslator.toFa(e.getMessage()));
        return redirectBack(request);
    }

    @ExceptionHandler(Exception.class)
    public String internalError(Exception e, HttpServletRequest request, RedirectAttributes ra) {
        log.error("Unhandled web error on {}", request.getRequestURI(), e);
        ra.addFlashAttribute("errorMessage", FaMessages.genericError());
        return redirectBack(request);
    }

    private static String redirectBack(HttpServletRequest request) {
        return "redirect:" + WebRedirectSupport.backUrl(request);
    }
}
