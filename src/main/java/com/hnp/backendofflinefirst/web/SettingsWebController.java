package com.hnp.backendofflinefirst.web;

import com.hnp.backendofflinefirst.dto.AuditRetentionProgress;
import com.hnp.backendofflinefirst.dto.AttachmentSweepProgress;
import com.hnp.backendofflinefirst.dto.AuditRetentionStatusResponse;
import com.hnp.backendofflinefirst.service.AppSettingsService;
import com.hnp.backendofflinefirst.service.AttachmentSweepService;
import com.hnp.backendofflinefirst.service.AuditRetentionService;
import com.hnp.backendofflinefirst.ui.AuditRetentionViewHelper;
import com.hnp.backendofflinefirst.ui.ErrorTranslator;
import com.hnp.backendofflinefirst.ui.FaMessages;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/settings")
@RequiredArgsConstructor
public class SettingsWebController {

    @Value("${spring.application.name}")
    private String applicationName;

    @Value("${server.port}")
    private String serverPort;

    private final AppSettingsService appSettingsService;
    private final AuditRetentionService auditRetentionService;
    private final AttachmentSweepService attachmentSweepService;

    @GetMapping
    @PreAuthorize("hasAuthority('GET:/settings')")
    public String settings(Model model) {
        model.addAttribute("activePage", "settings");
        model.addAttribute("applicationName", applicationName);
        model.addAttribute("serverPort", serverPort);
        model.addAttribute("excelExportMaxRows", appSettingsService.getExcelExportMaxRows());
        model.addAttribute("minExcelExportMaxRows", AppSettingsService.MIN_EXCEL_EXPORT_MAX_ROWS);
        model.addAttribute("maxExcelExportMaxRows", AppSettingsService.MAX_EXCEL_EXPORT_MAX_ROWS);
        model.addAttribute("auditRetentionDays", appSettingsService.getAuditRetentionDays());
        model.addAttribute("minAuditRetentionDays", AppSettingsService.MIN_AUDIT_RETENTION_DAYS);
        model.addAttribute("maxAuditRetentionDays", AppSettingsService.MAX_AUDIT_RETENTION_DAYS);
        model.addAttribute("jwtExpiryMinutes", appSettingsService.getJwtExpiryMinutes());
        model.addAttribute("minJwtExpiryMinutes", AppSettingsService.MIN_JWT_EXPIRY_MINUTES);
        model.addAttribute("maxJwtExpiryMinutes", AppSettingsService.MAX_JWT_EXPIRY_MINUTES);
        AppSettingsService.AttachmentLimits limits = appSettingsService.getAttachmentLimits();
        model.addAttribute("attachmentLimits", limits);
        model.addAttribute("minAttachmentsPerField", AppSettingsService.MIN_ATTACHMENTS_PER_FIELD);
        model.addAttribute("maxAttachmentsPerField", AppSettingsService.MAX_ATTACHMENTS_PER_FIELD);
        model.addAttribute("minMediaSeconds", AppSettingsService.MIN_MEDIA_SECONDS);
        model.addAttribute("maxMediaSeconds", AppSettingsService.MAX_MEDIA_SECONDS);
        model.addAttribute("auditEligibleCount", auditRetentionService.countRowsEligibleForPurge());
        AuditRetentionProgress auditProgress = auditRetentionService.getProgress();
        model.addAttribute("auditRetentionProgress", auditProgress);
        model.addAttribute("auditRetentionMessageFa", AuditRetentionViewHelper.messageFa(auditProgress));

        // Estimated rather than exact-at-render: it walks the storage root, so it is a real
        // cost on a large installation. Shown so an administrator can see whether running the
        // sweep is worth anything before starting it.
        model.addAttribute("sweepEstimate", attachmentSweepService.estimate());
        model.addAttribute("sweepProgress", attachmentSweepService.getProgress());
        model.addAttribute("sweepGraceHours", attachmentSweepService.getGraceHours());
        return "settings";
    }

    @PostMapping
    @PreAuthorize("hasAuthority('POST:/settings')")
    public String save(@RequestParam int excelExportMaxRows,
                       @RequestParam int auditRetentionDays,
                       @RequestParam int jwtExpiryMinutes,
                       @RequestParam int maxImagesPerField,
                       @RequestParam int maxAudiosPerField,
                       @RequestParam int maxVideosPerField,
                       @RequestParam int maxAudioSeconds,
                       @RequestParam int maxVideoSeconds,
                       RedirectAttributes ra) {
        try {
            appSettingsService.saveAll(excelExportMaxRows, auditRetentionDays, jwtExpiryMinutes);
            // Saved after the others so a rejected limit does not leave the page reporting
            // success for settings that did land. Both calls validate before writing.
            appSettingsService.saveAttachmentLimits(new AppSettingsService.AttachmentLimits(
                    maxImagesPerField, maxAudiosPerField, maxVideosPerField,
                    maxAudioSeconds, maxVideoSeconds));
            ra.addFlashAttribute("successMessage", FaMessages.settingsSaved());
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("errorMessage", ErrorTranslator.toFa(e.getMessage()));
        }
        return "redirect:/settings";
    }

    @PostMapping("/audit-retention/run")
    @PreAuthorize("hasAuthority('POST:/settings/audit-retention/run')")
    public String runAuditRetention(RedirectAttributes ra) {
        try {
            auditRetentionService.startPurge();
            ra.addFlashAttribute("successMessage", FaMessages.auditPurgeStartedBackground());
        } catch (IllegalStateException e) {
            ra.addFlashAttribute("errorMessage", ErrorTranslator.toFa(e.getMessage()));
        }
        return "redirect:/settings";
    }

    @PostMapping("/audit-retention/cancel")
    @PreAuthorize("hasAuthority('POST:/settings/audit-retention/cancel')")
    public String cancelAuditRetention(RedirectAttributes ra) {
        try {
            auditRetentionService.requestCancel();
            ra.addFlashAttribute("successMessage", FaMessages.auditPurgeCancelRequested());
        } catch (IllegalStateException e) {
            ra.addFlashAttribute("errorMessage", ErrorTranslator.toFa(e.getMessage()));
        }
        return "redirect:/settings";
    }

    @PostMapping("/attachment-sweep/run")
    @PreAuthorize("hasAuthority('POST:/settings/attachment-sweep/run')")
    public String runAttachmentSweep(RedirectAttributes ra) {
        try {
            attachmentSweepService.startSweep();
            ra.addFlashAttribute("successMessage",
                    "پاکسازی فایل‌های بدون مرجع در پس‌زمینه آغاز شد.");
        } catch (IllegalStateException e) {
            ra.addFlashAttribute("errorMessage", ErrorTranslator.toFa(e.getMessage()));
        }
        return "redirect:/settings";
    }

    @PostMapping("/attachment-sweep/cancel")
    @PreAuthorize("hasAuthority('POST:/settings/attachment-sweep/cancel')")
    public String cancelAttachmentSweep(RedirectAttributes ra) {
        try {
            attachmentSweepService.requestCancel();
            ra.addFlashAttribute("successMessage", "درخواست توقف پاکسازی ثبت شد.");
        } catch (IllegalStateException e) {
            ra.addFlashAttribute("errorMessage", ErrorTranslator.toFa(e.getMessage()));
        }
        return "redirect:/settings";
    }

    @GetMapping("/attachment-sweep/status")
    @PreAuthorize("hasAuthority('GET:/settings')")
    @ResponseBody
    public AttachmentSweepProgress attachmentSweepStatus() {
        return attachmentSweepService.getProgress();
    }

    @GetMapping("/audit-retention/status")
    @PreAuthorize("hasAuthority('GET:/settings')")
    @ResponseBody
    public AuditRetentionStatusResponse auditRetentionStatus() {
        return AuditRetentionStatusResponse.from(auditRetentionService.getProgress());
    }
}
