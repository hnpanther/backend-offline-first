package com.hnp.backendofflinefirst.web;

import com.hnp.backendofflinefirst.security.SecurityUtils;
import com.hnp.backendofflinefirst.service.ExcelExportService;
import com.hnp.backendofflinefirst.service.LogSheetAccessService;
import com.hnp.backendofflinefirst.service.LogSheetProgressViewService;
import com.hnp.backendofflinefirst.util.ReferenceLabelService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.io.IOException;

/**
 * "My Inbox" (کارتابل من): the operator/supervisor view of their own assigned work
 * plus the pool of pending sheets they may pick up — the web counterpart of the
 * mobile inbox.
 */
@Controller
@RequiredArgsConstructor
public class MyInboxWebController {

    private final LogSheetAccessService logSheetAccessService;
    private final ExcelExportService excelExportService;
    private final LogSheetProgressViewService progressViewService;
    private final ReferenceLabelService referenceLabelService;

    @GetMapping("/my-inbox")
    @PreAuthorize("hasAuthority('GET:/my-inbox')")
    public String inbox(Model model) {
        Long userId = SecurityUtils.currentUserId();
        model.addAttribute("activePage", "my-inbox");
        var assigned = logSheetAccessService.findAssignedTo(userId);
        model.addAttribute("assigned", assigned);
        var available = logSheetAccessService.findAvailablePool(userId);
        model.addAttribute("available", available);
        // Both buckets share one map: the two lists are rendered by the same scope column, and a
        // sheet often sits in the pool under a scope another sheet is already assigned to.
        model.addAttribute("scopeLabels", referenceLabelService.scopeSummaryLabels(
                java.util.stream.Stream.concat(assigned.stream(), available.stream())
                        .map(ls -> ls.getScopeSummary()).toList()));
        // Only the assigned bucket: a pool sheet nobody has picked up has nothing to report,
        // and asking for it would be a second query to render a row of zeroes.
        model.addAttribute("progressBySheetId", progressViewService.summarise(assigned));
        return "my-inbox";
    }

    @GetMapping("/my-inbox/export")
    @PreAuthorize("hasAuthority('GET:/my-inbox')")
    public void export(HttpServletResponse response) throws IOException {
        excelExportService.exportMyInbox(SecurityUtils.currentUserId(), response);
    }
}
