package com.hnp.backendofflinefirst.controller;

import com.hnp.backendofflinefirst.dto.LogSheetBatchRequest;
import com.hnp.backendofflinefirst.dto.LogSheetSubmitResult;
import com.hnp.backendofflinefirst.service.LogSheetService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/log-sheets")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('POST:/api/log-sheets/batch')")
public class LogSheetController {

    private final LogSheetService logSheetService;

    @PostMapping("/batch")
    public List<LogSheetSubmitResult> submitBatch(@RequestBody LogSheetBatchRequest request) {
        return logSheetService.submitBatch(request.getLogSheets());
    }
}
