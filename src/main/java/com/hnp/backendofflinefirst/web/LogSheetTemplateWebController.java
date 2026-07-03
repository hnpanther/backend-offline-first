package com.hnp.backendofflinefirst.web;

import com.hnp.backendofflinefirst.entity.LogSheetTemplate;
import com.hnp.backendofflinefirst.repository.AssetClassRepository;
import com.hnp.backendofflinefirst.repository.LocationRepository;
import com.hnp.backendofflinefirst.repository.LogSheetTemplateRepository;
import com.hnp.backendofflinefirst.repository.MainFunctionRepository;
import com.hnp.backendofflinefirst.repository.OperationalUnitRepository;
import com.hnp.backendofflinefirst.repository.PlantSystemRepository;
import com.hnp.backendofflinefirst.service.ExcelExportService;
import com.hnp.backendofflinefirst.service.LogSheetGenerationService;
import com.hnp.backendofflinefirst.service.LogSheetTemplateService;
import com.hnp.backendofflinefirst.ui.FaMessages;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDateTime;
import java.time.ZoneId;

@Controller
@RequestMapping("/log-sheet-templates")
@RequiredArgsConstructor
public class LogSheetTemplateWebController {

    private final LogSheetTemplateRepository logSheetTemplateRepository;
    private final LogSheetTemplateService logSheetTemplateService;
    private final LocationRepository locationRepository;
    private final PlantSystemRepository plantSystemRepository;
    private final MainFunctionRepository mainFunctionRepository;
    private final AssetClassRepository assetClassRepository;
    private final OperationalUnitRepository operationalUnitRepository;
    private final ExcelExportService excelExportService;
    private final LogSheetGenerationService logSheetGenerationService;

    @GetMapping
    @PreAuthorize("hasAuthority('GET:/log-sheet-templates')")
    public String list(@RequestParam(required = false) Long editId, Model model) {
        model.addAttribute("activePage", "log-sheet-templates");
        model.addAttribute("templates", logSheetTemplateRepository.findAllByOrderByIdDesc());
        model.addAttribute("locations", locationRepository.findAllByOrderByIdDesc());
        model.addAttribute("plantSystems", plantSystemRepository.findAllByOrderByIdDesc());
        model.addAttribute("mainFunctions", mainFunctionRepository.findAllByOrderByIdDesc());
        model.addAttribute("assetClasses", assetClassRepository.findAllByOrderByIdDesc());
        model.addAttribute("operationalUnits", operationalUnitRepository.findAllByOrderByIdDesc());
        if (editId != null) {
            logSheetTemplateRepository.findById(editId).ifPresent(e -> model.addAttribute("editEntity", e));
        }
        return "log-sheet-templates";
    }

    @GetMapping("/export")
    @PreAuthorize("hasAuthority('GET:/log-sheet-templates')")
    public void export(HttpServletResponse response) throws java.io.IOException {
        excelExportService.exportLogSheetTemplates(response);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('POST:/log-sheet-templates')")
    public String create(@ModelAttribute LogSheetTemplate form,
                         @RequestParam(required = false) String scheduleStart,
                         RedirectAttributes ra) {
        form.setScheduleStartAt(parseLocalDateTime(scheduleStart));
        logSheetTemplateService.create(form);
        ra.addFlashAttribute("successMessage", FaMessages.templateCreated());
        return "redirect:/log-sheet-templates";
    }

    @PostMapping("/{id}")
    @PreAuthorize("hasAuthority('POST:/log-sheet-templates/{id}')")
    public String update(@PathVariable Long id, @ModelAttribute LogSheetTemplate form,
                         @RequestParam(required = false) String scheduleStart,
                         RedirectAttributes ra) {
        form.setScheduleStartAt(parseLocalDateTime(scheduleStart));
        logSheetTemplateService.update(id, form);
        ra.addFlashAttribute("successMessage", FaMessages.templateUpdated());
        return "redirect:/log-sheet-templates";
    }

    @PostMapping("/{id}/delete")
    @PreAuthorize("hasAuthority('POST:/log-sheet-templates/{id}/delete')")
    public String delete(@PathVariable Long id, RedirectAttributes ra) {
        logSheetTemplateService.delete(id);
        ra.addFlashAttribute("successMessage", FaMessages.templateDeleted());
        return "redirect:/log-sheet-templates";
    }

    @GetMapping("/{id}/preview-assets")
    @PreAuthorize("hasAuthority('GET:/log-sheet-templates')")
    public String previewAssets(@PathVariable Long id, Model model) {
        return logSheetTemplateRepository.findById(id)
                .map(template -> {
                    model.addAttribute("activePage", "log-sheet-templates");
                    model.addAttribute("template", template);
                    model.addAttribute("scopeLabel", logSheetGenerationService.buildScopeDisplaySummary(template));
                    model.addAttribute("assets", logSheetGenerationService.listAssetsInScope(template));
                    return "log-sheet-template-assets-preview";
                })
                .orElse("redirect:/log-sheet-templates");
    }

    /** Converts an HTML datetime-local value (yyyy-MM-ddTHH:mm) to epoch millis. */
    private Long parseLocalDateTime(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return LocalDateTime.parse(value).atZone(ZoneId.of("Asia/Tehran")).toInstant().toEpochMilli();
        } catch (Exception e) {
            return null;
        }
    }
}
