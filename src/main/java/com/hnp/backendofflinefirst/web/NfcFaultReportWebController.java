package com.hnp.backendofflinefirst.web;

import com.hnp.backendofflinefirst.entity.AssetEntry;
import com.hnp.backendofflinefirst.entity.LogSheet;
import com.hnp.backendofflinefirst.entity.NfcFaultReport;
import com.hnp.backendofflinefirst.repository.AssetEntryRepository;
import com.hnp.backendofflinefirst.repository.LogSheetRepository;
import com.hnp.backendofflinefirst.service.NfcFaultReportService;
import com.hnp.backendofflinefirst.domain.NfcFaultReportStatus;
import com.hnp.backendofflinefirst.ui.WebListSupport;
import org.springframework.data.domain.Page;
import com.hnp.backendofflinefirst.ui.FaMessages;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.StringUtils;
import com.hnp.backendofflinefirst.security.SecurityUtils;
import org.springframework.security.access.AccessDeniedException;
import com.hnp.backendofflinefirst.ui.ErrorTranslator;
import com.hnp.backendofflinefirst.entity.User;
import com.hnp.backendofflinefirst.repository.UserRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Browse and file NFC fault reports (see {@link NfcFaultReportService}). Reports are
 * insert-only: this controller intentionally has no edit endpoint, and delete is
 * ADMIN-only. Creating from the log-sheet detail page posts back here too.
 */
@Controller
@RequestMapping("/nfc-fault-reports")
@RequiredArgsConstructor
public class NfcFaultReportWebController {

    private final NfcFaultReportService nfcFaultReportService;
    private final LogSheetRepository logSheetRepository;
    private final AssetEntryRepository assetEntryRepository;
    private final UserRepository userRepository;

    /**
     * The review queue: one page of it, narrowed by status and free text.
     *
     * <p>Nothing is filtered in Java. Scope, status, search and paging are one query — the page
     * used to render every report ever filed, and this table only ever grows.
     */
    @GetMapping
    @PreAuthorize("hasAuthority('GET:/nfc-fault-reports')")
    public String list(@RequestParam(required = false) String status,
                       @RequestParam(required = false) String q,
                       @RequestParam(defaultValue = "0") int page,
                       @RequestParam(required = false) Integer size,
                       Model model) {
        NfcFaultReportStatus statusFilter = parseStatus(status);
        int pageSize = size != null ? size : WebListSupport.DEFAULT_SIZE;
        Page<NfcFaultReport> result = nfcFaultReportService.findVisible(
                statusFilter, WebListSupport.searchTerm(q),
                WebListSupport.unsortedPageable(page, pageSize));

        model.addAttribute("activePage", "nfc-fault-reports");
        model.addAttribute("reports", result.getContent());
        // The status filter echoes back the raw parameter, not the parsed enum: an unrecognised
        // value falls through to "all", and re-selecting it in the dropdown would be a lie.
        model.addAttribute("statusFilter", statusFilter != null ? statusFilter.name() : "");
        model.addAttribute("openCount", nfcFaultReportService.countOpenVisible());
        WebListSupport.addPagination(model, result, q, page, pageSize);
        addLookups(model, result.getContent());
        return "nfc-fault-reports";
    }

    /** Unknown or empty means "every status" — a bad query parameter must not be an error page. */
    private static NfcFaultReportStatus parseStatus(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return NfcFaultReportStatus.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    @PostMapping
    @PreAuthorize("hasAuthority('POST:/nfc-fault-reports')")
    public String create(@RequestParam Long logSheetId, @RequestParam Long assetId,
                         @RequestParam(required = false) String reason, RedirectAttributes ra) {
        nfcFaultReportService.createFromWeb(logSheetId, assetId, reason);
        ra.addFlashAttribute("successMessage", FaMessages.nfcFaultReportCreated());
        return "redirect:/log-sheets/" + logSheetId;
    }

    /**
     * Admin-only review toggle.
     *
     * <p>The permission is seeded for ADMIN alone, and the service re-checks — a report that can
     * be closed by anyone who sees it does not mean anything.
     */
    @PostMapping("/{id}/review")
    @PreAuthorize("hasAuthority('POST:/nfc-fault-reports/{id}/review')")
    public String review(@PathVariable Long id,
                         @RequestParam(defaultValue = "true") boolean reviewed,
                         @RequestParam(required = false) String returnTo, RedirectAttributes ra) {
        try {
            nfcFaultReportService.setReviewed(id, reviewed, SecurityUtils.currentUserId());
            ra.addFlashAttribute("successMessage",
                    reviewed ? "گزارش به «بررسی شده» تغییر یافت." : "گزارش دوباره باز شد.");
        } catch (IllegalArgumentException | AccessDeniedException e) {
            ra.addFlashAttribute("errorMessage", ErrorTranslator.toFa(e.getMessage()));
        }
        return "redirect:" + (StringUtils.hasText(returnTo) ? returnTo : "/nfc-fault-reports");
    }

    @PostMapping("/{id}/delete")
    @PreAuthorize("hasAuthority('POST:/nfc-fault-reports/{id}/delete')")
    public String delete(@PathVariable Long id,
                         @RequestParam(required = false) String returnTo, RedirectAttributes ra) {
        nfcFaultReportService.delete(id);
        ra.addFlashAttribute("successMessage", FaMessages.nfcFaultReportDeleted());
        return "redirect:" + (StringUtils.hasText(returnTo) ? returnTo : "/nfc-fault-reports");
    }

    private void addLookups(Model model, List<NfcFaultReport> reports) {
        Set<Long> logSheetIds = reports.stream().map(NfcFaultReport::getLogSheetId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Set<Long> assetIds = reports.stream().map(NfcFaultReport::getAssetId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, LogSheet> logSheetById = logSheetIds.isEmpty() ? Map.of()
                : logSheetRepository.findAllById(logSheetIds).stream()
                        .collect(Collectors.toMap(LogSheet::getId, s -> s, (a, b) -> a, LinkedHashMap::new));
        Map<Long, AssetEntry> assetById = assetIds.isEmpty() ? Map.of()
                : assetEntryRepository.findAllById(assetIds).stream()
                        .collect(Collectors.toMap(AssetEntry::getId, a -> a, (a, b) -> a, LinkedHashMap::new));
        // Reviewers, so the status column can name who closed a report rather than just
        // asserting that somebody did. One query for the whole page, not one per row.
        Set<Long> reviewerIds = reports.stream().map(NfcFaultReport::getReviewedByUserId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, User> userById = reviewerIds.isEmpty() ? Map.of()
                : userRepository.findAllById(reviewerIds).stream()
                        .collect(Collectors.toMap(User::getId, u -> u, (a, b) -> a, LinkedHashMap::new));

        model.addAttribute("logSheetById", logSheetById);
        model.addAttribute("assetById", assetById);
        model.addAttribute("userById", userById);
    }
}
