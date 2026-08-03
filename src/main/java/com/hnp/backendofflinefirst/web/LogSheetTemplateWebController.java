package com.hnp.backendofflinefirst.web;

import com.hnp.backendofflinefirst.dto.SelectOptionDto;
import com.hnp.backendofflinefirst.entity.LogSheetTemplate;
import com.hnp.backendofflinefirst.entity.OperationalUnit;
import com.hnp.backendofflinefirst.repository.AssetClassRepository;
import com.hnp.backendofflinefirst.repository.LogSheetTemplateRepository;
import com.hnp.backendofflinefirst.repository.OperationalUnitRepository;
import com.hnp.backendofflinefirst.service.ExcelExportService;
import com.hnp.backendofflinefirst.service.LogSheetGenerationService;
import com.hnp.backendofflinefirst.service.LogSheetTemplateService;
import com.hnp.backendofflinefirst.service.MasterDataOptionsService;
import com.hnp.backendofflinefirst.ui.FaMessages;
import com.hnp.backendofflinefirst.ui.WebListSupport;
import com.hnp.backendofflinefirst.util.DateUtils;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.Collection;
import java.util.List;

@Controller
@RequestMapping("/log-sheet-templates")
@RequiredArgsConstructor
public class LogSheetTemplateWebController {

    private final LogSheetTemplateRepository logSheetTemplateRepository;
    private final LogSheetTemplateService logSheetTemplateService;
    private final AssetClassRepository assetClassRepository;
    private final OperationalUnitRepository operationalUnitRepository;
    private final ExcelExportService excelExportService;
    private final LogSheetGenerationService logSheetGenerationService;
    private final MasterDataOptionsService masterDataOptionsService;
    private final DateUtils dateUtils;

    @GetMapping
    @PreAuthorize("hasAuthority('GET:/log-sheet-templates')")
    public String list(@RequestParam(required = false) Long editId,
                       @RequestParam(required = false) String q,
                       @RequestParam(defaultValue = "0") int page,
                       @RequestParam(required = false) Integer size,
                       Model model) {
        int pageSize = size != null ? size : WebListSupport.DEFAULT_SIZE;
        Pageable pageable = WebListSupport.pageable(page, pageSize);
        var result = logSheetTemplateService.findVisible(q, pageable);
        model.addAttribute("activePage", "log-sheet-templates");
        model.addAttribute("templates", result.getContent());
        model.addAttribute("canEditTemplates", logSheetTemplateService.canEditOrDelete());
        model.addAttribute("canUnrestrictScope", logSheetTemplateService.canUnrestrictScope());
        WebListSupport.addPagination(model, result, q, page, pageSize);
        model.addAttribute("assetClasses", assetClassRepository.findAllByOrderByIdDesc());
        model.addAttribute("operationalUnits", filterOperationalUnits());
        if (editId != null && logSheetTemplateService.canEditOrDelete()) {
            logSheetTemplateService.requireVisible(editId);
            logSheetTemplateRepository.findById(editId).ifPresent(e -> {
                model.addAttribute("editEntity", e);
                model.addAttribute("selectedScope",
                        masterDataOptionsService.scopeOption(e.getScopeType(), e.getScopeId()));
                model.addAttribute("editEntityAssets", masterDataOptionsService.assetOptionsByIds(
                        logSheetTemplateService.assetIdsForTemplate(editId)));
            });
        }
        return "log-sheet-templates";
    }

    @GetMapping("/options/locations")
    @PreAuthorize("hasAuthority('GET:/log-sheet-templates')")
    @ResponseBody
    public List<SelectOptionDto> locationOptions(@RequestParam(required = false) String q,
                                                 @RequestParam(required = false) Long unitId,
                                                 @RequestParam(defaultValue = "true") boolean restrictToUnit,
                                                 @RequestParam(defaultValue = "30") int limit) {
        if (allowUnrestrictedScope(restrictToUnit)) {
            return masterDataOptionsService.searchLocations(q, limit);
        }
        return unitId == null
                ? List.of()
                : masterDataOptionsService.searchLocationsForUnit(q, unitId, limit);
    }

    @GetMapping("/options/plant-systems")
    @PreAuthorize("hasAuthority('GET:/log-sheet-templates')")
    @ResponseBody
    public List<SelectOptionDto> plantSystemOptions(@RequestParam(required = false) String q,
                                                    @RequestParam(required = false) Long unitId,
                                                    @RequestParam(defaultValue = "true") boolean restrictToUnit,
                                                    @RequestParam(defaultValue = "30") int limit) {
        if (allowUnrestrictedScope(restrictToUnit)) {
            return masterDataOptionsService.searchPlantSystems(q, limit);
        }
        return unitId == null
                ? List.of()
                : masterDataOptionsService.searchPlantSystemsForUnit(q, unitId, limit);
    }

    @GetMapping("/options/main-functions")
    @PreAuthorize("hasAuthority('GET:/log-sheet-templates')")
    @ResponseBody
    public List<SelectOptionDto> mainFunctionOptions(@RequestParam(required = false) String q,
                                                     @RequestParam(required = false) Long unitId,
                                                     @RequestParam(defaultValue = "true") boolean restrictToUnit,
                                                     @RequestParam(defaultValue = "30") int limit) {
        if (allowUnrestrictedScope(restrictToUnit)) {
            return masterDataOptionsService.searchMainFunctions(q, limit);
        }
        return unitId == null
                ? List.of()
                : masterDataOptionsService.searchMainFunctionsForUnit(q, unitId, limit);
    }

    /** Asset picker for EXPLICIT templates (the frozen, hand-picked set). */
    @GetMapping("/options/assets")
    @PreAuthorize("hasAuthority('GET:/log-sheet-templates')")
    @ResponseBody
    public List<SelectOptionDto> assetOptions(@RequestParam(required = false) String q,
                                              @RequestParam(required = false) Long unitId,
                                              @RequestParam(defaultValue = "true") boolean restrictToUnit,
                                              @RequestParam(defaultValue = "30") int limit) {
        if (allowUnrestrictedScope(restrictToUnit)) {
            return masterDataOptionsService.searchActiveAssets(q, limit);
        }
        return unitId == null
                ? List.of()
                : masterDataOptionsService.searchAssetsForUnit(q, unitId, limit);
    }

    /**
     * Unrestricted scope pickers list the whole plant, so they are only offered to roles
     * that already see the whole plant. A unit-scoped supervisor always gets the
     * unit-filtered list regardless of what the client asks for — mirrors the same guard
     * in {@code LogSheetTemplateService.applyScopeRestrictionPolicy}.
     */
    private boolean allowUnrestrictedScope(boolean restrictToUnit) {
        return !restrictToUnit && logSheetTemplateService.canUnrestrictScope();
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
                         @RequestParam(required = false) List<Long> assetIds,
                         RedirectAttributes ra) {
        form.setScheduleStartAt(dateUtils.parseInput(scheduleStart));
        logSheetTemplateService.create(form, assetIds);
        ra.addFlashAttribute("successMessage", FaMessages.templateCreated());
        if (logSheetTemplateService.scheduleOverlapRisk(form)) {
            ra.addFlashAttribute("warningMessage", FaMessages.templateScheduleOverlapWarning());
        }
        return "redirect:/log-sheet-templates";
    }

    @PostMapping("/{id}")
    @PreAuthorize("hasAuthority('POST:/log-sheet-templates/{id}')")
    public String update(@PathVariable Long id, @ModelAttribute LogSheetTemplate form,
                         @RequestParam(required = false) String scheduleStart,
                         @RequestParam(required = false) List<Long> assetIds,
                         RedirectAttributes ra) {
        form.setScheduleStartAt(dateUtils.parseInput(scheduleStart));
        logSheetTemplateService.update(id, form, assetIds);
        ra.addFlashAttribute("successMessage", FaMessages.templateUpdated());
        if (logSheetTemplateService.scheduleOverlapRisk(form)) {
            ra.addFlashAttribute("warningMessage", FaMessages.templateScheduleOverlapWarning());
        }
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
        LogSheetTemplate template = logSheetTemplateService.requireVisible(id);
        model.addAttribute("activePage", "log-sheet-templates");
        model.addAttribute("template", template);
        model.addAttribute("scopeLabel", logSheetGenerationService.buildScopeDisplaySummary(template));
        model.addAttribute("assets", logSheetGenerationService.listAssetsInScope(template));
        return "log-sheet-template-assets-preview";
    }

    private List<OperationalUnit> filterOperationalUnits() {
        List<OperationalUnit> all = operationalUnitRepository.findAllByOrderByIdDesc();
        Collection<Long> visibleUnitIds = logSheetTemplateService.visibleUnitIds();
        if (visibleUnitIds == null) {
            return all;
        }
        return all.stream().filter(u -> visibleUnitIds.contains(u.getId())).toList();
    }
}
