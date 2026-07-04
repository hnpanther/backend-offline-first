package com.hnp.backendofflinefirst.web;

import com.hnp.backendofflinefirst.dto.ImportResult;
import com.hnp.backendofflinefirst.entity.AssetEntry;
import com.hnp.backendofflinefirst.repository.AssetClassRepository;
import com.hnp.backendofflinefirst.repository.AssetEntryRepository;
import com.hnp.backendofflinefirst.repository.SubFunctionRepository;
import com.hnp.backendofflinefirst.service.AssetEntryService;
import com.hnp.backendofflinefirst.service.ExcelExportService;
import com.hnp.backendofflinefirst.service.ExcelImportService;
import com.hnp.backendofflinefirst.ui.ErrorTranslator;
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
@RequestMapping("/asset-entries")
@RequiredArgsConstructor
public class AssetEntryWebController {

    private final AssetEntryRepository assetEntryRepository;
    private final AssetClassRepository assetClassRepository;
    private final SubFunctionRepository subFunctionRepository;
    private final AssetEntryService assetEntryService;
    private final ExcelImportService excelImportService;
    private final ExcelExportService excelExportService;

    @GetMapping
    @PreAuthorize("hasAuthority('GET:/asset-entries')")
    public String list(@RequestParam(required = false) Long editId,
                       @RequestParam(required = false) String q,
                       @RequestParam(defaultValue = "0") int page,
                       @RequestParam(required = false) Integer size,
                       Model model) {
        int pageSize = size != null ? size : WebListSupport.DEFAULT_SIZE;
        Pageable pageable = WebListSupport.pageable(page, pageSize);
        var result = WebListSupport.pagedList(q, pageable,
                assetEntryRepository::findAll,
                assetEntryRepository::search);
        model.addAttribute("activePage", "asset-entries");
        model.addAttribute("assetEntries", result.getContent());
        WebListSupport.addPagination(model, result, q, page, pageSize);
        model.addAttribute("assetClasses", assetClassRepository.findAllByOrderByIdDesc());
        model.addAttribute("subFunctions", subFunctionRepository.findAllByOrderByIdDesc());
        if (editId != null) {
            assetEntryRepository.findById(editId).ifPresent(e -> model.addAttribute("editEntity", e));
        }
        return "asset-entries";
    }

    @PostMapping
    @PreAuthorize("hasAuthority('POST:/asset-entries')")
    public String create(@ModelAttribute AssetEntry form, RedirectAttributes ra) {
        try {
            assetEntryService.create(form);
            ra.addFlashAttribute("successMessage", FaMessages.assetCreated());
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("errorMessage", ErrorTranslator.toFa(e.getMessage()));
        }
        return "redirect:/asset-entries";
    }

    @PostMapping("/{id}")
    @PreAuthorize("hasAuthority('POST:/asset-entries/{id}')")
    public String update(@PathVariable Long id, @ModelAttribute AssetEntry form, RedirectAttributes ra) {
        try {
            assetEntryService.update(id, form);
            ra.addFlashAttribute("successMessage", FaMessages.assetUpdated());
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("errorMessage", ErrorTranslator.toFa(e.getMessage()));
        }
        return "redirect:/asset-entries";
    }

    @PostMapping("/{id}/delete")
    @PreAuthorize("hasAuthority('POST:/asset-entries/{id}/delete')")
    public String delete(@PathVariable Long id, RedirectAttributes ra) {
        assetEntryRepository.deleteById(id);
        ra.addFlashAttribute("successMessage", FaMessages.assetDeleted());
        return "redirect:/asset-entries";
    }

    @PostMapping("/import")
    @PreAuthorize("hasAuthority('POST:/asset-entries/import')")
    public String importExcel(@RequestParam("file") MultipartFile file, RedirectAttributes ra) {
        try {
            ImportResult result = excelImportService.importAssetEntries(file);
            ImportWebSupport.applyImportResult(result, ra);
        } catch (Exception e) {
            ImportWebSupport.applyFileError(e, ra);
        }
        return "redirect:/asset-entries";
    }

    @GetMapping("/export")
    @PreAuthorize("hasAuthority('GET:/asset-entries')")
    public void export(HttpServletResponse response) throws IOException {
        excelExportService.exportAssetEntries(response);
    }

    @GetMapping("/import-template")
    @PreAuthorize("hasAuthority('GET:/asset-entries/import-template')")
    public void downloadTemplate(HttpServletResponse response) throws IOException {
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setHeader("Content-Disposition", "attachment; filename=\"asset-entries-template.xlsx\"");
        try (var wb = new XSSFWorkbook()) {
            var sheet = wb.createSheet("asset-entries");
            var header = sheet.createRow(0);
            String[] cols = {"assetCode", "assetName", "nfcTagId", "subFunctionCode", "className"};
            for (int i = 0; i < cols.length; i++) header.createCell(i).setCellValue(cols[i]);
            wb.write(response.getOutputStream());
        }
    }
}
