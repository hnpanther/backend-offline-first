package com.hnp.backendofflinefirst.controller;

import com.hnp.backendofflinefirst.domain.ActionSource;
import com.hnp.backendofflinefirst.dto.LogSheetBatchRequest;
import com.hnp.backendofflinefirst.dto.LogSheetInboxResponse;
import com.hnp.backendofflinefirst.dto.LogSheetSubmitResult;
import com.hnp.backendofflinefirst.entity.LogSheet;
import com.hnp.backendofflinefirst.security.SecurityUtils;
import com.hnp.backendofflinefirst.service.LogSheetAccessService;
import com.hnp.backendofflinefirst.service.LogSheetAssignmentService;
import com.hnp.backendofflinefirst.service.LogSheetService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Mobile-facing log-sheet API for the offline-first operator app: pull the inbox,
 * claim/release work while online, and sync completed sheets in batch.
 */
@RestController
@RequestMapping("/api/log-sheets")
@RequiredArgsConstructor
public class LogSheetController {

    private final LogSheetService logSheetService;
    private final LogSheetAccessService logSheetAccessService;
    private final LogSheetAssignmentService assignmentService;

    @GetMapping("/inbox")
    @PreAuthorize("hasAuthority('GET:/api/log-sheets/inbox')")
    public LogSheetInboxResponse inbox() {
        Long userId = SecurityUtils.currentUserId();
        return new LogSheetInboxResponse(
                System.currentTimeMillis(),
                logSheetAccessService.findAssignedTo(userId),
                logSheetAccessService.findAvailablePool(userId));
    }

    @PostMapping("/{id}/claim")
    @PreAuthorize("hasAuthority('POST:/api/log-sheets/{id}/claim')")
    public LogSheet claim(@PathVariable Long id) {
        return assignmentService.claim(id, SecurityUtils.currentUserId(), ActionSource.MOBILE);
    }

    @PostMapping("/{id}/release")
    @PreAuthorize("hasAuthority('POST:/api/log-sheets/{id}/release')")
    public LogSheet release(@PathVariable Long id) {
        return assignmentService.release(id, SecurityUtils.currentUserId(), ActionSource.MOBILE);
    }

    @PostMapping("/batch")
    @PreAuthorize("hasAuthority('POST:/api/log-sheets/batch')")
    public List<LogSheetSubmitResult> submitBatch(@RequestBody LogSheetBatchRequest request) {
        return logSheetService.submitBatch(request.getLogSheets());
    }
}
