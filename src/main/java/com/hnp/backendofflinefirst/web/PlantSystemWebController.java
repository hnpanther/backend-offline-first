package com.hnp.backendofflinefirst.web;

import com.hnp.backendofflinefirst.dto.ImportResult;
import com.hnp.backendofflinefirst.entity.PlantSystem;
import com.hnp.backendofflinefirst.repository.LocationRepository;
import com.hnp.backendofflinefirst.repository.PlantSystemRepository;
import com.hnp.backendofflinefirst.service.ExcelImportService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.IOException;
import java.util.UUID;

@Controller
@RequestMapping("/plant-systems")
@RequiredArgsConstructor
public class PlantSystemWebController {

    private final PlantSystemRepository plantSystemRepository;
    private final LocationRepository locationRepository;
    private final ExcelImportService excelImportService;

    @GetMapping
    public String list(@RequestParam(required = false) String editId, Model model) {
        model.addAttribute("activePage", "plant-systems");
        model.addAttribute("plantSystems", plantSystemRepository.findAll());
        model.addAttribute("locations", locationRepository.findAll());
        if (editId != null) {
            plantSystemRepository.findById(editId).ifPresent(e -> model.addAttribute("editEntity", e));
        }
        return "plant-systems";
    }

    @PostMapping
    public String create(@ModelAttribute PlantSystem form, RedirectAttributes ra) {
        long now = System.currentTimeMillis();
        form.setId(UUID.randomUUID().toString());
        form.setCreatedAt(now);
        form.setUpdatedAt(now);
        if ("".equals(form.getLocationId())) form.setLocationId(null);
        plantSystemRepository.save(form);
        ra.addFlashAttribute("successMessage", "سیستم واحد با موفقیت ایجاد شد.");
        return "redirect:/plant-systems";
    }

    @PostMapping("/{id}")
    public String update(@PathVariable String id, @ModelAttribute PlantSystem form, RedirectAttributes ra) {
        plantSystemRepository.findById(id).ifPresent(e -> {
            e.setCode(form.getCode());
            e.setName(form.getName());
            e.setLocationId("".equals(form.getLocationId()) ? null : form.getLocationId());
            e.setUpdatedAt(System.currentTimeMillis());
            plantSystemRepository.save(e);
        });
        ra.addFlashAttribute("successMessage", "سیستم واحد با موفقیت ویرایش شد.");
        return "redirect:/plant-systems";
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable String id, RedirectAttributes ra) {
        plantSystemRepository.deleteById(id);
        ra.addFlashAttribute("successMessage", "سیستم واحد با موفقیت حذف شد.");
        return "redirect:/plant-systems";
    }

    @PostMapping("/import")
    public String importExcel(@RequestParam("file") MultipartFile file, RedirectAttributes ra) {
        try {
            ImportResult result = excelImportService.importPlantSystems(file);
            ra.addFlashAttribute("successMessage", result.summary());
            if (result.hasErrors()) ra.addFlashAttribute("importErrors", result.getErrors());
        } catch (Exception e) {
            ra.addFlashAttribute("errorMessage", "خطا در پردازش فایل: " + e.getMessage());
        }
        return "redirect:/plant-systems";
    }

    @GetMapping("/import-template")
    public void downloadTemplate(HttpServletResponse response) throws IOException {
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setHeader("Content-Disposition", "attachment; filename=\"plant-systems-template.xlsx\"");
        try (var wb = new XSSFWorkbook()) {
            var sheet = wb.createSheet("plant-systems");
            var header = sheet.createRow(0);
            String[] cols = {"code", "name", "locationCode", "locationName"};
            for (int i = 0; i < cols.length; i++) header.createCell(i).setCellValue(cols[i]);
            wb.write(response.getOutputStream());
        }
    }
}
