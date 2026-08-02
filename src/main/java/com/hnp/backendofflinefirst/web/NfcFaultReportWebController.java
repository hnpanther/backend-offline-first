package com.hnp.backendofflinefirst.web;

import com.hnp.backendofflinefirst.entity.AssetEntry;
import com.hnp.backendofflinefirst.entity.LogSheet;
import com.hnp.backendofflinefirst.entity.NfcFaultReport;
import com.hnp.backendofflinefirst.repository.AssetEntryRepository;
import com.hnp.backendofflinefirst.repository.LogSheetRepository;
import com.hnp.backendofflinefirst.service.NfcFaultReportService;
import com.hnp.backendofflinefirst.ui.FaMessages;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.StringUtils;
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

    @GetMapping
    @PreAuthorize("hasAuthority('GET:/nfc-fault-reports')")
    public String list(Model model) {
        List<NfcFaultReport> reports = nfcFaultReportService.findVisible();
        model.addAttribute("activePage", "nfc-fault-reports");
        model.addAttribute("reports", reports);
        addLookups(model, reports);
        return "nfc-fault-reports";
    }

    @PostMapping
    @PreAuthorize("hasAuthority('POST:/nfc-fault-reports')")
    public String create(@RequestParam Long logSheetId, @RequestParam Long assetId,
                         @RequestParam(required = false) String reason, RedirectAttributes ra) {
        nfcFaultReportService.createFromWeb(logSheetId, assetId, reason);
        ra.addFlashAttribute("successMessage", FaMessages.nfcFaultReportCreated());
        return "redirect:/log-sheets/" + logSheetId;
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
        model.addAttribute("logSheetById", logSheetById);
        model.addAttribute("assetById", assetById);
    }
}
