package com.hnp.backendofflinefirst.web;

import com.hnp.backendofflinefirst.domain.FieldDefinitionMaps;
import com.hnp.backendofflinefirst.domain.FieldValidationSupport;
import com.hnp.backendofflinefirst.entity.AssetClass;
import com.hnp.backendofflinefirst.entity.FieldDefinition;
import com.hnp.backendofflinefirst.repository.AssetClassRepository;
import com.hnp.backendofflinefirst.repository.FieldDefinitionRepository;
import com.hnp.backendofflinefirst.service.ExcelExportService;
import com.hnp.backendofflinefirst.service.MasterDataUniquenessValidator;
import com.hnp.backendofflinefirst.ui.FaMessages;
import com.hnp.backendofflinefirst.ui.WebListSupport;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Controller
@RequestMapping("/asset-classes")
@RequiredArgsConstructor
public class AssetClassWebController {

    private final AssetClassRepository assetClassRepository;
    private final FieldDefinitionRepository fieldDefinitionRepository;
    private final ExcelExportService excelExportService;
    private final MasterDataUniquenessValidator uniquenessValidator;

    @GetMapping
    @PreAuthorize("hasAuthority('GET:/asset-classes')")
    public String list(@RequestParam(required = false) Long editId,
                       @RequestParam(required = false) String q,
                       @RequestParam(defaultValue = "0") int page,
                       @RequestParam(required = false) Integer size,
                       Model model) {
        int pageSize = size != null ? size : WebListSupport.DEFAULT_SIZE;
        Pageable pageable = WebListSupport.pageable(page, pageSize);
        var result = WebListSupport.pagedList(q, pageable,
                assetClassRepository::findAll,
                assetClassRepository::search);
        model.addAttribute("activePage", "asset-classes");
        model.addAttribute("assetClasses", result.getContent());
        WebListSupport.addPagination(model, result, q, page, pageSize);
        if (editId != null) {
            assetClassRepository.findById(editId).ifPresent(e -> model.addAttribute("editEntity", e));
        }
        return "asset-classes";
    }

    @PostMapping
    @PreAuthorize("hasAuthority('POST:/asset-classes')")
    public String create(@ModelAttribute AssetClass form, RedirectAttributes ra) {
        String name = form.getName() == null ? null : form.getName().trim();
        form.setName(name);
        uniquenessValidator.validateAssetClass(null, name);
        long now = System.currentTimeMillis();
        form.setCreatedAt(now);
        form.setUpdatedAt(now);
        assetClassRepository.save(form);
        ra.addFlashAttribute("successMessage", FaMessages.assetClassCreated());
        return "redirect:/asset-classes";
    }

    @PostMapping("/{id}")
    @PreAuthorize("hasAuthority('POST:/asset-classes/{id}')")
    public String update(@PathVariable Long id, @ModelAttribute AssetClass form, RedirectAttributes ra) {
        assetClassRepository.findById(id).ifPresent(e -> {
            String name = form.getName() == null ? null : form.getName().trim();
            uniquenessValidator.validateAssetClass(id, name);
            e.setName(name);
            e.setUpdatedAt(System.currentTimeMillis());
            assetClassRepository.save(e);
        });
        ra.addFlashAttribute("successMessage", FaMessages.assetClassUpdated());
        return "redirect:/asset-classes";
    }

    @PostMapping("/{id}/delete")
    @PreAuthorize("hasAuthority('POST:/asset-classes/{id}/delete')")
    public String delete(@PathVariable Long id, RedirectAttributes ra) {
        assetClassRepository.deleteById(id);
        ra.addFlashAttribute("successMessage", FaMessages.assetClassDeleted());
        return "redirect:/asset-classes";
    }

    @GetMapping("/export")
    @PreAuthorize("hasAuthority('GET:/asset-classes')")
    public void export(HttpServletResponse response) throws IOException {
        excelExportService.exportAssetClasses(response);
    }

    @GetMapping("/{classId}/fields")
    @PreAuthorize("hasAuthority('GET:/asset-classes/{classId}/fields')")
    public String fields(@PathVariable Long classId,
                         @RequestParam(required = false) Long editId,
                         @RequestParam(required = false) String q,
                         @RequestParam(defaultValue = "0") int page,
                         @RequestParam(required = false) Integer size,
                         Model model) {
        int pageSize = size != null ? size : WebListSupport.DEFAULT_SIZE;
        Pageable pageable = WebListSupport.pageable(page, pageSize);
        var result = WebListSupport.pagedList(q, pageable,
                p -> fieldDefinitionRepository.findByClassId(classId, p),
                (term, p) -> fieldDefinitionRepository.searchByClassId(classId, term, p));
        model.addAttribute("activePage", "asset-classes");
        assetClassRepository.findById(classId).ifPresent(c -> model.addAttribute("assetClass", c));
        model.addAttribute("fieldDefs", result.getContent());
        WebListSupport.addPagination(model, result, q, page, pageSize);
        if (editId != null) {
            fieldDefinitionRepository.findById(editId).ifPresent(e -> {
                model.addAttribute("editEntity", e);
                model.addAttribute("editSelectOptions", formatSelectOptions(e.getValidation()));
            });
        }
        return "field-definitions";
    }

    @GetMapping("/{classId}/fields/export")
    @PreAuthorize("hasAuthority('GET:/asset-classes/{classId}/fields')")
    public void exportFields(@PathVariable Long classId, HttpServletResponse response) throws IOException {
        excelExportService.exportFieldDefinitions(classId, response);
    }

    @PostMapping("/{classId}/fields")
    @PreAuthorize("hasAuthority('POST:/asset-classes/{classId}/fields')")
    public String addField(@PathVariable Long classId,
                           @ModelAttribute FieldDefinition form,
                           @RequestParam(required = false) String selectOptions,
                           @RequestParam(required = false) Double warningMin,
                           @RequestParam(required = false) Double warningMax,
                           @RequestParam(required = false) Double dangerMin,
                           @RequestParam(required = false) Double dangerMax,
                           RedirectAttributes ra) {
        long now = System.currentTimeMillis();
        form.setClassId(classId);
        String key = form.getKey() == null ? null : form.getKey().trim();
        form.setKey(key);
        uniquenessValidator.validateFieldDefinition(null, classId, key);
        form.setVersion(1);
        form.setDeleted(false);
        form.setSynced(false);
        form.setCreatedAt(now);
        form.setUpdatedAt(now);
        form.setValidation(FieldValidationSupport.build(
                form.getDataType(), selectOptions, warningMin, warningMax, dangerMin, dangerMax));
        fieldDefinitionRepository.save(form);
        syncEmbeddedClassFields(classId);
        ra.addFlashAttribute("successMessage", FaMessages.fieldDefinitionCreated());
        return "redirect:/asset-classes/" + classId + "/fields";
    }

    @PostMapping("/{classId}/fields/{fieldId}")
    @PreAuthorize("hasAuthority('POST:/asset-classes/{classId}/fields/{fieldId}')")
    public String updateField(@PathVariable Long classId,
                              @PathVariable Long fieldId,
                              @ModelAttribute FieldDefinition form,
                              @RequestParam(required = false) String selectOptions,
                              @RequestParam(required = false) Double warningMin,
                              @RequestParam(required = false) Double warningMax,
                              @RequestParam(required = false) Double dangerMin,
                              @RequestParam(required = false) Double dangerMax,
                              RedirectAttributes ra) {
        fieldDefinitionRepository.findById(fieldId).ifPresent(e -> {
            String key = form.getKey() == null ? null : form.getKey().trim();
            uniquenessValidator.validateFieldDefinition(fieldId, classId, key);
            e.setKey(key);
            e.setLabel(form.getLabel());
            e.setDataType(form.getDataType());
            e.setUnit(form.getUnit());
            e.setRequired(form.isRequired());
            e.setOrder(form.getOrder());
            e.setValidation(FieldValidationSupport.build(
                    form.getDataType(), selectOptions, warningMin, warningMax, dangerMin, dangerMax));
            e.setVersion(e.getVersion() == null ? 1 : e.getVersion() + 1);
            e.setUpdatedAt(System.currentTimeMillis());
            fieldDefinitionRepository.save(e);
        });
        syncEmbeddedClassFields(classId);
        ra.addFlashAttribute("successMessage", FaMessages.fieldDefinitionUpdated());
        return "redirect:/asset-classes/" + classId + "/fields";
    }

    @PostMapping("/{classId}/fields/{fieldId}/delete")
    @PreAuthorize("hasAuthority('POST:/asset-classes/{classId}/fields/{fieldId}/delete')")
    public String deleteField(@PathVariable Long classId,
                              @PathVariable Long fieldId,
                              RedirectAttributes ra) {
        fieldDefinitionRepository.deleteById(fieldId);
        syncEmbeddedClassFields(classId);
        ra.addFlashAttribute("successMessage", FaMessages.fieldDefinitionDeleted());
        return "redirect:/asset-classes/" + classId + "/fields";
    }

    /** Keep legacy {@code asset_classes.fields} JSON aligned with {@code field_definitions}. */
    private void syncEmbeddedClassFields(Long classId) {
        assetClassRepository.findById(classId).ifPresent(assetClass -> {
            List<FieldDefinition> defs = fieldDefinitionRepository.findByClassId(classId).stream()
                    .filter(field -> !field.isDeleted())
                    .sorted(Comparator
                            .comparing((FieldDefinition f) -> f.getOrder() != null ? f.getOrder() : Integer.MAX_VALUE)
                            .thenComparing(FieldDefinition::getId, Comparator.nullsLast(Comparator.naturalOrder())))
                    .toList();
            assetClass.setFields(FieldDefinitionMaps.toEmbeddedFields(defs));
            assetClass.setUpdatedAt(System.currentTimeMillis());
            assetClassRepository.save(assetClass);
        });
    }

    @SuppressWarnings("unchecked")
    private static String formatSelectOptions(Map<String, Object> validation) {
        if (validation == null || !validation.containsKey(FieldValidationSupport.KEY_OPTIONS)) return "";
        Object opts = validation.get(FieldValidationSupport.KEY_OPTIONS);
        if (opts instanceof List<?> list) {
            return list.stream().map(String::valueOf).reduce((a, b) -> a + "\n" + b).orElse("");
        }
        return "";
    }
}
