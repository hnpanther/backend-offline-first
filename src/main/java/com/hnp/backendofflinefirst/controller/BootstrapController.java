package com.hnp.backendofflinefirst.controller;

import com.hnp.backendofflinefirst.dto.BootstrapResponse;
import com.hnp.backendofflinefirst.security.SecurityUtils;
import com.hnp.backendofflinefirst.service.BootstrapService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/bootstrap")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('GET:/api/bootstrap')")
public class BootstrapController {

    private final BootstrapService bootstrapService;

    @GetMapping
    public BootstrapResponse bootstrap() {
        return bootstrapService.getBootstrap(
                SecurityUtils.currentUserId(),
                SecurityUtils.isUnitScopedOnly());
    }
}
