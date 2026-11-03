package com.hnp.backendofflinefirst.web;

import com.hnp.backendofflinefirst.domain.FieldDataTypes;
import com.hnp.backendofflinefirst.domain.FieldValidationSupport;
import com.hnp.backendofflinefirst.dto.SelectOptionDto;
import com.hnp.backendofflinefirst.entity.LogSheetField;
import com.hnp.backendofflinefirst.entity.LogSheetTemplate;
import com.hnp.backendofflinefirst.repository.AssetClassRepository;
import com.hnp.backendofflinefirst.repository.LogSheetFieldRepository;
import com.hnp.backendofflinefirst.repository.LogSheetTemplateRepository;
import com.hnp.backendofflinefirst.service.ExcelExportService;
import com.hnp.backendofflinefirst.service.LogSheetFieldService;
import com.hnp.backendofflinefirst.service.LogSheetGenerationService;
import com.hnp.backendofflinefirst.service.LogSheetTemplateService;
import com.hnp.backendofflinefirst.service.MasterDataOptionsService;
import com.hnp.backendofflinefirst.service.MasterDataUniquenessValidator;
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
import java.util.Map;

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
    private final LogSheetFieldRepository logSheetFieldRepository;
    private final LogSheetFieldService logSheetFieldService;
    private final MasterDataUniquenessValidator uniquenessValidator;
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

    // =====================================================================
    // The template's own questions — asked once per round, not once per asset
    //
    // A near-copy of AssetClassWebController's field editor, and deliberately so: an engineer
    // who has configured one should recognise the other at a glance. The three differences are
    // all consequences of the owner being a template rather than a class:
    //
    //   * the data-type list is narrowed (FieldDataTypes.sheetLevelLabels) — an attachment type
    //     has nowhere to put its bytes when there is no asset;
    //   * `required` is not offered yet — nothing may block a completion until every tablet
    //     carries the field (roadmap §24, "the rollout constraint");
    //   * the hard delete is guarded by whether any ANSWER names the key, scoped to this
    //     template's own rounds.
    // =====================================================================

    @GetMapping("/{id}/fields")
    @PreAuthorize("hasAuthority('GET:/log-sheet-templates/{id}/fields')")
    public String fields(@PathVariable Long id,
                         @RequestParam(required = false) Long editId,
                         Model model) {
        LogSheetTemplate template = logSheetTemplateService.requireVisible(id);
        model.addAttribute("activePage", "log-sheet-templates");
        model.addAttribute("fields", logSheetFieldService.editorRowsForTemplate(id));
        model.addAttribute("dataTypes", FieldDataTypes.labels());
        model.addAttribute("canManage", logSheetTemplateService.canEditOrDelete());
        // One page serves both owners — a template's questions and a custom sheet's — because
        // the markup is identical and two copies of it would drift. What differs is supplied
        // here: the title, where «back» goes, and the sentence about who inherits these.
        model.addAttribute("questionsOwnerName", template.getName());
        model.addAttribute("questionsTitle", "پرسش‌های قالب لاگ شیت");
        model.addAttribute("questionsBackPath", "/log-sheet-templates");
        model.addAttribute("questionsBackLabel", "قالب‌های لاگ شیت");
        model.addAttribute("questionsBasePath", "/log-sheet-templates/" + id + "/fields");
        model.addAttribute("questionsScopeNote",
                "هر لاگ شیتی که از این قالب ساخته شود، نسخه‌ای از همین پرسش‌ها را برای خودش ثبت می‌کند؛ "
                        + "تغییر بعدی این فهرست، لاگ شیت‌های قبلی را عوض نمی‌کند.");
        if (editId != null) {
            // Null when the id names a field of another template: the page renders the create
            // form instead of an edit form, which is the honest outcome of a tampered link.
            model.addAttribute("editField",
                    logSheetFieldRepository.findByIdAndTemplateId(editId, id).orElse(null));
        }
        return "log-sheet-questions";
    }

    @PostMapping("/{id}/fields")
    @PreAuthorize("hasAuthority('POST:/log-sheet-templates/{id}/fields')")
    public String addField(@PathVariable Long id,
                           @ModelAttribute LogSheetField form,
                           @RequestParam(required = false) String selectOptions,
                           @RequestParam(required = false) Double warningMin,
                           @RequestParam(required = false) Double warningMax,
                           @RequestParam(required = false) Double dangerMin,
                           @RequestParam(required = false) Double dangerMax,
                           @RequestParam(required = false) String warningOptions,
                           @RequestParam(required = false) String dangerOptions,
                           RedirectAttributes ra) {
        LogSheetTemplate template = logSheetTemplateService.requireVisible(id);
        logSheetTemplateService.assertCanManageUnit(template.getOperationalUnitId());
        if (!FieldDataTypes.isValid(form.getDataType())) {
            // A dropdown is not a boundary: the form posts a string, and a typo or a
            // hand-made request would otherwise store a type nothing can render.
            ra.addFlashAttribute("errorMessage",
                    FaMessages.fieldDefinitionInvalidDataType(form.getDataType()));
            return "redirect:/log-sheet-templates/" + id + "/fields";
        }
        String key = form.getKey() == null ? null : form.getKey().trim();
        uniquenessValidator.validateLogSheetField(null, id, key);

        long now = System.currentTimeMillis();
        LogSheetField field = new LogSheetField();
        field.setTemplateId(id);
        field.setLogSheetId(null);
        field.setKey(key);
        field.setLabel(form.getLabel());
        field.setDataType(form.getDataType());
        field.setUnit(trimToNull(form.getUnit()));
        // Read from the form since V10. Safe because `required` is judged ONLY when somebody
        // presses the round-level save button — never on a completion and never on a mobile
        // submit — so no unanswered question can ever hold up delivered work.
        field.setRequired(form.isRequired());
        field.setOrder(form.getOrder());
        field.setValidation(FieldValidationSupport.build(
                form.getDataType(), selectOptions, warningMin, warningMax, dangerMin, dangerMax,
                warningOptions, dangerOptions));
        field.setVersion(1);
        field.setDeleted(false);
        field.setCreatedAt(now);
        field.setUpdatedAt(now);
        logSheetFieldRepository.save(field);
        ra.addFlashAttribute("successMessage", FaMessages.logSheetFieldCreated());
        return "redirect:/log-sheet-templates/" + id + "/fields";
    }

    @PostMapping("/{id}/fields/{fieldId}")
    @PreAuthorize("hasAuthority('POST:/log-sheet-templates/{id}/fields/{fieldId}')")
    public String updateField(@PathVariable Long id,
                              @PathVariable Long fieldId,
                              @ModelAttribute LogSheetField form,
                              @RequestParam(required = false) String selectOptions,
                              @RequestParam(required = false) Double warningMin,
                              @RequestParam(required = false) Double warningMax,
                              @RequestParam(required = false) Double dangerMin,
                              @RequestParam(required = false) Double dangerMax,
                              @RequestParam(required = false) String warningOptions,
                              @RequestParam(required = false) String dangerOptions,
                              RedirectAttributes ra) {
        LogSheetTemplate template = logSheetTemplateService.requireVisible(id);
        logSheetTemplateService.assertCanManageUnit(template.getOperationalUnitId());
        var existing = logSheetFieldRepository.findByIdAndTemplateId(fieldId, id);
        if (existing.isEmpty()) {
            // The path id decides access and the field id decides what is written, so these two
            // have to agree or holding one template is a way into another's questions.
            ra.addFlashAttribute("errorMessage", FaMessages.logSheetFieldNotInTemplate());
            return "redirect:/log-sheet-templates/" + id + "/fields";
        }
        LogSheetField field = existing.get();
        if (!FieldDataTypes.isValidFor(form.getDataType(), field.getDataType())) {
            // Refused before `field` is touched: it is attached for the whole request under Open
            // Session In View, so a half-applied edit would still be flushed. `isValidFor`
            // rather than `isValid`: a question carrying a legacy type must stay editable.
            ra.addFlashAttribute("errorMessage",
                    FaMessages.fieldDefinitionInvalidDataType(form.getDataType()));
            return "redirect:/log-sheet-templates/" + id + "/fields";
        }
        String key = form.getKey() == null ? null : form.getKey().trim();
        uniquenessValidator.validateLogSheetField(fieldId, id, key);
        // Built before the entity is mutated, for the same reason: it throws on min > max.
        Map<String, Object> validation = FieldValidationSupport.build(
                form.getDataType(), selectOptions, warningMin, warningMax, dangerMin, dangerMax,
                warningOptions, dangerOptions);

        field.setKey(key);
        field.setLabel(form.getLabel());
        field.setDataType(form.getDataType());
        field.setUnit(trimToNull(form.getUnit()));
        field.setRequired(form.isRequired());
        field.setOrder(form.getOrder());
        field.setValidation(validation);
        field.setVersion(field.getVersion() == null ? 1 : field.getVersion() + 1);
        field.setUpdatedAt(System.currentTimeMillis());
        logSheetFieldRepository.save(field);
        ra.addFlashAttribute("successMessage", FaMessages.logSheetFieldUpdated());
        return "redirect:/log-sheet-templates/" + id + "/fields";
    }

    /**
     * Switches a question off, or back on.
     *
     * <p><strong>Off is not gone.</strong> The row stays with its vocabulary and its id; a round
     * raised while the question existed keeps asking it from its own frozen snapshot, and a
     * round raised from now on does not. Switching off and on again is a round trip.
     */
    @PostMapping("/{id}/fields/{fieldId}/state")
    @PreAuthorize("hasAuthority('POST:/log-sheet-templates/{id}/fields/{fieldId}/state')")
    public String setFieldState(@PathVariable Long id,
                                @PathVariable Long fieldId,
                                @RequestParam boolean disabled,
                                RedirectAttributes ra) {
        LogSheetTemplate template = logSheetTemplateService.requireVisible(id);
        logSheetTemplateService.assertCanManageUnit(template.getOperationalUnitId());
        var existing = logSheetFieldRepository.findByIdAndTemplateId(fieldId, id);
        if (existing.isEmpty()) {
            ra.addFlashAttribute("errorMessage", FaMessages.logSheetFieldNotInTemplate());
            return "redirect:/log-sheet-templates/" + id + "/fields";
        }
        LogSheetField field = existing.get();
        if (field.isDeleted() != disabled) {
            field.setDeleted(disabled);
            // Bumped like any other edit — this IS an edit of the definition.
            field.setVersion(field.getVersion() == null ? 1 : field.getVersion() + 1);
            field.setUpdatedAt(System.currentTimeMillis());
            logSheetFieldRepository.save(field);
        }
        ra.addFlashAttribute("successMessage",
                disabled ? FaMessages.logSheetFieldDisabled() : FaMessages.logSheetFieldEnabled());
        return "redirect:/log-sheet-templates/" + id + "/fields";
    }

    /**
     * Removes a question outright — and refuses once any round has answered it.
     *
     * <p>Kept for the one case switching off does not cover: a question created, mistyped,
     * never answered, and removed again. The moment an answer names the key, deleting the
     * definition would orphan it exactly as AGENTS.md #157 describes, so the guard sends the
     * administrator to «غیرفعال» instead.
     */
    @PostMapping("/{id}/fields/{fieldId}/delete")
    @PreAuthorize("hasAuthority('POST:/log-sheet-templates/{id}/fields/{fieldId}/delete')")
    public String deleteField(@PathVariable Long id,
                              @PathVariable Long fieldId,
                              RedirectAttributes ra) {
        LogSheetTemplate template = logSheetTemplateService.requireVisible(id);
        logSheetTemplateService.assertCanManageUnit(template.getOperationalUnitId());
        var existing = logSheetFieldRepository.findByIdAndTemplateId(fieldId, id);
        if (existing.isEmpty()) {
            ra.addFlashAttribute("errorMessage", FaMessages.logSheetFieldNotInTemplate());
            return "redirect:/log-sheet-templates/" + id + "/fields";
        }
        LogSheetField field = existing.get();
        if (logSheetFieldRepository.isTemplateFieldKeyAnswered(id, field.getKey())) {
            ra.addFlashAttribute("errorMessage", FaMessages.logSheetFieldInUseUseDisable());
            return "redirect:/log-sheet-templates/" + id + "/fields";
        }
        logSheetFieldRepository.delete(field);
        ra.addFlashAttribute("successMessage", FaMessages.logSheetFieldDeleted());
        return "redirect:/log-sheet-templates/" + id + "/fields";
    }

    /**
     * An empty text input posts {@code ""}, and "no unit" has to be one value rather than two.
     *
     * <p>Not cosmetic: every reader then asks one question instead of two, and Thymeleaf's own
     * truthiness makes the second one a trap — it calls a non-null empty String <em>true</em>,
     * so a template guarding on {@code th:if="${q.unit}"} prints an empty «()».
     */
    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
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
