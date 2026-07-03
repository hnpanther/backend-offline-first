package com.hnp.backendofflinefirst.web;

import com.hnp.backendofflinefirst.repository.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("/audit-logs")
@RequiredArgsConstructor
public class AuditLogWebController {

    private final AuditLogRepository auditLogRepository;

    @GetMapping
    @PreAuthorize("hasAuthority('GET:/audit-logs')")
    public String list(Model model) {
        model.addAttribute("activePage", "audit-logs");
        model.addAttribute("logs", auditLogRepository.findTop200ByOrderByRecordedAtDesc());
        return "audit-logs";
    }
}
