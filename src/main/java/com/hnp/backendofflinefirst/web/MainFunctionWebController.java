package com.hnp.backendofflinefirst.web;

import com.hnp.backendofflinefirst.dto.ImportResult;
import com.hnp.backendofflinefirst.entity.MainFunction;
import com.hnp.backendofflinefirst.repository.LocationRepository;
import com.hnp.backendofflinefirst.repository.MainFunctionRepository;
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
@RequestMapping("/main-functions")
@RequiredArgsConstructor
public class MainFunctionWebController {

    private final MainFunctionRepository mainFunctionRepository;
    private final PlantSystemRepository plantSystemRepository;
    private final LocationRepository locationRepository;
    private final AssetHierarchyService hierarchyService;
    private final ExcelImportService excelImportService;
    private final ExcelExportService excelExportService;

    @GetMapping
    @PreAuthorize("hasAuthority('GET:/main-functions')")
    public String list(@RequestParam(required = false) Long editId,
                       @RequestParam(required = false) String q,
                       @RequestParam(defaultValue = "0") int page,
                       @RequestParam(required = false) Integer size,
                       Model model) {
        int pageSize = size != null ? size : WebListSupport.DEFAULT_SIZE;
        Pageable pageable = WebListSupport.pageable(page, pageSize);
        var result = WebListSupport.pagedList(q, pageable,
                mainFunctionRepository::findAll,
                mainFunctionRepository::search);
        model.addAttribute("activePage", "main-functions");
        model.addAttribute("mainFunctions", result.getContent());
        WebListSupport.addPagination(model, result, q, page, pageSize);
        model.addAttribute("plantSystems", plantSystemRepository.findAllByOrderByIdDesc());
        model.addAttribute("locations", locationRepository.findAllByOrderByIdDesc());
        model.addAttribute("allMainFunctions", mainFunctionRepository.findAllByOrderByIdDesc());
        if (editId != null) {
            mainFunctionRepository.findById(editId).ifPresent(e -> model.addAttribute("editEntity", e));
        }
        return "main-functions";
    }

    @PostMapping
    @PreAuthorize("hasAuthority('POST:/main-functions')")
    public String create(@ModelAttribute MainFunction form,
                         @RequestParam(required = false) String parentRef, RedirectAttributes ra) {
        long now = System.currentTimeMillis();
        form.setCreatedAt(now);
        form.setUpdatedAt(now);
        applyParent(form, parentRef);
        hierarchyService.saveMainFunction(form);
        ra.addFlashAttribute("successMessage", FaMessages.mainFunctionCreated());
        return "redirect:/main-functions";
    }

    @PostMapping("/{id}")
    @PreAuthorize("hasAuthority('POST:/main-functions/{id}')")
    public String update(@PathVariable Long id, @ModelAttribute MainFunction form,
                        @RequestParam(required = false) String parentRef, RedirectAttributes ra) {
        mainFunctionRepository.findById(id).ifPresent(e -> {
            Long priorSystemId = e.getSystemId();
            Long priorLocationId = e.getLocationId();
            Long priorParentId = e.getParentId();
            e.setCode(form.getCode());
            e.setName(form.getName());
            applyParent(e, parentRef);
            e.setUpdatedAt(System.currentTimeMillis());
            hierarchyService.saveMainFunction(e, priorSystemId, priorLocationId, priorParentId);
        });
        ra.addFlashAttribute("successMessage", FaMessages.mainFunctionUpdated());
        return "redirect:/main-functions";
    }

    /** parentRef is "type:id" (system|location|mainFunction); fills the ancestry chain. */
    private void applyParent(MainFunction mf, String parentRef) {
        String type = null;
        Long id = null;
        if (parentRef != null && parentRef.contains(":")) {
            int i = parentRef.indexOf(':');
            type = parentRef.substring(0, i);
            String idStr = parentRef.substring(i + 1);
            if (!idStr.isBlank()) id = Long.valueOf(idStr);
        }
        hierarchyService.applyMainFunctionParent(mf, type, id);
    }

    @PostMapping("/{id}/delete")
    @PreAuthorize("hasAuthority('POST:/main-functions/{id}/delete')")
    public String delete(@PathVariable Long id, RedirectAttributes ra) {
        mainFunctionRepository.deleteById(id);
        ra.addFlashAttribute("successMessage", FaMessages.mainFunctionDeleted());
        return "redirect:/main-functions";
    }

    @PostMapping("/import")
    @PreAuthorize("hasAuthority('POST:/main-functions/import')")
    public String importExcel(@RequestParam("file") MultipartFile file, RedirectAttributes ra) {
        try {
            ImportResult result = excelImportService.importMainFunctions(file);
            ImportWebSupport.applyImportResult(result, ra);
        } catch (Exception e) {
            ImportWebSupport.applyFileError(e, ra);
        }
        return "redirect:/main-functions";
    }

    @GetMapping("/export")
    @PreAuthorize("hasAuthority('GET:/main-functions')")
    public void export(HttpServletResponse response) throws IOException {
        excelExportService.exportMainFunctions(response);
    }

    @GetMapping("/import-template")
    @PreAuthorize("hasAuthority('GET:/main-functions/import-template')")
    public void downloadTemplate(HttpServletResponse response) throws IOException {
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setHeader("Content-Disposition", "attachment; filename=\"main-functions-template.xlsx\"");
        try (var wb = new XSSFWorkbook()) {
            var sheet = wb.createSheet("main-functions");
            var header = sheet.createRow(0);
            String[] cols = {"code", "name", "parentMainFunctionCode", "systemCode", "locationCode"};
            for (int i = 0; i < cols.length; i++) header.createCell(i).setCellValue(cols[i]);
            wb.write(response.getOutputStream());
        }
    }
}
