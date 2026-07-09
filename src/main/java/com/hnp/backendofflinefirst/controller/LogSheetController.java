package com.hnp.backendofflinefirst.controller;

import com.hnp.backendofflinefirst.domain.ActionSource;
import com.hnp.backendofflinefirst.dto.LogSheetAssignRequest;
import com.hnp.backendofflinefirst.dto.LogSheetBatchRequest;
import com.hnp.backendofflinefirst.dto.LogSheetBundleDto;
import com.hnp.backendofflinefirst.dto.LogSheetEntryDto;
import com.hnp.backendofflinefirst.dto.LogSheetInboxResponse;
import com.hnp.backendofflinefirst.dto.LogSheetSubmitResult;
import com.hnp.backendofflinefirst.entity.LogSheet;
import com.hnp.backendofflinefirst.entity.LogSheetEntry;
import com.hnp.backendofflinefirst.mapper.LogSheetEntryMapper;
import com.hnp.backendofflinefirst.repository.LogSheetEntryRepository;
import com.hnp.backendofflinefirst.security.SecurityUtils;
import com.hnp.backendofflinefirst.service.LogSheetAccessService;
import com.hnp.backendofflinefirst.service.LogSheetAssignmentService;
import com.hnp.backendofflinefirst.service.LogSheetBundleService;
import com.hnp.backendofflinefirst.service.LogSheetService;
import com.hnp.backendofflinefirst.ui.ApiResponseSupport;
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
    private final LogSheetBundleService bundleService;
    private final LogSheetEntryRepository logSheetEntryRepository;

    @GetMapping("/inbox")
    @PreAuthorize("hasAuthority('GET:/api/log-sheets/inbox')")
    public LogSheetInboxResponse inbox() {
        Long userId = SecurityUtils.currentUserId();
        List<LogSheetBundleDto> assigned = logSheetAccessService.findAssignedTo(userId).stream()
                .map(bundleService::buildFullBundle)
                .toList();
        return new LogSheetInboxResponse(
                System.currentTimeMillis(),
                assigned,
                logSheetAccessService.findAvailablePool(userId),
                logSheetAccessService.findTeamOpenForSupervisor(userId));
    }

    /** Full offline bundle for a single log sheet (metadata + entries + scoped context). */
    @GetMapping("/{id}/bundle")
    @PreAuthorize("hasAuthority('GET:/api/log-sheets/{id}/bundle')")
    public LogSheetBundleDto bundle(@PathVariable Long id) {
        return bundleService.buildFullBundle(id);
    }

    /** Authoritative asset rows for a log sheet (generated on the server at sheet creation). */
    @GetMapping("/{id}/entries")
    @PreAuthorize("hasAuthority('GET:/api/log-sheets/inbox')")
    public List<LogSheetEntryDto> entries(@PathVariable Long id) {
        logSheetAccessService.requireVisibleLogSheet(id);
        return logSheetEntryRepository.findByLogSheetId(id).stream()
                .map(LogSheetEntryMapper::toDto)
                .toList();
    }

    @PostMapping("/{id}/claim")
    @PreAuthorize("hasAuthority('POST:/api/log-sheets/{id}/claim')")
    public LogSheetBundleDto claim(@PathVariable Long id) {
        LogSheet sheet = assignmentService.claim(id, SecurityUtils.currentUserId(), ActionSource.MOBILE);
        return bundleService.buildFullBundle(sheet);
    }

    @PostMapping("/{id}/release")
    @PreAuthorize("hasAuthority('POST:/api/log-sheets/{id}/release')")
    public LogSheet release(@PathVariable Long id) {
        return assignmentService.release(id, SecurityUtils.currentUserId(), ActionSource.MOBILE);
    }

    @PostMapping("/{id}/assign")
    @PreAuthorize("hasAuthority('POST:/api/log-sheets/{id}/assign')")
    public LogSheet assign(@PathVariable Long id, @RequestBody LogSheetAssignRequest body) {
        if (body == null || body.getOperatorId() == null) {
            throw new IllegalArgumentException("operatorId is required.");
        }
        return assignmentService.assign(
                id, body.getOperatorId(), SecurityUtils.currentUserId(), ActionSource.MOBILE);
    }

    @PostMapping("/{id}/reassign")
    @PreAuthorize("hasAuthority('POST:/api/log-sheets/{id}/reassign')")
    public LogSheet reassign(@PathVariable Long id, @RequestBody LogSheetAssignRequest body) {
        if (body == null || body.getOperatorId() == null) {
            throw new IllegalArgumentException("operatorId is required.");
        }
        return assignmentService.reassign(
                id, body.getOperatorId(), SecurityUtils.currentUserId(), ActionSource.MOBILE);
    }

    @PostMapping("/batch")
    @PreAuthorize("hasAuthority('POST:/api/log-sheets/batch')")
    public List<LogSheetSubmitResult> submitBatch(@RequestBody LogSheetBatchRequest request) {
        return ApiResponseSupport.localizeLogSheetSubmitResults(
                logSheetService.submitBatch(request.getLogSheets()));
    }
}
