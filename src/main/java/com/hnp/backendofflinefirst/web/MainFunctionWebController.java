package com.hnp.backendofflinefirst.web;

import com.hnp.backendofflinefirst.dto.ImportResult;
import com.hnp.backendofflinefirst.entity.MainFunction;
import com.hnp.backendofflinefirst.repository.LocationRepository;
import com.hnp.backendofflinefirst.repository.MainFunctionRepository;
import com.hnp.backendofflinefirst.repository.PlantSystemRepository;
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
import java.util.UUID;

@Controller
@RequestMapping("/main-functions")
@RequiredArgsConstructor
public class MainFunctionWebController {

    private final MainFunctionRepository mainFunctionRepository;
    private final PlantSystemRepository plantSystemRepository;
    private final LocationRepository locationRepository;
    private final ExcelImportService excelImportService;

    @GetMapping
    @PreAuthorize("hasAuthority('GET:/main-functions')")
    public String list(@RequestParam(required = false) String editId, Model model) {
        model.addAttribute("activePage", "main-functions");
        model.addAttribute("mainFunctions", mainFunctionRepository.findAll());
        model.addAttribute("plantSystems", plantSystemRepository.findAll());
        model.addAttribute("locations", locationRepository.findAll());
        if (editId != null) {
            mainFunctionRepository.findById(editId).ifPresent(e -> model.addAttribute("editEntity", e));
        }
        return "main-functions";
    }

    @PostMapping
    @PreAuthorize("hasAuthority('POST:/main-functions')")
    public String create(@ModelAttribute MainFunction form, RedirectAttributes ra) {
        long now = System.currentTimeMillis();
        form.setId(UUID.randomUUID().toString());
        form.setCreatedAt(now);
        form.setUpdatedAt(now);
        if ("".equals(form.getSystemId())) form.setSystemId(null);
        if ("".equals(form.getLocationId())) form.setLocationId(null);
        mainFunctionRepository.save(form);
        ra.addFlashAttribute("successMessage", "تابع اصلی با موفقیت ایجاد شد.");
        return "redirect:/main-functions";
    }

    @PostMapping("/{id}")
    @PreAuthorize("hasAuthority('POST:/main-functions/{id}')")
    public String update(@PathVariable String id, @ModelAttribute MainFunction form, RedirectAttributes ra) {
        mainFunctionRepository.findById(id).ifPresent(e -> {
            e.setCode(form.getCode());
            e.setName(form.getName());
            e.setSystemId("".equals(form.getSystemId()) ? null : form.getSystemId());
            e.setLocationId("".equals(form.getLocationId()) ? null : form.getLocationId());
            e.setUpdatedAt(System.currentTimeMillis());
            mainFunctionRepository.save(e);
        });
        ra.addFlashAttribute("successMessage", "تابع اصلی با موفقیت ویرایش شد.");
        return "redirect:/main-functions";
    }

    @PostMapping("/{id}/delete")
    @PreAuthorize("hasAuthority('POST:/main-functions/{id}/delete')")
    public String delete(@PathVariable String id, RedirectAttributes ra) {
        mainFunctionRepository.deleteById(id);
        ra.addFlashAttribute("successMessage", "تابع اصلی با موفقیت حذف شد.");
        return "redirect:/main-functions";
    }

    @PostMapping("/import")
    @PreAuthorize("hasAuthority('POST:/main-functions/import')")
    public String importExcel(@RequestParam("file") MultipartFile file, RedirectAttributes ra) {
        try {
            ImportResult result = excelImportService.importMainFunctions(file);
            ra.addFlashAttribute("successMessage", result.summary());
            if (result.hasErrors()) ra.addFlashAttribute("importErrors", result.getErrors());
        } catch (Exception e) {
            ra.addFlashAttribute("errorMessage", "خطا در پردازش فایل: " + e.getMessage());
        }
        return "redirect:/main-functions";
    }

    @GetMapping("/import-template")
    @PreAuthorize("hasAuthority('GET:/main-functions/import-template')")
    public void downloadTemplate(HttpServletResponse response) throws IOException {
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setHeader("Content-Disposition", "attachment; filename=\"main-functions-template.xlsx\"");
        try (var wb = new XSSFWorkbook()) {
            var sheet = wb.createSheet("main-functions");
            var header = sheet.createRow(0);
            String[] cols = {"code", "name", "systemCode", "systemName", "locationCode", "locationName"};
            for (int i = 0; i < cols.length; i++) header.createCell(i).setCellValue(cols[i]);
            wb.write(response.getOutputStream());
        }
    }
}
