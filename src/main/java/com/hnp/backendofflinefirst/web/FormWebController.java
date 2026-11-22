package com.hnp.backendofflinefirst.web;

import com.hnp.backendofflinefirst.domain.FieldDataTypes;
import com.hnp.backendofflinefirst.domain.FieldValidationSupport;
import com.hnp.backendofflinefirst.entity.FormDefinition;
import com.hnp.backendofflinefirst.entity.FormField;
import com.hnp.backendofflinefirst.service.FormDefinitionService;
import com.hnp.backendofflinefirst.service.FormFieldService;
import com.hnp.backendofflinefirst.service.LookupListService;
import com.hnp.backendofflinefirst.service.MasterDataOptionsService;
import com.hnp.backendofflinefirst.ui.FaMessages;
import com.hnp.backendofflinefirst.ui.WebListSupport;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;
import java.util.Map;

/**
 * ADMIN panel for ad-hoc form definitions and their fields (V29 / carrier 3).
 * Field editor reuses {@code log-sheet-questions.html} — same DSL, different owner.
 */
@Controller
@RequestMapping("/forms")
@RequiredArgsConstructor
public class FormWebController {

    private final FormDefinitionService formDefinitionService;
    private final FormFieldService formFieldService;
    private final LookupListService lookupListService;
    private final MasterDataOptionsService optionsService;

    @GetMapping
    @PreAuthorize("hasAuthority('GET:/forms')")
    public String list(@RequestParam(required = false) Long editId,
                       @RequestParam(required = false) String q,
                       @RequestParam(defaultValue = "0") int page,
                       @RequestParam(required = false) Integer size,
                       Model model) {
        int pageSize = size != null ? size : WebListSupport.DEFAULT_SIZE;
        var result = formDefinitionService.page(q, WebListSupport.pageable(page, pageSize));
        model.addAttribute("activePage", "forms");
        model.addAttribute("forms", result.getContent());
        WebListSupport.addPagination(model, result, q, page, pageSize);
        if (editId != null) {
            FormDefinition edit = formDefinitionService.require(editId);
            model.addAttribute("editEntity", edit);
            model.addAttribute("editUnitIds", formDefinitionService.unitIds(editId));
            model.addAttribute("editUnitOptions",
                    optionsService.operationalUnitOptionsByIds(formDefinitionService.unitIds(editId)));
        }
        return "forms";
    }

    @PostMapping
    @PreAuthorize("hasAuthority('POST:/forms')")
    public String create(@RequestParam String code,
                         @RequestParam String title,
                         @RequestParam(required = false) String description,
                         @RequestParam(required = false) List<Long> unitIds,
                         @RequestParam(name = "_active", required = false) String activeMarker,
                         @RequestParam(required = false) Boolean active,
                         RedirectAttributes ra) {
        boolean resolvedActive = activeMarker == null || Boolean.TRUE.equals(active);
        formDefinitionService.create(code, title, description, unitIds, resolvedActive);
        ra.addFlashAttribute("successMessage", "فرم میدانی ایجاد شد.");
        return "redirect:/forms";
    }

    @PostMapping("/{id}")
    @PreAuthorize("hasAuthority('POST:/forms/{id}')")
    public String update(@PathVariable Long id,
                         @RequestParam(required = false) String code,
                         @RequestParam String title,
                         @RequestParam(required = false) String description,
                         @RequestParam(required = false) List<Long> unitIds,
                         @RequestParam(name = "_active", required = false) String activeMarker,
                         @RequestParam(required = false) Boolean active,
                         RedirectAttributes ra) {
        Boolean resolved = activeMarker == null ? null : Boolean.TRUE.equals(active);
        formDefinitionService.update(id, code, title, description, unitIds, resolved);
        ra.addFlashAttribute("successMessage", "فرم میدانی ذخیره شد.");
        return "redirect:/forms";
    }

    @PostMapping("/{id}/delete")
    @PreAuthorize("hasAuthority('POST:/forms/{id}/delete')")
    public String delete(@PathVariable Long id, RedirectAttributes ra) {
        formDefinitionService.delete(id);
        ra.addFlashAttribute("successMessage", "فرم میدانی حذف شد.");
        return "redirect:/forms";
    }

    // ── Fields (reuse log-sheet-questions.html) ───────────────────────────────

    @GetMapping("/{id}/fields")
    @PreAuthorize("hasAuthority('GET:/forms/{id}/fields')")
    public String fields(@PathVariable Long id,
                         @RequestParam(required = false) Long editId,
                         Model model) {
        FormDefinition form = formDefinitionService.require(id);
        model.addAttribute("activePage", "forms");
        model.addAttribute("fields", formFieldService.listForForm(id));
        model.addAttribute("dataTypes", FieldDataTypes.labels());
        model.addAttribute("canManage", true);
        model.addAttribute("questionsOwnerName", form.getTitle());
        model.addAttribute("questionsTitle", "فیلدهای فرم میدانی");
        model.addAttribute("questionsBackPath", "/forms");
        model.addAttribute("questionsBackLabel", "فرم‌های میدانی");
        model.addAttribute("questionsBasePath", "/forms/" + id + "/fields");
        model.addAttribute("questionsScopeNote",
                "نسخهٔ فیلدها در اولین ذخیرهٔ پیش‌نویس روی همان ارسال قفل می‌شود؛ "
                        + "ویرایش بعدی این فهرست، ارسال‌های قبلی یا پیش‌نویس‌های باز را عوض نمی‌کند.");
        if (editId != null) {
            model.addAttribute("editField",
                    formFieldService.listForForm(id).stream()
                            .filter(f -> f.getId().equals(editId))
                            .findFirst()
                            .orElse(null));
        }
        model.addAttribute("lookupLists", lookupListService.findAllForPicker());
        return "log-sheet-questions";
    }

    @PostMapping("/{id}/fields")
    @PreAuthorize("hasAuthority('POST:/forms/{id}/fields')")
    public String addField(@PathVariable Long id,
                           @ModelAttribute FormField form,
                           @RequestParam(required = false) String selectOptions,
                           @RequestParam(required = false) Double warningMin,
                           @RequestParam(required = false) Double warningMax,
                           @RequestParam(required = false) Double dangerMin,
                           @RequestParam(required = false) Double dangerMax,
                           @RequestParam(required = false) String warningOptions,
                           @RequestParam(required = false) String dangerOptions,
                           @RequestParam(name = "scanSources", required = false) List<String> scanSources,
                           @RequestParam(name = "scanManual", required = false) Boolean scanManualChecked,
                           @RequestParam(name = "_scanManual", required = false) String scanManualMarker,
                           @RequestParam(name = "allowCustom", required = false) Boolean allowCustomChecked,
                           @RequestParam(name = "_allowCustom", required = false) String allowCustomMarker,
                           @RequestParam(required = false) String signatureScope,
                           @RequestParam(required = false) Double entryMin,
                           @RequestParam(required = false) Double entryMax,
                           @RequestParam(name = "integerOnly", required = false) Boolean integerOnlyChecked,
                           @RequestParam(name = "_integerOnly", required = false) String integerOnlyMarker,
                           @RequestParam(required = false) String lookupList,
                           @RequestParam(name = "lookupAcceptsUnlistedCode", required = false) Boolean lookupAcceptsCodeChecked,
                           @RequestParam(name = "_lookupAcceptsUnlistedCode", required = false) String lookupAcceptsCodeMarker,
                           RedirectAttributes ra) {
        if (!FieldDataTypes.isValid(form.getDataType())) {
            ra.addFlashAttribute("errorMessage", FaMessages.fieldDefinitionInvalidDataType(form.getDataType()));
            return "redirect:/forms/" + id + "/fields";
        }
        Map<String, Object> validation = FieldValidationSupport.build(
                form.getDataType(), selectOptions, warningMin, warningMax, dangerMin, dangerMax,
                warningOptions, dangerOptions, scanSources,
                FieldValidationSupport.scanManualFromForm(scanManualMarker, scanManualChecked),
                null,
                FieldValidationSupport.FieldBehaviour.fromForm(
                        allowCustomMarker, allowCustomChecked, signatureScope,
                        FieldValidationSupport.EntryLimit.fromForm(
                                entryMin, entryMax, integerOnlyMarker, integerOnlyChecked),
                        lookupListService.listCodeForField(form.getDataType(), lookupList),
                        lookupAcceptsCodeMarker, lookupAcceptsCodeChecked));
        formFieldService.create(id, form.getKey(), form.getLabel(), form.getDataType(),
                form.getUnit(), form.isRequired(), validation, form.getOrder());
        ra.addFlashAttribute("successMessage", "فیلد فرم افزوده شد.");
        return "redirect:/forms/" + id + "/fields";
    }

    @PostMapping("/{id}/fields/{fieldId}")
    @PreAuthorize("hasAuthority('POST:/forms/{id}/fields/{fieldId}')")
    public String updateField(@PathVariable Long id,
                              @PathVariable Long fieldId,
                              @ModelAttribute FormField form,
                              @RequestParam(required = false) String selectOptions,
                              @RequestParam(required = false) Double warningMin,
                              @RequestParam(required = false) Double warningMax,
                              @RequestParam(required = false) Double dangerMin,
                              @RequestParam(required = false) Double dangerMax,
                              @RequestParam(required = false) String warningOptions,
                              @RequestParam(required = false) String dangerOptions,
                              @RequestParam(name = "scanSources", required = false) List<String> scanSources,
                              @RequestParam(name = "scanManual", required = false) Boolean scanManualChecked,
                              @RequestParam(name = "_scanManual", required = false) String scanManualMarker,
                              @RequestParam(name = "allowCustom", required = false) Boolean allowCustomChecked,
                              @RequestParam(name = "_allowCustom", required = false) String allowCustomMarker,
                              @RequestParam(required = false) String signatureScope,
                              @RequestParam(required = false) Double entryMin,
                              @RequestParam(required = false) Double entryMax,
                              @RequestParam(name = "integerOnly", required = false) Boolean integerOnlyChecked,
                              @RequestParam(name = "_integerOnly", required = false) String integerOnlyMarker,
                              @RequestParam(required = false) String lookupList,
                              @RequestParam(name = "lookupAcceptsUnlistedCode", required = false) Boolean lookupAcceptsCodeChecked,
                              @RequestParam(name = "_lookupAcceptsUnlistedCode", required = false) String lookupAcceptsCodeMarker,
                              RedirectAttributes ra) {
        if (!FieldDataTypes.isValid(form.getDataType())) {
            ra.addFlashAttribute("errorMessage", FaMessages.fieldDefinitionInvalidDataType(form.getDataType()));
            return "redirect:/forms/" + id + "/fields";
        }
        Map<String, Object> validation = FieldValidationSupport.build(
                form.getDataType(), selectOptions, warningMin, warningMax, dangerMin, dangerMax,
                warningOptions, dangerOptions, scanSources,
                FieldValidationSupport.scanManualFromForm(scanManualMarker, scanManualChecked),
                null,
                FieldValidationSupport.FieldBehaviour.fromForm(
                        allowCustomMarker, allowCustomChecked, signatureScope,
                        FieldValidationSupport.EntryLimit.fromForm(
                                entryMin, entryMax, integerOnlyMarker, integerOnlyChecked),
                        lookupListService.listCodeForField(form.getDataType(), lookupList),
                        lookupAcceptsCodeMarker, lookupAcceptsCodeChecked));
        formFieldService.update(id, fieldId, form.getKey(), form.getLabel(), form.getDataType(),
                form.getUnit(), form.isRequired(), validation, form.getOrder());
        ra.addFlashAttribute("successMessage", "فیلد فرم ذخیره شد.");
        return "redirect:/forms/" + id + "/fields";
    }

    @PostMapping("/{id}/fields/{fieldId}/state")
    @PreAuthorize("hasAuthority('POST:/forms/{id}/fields/{fieldId}/state')")
    public String setState(@PathVariable Long id,
                           @PathVariable Long fieldId,
                           @RequestParam boolean disabled,
                           RedirectAttributes ra) {
        formFieldService.setDeleted(id, fieldId, disabled);
        ra.addFlashAttribute("successMessage", disabled ? "فیلد غیرفعال شد." : "فیلد فعال شد.");
        return "redirect:/forms/" + id + "/fields";
    }

    @PostMapping("/{id}/fields/{fieldId}/delete")
    @PreAuthorize("hasAuthority('POST:/forms/{id}/fields/{fieldId}/delete')")
    public String deleteField(@PathVariable Long id,
                              @PathVariable Long fieldId,
                              RedirectAttributes ra) {
        formFieldService.hardDelete(id, fieldId);
        ra.addFlashAttribute("successMessage", "فیلد حذف شد.");
        return "redirect:/forms/" + id + "/fields";
    }
}
