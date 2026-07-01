package com.hnp.backendofflinefirst.web;

import com.hnp.backendofflinefirst.service.LogSheetAccessService;
import com.hnp.backendofflinefirst.repository.LogSheetEntryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
@RequestMapping("/log-sheets")
@RequiredArgsConstructor
public class LogSheetWebController {

    private final LogSheetAccessService logSheetAccessService;
    private final LogSheetEntryRepository logSheetEntryRepository;

    @GetMapping
    @PreAuthorize("hasAuthority('GET:/log-sheets')")
    public String list(@RequestParam(required = false) String status, Model model) {
        model.addAttribute("activePage", "log-sheets");
        model.addAttribute("logSheets", logSheetAccessService.findVisibleLogSheets(status));
        model.addAttribute("filterStatus", status);
        return "log-sheets";
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('GET:/log-sheets/{id}')")
    public String detail(@PathVariable String id, Model model) {
        model.addAttribute("activePage", "log-sheets");
        var sheet = logSheetAccessService.requireVisibleLogSheet(id);
        model.addAttribute("logSheet", sheet);
        model.addAttribute("entries", logSheetEntryRepository.findByLogSheetId(id));
        return "log-sheet-detail";
    }
}
