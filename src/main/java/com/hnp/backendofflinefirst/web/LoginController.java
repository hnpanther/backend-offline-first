package com.hnp.backendofflinefirst.web;

import com.hnp.backendofflinefirst.ui.ErrorTranslator;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
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
            // Only the lockout reason is surfaced specifically — other failures (bad password,
            // disabled account) keep the generic message in login.html to avoid revealing
            // account state to a caller who may not even own the username.
            if (lastException instanceof LockedException lockedException) {
                model.addAttribute("loginErrorMessage", ErrorTranslator.toFa(lockedException.getMessage()));
            }
            session.removeAttribute(WebAttributes.AUTHENTICATION_EXCEPTION);
        }
        return "login";
    }
}
