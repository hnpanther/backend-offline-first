package com.hnp.backendofflinefirst.web;

import com.hnp.backendofflinefirst.entity.LogSheetTemplate;
import com.hnp.backendofflinefirst.repository.LocationRepository;
import com.hnp.backendofflinefirst.repository.LogSheetTemplateRepository;
import com.hnp.backendofflinefirst.repository.MainFunctionRepository;
import com.hnp.backendofflinefirst.repository.PlantSystemRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.UUID;

@Controller
@RequestMapping("/log-sheet-templates")
@RequiredArgsConstructor
public class LogSheetTemplateWebController {

    private final LogSheetTemplateRepository logSheetTemplateRepository;
    private final LocationRepository locationRepository;
    private final PlantSystemRepository plantSystemRepository;
    private final MainFunctionRepository mainFunctionRepository;

    @GetMapping
    @PreAuthorize("hasAuthority('GET:/log-sheet-templates')")
    public String list(@RequestParam(required = false) String editId, Model model) {
        model.addAttribute("activePage", "log-sheet-templates");
        model.addAttribute("templates", logSheetTemplateRepository.findAll());
        model.addAttribute("locations", locationRepository.findAll());
        model.addAttribute("plantSystems", plantSystemRepository.findAll());
        model.addAttribute("mainFunctions", mainFunctionRepository.findAll());
        if (editId != null) {
            logSheetTemplateRepository.findById(editId).ifPresent(e -> model.addAttribute("editEntity", e));
        }
        return "log-sheet-templates";
    }

    @PostMapping
    @PreAuthorize("hasAuthority('POST:/log-sheet-templates')")
    public String create(@ModelAttribute LogSheetTemplate form, RedirectAttributes ra) {
        long now = System.currentTimeMillis();
        form.setId(UUID.randomUUID().toString());
        form.setCreatedAt(now);
        form.setUpdatedAt(now);
        if ("".equals(form.getDescription())) form.setDescription(null);
        logSheetTemplateRepository.save(form);
        ra.addFlashAttribute("successMessage", "قالب لاگ شیت با موفقیت ایجاد شد.");
        return "redirect:/log-sheet-templates";
    }

    @PostMapping("/{id}")
    @PreAuthorize("hasAuthority('POST:/log-sheet-templates/{id}')")
    public String update(@PathVariable String id, @ModelAttribute LogSheetTemplate form, RedirectAttributes ra) {
        logSheetTemplateRepository.findById(id).ifPresent(e -> {
            e.setName(form.getName());
            e.setDescription("".equals(form.getDescription()) ? null : form.getDescription());
            e.setScopeType(form.getScopeType());
            e.setScopeId(form.getScopeId());
            e.setUpdatedAt(System.currentTimeMillis());
            logSheetTemplateRepository.save(e);
        });
        ra.addFlashAttribute("successMessage", "قالب لاگ شیت با موفقیت ویرایش شد.");
        return "redirect:/log-sheet-templates";
    }

    @PostMapping("/{id}/delete")
    @PreAuthorize("hasAuthority('POST:/log-sheet-templates/{id}/delete')")
    public String delete(@PathVariable String id, RedirectAttributes ra) {
        logSheetTemplateRepository.deleteById(id);
        ra.addFlashAttribute("successMessage", "قالب لاگ شیت با موفقیت حذف شد.");
        return "redirect:/log-sheet-templates";
    }
}
