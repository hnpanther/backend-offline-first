package com.hnp.backendofflinefirst.web;

import com.hnp.backendofflinefirst.dto.ImportResult;
import com.hnp.backendofflinefirst.entity.PlantSystem;
import com.hnp.backendofflinefirst.repository.LocationRepository;
import com.hnp.backendofflinefirst.repository.PlantSystemRepository;
import com.hnp.backendofflinefirst.service.AssetHierarchyService;
import com.hnp.backendofflinefirst.service.ExcelExportService;
import com.hnp.backendofflinefirst.service.ExcelImportService;
import com.hnp.backendofflinefirst.ui.FaMessages;
import com.hnp.backendofflinefirst.ui.ImportWebSupport;
import com.hnp.backendofflinefirst.ui.WebListSupport;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.IOException;

@Controller
@RequestMapping("/plant-systems")
@RequiredArgsConstructor
public class PlantSystemWebController {

    private final PlantSystemRepository plantSystemRepository;
    private final LocationRepository locationRepository;
    private final AssetHierarchyService hierarchyService;
    private final ExcelImportService excelImportService;
    private final ExcelExportService excelExportService;

    @GetMapping
    @PreAuthorize("hasAuthority('GET:/plant-systems')")
    public String list(@RequestParam(required = false) Long editId,
                       @RequestParam(required = false) String q,
                       @RequestParam(defaultValue = "0") int page,
                       @RequestParam(required = false) Integer size,
                       Model model) {
        int pageSize = size != null ? size : WebListSupport.DEFAULT_SIZE;
        Pageable pageable = WebListSupport.pageable(page, pageSize);
        var result = WebListSupport.pagedList(q, pageable,
                plantSystemRepository::findAll,
                plantSystemRepository::search);
        model.addAttribute("activePage", "plant-systems");
        model.addAttribute("plantSystems", result.getContent());
        WebListSupport.addPagination(model, result, q, page, pageSize);
        model.addAttribute("locations", locationRepository.findAllByOrderByIdDesc());
        model.addAttribute("allPlantSystems", plantSystemRepository.findAllByOrderByIdDesc());
        if (editId != null) {
            plantSystemRepository.findById(editId).ifPresent(e -> model.addAttribute("editEntity", e));
        }
        return "plant-systems";
    }

    @PostMapping
    @PreAuthorize("hasAuthority('POST:/plant-systems')")
    public String create(@ModelAttribute PlantSystem form, RedirectAttributes ra) {
        long now = System.currentTimeMillis();
        form.setCreatedAt(now);
        form.setUpdatedAt(now);
        hierarchyService.savePlantSystem(form);
        ra.addFlashAttribute("successMessage", FaMessages.systemCreated());
        return "redirect:/plant-systems";
    }

    @PostMapping("/{id}")
    @PreAuthorize("hasAuthority('POST:/plant-systems/{id}')")
    public String update(@PathVariable Long id, @ModelAttribute PlantSystem form, RedirectAttributes ra) {
        plantSystemRepository.findById(id).ifPresent(e -> {
            Long priorLocationId = e.getLocationId();
            Long priorParentId = e.getParentId();
            e.setCode(form.getCode());
            e.setName(form.getName());
            e.setParentId(form.getParentId());
            e.setLocationId(form.getLocationId());
            e.setUpdatedAt(System.currentTimeMillis());
            hierarchyService.savePlantSystem(e, priorLocationId, priorParentId);
        });
        ra.addFlashAttribute("successMessage", FaMessages.systemUpdated());
        return "redirect:/plant-systems";
    }

    @PostMapping("/{id}/delete")
    @PreAuthorize("hasAuthority('POST:/plant-systems/{id}/delete')")
    public String delete(@PathVariable Long id, RedirectAttributes ra) {
        plantSystemRepository.deleteById(id);
        ra.addFlashAttribute("successMessage", FaMessages.systemDeleted());
        return "redirect:/plant-systems";
    }

    @PostMapping("/import")
    @PreAuthorize("hasAuthority('POST:/plant-systems/import')")
    public String importExcel(@RequestParam("file") MultipartFile file, RedirectAttributes ra) {
        try {
            ImportResult result = excelImportService.importPlantSystems(file);
            ImportWebSupport.applyImportResult(result, ra);
        } catch (Exception e) {
            ImportWebSupport.applyFileError(e, ra);
        }
        return "redirect:/plant-systems";
    }

    @GetMapping("/export")
    @PreAuthorize("hasAuthority('GET:/plant-systems')")
    public void export(HttpServletResponse response) throws IOException {
        excelExportService.exportPlantSystems(response);
    }

    @GetMapping("/import-template")
    @PreAuthorize("hasAuthority('GET:/plant-systems/import-template')")
    public void downloadTemplate(HttpServletResponse response) throws IOException {
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setHeader("Content-Disposition", "attachment; filename=\"plant-systems-template.xlsx\"");
        try (var wb = new XSSFWorkbook()) {
            var sheet = wb.createSheet("plant-systems");
            var header = sheet.createRow(0);
            String[] cols = {"code", "name", "parentSystemCode", "locationCode"};
            for (int i = 0; i < cols.length; i++) header.createCell(i).setCellValue(cols[i]);
            wb.write(response.getOutputStream());
        }
    }
}
