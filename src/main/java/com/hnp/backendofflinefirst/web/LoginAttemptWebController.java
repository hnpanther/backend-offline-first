package com.hnp.backendofflinefirst.web;

import com.hnp.backendofflinefirst.security.LoginAttemptService;
import com.hnp.backendofflinefirst.ui.FaMessages;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.Comparator;
import java.util.List;

/** Admin view over the login-attempt throttle: locked usernames, near-lockout warnings, manual unlock. */
@Controller
@RequestMapping("/login-attempts")
@RequiredArgsConstructor
public class LoginAttemptWebController {

    private final LoginAttemptService loginAttemptService;

    @GetMapping
    @PreAuthorize("hasAuthority('GET:/login-attempts')")
    public String list(Model model) {
        List<LoginAttemptService.Status> all = loginAttemptService.snapshot();
        List<LoginAttemptService.Status> locked = all.stream()
                .filter(LoginAttemptService.Status::locked)
                .sorted(Comparator.comparingLong(LoginAttemptService.Status::remainingLockSeconds).reversed())
                .toList();
        List<LoginAttemptService.Status> nearLockout = all.stream()
                .filter(s -> !s.locked())
                .sorted(Comparator.comparingLong(LoginAttemptService.Status::lastFailureAt).reversed())
                .toList();
        model.addAttribute("activePage", "login-attempts");
        model.addAttribute("lockedUsers", locked);
        model.addAttribute("nearLockoutUsers", nearLockout);
        model.addAttribute("lockedCount", locked.size());
        return "login-attempts";
    }

    @PostMapping("/{username}/unlock")
    @PreAuthorize("hasAuthority('POST:/login-attempts/{username}/unlock')")
    public String unlock(@PathVariable String username, RedirectAttributes ra) {
        loginAttemptService.unlock(username);
        ra.addFlashAttribute("successMessage", FaMessages.loginAttemptUnlocked(username));
        return "redirect:/login-attempts";
    }
}
