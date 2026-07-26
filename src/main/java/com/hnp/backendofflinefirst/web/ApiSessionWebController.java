package com.hnp.backendofflinefirst.web;

import com.hnp.backendofflinefirst.security.SecurityUtils;
import com.hnp.backendofflinefirst.service.ApiSessionService;
import com.hnp.backendofflinefirst.ui.ErrorTranslator;
import com.hnp.backendofflinefirst.ui.FaMessages;
import com.hnp.backendofflinefirst.ui.WebListSupport;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** Admin view over issued mobile-API JWTs: which devices are logged in, and revoking them. */
@Controller
@RequestMapping("/api-sessions")
@RequiredArgsConstructor
public class ApiSessionWebController {

    private final ApiSessionService apiSessionService;

    @GetMapping
    @PreAuthorize("hasAuthority('GET:/api-sessions')")
    public String list(@RequestParam(required = false) String q,
                       @RequestParam(defaultValue = "true") boolean activeOnly,
                       @RequestParam(defaultValue = "0") int page,
                       @RequestParam(required = false) Integer size,
                       Model model) {
        long now = System.currentTimeMillis();
        int pageSize = size != null ? size : WebListSupport.DEFAULT_SIZE;
        Pageable pageable = WebListSupport.pageable(page, pageSize);
        var result = apiSessionService.list(q, activeOnly, now, pageable);

        model.addAttribute("activePage", "api-sessions");
        model.addAttribute("sessions", result.getContent());
        model.addAttribute("activeOnly", activeOnly);
        model.addAttribute("activeCount", apiSessionService.countActive(now));
        model.addAttribute("now", now);
        WebListSupport.addPagination(model, result, q, page, pageSize);
        return "api-sessions";
    }

    @PostMapping("/{id}/revoke")
    @PreAuthorize("hasAuthority('POST:/api-sessions/{id}/revoke')")
    public String revoke(@PathVariable Long id, RedirectAttributes ra) {
        try {
            apiSessionService.revoke(id, SecurityUtils.currentUserId(), System.currentTimeMillis());
            ra.addFlashAttribute("successMessage", FaMessages.apiSessionRevoked());
        } catch (IllegalArgumentException | IllegalStateException e) {
            ra.addFlashAttribute("errorMessage", ErrorTranslator.toFa(e.getMessage()));
        }
        return "redirect:/api-sessions";
    }

    @PostMapping("/revoke-user/{userId}")
    @PreAuthorize("hasAuthority('POST:/api-sessions/revoke-user/{userId}')")
    public String revokeUser(@PathVariable Long userId, RedirectAttributes ra) {
        int revoked = apiSessionService.revokeAllForUser(
                userId, SecurityUtils.currentUserId(), System.currentTimeMillis());
        ra.addFlashAttribute("successMessage", FaMessages.apiSessionsRevokedForUser(revoked));
        return "redirect:/api-sessions";
    }
}
