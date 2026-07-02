package com.hnp.backendofflinefirst.web;

import com.hnp.backendofflinefirst.dto.ImportResult;
import com.hnp.backendofflinefirst.entity.AssetEntry;
import com.hnp.backendofflinefirst.repository.AssetClassRepository;
import com.hnp.backendofflinefirst.repository.AssetEntryRepository;
import com.hnp.backendofflinefirst.repository.SubFunctionRepository;
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
@RequestMapping("/asset-entries")
@RequiredArgsConstructor
public class AssetEntryWebController {

    private final AssetEntryRepository assetEntryRepository;
    private final AssetClassRepository assetClassRepository;
    private final SubFunctionRepository subFunctionRepository;
    private final ExcelImportService excelImportService;

    @GetMapping
    @PreAuthorize("hasAuthority('GET:/asset-entries')")
    public String list(@RequestParam(required = false) Long editId, Model model) {
        model.addAttribute("activePage", "asset-entries");
        model.addAttribute("assetEntries", assetEntryRepository.findAll());
        model.addAttribute("assetClasses", assetClassRepository.findAll());
        model.addAttribute("subFunctions", subFunctionRepository.findAll());
        if (editId != null) {
            assetEntryRepository.findById(editId).ifPresent(e -> model.addAttribute("editEntity", e));
        }
        return "asset-entries";
    }

    @PostMapping
    @PreAuthorize("hasAuthority('POST:/asset-entries')")
    public String create(@ModelAttribute AssetEntry form, RedirectAttributes ra) {
        long now = System.currentTimeMillis();
        form.setCreatedAt(now);
        form.setUpdatedAt(now);
        if ("".equals(form.getLocation())) form.setLocation(null);
        assetEntryRepository.save(form);
        ra.addFlashAttribute("successMessage", "دارایی با موفقیت ایجاد شد.");
        return "redirect:/asset-entries";
    }

    @PostMapping("/{id}")
    @PreAuthorize("hasAuthority('POST:/asset-entries/{id}')")
    public String update(@PathVariable Long id, @ModelAttribute AssetEntry form, RedirectAttributes ra) {
        assetEntryRepository.findById(id).ifPresent(e -> {
            e.setNfcTagId(form.getNfcTagId());
            e.setAssetName(form.getAssetName());
            e.setClassId(form.getClassId());
            e.setSubFunctionId(form.getSubFunctionId());
            e.setLocation("".equals(form.getLocation()) ? null : form.getLocation());
            e.setUpdatedAt(System.currentTimeMillis());
            assetEntryRepository.save(e);
        });
        ra.addFlashAttribute("successMessage", "دارایی با موفقیت ویرایش شد.");
        return "redirect:/asset-entries";
    }

    @PostMapping("/{id}/delete")
    @PreAuthorize("hasAuthority('POST:/asset-entries/{id}/delete')")
    public String delete(@PathVariable Long id, RedirectAttributes ra) {
        assetEntryRepository.deleteById(id);
        ra.addFlashAttribute("successMessage", "دارایی با موفقیت حذف شد.");
        return "redirect:/asset-entries";
    }

    @PostMapping("/import")
    @PreAuthorize("hasAuthority('POST:/asset-entries/import')")
    public String importExcel(@RequestParam("file") MultipartFile file, RedirectAttributes ra) {
        try {
            ImportResult result = excelImportService.importAssetEntries(file);
            ra.addFlashAttribute("successMessage", result.summary());
            if (result.hasErrors()) ra.addFlashAttribute("importErrors", result.getErrors());
        } catch (Exception e) {
            ra.addFlashAttribute("errorMessage", "خطا در پردازش فایل: " + e.getMessage());
        }
        return "redirect:/asset-entries";
    }

    @GetMapping("/import-template")
    @PreAuthorize("hasAuthority('GET:/asset-entries/import-template')")
    public void downloadTemplate(HttpServletResponse response) throws IOException {
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setHeader("Content-Disposition", "attachment; filename=\"asset-entries-template.xlsx\"");
        try (var wb = new XSSFWorkbook()) {
            var sheet = wb.createSheet("asset-entries");
            var header = sheet.createRow(0);
            String[] cols = {"nfcTagId", "assetName", "subFunctionCode", "subFunctionName", "className"};
            for (int i = 0; i < cols.length; i++) header.createCell(i).setCellValue(cols[i]);
            wb.write(response.getOutputStream());
        }
    }
}
