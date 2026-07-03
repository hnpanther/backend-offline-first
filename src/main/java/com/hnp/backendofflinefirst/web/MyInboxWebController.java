package com.hnp.backendofflinefirst.web;

import com.hnp.backendofflinefirst.security.SecurityUtils;
import com.hnp.backendofflinefirst.service.ExcelExportService;
import com.hnp.backendofflinefirst.service.LogSheetAccessService;
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

    @GetMapping("/my-inbox")
    @PreAuthorize("hasAuthority('GET:/my-inbox')")
    public String inbox(Model model) {
        Long userId = SecurityUtils.currentUserId();
        model.addAttribute("activePage", "my-inbox");
        model.addAttribute("assigned", logSheetAccessService.findAssignedTo(userId));
        model.addAttribute("available", logSheetAccessService.findAvailablePool(userId));
        return "my-inbox";
    }

    @GetMapping("/my-inbox/export")
    @PreAuthorize("hasAuthority('GET:/my-inbox')")
    public void export(HttpServletResponse response) throws IOException {
        excelExportService.exportMyInbox(SecurityUtils.currentUserId(), response);
    }
}
