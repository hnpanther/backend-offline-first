package com.hnp.backendofflinefirst.web;

import com.hnp.backendofflinefirst.dto.ImportResult;
import com.hnp.backendofflinefirst.entity.SubFunction;
import com.hnp.backendofflinefirst.repository.LocationRepository;
import com.hnp.backendofflinefirst.repository.MainFunctionRepository;
import com.hnp.backendofflinefirst.repository.PlantSystemRepository;
import com.hnp.backendofflinefirst.repository.SubFunctionRepository;
import com.hnp.backendofflinefirst.service.AssetHierarchyService;
import com.hnp.backendofflinefirst.service.ExcelImportService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.IOException;

@Controller
@RequestMapping("/sub-functions")
@RequiredArgsConstructor
public class SubFunctionWebController {

    private final SubFunctionRepository subFunctionRepository;
    private final MainFunctionRepository mainFunctionRepository;
    private final PlantSystemRepository plantSystemRepository;
    private final LocationRepository locationRepository;
    private final AssetHierarchyService hierarchyService;
    private final ExcelImportService excelImportService;

    @GetMapping
    @PreAuthorize("hasAuthority('GET:/sub-functions')")
    public String list(@RequestParam(required = false) Long editId, Model model) {
        model.addAttribute("activePage", "sub-functions");
        model.addAttribute("subFunctions", subFunctionRepository.findAll());
        model.addAttribute("mainFunctions", mainFunctionRepository.findAll());
        model.addAttribute("plantSystems", plantSystemRepository.findAll());
        model.addAttribute("locations", locationRepository.findAll());
        if (editId != null) {
            subFunctionRepository.findById(editId).ifPresent(e -> model.addAttribute("editEntity", e));
        }
        return "sub-functions";
    }

    @PostMapping
    @PreAuthorize("hasAuthority('POST:/sub-functions')")
    public String create(@ModelAttribute SubFunction form,
                         @RequestParam(required = false) String parentRef, RedirectAttributes ra) {
        long now = System.currentTimeMillis();
        form.setCreatedAt(now);
        form.setUpdatedAt(now);
        applyParent(form, parentRef);
        subFunctionRepository.save(form);
        ra.addFlashAttribute("successMessage", "تابع فرعی با موفقیت ایجاد شد.");
        return "redirect:/sub-functions";
    }

    @PostMapping("/{id}")
    @PreAuthorize("hasAuthority('POST:/sub-functions/{id}')")
    public String update(@PathVariable Long id, @ModelAttribute SubFunction form,
                        @RequestParam(required = false) String parentRef, RedirectAttributes ra) {
        subFunctionRepository.findById(id).ifPresent(e -> {
            e.setCode(form.getCode());
            e.setName(form.getName());
            e.setTag(form.getTag());
            applyParent(e, parentRef);
            e.setUpdatedAt(System.currentTimeMillis());
            subFunctionRepository.save(e);
        });
        ra.addFlashAttribute("successMessage", "تابع فرعی با موفقیت ویرایش شد.");
        return "redirect:/sub-functions";
    }

    /** parentRef is "type:id" (mainFunction|system|location); fills the ancestry chain. */
    private void applyParent(SubFunction sf, String parentRef) {
        String type = null;
        Long id = null;
        if (parentRef != null && parentRef.contains(":")) {
            int i = parentRef.indexOf(':');
            type = parentRef.substring(0, i);
            String idStr = parentRef.substring(i + 1);
            if (!idStr.isBlank()) id = Long.valueOf(idStr);
        }
        hierarchyService.applySubFunctionParent(sf, type, id);
    }

    @PostMapping("/{id}/delete")
    @PreAuthorize("hasAuthority('POST:/sub-functions/{id}/delete')")
    public String delete(@PathVariable Long id, RedirectAttributes ra) {
        subFunctionRepository.deleteById(id);
        ra.addFlashAttribute("successMessage", "تابع فرعی با موفقیت حذف شد.");
        return "redirect:/sub-functions";
    }

    @PostMapping("/import")
    @PreAuthorize("hasAuthority('POST:/sub-functions/import')")
    public String importExcel(@RequestParam("file") MultipartFile file, RedirectAttributes ra) {
        try {
            ImportResult result = excelImportService.importSubFunctions(file);
            ra.addFlashAttribute("successMessage", result.summary());
            if (result.hasErrors()) ra.addFlashAttribute("importErrors", result.getErrors());
        } catch (Exception e) {
            ra.addFlashAttribute("errorMessage", "خطا در پردازش فایل: " + e.getMessage());
        }
        return "redirect:/sub-functions";
    }

    @GetMapping("/import-template")
    @PreAuthorize("hasAuthority('GET:/sub-functions/import-template')")
    public void downloadTemplate(HttpServletResponse response) throws IOException {
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setHeader("Content-Disposition", "attachment; filename=\"sub-functions-template.xlsx\"");
        try (var wb = new XSSFWorkbook()) {
            var sheet = wb.createSheet("sub-functions");
            var header = sheet.createRow(0);
            String[] cols = {"code", "name", "tag", "mainFunctionCode", "mainFunctionName", "systemCode", "systemName", "locationCode", "locationName"};
            for (int i = 0; i < cols.length; i++) header.createCell(i).setCellValue(cols[i]);
            wb.write(response.getOutputStream());
        }
    }
}
