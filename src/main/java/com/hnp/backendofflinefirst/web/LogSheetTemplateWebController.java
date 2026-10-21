package com.hnp.backendofflinefirst.web;

import com.hnp.backendofflinefirst.dto.SelectOptionDto;
import com.hnp.backendofflinefirst.entity.LogSheetTemplate;
import com.hnp.backendofflinefirst.repository.AssetClassRepository;
import com.hnp.backendofflinefirst.repository.LogSheetTemplateRepository;
import com.hnp.backendofflinefirst.service.ExcelExportService;
import com.hnp.backendofflinefirst.service.LogSheetGenerationService;
import com.hnp.backendofflinefirst.service.LogSheetTemplateService;
import com.hnp.backendofflinefirst.service.MasterDataOptionsService;
import com.hnp.backendofflinefirst.domain.AssetSelectionMode;
import com.hnp.backendofflinefirst.ui.ErrorTranslator;
import com.hnp.backendofflinefirst.ui.FaMessages;
import com.hnp.backendofflinefirst.ui.WebListSupport;
import com.hnp.backendofflinefirst.util.DateUtils;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import com.hnp.backendofflinefirst.domain.LogSheetSizeLimits;
import com.hnp.backendofflinefirst.dto.ScopedAssetPreviewRow;
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
    private final LogSheetSizeLimits sizeLimits;
    private final AssetClassRepository assetClassRepository;
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
        // Every class, parents and sub-classes alike — the label map below has to
        // resolve a sub-class's name too, and a sub-class is a legitimate choice here.
        model.addAttribute("assetClasses", assetClassRepository.findAllByOrderByIdDesc());
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

    /**
     * Searchable owning-unit picker. Restricted to the units the current user may actually
     * target, so it can never be used to enumerate units outside their scope.
     */
    @GetMapping("/options/operational-units")
    @PreAuthorize("hasAuthority('GET:/log-sheet-templates')")
    @ResponseBody
    public List<SelectOptionDto> operationalUnitOptions(@RequestParam(required = false) String q,
                                                        @RequestParam(defaultValue = "30") int limit) {
        Collection<Long> visibleUnitIds = logSheetTemplateService.visibleUnitIds();
        return visibleUnitIds == null
                ? masterDataOptionsService.searchOperationalUnits(q, limit)
                : masterDataOptionsService.searchOperationalUnitsInIds(q, visibleUnitIds, limit);
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
        List<ScopedAssetPreviewRow> assets = logSheetGenerationService.listAssetsInScope(template);
        model.addAttribute("assets", assets);
        // The preview is the only screen that shows what a SCOPE template currently resolves to,
        // and a scope grows on its own. Saying "over the limit" here, next to the count, is how
        // somebody learns before the scheduler starts warning into a log nobody reads.
        model.addAttribute("assetLimitExceeded", sizeLimits.exceedsMax(assets.size()));
        model.addAttribute("assetLimitWarning",
                !sizeLimits.exceedsMax(assets.size()) && sizeLimits.deservesWarning(assets.size()));
        model.addAttribute("assetLimitMax", sizeLimits.max());
        model.addAttribute("canConvertToExplicit",
                template.getAssetSelectionMode() != AssetSelectionMode.EXPLICIT
                        && logSheetTemplateService.canEditOrDelete());
        return "log-sheet-template-assets-preview";
    }

    /**
     * Freezes the edited asset list and converts the template from SCOPE to EXPLICIT.
     *
     * <p>No permission of its own: this is an edit of the template, so it carries the same
     * {@code POST:/log-sheet-templates/{id}} the ordinary update does, and the service repeats
     * {@code assertCanEditOrDelete} on top. A separate permission would let a site grant
     * "may edit a template" without "may stop it following the plant", which is a distinction
     * nobody asked for and one more row for every custom role to get wrong.
     *
     * <p>The screen confirms before posting, because what is lost is invisible afterwards: a
     * SCOPE template picks up newly installed equipment and a converted one never will.
     */
    @PostMapping("/{id}/convert-to-explicit")
    @PreAuthorize("hasAuthority('POST:/log-sheet-templates/{id}')")
    public String convertToExplicit(@PathVariable Long id,
                                     @RequestParam(name = "assetIds", required = false) List<Long> assetIds,
                                     RedirectAttributes ra) {
        try {
            logSheetTemplateService.convertToExplicit(id, assetIds);
            ra.addFlashAttribute("successMessage", FaMessages.templateConvertedToExplicit());
        } catch (IllegalArgumentException | IllegalStateException e) {
            ra.addFlashAttribute("errorMessage", ErrorTranslator.toFa(e.getMessage()));
            return "redirect:/log-sheet-templates/" + id + "/preview-assets";
        }
        return "redirect:/log-sheet-templates?editId=" + id;
    }

}
