package com.hnp.backendofflinefirst.web;

import com.hnp.backendofflinefirst.security.AppAuthenticationProvider;
import com.hnp.backendofflinefirst.ui.ErrorTranslator;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.web.WebAttributes;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class LoginController {

    @GetMapping("/login")
    public String login(HttpServletRequest request, Model model) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            Object lastException = session.getAttribute(WebAttributes.AUTHENTICATION_EXCEPTION);
            // Only two reasons are surfaced specifically — the lockout, and the directory being
            // unreachable, which tells a HYBRID operator to use the local password instead. Other
            // failures (bad password, disabled account) keep the generic message in login.html to
            // avoid revealing account state to a caller who may not even own the username.
            if (lastException instanceof LockedException lockedException) {
                model.addAttribute("loginErrorMessage", ErrorTranslator.toFa(lockedException.getMessage()));
            } else if (isDirectoryUnavailable(lastException)) {
                model.addAttribute("loginErrorMessage",
                        ErrorTranslator.toFa(AppAuthenticationProvider.DIRECTORY_UNAVAILABLE));
            }
            session.removeAttribute(WebAttributes.AUTHENTICATION_EXCEPTION);
        }
        return "login";
    }

    /**
     * Matched on the exact message, not the type: {@code InternalAuthenticationServiceException}
     * is a subclass, and its message is whatever the database or JNDI threw — never something to
     * print on a public page.
     */
    private static boolean isDirectoryUnavailable(Object exception) {
        return exception instanceof AuthenticationServiceException e
                && AppAuthenticationProvider.DIRECTORY_UNAVAILABLE.equals(e.getMessage());
    }
}
