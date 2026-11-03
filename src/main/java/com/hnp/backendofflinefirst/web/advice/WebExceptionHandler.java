package com.hnp.backendofflinefirst.web.advice;

import com.hnp.backendofflinefirst.ui.ErrorTranslator;
import com.hnp.backendofflinefirst.ui.FaMessages;
import com.hnp.backendofflinefirst.ui.WebRedirectSupport;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
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
        ra.addFlashAttribute("errorMessage", forbiddenMessage(request, e));
        return redirectBack(request);
    }

    /**
     * The refusal to show, which for one path is not the generic one.
     *
     * <p>{@code @PreAuthorize} denials all arrive carrying "Access Denied", so every missing
     * authority produces the same sentence — correct, and useless where the reader could do the
     * thing last week. Since V8 withdrawing an approval is the system administrator's alone and
     * its button is gone from the page, which leaves an old bookmark or a tab left open as the
     * only ways to reach the endpoint. Both readers deserve to be told what changed rather than
     * to conclude the panel is broken.
     *
     * <p><b>Only when the denial is the generic one</b>, and that condition is the whole
     * subtlety. The same path also refuses through
     * {@code LogSheetAssignmentService.requireSupervisorOrAdmin}, which says «شما سرپرست این
     * واحد نیستید» — the right answer for a role that was granted the permission but supervises
     * nothing, and the case this restriction is expected to produce once an administrator grants
     * it onward. Overriding by path alone would replace that precise message with a wrong one.
     *
     * <p>Kept deliberately to one path. A map of URI patterns to messages here would become the
     * place every endpoint's refusal wording lived, away from the code that refuses.
     */
    private static String forbiddenMessage(HttpServletRequest request, AccessDeniedException e) {
        String translated = ErrorTranslator.toFa(e.getMessage());
        String uri = request.getRequestURI();
        if (uri != null && uri.endsWith("/unapprove")
                && FaMessages.apiAccessDenied().equals(translated)) {
            return FaMessages.logSheetUnapproveRestrictedToAdmin();
        }
        return translated;
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public String dataIntegrity(DataIntegrityViolationException e, HttpServletRequest request, RedirectAttributes ra) {
        log.warn("Data integrity violation on {}: {}", request.getRequestURI(), e.getMessage());
        ra.addFlashAttribute("errorMessage", ErrorTranslator.dataIntegrityViolation(e));
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
