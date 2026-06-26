package com.hnp.backendofflinefirst.web;

import com.hnp.backendofflinefirst.entity.AssetClass;
import com.hnp.backendofflinefirst.entity.FieldDefinition;
import com.hnp.backendofflinefirst.repository.AssetClassRepository;
import com.hnp.backendofflinefirst.repository.FieldDefinitionRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.Map;
import java.util.UUID;

@Controller
@RequestMapping("/asset-classes")
@RequiredArgsConstructor
public class AssetClassWebController {

    private final AssetClassRepository assetClassRepository;
    private final FieldDefinitionRepository fieldDefinitionRepository;
    private final ObjectMapper objectMapper;

    @GetMapping
    public String list(@RequestParam(required = false) String editId, Model model) {
        model.addAttribute("activePage", "asset-classes");
        model.addAttribute("assetClasses", assetClassRepository.findAll());
        if (editId != null) {
            assetClassRepository.findById(editId).ifPresent(e -> model.addAttribute("editEntity", e));
        }
        return "asset-classes";
    }

    @PostMapping
    public String create(@ModelAttribute AssetClass form, RedirectAttributes ra) {
        long now = System.currentTimeMillis();
        form.setId(UUID.randomUUID().toString());
        form.setCreatedAt(now);
        form.setUpdatedAt(now);
        assetClassRepository.save(form);
        ra.addFlashAttribute("successMessage", "کلاس دارایی با موفقیت ایجاد شد.");
        return "redirect:/asset-classes";
    }

    @PostMapping("/{id}")
    public String update(@PathVariable String id, @ModelAttribute AssetClass form, RedirectAttributes ra) {
        assetClassRepository.findById(id).ifPresent(e -> {
            e.setName(form.getName());
            e.setUpdatedAt(System.currentTimeMillis());
            assetClassRepository.save(e);
        });
        ra.addFlashAttribute("successMessage", "کلاس دارایی با موفقیت ویرایش شد.");
        return "redirect:/asset-classes";
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable String id, RedirectAttributes ra) {
        assetClassRepository.deleteById(id);
        ra.addFlashAttribute("successMessage", "کلاس دارایی با موفقیت حذف شد.");
        return "redirect:/asset-classes";
    }

    // --- Field Definitions ---

    @GetMapping("/{classId}/fields")
    public String fields(@PathVariable String classId,
                         @RequestParam(required = false) String editId,
                         Model model) {
        model.addAttribute("activePage", "asset-classes");
        assetClassRepository.findById(classId).ifPresent(c -> model.addAttribute("assetClass", c));
        model.addAttribute("fieldDefs", fieldDefinitionRepository.findByClassId(classId));
        if (editId != null) {
            fieldDefinitionRepository.findById(editId).ifPresent(e -> model.addAttribute("editEntity", e));
        }
        return "field-definitions";
    }

    @PostMapping("/{classId}/fields")
    public String addField(@PathVariable String classId,
                           @ModelAttribute FieldDefinition form,
                           @RequestParam(required = false) String validationJson,
                           RedirectAttributes ra) {
        long now = System.currentTimeMillis();
        form.setId(UUID.randomUUID().toString());
        form.setClassId(classId);
        form.setVersion(1);
        form.setDeleted(false);
        form.setSynced(false);
        form.setCreatedAt(now);
        form.setUpdatedAt(now);
        form.setValidation(parseJson(validationJson));
        fieldDefinitionRepository.save(form);
        ra.addFlashAttribute("successMessage", "فیلد با موفقیت افزوده شد.");
        return "redirect:/asset-classes/" + classId + "/fields";
    }

    @PostMapping("/{classId}/fields/{fieldId}")
    public String updateField(@PathVariable String classId,
                              @PathVariable String fieldId,
                              @ModelAttribute FieldDefinition form,
                              @RequestParam(required = false) String validationJson,
                              RedirectAttributes ra) {
        fieldDefinitionRepository.findById(fieldId).ifPresent(e -> {
            e.setKey(form.getKey());
            e.setLabel(form.getLabel());
            e.setDataType(form.getDataType());
            e.setUnit(form.getUnit());
            e.setRequired(form.isRequired());
            e.setOrder(form.getOrder());
            e.setValidation(parseJson(validationJson));
            e.setVersion(e.getVersion() == null ? 1 : e.getVersion() + 1);
            e.setUpdatedAt(System.currentTimeMillis());
            fieldDefinitionRepository.save(e);
        });
        ra.addFlashAttribute("successMessage", "فیلد با موفقیت ویرایش شد.");
        return "redirect:/asset-classes/" + classId + "/fields";
    }

    @PostMapping("/{classId}/fields/{fieldId}/delete")
    public String deleteField(@PathVariable String classId,
                              @PathVariable String fieldId,
                              RedirectAttributes ra) {
        fieldDefinitionRepository.deleteById(fieldId);
        ra.addFlashAttribute("successMessage", "فیلد با موفقیت حذف شد.");
        return "redirect:/asset-classes/" + classId + "/fields";
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
