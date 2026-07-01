package com.hnp.backendofflinefirst.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("/settings")
public class SettingsWebController {

    @Value("${spring.application.name}")
    private String applicationName;

    @Value("${server.port}")
    private String serverPort;

    @GetMapping
    @PreAuthorize("hasAuthority('GET:/settings')")
    public String settings(Model model) {
        model.addAttribute("activePage", "settings");
        model.addAttribute("applicationName", applicationName);
        model.addAttribute("serverPort", serverPort);
        return "settings";
    }
}
