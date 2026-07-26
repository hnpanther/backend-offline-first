package com.hnp.backendofflinefirst.web;

import com.hnp.backendofflinefirst.security.SecurityUtils;
import com.hnp.backendofflinefirst.service.WebSessionService;
import com.hnp.backendofflinefirst.ui.ErrorTranslator;
import com.hnp.backendofflinefirst.ui.FaMessages;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** Admin view over live web-panel (form-login) sessions: who is logged in, and expiring them. */
@Controller
@RequestMapping("/web-sessions")
@RequiredArgsConstructor
public class WebSessionWebController {

    private final WebSessionService webSessionService;

    @GetMapping
    @PreAuthorize("hasAuthority('GET:/web-sessions')")
    public String list(HttpServletRequest request, Model model) {
        HttpSession session = request.getSession(false);
        String currentSessionId = session != null ? session.getId() : null;
        var sessions = webSessionService.listActiveSessions(currentSessionId);
        model.addAttribute("activePage", "web-sessions");
        model.addAttribute("sessions", sessions);
        model.addAttribute("activeCount", sessions.size());
        return "web-sessions";
    }

    @PostMapping("/{key}/expire")
    @PreAuthorize("hasAuthority('POST:/web-sessions/{key}/expire')")
    public String expire(@PathVariable String key, RedirectAttributes ra) {
        try {
            webSessionService.expireByKey(key, SecurityUtils.currentUserId());
            ra.addFlashAttribute("successMessage", FaMessages.webSessionExpired());
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("errorMessage", ErrorTranslator.toFa(e.getMessage()));
        }
        return "redirect:/web-sessions";
    }
}
