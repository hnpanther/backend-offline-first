package com.hnp.backendofflinefirst.web;

import com.hnp.backendofflinefirst.domain.ImportEntityType;
import com.hnp.backendofflinefirst.dto.ImportJobSummaryDto;
import com.hnp.backendofflinefirst.entity.ImportJob;
import com.hnp.backendofflinefirst.entity.ImportJobError;
import com.hnp.backendofflinefirst.service.importjob.ImportJobService;
import com.hnp.backendofflinefirst.security.SecurityUtils;
import com.hnp.backendofflinefirst.ui.ErrorTranslator;
import com.hnp.backendofflinefirst.ui.FaMessages;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;

@Controller
@RequestMapping("/batch-import")
@RequiredArgsConstructor
public class BatchImportWebController {

    private final ImportJobService importJobService;

    @GetMapping
    @PreAuthorize("hasAuthority('GET:/batch-import')")
    public String page(Model model) {
        Long userId = SecurityUtils.currentUserId();
        model.addAttribute("activePage", "batch-import");
        model.addAttribute("entityTypes", importJobService.availableEntityTypesForCurrentUser());
        model.addAttribute("importMaxRows", importJobService.maxRowsPerFile());
        model.addAttribute("importBusy", importJobService.hasActiveImport());
        model.addAttribute("jobSummaries", importJobService.listRecentJobs(userId).stream()
                .map(ImportJobSummaryDto::from)
                .toList());
        return "batch-import";
    }

    @PostMapping
    @PreAuthorize("hasAuthority('POST:/batch-import')")
    public String submit(@RequestParam("entityType") String entityTypeCode,
                         @RequestParam("file") MultipartFile file,
                         RedirectAttributes ra) {
        try {
            ImportEntityType entityType = ImportEntityType.fromCode(entityTypeCode)
                    .orElseThrow(() -> new IllegalArgumentException("Invalid entity type."));
            importJobService.submit(entityType, file, SecurityUtils.currentUserId());
            ra.addFlashAttribute("successMessage", "ورود دسته‌ای در صف پردازش قرار گرفت.");
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("errorMessage", ErrorTranslator.toFa(e.getMessage()));
        } catch (Exception e) {
            ra.addFlashAttribute("errorMessage", FaMessages.fileProcessingError(e));
        }
        return "redirect:/batch-import";
    }

    /**
     * Live job list for the page's poller.
     * <p>
     * {@code busy} is not derivable from {@code jobs}: the list is scoped to the caller's own
     * jobs, while the submit gate ({@code assertNoActiveImport}) is system-wide. Without it
     * the page can only learn that the form is usable again by being reloaded — and it would
     * mis-enable the form while another user's import is running. Same authority as the
     * plain list; this is a shape change, not a new capability.
     */
    @GetMapping("/jobs")
    @PreAuthorize("hasAuthority('GET:/batch-import/jobs')")
    @ResponseBody
    public JobsResponse jobsJson() {
        Long userId = SecurityUtils.currentUserId();
        List<ImportJobSummaryDto> jobs = importJobService.listRecentJobs(userId).stream()
                .map(ImportJobSummaryDto::from)
                .toList();
        return new JobsResponse(importJobService.hasActiveImport(), jobs);
    }

    @GetMapping("/jobs/{jobUuid}/errors")
    @PreAuthorize("hasAuthority('GET:/batch-import')")
    @ResponseBody
    public List<ImportJobErrorRowDto> jobErrors(@PathVariable String jobUuid) {
        ImportJob job = importJobService.requireJobForUser(jobUuid, SecurityUtils.currentUserId());
        return importJobService.listErrors(job.getId(), SecurityUtils.currentUserId()).stream()
                .map(ImportJobErrorRowDto::from)
                .toList();
    }

    @PostMapping("/jobs/{jobUuid}/cancel")
    @PreAuthorize("hasAuthority('POST:/batch-import')")
    @ResponseBody
    public JobActionResponse cancelJob(@PathVariable String jobUuid) {
        try {
            importJobService.cancel(jobUuid, SecurityUtils.currentUserId());
            return JobActionResponse.ok(FaMessages.importJobCancelRequested());
        } catch (IllegalArgumentException e) {
            return JobActionResponse.error(ErrorTranslator.toFa(e.getMessage()));
        }
    }

    /**
     * Force-terminates an active job whose worker is gone. Reuses {@code POST:/batch-import}
     * like cancel and delete do — no new authority, so no Flyway permission row.
     */
    @PostMapping("/jobs/{jobUuid}/abandon")
    @PreAuthorize("hasAuthority('POST:/batch-import')")
    @ResponseBody
    public JobActionResponse abandonJob(@PathVariable String jobUuid) {
        try {
            importJobService.abandon(jobUuid, SecurityUtils.currentUserId());
            return JobActionResponse.ok(FaMessages.importJobAbandoned());
        } catch (IllegalArgumentException e) {
            return JobActionResponse.error(ErrorTranslator.toFa(e.getMessage()));
        }
    }

    @PostMapping("/jobs/{jobUuid}/delete")
    @PreAuthorize("hasAuthority('POST:/batch-import')")
    @ResponseBody
    public JobActionResponse deleteJob(@PathVariable String jobUuid) {
        try {
            importJobService.delete(jobUuid, SecurityUtils.currentUserId());
            return JobActionResponse.ok(FaMessages.importJobDeleted());
        } catch (IllegalArgumentException e) {
            return JobActionResponse.error(ErrorTranslator.toFa(e.getMessage()));
        }
    }

    /** @param busy whether ANY import is queued or running system-wide (submit gate) */
    public record JobsResponse(boolean busy, List<ImportJobSummaryDto> jobs) {
    }

    public record JobActionResponse(boolean success, String message) {
        static JobActionResponse ok(String message) {
            return new JobActionResponse(true, message);
        }

        static JobActionResponse error(String message) {
            return new JobActionResponse(false, message);
        }
    }

    public record ImportJobErrorRowDto(int row, String message) {
        static ImportJobErrorRowDto from(ImportJobError e) {
            return new ImportJobErrorRowDto(e.getRowNum(), ErrorTranslator.toFa(e.getMessageEn()));
        }
    }
}
