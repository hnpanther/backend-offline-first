package com.hnp.backendofflinefirst.web;

import com.hnp.backendofflinefirst.dto.ImportResult;
import com.hnp.backendofflinefirst.entity.SubFunction;
import com.hnp.backendofflinefirst.repository.LocationRepository;
import com.hnp.backendofflinefirst.repository.MainFunctionRepository;
import com.hnp.backendofflinefirst.repository.PlantSystemRepository;
import com.hnp.backendofflinefirst.repository.SubFunctionRepository;
import com.hnp.backendofflinefirst.service.AssetHierarchyService;
import com.hnp.backendofflinefirst.service.ExcelExportService;
import com.hnp.backendofflinefirst.service.ExcelImportService;
import com.hnp.backendofflinefirst.service.MasterDataDeleteService;
import com.hnp.backendofflinefirst.ui.ErrorTranslator;
import com.hnp.backendofflinefirst.ui.FaMessages;
import com.hnp.backendofflinefirst.ui.ImportWebSupport;
import com.hnp.backendofflinefirst.ui.WebBulkDeleteSupport;
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
import java.util.List;

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
    private final ExcelExportService excelExportService;
    private final MasterDataDeleteService deleteService;

    @GetMapping
    @PreAuthorize("hasAuthority('GET:/sub-functions')")
    public String list(@RequestParam(required = false) Long editId,
                       @RequestParam(required = false) String q,
                       @RequestParam(defaultValue = "0") int page,
                       @RequestParam(required = false) Integer size,
                       Model model) {
        int pageSize = size != null ? size : WebListSupport.DEFAULT_SIZE;
        Pageable pageable = WebListSupport.pageable(page, pageSize);
        var result = WebListSupport.pagedList(q, pageable,
                subFunctionRepository::findAll,
                subFunctionRepository::search);
        model.addAttribute("activePage", "sub-functions");
        model.addAttribute("subFunctions", result.getContent());
        WebListSupport.addPagination(model, result, q, page, pageSize);
        model.addAttribute("mainFunctions", mainFunctionRepository.findAllByOrderByIdDesc());
        model.addAttribute("plantSystems", plantSystemRepository.findAllByOrderByIdDesc());
        model.addAttribute("locations", locationRepository.findAllByOrderByIdDesc());
        model.addAttribute("allSubFunctions", subFunctionRepository.findAllByOrderByIdDesc());
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
        hierarchyService.saveSubFunction(form);
        ra.addFlashAttribute("successMessage", FaMessages.subFunctionCreated());
        return "redirect:/sub-functions";
    }

    @PostMapping("/{id}")
    @PreAuthorize("hasAuthority('POST:/sub-functions/{id}')")
    public String update(@PathVariable Long id, @ModelAttribute SubFunction form,
                        @RequestParam(required = false) String parentRef, RedirectAttributes ra) {
        subFunctionRepository.findById(id).ifPresent(e -> {
            Long priorMainFunctionId = e.getMainFunctionId();
            Long priorSystemId = e.getSystemId();
            Long priorLocationId = e.getLocationId();
            Long priorParentId = e.getParentId();
            e.setCode(form.getCode());
            e.setName(form.getName());
            e.setTag(form.getTag());
            applyParent(e, parentRef);
            e.setUpdatedAt(System.currentTimeMillis());
            hierarchyService.saveSubFunction(e, priorMainFunctionId, priorSystemId, priorLocationId, priorParentId);
        });
        ra.addFlashAttribute("successMessage", FaMessages.subFunctionUpdated());
        return "redirect:/sub-functions";
    }

    /** parentRef is "type:id" (subFunction|mainFunction|system|location); fills the ancestry chain. */
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
        try {
            deleteService.deleteSubFunction(id);
            ra.addFlashAttribute("successMessage", FaMessages.subFunctionDeleted());
        } catch (IllegalStateException e) {
            ra.addFlashAttribute("errorMessage", ErrorTranslator.toFa(e.getMessage()));
        }
        return "redirect:/sub-functions";
    }

    @PostMapping("/delete-bulk")
    @PreAuthorize("hasAuthority('POST:/sub-functions/{id}/delete')")
    public String deleteBulk(@RequestParam(required = false) List<Long> ids,
                             @RequestParam(required = false) String q,
                             @RequestParam(defaultValue = "0") int page,
                             RedirectAttributes ra) {
        WebBulkDeleteSupport.applyResult(deleteService.deleteSubFunctions(ids), ra, "تابع فرعی");
        return WebBulkDeleteSupport.listRedirect("/sub-functions", q, page);
    }

    @PostMapping("/import")
    @PreAuthorize("hasAuthority('POST:/sub-functions/import')")
    public String importExcel(@RequestParam("file") MultipartFile file, RedirectAttributes ra) {
        try {
            ImportResult result = excelImportService.importSubFunctions(file);
            ImportWebSupport.applyImportResult(result, ra);
        } catch (Exception e) {
            ImportWebSupport.applyFileError(e, ra);
        }
        return "redirect:/sub-functions";
    }

    @GetMapping("/export")
    @PreAuthorize("hasAuthority('GET:/sub-functions')")
    public void export(HttpServletResponse response) throws IOException {
        excelExportService.exportSubFunctions(response);
    }

    @GetMapping("/import-template")
    @PreAuthorize("hasAuthority('GET:/sub-functions/import-template')")
    public void downloadTemplate(HttpServletResponse response) throws IOException {
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setHeader("Content-Disposition", "attachment; filename=\"sub-functions-template.xlsx\"");
        try (var wb = new XSSFWorkbook()) {
            var sheet = wb.createSheet("sub-functions");
            var header = sheet.createRow(0);
            String[] cols = {"code", "name", "tag", "parentSubFunctionCode", "mainFunctionCode", "systemCode", "locationCode"};
            for (int i = 0; i < cols.length; i++) header.createCell(i).setCellValue(cols[i]);
            wb.write(response.getOutputStream());
        }
    }
}
