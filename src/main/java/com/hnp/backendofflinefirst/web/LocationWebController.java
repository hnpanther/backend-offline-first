package com.hnp.backendofflinefirst.web;

import com.hnp.backendofflinefirst.dto.ImportResult;
import com.hnp.backendofflinefirst.entity.Location;
import com.hnp.backendofflinefirst.entity.OperationalUnit;
import com.hnp.backendofflinefirst.repository.LocationRepository;
import com.hnp.backendofflinefirst.repository.OperationalUnitRepository;
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
import java.util.Map;
import java.util.stream.Collectors;

/** Web CRUD for locations — each action guarded by a separate permission. */
@Controller
@RequestMapping("/locations")
@RequiredArgsConstructor
public class LocationWebController {

    private final LocationRepository locationRepository;
    private final OperationalUnitRepository operationalUnitRepository;
    private final ExcelImportService excelImportService;

    @GetMapping
    @PreAuthorize("hasAuthority('GET:/locations')")
    public String list(@RequestParam(required = false) Long editId, Model model) {
        model.addAttribute("activePage", "locations");
        model.addAttribute("locations", locationRepository.findAll());
        model.addAttribute("operationalUnits", operationalUnitRepository.findAll());
        model.addAttribute("unitNameById", buildUnitNameMap());
        if (editId != null) {
            locationRepository.findById(editId).ifPresent(e -> model.addAttribute("editEntity", e));
        }
        return "locations";
    }

    @PostMapping
    @PreAuthorize("hasAuthority('POST:/locations')")
    public String create(@ModelAttribute Location location, RedirectAttributes ra) {
        long now = System.currentTimeMillis();
        location.setCreatedAt(now);
        location.setUpdatedAt(now);
        locationRepository.save(location);
        ra.addFlashAttribute("successMessage", "مکان با موفقیت ایجاد شد.");
        return "redirect:/locations";
    }

    @PostMapping("/{id}")
    @PreAuthorize("hasAuthority('POST:/locations/{id}')")
    public String update(@PathVariable Long id, @ModelAttribute Location form, RedirectAttributes ra) {
        locationRepository.findById(id).ifPresent(e -> {
            e.setCode(form.getCode());
            e.setName(form.getName());
            e.setParentId(form.getParentId());
            e.setUnitId(form.getUnitId());
            e.setUpdatedAt(System.currentTimeMillis());
            locationRepository.save(e);
        });
        ra.addFlashAttribute("successMessage", "مکان با موفقیت ویرایش شد.");
        return "redirect:/locations";
    }

    @PostMapping("/{id}/delete")
    @PreAuthorize("hasAuthority('POST:/locations/{id}/delete')")
    public String delete(@PathVariable Long id, RedirectAttributes ra) {
        locationRepository.deleteById(id);
        ra.addFlashAttribute("successMessage", "مکان با موفقیت حذف شد.");
        return "redirect:/locations";
    }

    @PostMapping("/import")
    @PreAuthorize("hasAuthority('POST:/locations/import')")
    public String importExcel(@RequestParam("file") MultipartFile file, RedirectAttributes ra) {
        try {
            ImportResult result = excelImportService.importLocations(file);
            ra.addFlashAttribute("successMessage", result.summary());
            if (result.hasErrors()) ra.addFlashAttribute("importErrors", result.getErrors());
        } catch (Exception e) {
            ra.addFlashAttribute("errorMessage", "خطا در پردازش فایل: " + e.getMessage());
        }
        return "redirect:/locations";
    }

    @GetMapping("/import-template")
    @PreAuthorize("hasAuthority('GET:/locations/import-template')")
    public void downloadTemplate(HttpServletResponse response) throws IOException {
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setHeader("Content-Disposition", "attachment; filename=\"locations-template.xlsx\"");
        try (var wb = new XSSFWorkbook()) {
            var sheet = wb.createSheet("locations");
            var header = sheet.createRow(0);
            String[] cols = {"code", "name", "parentCode", "parentName", "unitCode", "unitName"};
            for (int i = 0; i < cols.length; i++) header.createCell(i).setCellValue(cols[i]);
            wb.write(response.getOutputStream());
        }
    }

    private Map<Long, String> buildUnitNameMap() {
        return operationalUnitRepository.findAll().stream()
                .collect(Collectors.toMap(OperationalUnit::getId,
                        u -> u.getName() != null ? u.getName() : u.getCode()));
    }
}
