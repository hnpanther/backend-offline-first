package com.hnp.backendofflinefirst.controller;

import com.hnp.backendofflinefirst.dto.HealthResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/health")
public class HealthController {

    @GetMapping
    public HealthResponse health() {
        return new HealthResponse("ok", "1.0.0", System.currentTimeMillis());
    }
}
