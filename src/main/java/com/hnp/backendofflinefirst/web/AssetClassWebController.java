package com.hnp.backendofflinefirst.web;

import com.hnp.backendofflinefirst.entity.AssetClass;
import com.hnp.backendofflinefirst.entity.FieldDefinition;
import com.hnp.backendofflinefirst.repository.AssetClassRepository;
import com.hnp.backendofflinefirst.repository.FieldDefinitionRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hnp.backendofflinefirst.service.ExcelExportService;
import com.hnp.backendofflinefirst.ui.FaMessages;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.*;

@Controller
@RequestMapping("/asset-classes")
@RequiredArgsConstructor
public class AssetClassWebController {

    private final AssetClassRepository assetClassRepository;
    private final FieldDefinitionRepository fieldDefinitionRepository;
    private final ObjectMapper objectMapper;
    private final ExcelExportService excelExportService;

    @GetMapping
    @PreAuthorize("hasAuthority('GET:/asset-classes')")
    public String list(@RequestParam(required = false) Long editId, Model model) {
        model.addAttribute("activePage", "asset-classes");
        model.addAttribute("assetClasses", assetClassRepository.findAllByOrderByIdDesc());
        if (editId != null) {
            assetClassRepository.findById(editId).ifPresent(e -> model.addAttribute("editEntity", e));
        }
        return "asset-classes";
    }

    @PostMapping
    @PreAuthorize("hasAuthority('POST:/asset-classes')")
    public String create(@ModelAttribute AssetClass form, RedirectAttributes ra) {
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
            e.setName(form.getName());
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
                         Model model) {
        model.addAttribute("activePage", "asset-classes");
        assetClassRepository.findById(classId).ifPresent(c -> model.addAttribute("assetClass", c));
        model.addAttribute("fieldDefs", fieldDefinitionRepository.findByClassIdOrderByIdDesc(classId));
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
                           @RequestParam(required = false) String validationJson,
                           @RequestParam(required = false) String selectOptions,
                           RedirectAttributes ra) {
        long now = System.currentTimeMillis();
        form.setClassId(classId);
        form.setVersion(1);
        form.setDeleted(false);
        form.setSynced(false);
        form.setCreatedAt(now);
        form.setUpdatedAt(now);
        form.setValidation(buildValidation(validationJson, selectOptions, form.getDataType()));
        fieldDefinitionRepository.save(form);
        ra.addFlashAttribute("successMessage", FaMessages.fieldDefinitionCreated());
        return "redirect:/asset-classes/" + classId + "/fields";
    }

    @PostMapping("/{classId}/fields/{fieldId}")
    @PreAuthorize("hasAuthority('POST:/asset-classes/{classId}/fields/{fieldId}')")
    public String updateField(@PathVariable Long classId,
                              @PathVariable Long fieldId,
                              @ModelAttribute FieldDefinition form,
                              @RequestParam(required = false) String validationJson,
                              @RequestParam(required = false) String selectOptions,
                              RedirectAttributes ra) {
        fieldDefinitionRepository.findById(fieldId).ifPresent(e -> {
            e.setKey(form.getKey());
            e.setLabel(form.getLabel());
            e.setDataType(form.getDataType());
            e.setUnit(form.getUnit());
            e.setRequired(form.isRequired());
            e.setOrder(form.getOrder());
            e.setValidation(buildValidation(validationJson, selectOptions, form.getDataType()));
            e.setVersion(e.getVersion() == null ? 1 : e.getVersion() + 1);
            e.setUpdatedAt(System.currentTimeMillis());
            fieldDefinitionRepository.save(e);
        });
        ra.addFlashAttribute("successMessage", FaMessages.fieldDefinitionUpdated());
        return "redirect:/asset-classes/" + classId + "/fields";
    }

    @PostMapping("/{classId}/fields/{fieldId}/delete")
    @PreAuthorize("hasAuthority('POST:/asset-classes/{classId}/fields/{fieldId}/delete')")
    public String deleteField(@PathVariable Long classId,
                              @PathVariable Long fieldId,
                              RedirectAttributes ra) {
        fieldDefinitionRepository.deleteById(fieldId);
        ra.addFlashAttribute("successMessage", FaMessages.fieldDefinitionDeleted());
        return "redirect:/asset-classes/" + classId + "/fields";
    }

    private Map<String, Object> buildValidation(String validationJson, String selectOptions, String dataType) {
        Map<String, Object> validation = parseJson(validationJson);
        if (validation == null) {
            validation = new LinkedHashMap<>();
        }
        if (isSelectType(dataType) && selectOptions != null && !selectOptions.isBlank()) {
            List<String> options = Arrays.stream(selectOptions.split("[,\n،]"))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .toList();
            if (!options.isEmpty()) {
                validation.put("options", options);
            }
        }
        return validation.isEmpty() ? null : validation;
    }

    private static boolean isSelectType(String dataType) {
        return "select".equals(dataType) || "multiselect".equals(dataType);
    }

    @SuppressWarnings("unchecked")
    private static String formatSelectOptions(Map<String, Object> validation) {
        if (validation == null || !validation.containsKey("options")) return "";
        Object opts = validation.get("options");
        if (opts instanceof List<?> list) {
            return list.stream().map(String::valueOf).reduce((a, b) -> a + "\n" + b).orElse("");
        }
        return "";
    }

    private Map<String, Object> parseJson(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            return null;
        }
    }
}
