package com.hnp.backendofflinefirst.web;

import com.hnp.backendofflinefirst.entity.ApiKey;
import com.hnp.backendofflinefirst.repository.ApiKeyUsageRepository;
import com.hnp.backendofflinefirst.security.SecurityUtils;
import com.hnp.backendofflinefirst.service.ApiKeyService;
import com.hnp.backendofflinefirst.ui.ErrorTranslator;
import com.hnp.backendofflinefirst.ui.FaMessages;
import com.hnp.backendofflinefirst.ui.WebListSupport;
import com.hnp.backendofflinefirst.util.DateUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Admin page for third-party integration keys: issue, pause, revoke, and read the usage log.
 *
 * <p><b>The created key is passed as a flash attribute and then gone.</b> It is not stored, not
 * re-rendered on a refresh, and not recoverable from any page — which is the whole point of
 * storing only its hash. The page says so plainly, because an administrator who assumes they
 * can come back for it later will close the dialog and then need a new key issued.
 */
@Controller
@RequestMapping("/integration-keys")
@RequiredArgsConstructor
public class IntegrationKeyWebController {

    /** Usage rows shown beneath the key list. Enough to see today's traffic, not a report. */
    private static final int USAGE_PAGE_SIZE = 50;

    private final ApiKeyService apiKeyService;
    private final ApiKeyUsageRepository apiKeyUsageRepository;
    private final DateUtils dateUtils;

    @GetMapping
    @PreAuthorize("hasAuthority('GET:/integration-keys')")
    public String list(@RequestParam(required = false) String q,
                       @RequestParam(defaultValue = "0") int page,
                       @RequestParam(required = false) Integer size,
                       @RequestParam(required = false) Long usageKeyId,
                       @RequestParam(defaultValue = "0") int usagePage,
                       Model model) {
        int pageSize = size != null ? size : WebListSupport.DEFAULT_SIZE;
        var keys = apiKeyService.list(q, WebListSupport.pageable(page, pageSize));

        // A second list on one page needs its own page parameter, or the two pagers move
        // together and the operator loses their place in the one they were not looking at
        // (AGENTS.md §9d). Hence usagePage alongside page.
        Pageable usagePageable = PageRequest.of(Math.max(0, usagePage), USAGE_PAGE_SIZE);
        var usage = usageKeyId == null
                ? apiKeyUsageRepository.findAllByOrderByRequestedAtDesc(usagePageable)
                : apiKeyUsageRepository.findByApiKeyIdOrderByRequestedAtDesc(usageKeyId, usagePageable);

        model.addAttribute("activePage", "integration-keys");
        model.addAttribute("keys", keys.getContent());
        model.addAttribute("usage", usage.getContent());
        model.addAttribute("usagePage", usage.getNumber());
        model.addAttribute("usageTotalPages", usage.getTotalPages());
        model.addAttribute("usageKeyId", usageKeyId);
        model.addAttribute("now", System.currentTimeMillis());
        WebListSupport.addPagination(model, keys, q, page, pageSize);
        return "integration-keys";
    }

    @PostMapping
    @PreAuthorize("hasAuthority('POST:/integration-keys')")
    public String create(@RequestParam String clientName,
                         @RequestParam(required = false) String description,
                         @RequestParam(required = false) String expiresAt,
                         RedirectAttributes ra) {
        try {
            // The panel is Persian throughout, so the expiry field is the shared Jalali
            // datetime picker and its value arrives in the same shape every other form uses.
            Long expiry = dateUtils.parseInput(expiresAt);
            var issued = apiKeyService.create(clientName, description, expiry, SecurityUtils.currentUserId());
            // Flash, so it survives exactly one redirect and then no longer exists anywhere.
            ra.addFlashAttribute("createdApiKey", issued.apiKey());
            ra.addFlashAttribute("createdApiKeyClient", issued.key().getClientName());
            ra.addFlashAttribute("successMessage",
                    FaMessages.integrationKeyCreated(issued.key().getClientName()));
        } catch (IllegalArgumentException | IllegalStateException e) {
            ra.addFlashAttribute("errorMessage", ErrorTranslator.toFa(e.getMessage()));
        }
        return "redirect:/integration-keys";
    }

    @PostMapping("/{id}/status")
    @PreAuthorize("hasAuthority('POST:/integration-keys/{id}/status')")
    public String setStatus(@PathVariable Long id,
                            @RequestParam boolean active,
                            RedirectAttributes ra) {
        try {
            apiKeyService.setActive(id, active, SecurityUtils.currentUserId());
            ra.addFlashAttribute("successMessage",
                    active ? FaMessages.integrationKeyEnabled() : FaMessages.integrationKeyDisabled());
        } catch (IllegalArgumentException | IllegalStateException e) {
            ra.addFlashAttribute("errorMessage", ErrorTranslator.toFa(e.getMessage()));
        }
        return "redirect:/integration-keys";
    }

    @PostMapping("/{id}/revoke")
    @PreAuthorize("hasAuthority('POST:/integration-keys/{id}/revoke')")
    public String revoke(@PathVariable Long id,
                         @RequestParam(required = false) String reason,
                         RedirectAttributes ra) {
        try {
            ApiKey key = apiKeyService.revoke(id, reason, SecurityUtils.currentUserId());
            ra.addFlashAttribute("successMessage", FaMessages.integrationKeyRevoked());
            ra.addFlashAttribute("revokedClient", key.getClientName());
        } catch (IllegalArgumentException | IllegalStateException e) {
            ra.addFlashAttribute("errorMessage", ErrorTranslator.toFa(e.getMessage()));
        }
        return "redirect:/integration-keys";
    }
}
