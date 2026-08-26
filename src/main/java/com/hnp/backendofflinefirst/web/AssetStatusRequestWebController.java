package com.hnp.backendofflinefirst.web;

import com.hnp.backendofflinefirst.domain.AssetStatusRequestStatus;
import com.hnp.backendofflinefirst.entity.AssetEntry;
import com.hnp.backendofflinefirst.entity.AssetStatusChangeRequest;
import com.hnp.backendofflinefirst.entity.LogSheet;
import com.hnp.backendofflinefirst.dto.SelectOptionDto;
import com.hnp.backendofflinefirst.entity.User;
import com.hnp.backendofflinefirst.repository.AssetEntryRepository;
import com.hnp.backendofflinefirst.repository.AssetStatusChangeRequestRepository;
import com.hnp.backendofflinefirst.repository.LogSheetRepository;
import com.hnp.backendofflinefirst.repository.UserRepository;
import com.hnp.backendofflinefirst.service.AssetAccessService;
import com.hnp.backendofflinefirst.service.AssetStatusRequestService;
import com.hnp.backendofflinefirst.ui.WebListSupport;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The asset status change queue: file a request, and approve, reject or undo one.
 *
 * <p>Scope is the reporting scope — responsibility through a log sheet — so a supervisor sees
 * the requests for the equipment they are accountable for, the same rule as the asset history
 * page. Deciding is re-checked inside the service; the authority here only gates the page.
 */
@Controller
@RequestMapping("/asset-status-requests")
@RequiredArgsConstructor
public class AssetStatusRequestWebController {

    private final AssetStatusRequestService requestService;
    private final AssetStatusChangeRequestRepository requestRepository;
    private final AssetEntryRepository assetEntryRepository;
    private final AssetAccessService assetAccessService;
    private final LogSheetRepository logSheetRepository;
    private final UserRepository userRepository;

    @GetMapping
    @PreAuthorize("hasAuthority('GET:/asset-status-requests')")
    public String list(@RequestParam(required = false) String status,
                       @RequestParam(required = false) Long assetId,
                       @RequestParam(required = false) String q,
                       @RequestParam(defaultValue = "0") int page,
                       @RequestParam(required = false) Integer size,
                       Model model) {
        model.addAttribute("activePage", "asset-status-requests");

        AssetStatusRequestStatus statusFilter = parseStatus(status);
        Set<Long> unitIds = assetAccessService.visibleUnitIds();
        // null = unrestricted admin; empty = this user may see nothing at all.
        List<Long> scopedAssetIds = unitIds == null ? null : visibleAssetIds();
        if (scopedAssetIds != null && scopedAssetIds.isEmpty()) {
            // An empty IN () list would match everything or fail depending on the dialect, so
            // short-circuit rather than let the query decide.
            scopedAssetIds = List.of(-1L);
        }

        int pageSize = size != null ? size : WebListSupport.DEFAULT_SIZE;
        Page<AssetStatusChangeRequest> result = requestRepository.search(
                statusFilter, assetId, scopedAssetIds, likeOrNull(q),
                WebListSupport.unsortedPageable(page, pageSize));

        model.addAttribute("requests", result.getContent());
        WebListSupport.addPagination(model, result, q, page, pageSize);
        model.addAttribute("statusFilter", status != null ? status : "");
        model.addAttribute("selectedAssetId", assetId);
        model.addAttribute("pendingCount", requestRepository.countByStatus(AssetStatusRequestStatus.PENDING));

        addLookups(model, result.getContent());
        return "asset-status-requests";
    }

    @PostMapping
    @PreAuthorize("hasAuthority('POST:/asset-status-requests')")
    public String create(@RequestParam Long assetId,
                         @RequestParam(required = false) String requestedStatus,
                         @RequestParam(required = false) String reason,
                         @RequestParam(required = false) String returnTo,
                         RedirectAttributes ra) {
        try {
            requestService.raiseManual(assetId, requestedStatus, reason);
            ra.addFlashAttribute("successMessage", "درخواست تغییر وضعیت ثبت شد.");
        } catch (RuntimeException e) {
            ra.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:" + (returnTo != null && !returnTo.isBlank() ? returnTo : "/asset-status-requests");
    }

    @PostMapping("/{id}/decide")
    @PreAuthorize("hasAuthority('POST:/asset-status-requests/{id}/decide')")
    public String decide(@PathVariable Long id,
                         @RequestParam String target,
                         @RequestParam(required = false) String note,
                         @RequestParam(required = false) String returnTo,
                         RedirectAttributes ra) {
        try {
            AssetStatusRequestStatus parsed = AssetStatusRequestStatus.valueOf(target);
            requestService.decide(id, parsed, note);
            ra.addFlashAttribute("successMessage", switch (parsed) {
                case APPROVED -> "درخواست تأیید شد و وضعیت دارایی تغییر کرد.";
                case REJECTED -> "درخواست رد شد.";
                case PENDING -> "درخواست به حالت «ثبت شده» بازگشت.";
            });
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("errorMessage", e.getMessage());
        } catch (RuntimeException e) {
            ra.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:" + (returnTo != null && !returnTo.isBlank() ? returnTo : "/asset-status-requests");
    }

    /**
     * Searchable asset picker for the manual request form, scoped to what the user may act on.
     *
     * <p>Uses {@code findReportableAssets} — the same scope {@link AssetStatusRequestService}
     * validates against — so the list can never offer something the save would then refuse.
     * An admin sees everything; a unit-scoped user sees the assets of their operational units
     * and everything below them, exactly as elsewhere in the panel.
     */
    @GetMapping("/options/assets")
    @PreAuthorize("hasAuthority('POST:/asset-status-requests')")
    @ResponseBody
    public List<SelectOptionDto> assetOptions(@RequestParam(required = false) String q,
                                              @RequestParam(defaultValue = "30") int limit) {
        int safeLimit = Math.min(Math.max(limit, 1), 100);
        return assetAccessService.findReportableAssets(q, PageRequest.of(0, safeLimit))
                .getContent().stream()
                .map(a -> SelectOptionDto.of(String.valueOf(a.getId()),
                        a.getAssetCode() + " — " + a.getAssetName()))
                .toList();
    }

    /**
     * The status values this asset's class allows, so the form offers choices rather than a
     * free-text box the operators' own form could never produce.
     *
     * <p>{@code supported=false} means the class declares no status field at all; the form
     * disables submission, and {@code raiseManual} refuses it server-side regardless.
     */
    @GetMapping("/options/statuses")
    @PreAuthorize("hasAuthority('POST:/asset-status-requests')")
    @ResponseBody
    public Map<String, Object> statusOptions(@RequestParam Long assetId) {
        try {
            AssetStatusRequestService.StatusFieldOptions f = requestService.statusOptionsForAsset(assetId);
            AssetEntry asset = assetEntryRepository.findById(assetId).orElse(null);
            return Map.of(
                    "supported", f.supported(),
                    "fieldKey", f.fieldKey() == null ? "" : f.fieldKey(),
                    "options", f.options(),
                    "currentStatus", asset != null && asset.getStatus() != null ? asset.getStatus() : "");
        } catch (RuntimeException e) {
            // Access refused or asset gone: say "not supported" rather than leak which.
            return Map.of("supported", false, "fieldKey", "", "options", List.of(), "currentStatus", "");
        }
    }

    // ── Lookups ──────────────────────────────────────────────────────────────

    /**
     * Names and context resolved in batches rather than per row: a queue page is dominated by a
     * handful of repeat actors and sheets, so per-row lookups would be almost all duplicates.
     */
    private void addLookups(Model model, List<AssetStatusChangeRequest> requests) {
        Set<Long> assetIds = requests.stream().map(AssetStatusChangeRequest::getAssetId)
                .filter(Objects::nonNull).collect(Collectors.toCollection(LinkedHashSet::new));
        Set<Long> sheetIds = requests.stream().map(AssetStatusChangeRequest::getLogSheetId)
                .filter(Objects::nonNull).collect(Collectors.toCollection(LinkedHashSet::new));
        Set<Long> userIds = new LinkedHashSet<>();
        requests.forEach(r -> {
            if (r.getRequestedByUserId() != null) userIds.add(r.getRequestedByUserId());
            if (r.getDecidedByUserId() != null) userIds.add(r.getDecidedByUserId());
        });

        Map<Long, AssetEntry> assets = assetIds.isEmpty() ? Map.of()
                : assetEntryRepository.findAllById(assetIds).stream()
                        .collect(Collectors.toMap(AssetEntry::getId, a -> a, (a, b) -> a));
        Map<Long, LogSheet> sheets = sheetIds.isEmpty() ? Map.of()
                : logSheetRepository.findAllById(sheetIds).stream()
                        .collect(Collectors.toMap(LogSheet::getId, s -> s, (a, b) -> a));
        Map<Long, String> users = userIds.isEmpty() ? Map.of()
                : userRepository.findAllById(userIds).stream()
                        .collect(Collectors.toMap(User::getId,
                                u -> u.getFullName() != null && !u.getFullName().isBlank()
                                        ? u.getFullName() : u.getUsername(), (a, b) -> a));

        // Which rows may still be undone, worked out once here so the template can disable the
        // control instead of letting someone click into a refusal.
        Map<Long, Boolean> latest = requests.stream().collect(Collectors.toMap(
                AssetStatusChangeRequest::getId, requestService::isLatestForAsset, (a, b) -> a));

        model.addAttribute("assetById", assets);
        model.addAttribute("sheetById", sheets);
        model.addAttribute("userNameById", users);
        model.addAttribute("isLatestById", latest);
    }

    private List<Long> visibleAssetIds() {
        return assetAccessService.findReportableAssets(null,
                        org.springframework.data.domain.PageRequest.of(0, 5000))
                .getContent().stream().map(AssetEntry::getId).toList();
    }

    /**
     * Lower-cased and wrapped in {@code %} once here, so the query compares a column against a
     * literal instead of calling {@code LOWER} on the parameter for every row.
     */
    private static String likeOrNull(String q) {
        String term = WebListSupport.searchTerm(q);
        return term == null ? null : "%" + term.toLowerCase(java.util.Locale.ROOT) + "%";
    }

    private static AssetStatusRequestStatus parseStatus(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return AssetStatusRequestStatus.valueOf(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
