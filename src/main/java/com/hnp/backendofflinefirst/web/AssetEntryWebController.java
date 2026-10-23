package com.hnp.backendofflinefirst.web;

import com.hnp.backendofflinefirst.service.AssetFieldOverrideService;
import com.hnp.backendofflinefirst.dto.AssetFieldOverrideForm;
import com.hnp.backendofflinefirst.domain.FieldDataTypes;
import com.hnp.backendofflinefirst.domain.FieldSource;
import com.hnp.backendofflinefirst.dto.ImportResult;
import com.hnp.backendofflinefirst.dto.SelectOptionDto;
import com.hnp.backendofflinefirst.entity.AssetEntry;
import com.hnp.backendofflinefirst.repository.AssetClassRepository;
import com.hnp.backendofflinefirst.repository.AssetEntryRepository;
import com.hnp.backendofflinefirst.service.AssetEntryService;
import com.hnp.backendofflinefirst.service.ExcelExportService;
import com.hnp.backendofflinefirst.service.ExcelImportService;
import com.hnp.backendofflinefirst.service.MasterDataDeleteService;
import com.hnp.backendofflinefirst.service.MasterDataOptionsService;
import com.hnp.backendofflinefirst.ui.ErrorTranslator;
import com.hnp.backendofflinefirst.ui.FaMessages;
import com.hnp.backendofflinefirst.ui.ImportWebSupport;
import com.hnp.backendofflinefirst.ui.WebBulkDeleteSupport;
import com.hnp.backendofflinefirst.ui.WebListSupport;
import com.hnp.backendofflinefirst.util.ReferenceLabelService;
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
import java.util.Objects;
import java.util.stream.Collectors;

@Controller
@RequestMapping("/asset-entries")
@RequiredArgsConstructor
public class AssetEntryWebController {

    private final AssetEntryRepository assetEntryRepository;
    private final AssetFieldOverrideService fieldOverrideService;
    private final AssetClassRepository assetClassRepository;
    private final AssetEntryService assetEntryService;
    private final ExcelImportService excelImportService;
    private final ExcelExportService excelExportService;
    private final MasterDataDeleteService deleteService;
    private final MasterDataOptionsService masterDataOptionsService;
    private final ReferenceLabelService labels;

    @GetMapping
    @PreAuthorize("hasAuthority('GET:/asset-entries')")
    public String list(@RequestParam(required = false) Long editId,
                       @RequestParam(required = false) String q,
                       @RequestParam(required = false) Long classId,
                       @RequestParam(defaultValue = "0") int page,
                       @RequestParam(required = false) Integer size,
                       Model model) {
        int pageSize = size != null ? size : WebListSupport.DEFAULT_SIZE;
        Pageable pageable = WebListSupport.pageable(page, pageSize);
        // Filtered in SQL, never in Java: "show me only the pumps" has to mean every pump in the
        // registry, not the pumps that happen to be on the page already loaded.
        var result = assetEntryRepository.filterList(WebListSupport.searchTerm(q), classId, pageable);
        List<AssetEntry> entries = result.getContent();
        model.addAttribute("activePage", "asset-entries");
        model.addAttribute("assetEntries", entries);
        model.addAttribute("filterClassId", classId);
        WebListSupport.addPagination(model, result, q, page, pageSize);
        // Every class, parents and sub-classes alike — the label map below has to
        // resolve a sub-class's name too, and a sub-class is a legitimate choice here.
        var assetClasses = assetClassRepository.findAllByOrderByIdDesc();
        model.addAttribute("assetClasses", assetClasses);
        // Reuse dropdown load for class labels; batch-load only distinct sub-functions on this page.
        model.addAttribute("assetClassLabelById", labels.assetClassLabelsFrom(assetClasses));
        model.addAttribute("subFunctionLabelById", labels.subFunctionLabelsFor(
                entries.stream()
                        .map(AssetEntry::getSubFunctionId)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toSet())));
        if (editId != null) {
            assetEntryRepository.findById(editId).ifPresent(e -> {
                model.addAttribute("editEntity", e);
                if (e.getSubFunctionId() != null) {
                    model.addAttribute("selectedSubFunction",
                            masterDataOptionsService.subFunctionOption(e.getSubFunctionId()));
                }
            });
        }
        return "asset-entries";
    }

    @GetMapping("/options/sub-functions")
    @PreAuthorize("hasAuthority('GET:/asset-entries')")
    @ResponseBody
    public List<SelectOptionDto> subFunctionOptions(@RequestParam(required = false) String q,
                                                    @RequestParam(defaultValue = "30") int limit) {
        return masterDataOptionsService.searchSubFunctions(q, limit);
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

    // ===========================================================================================
    // Per-asset parameters and bands (V5)
    // ===========================================================================================

    /**
     * The asset's own parameter screen, pre-filled from its class and sub-class.
     *
     * <p>Nobody starts from a blank form: an empty row is how a field quietly loses its band,
     * and a band an administrator believes is set is worse than one they know is not.
     *
     * <p>No permission of its own — a band is part of the asset, so this carries the same
     * {@code GET:/asset-entries} the registry does, and the POST carries the same
     * {@code POST:/asset-entries/{id}} an ordinary edit does.
     */
    @GetMapping("/{id}/parameters")
    @PreAuthorize("hasAuthority('GET:/asset-entries')")
    public String parameters(@PathVariable Long id, Model model, RedirectAttributes ra) {
        AssetEntry asset = assetEntryRepository.findById(id).orElse(null);
        if (asset == null) {
            ra.addFlashAttribute("errorMessage", "دارایی یافت نشد.");
            return "redirect:/asset-entries";
        }
        model.addAttribute("activePage", "asset-entries");
        model.addAttribute("asset", asset);
        model.addAttribute("assetClassLabel",
                asset.getClassId() == null ? null : labels.assetClassLabel(asset.getClassId()));
        model.addAttribute("fieldSource", FieldSource.from(asset.getFieldSource()).name());
        // What the asset resolves to today, which is what the rows are filled with, and what
        // the class alone says, so the screen can show which rows actually differ.
        model.addAttribute("rows", fieldOverrideService.editorRows(asset));
        // The same vocabulary the class's field form offers. A parameter the asset adds is a
        // real field definition and may be any type the renderer understands; defaulting it to
        // "number" — which is what happened while this list was missing — made every added
        // parameter numeric with no way to say otherwise.
        model.addAttribute("dataTypeLabels", FieldDataTypes.labels());
        return "asset-entry-parameters";
    }

    @PostMapping("/{id}/parameters")
    @PreAuthorize("hasAuthority('POST:/asset-entries/{id}')")
    public String saveParameters(@PathVariable Long id,
                                  @ModelAttribute AssetFieldOverrideForm form,
                                  RedirectAttributes ra) {
        try {
            fieldOverrideService.save(id, form.source(), form.toOverrides());
            ra.addFlashAttribute("successMessage", FaMessages.assetParametersSaved());
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("errorMessage", ErrorTranslator.toFa(e.getMessage()));
            return "redirect:/asset-entries/" + id + "/parameters";
        }
        return "redirect:/asset-entries/" + id + "/parameters";
    }

    @PostMapping("/{id}/delete")
    @PreAuthorize("hasAuthority('POST:/asset-entries/{id}/delete')")
    public String delete(@PathVariable Long id, RedirectAttributes ra) {
        try {
            deleteService.deleteAssetEntry(id);
            ra.addFlashAttribute("successMessage", FaMessages.assetDeleted());
        } catch (IllegalStateException e) {
            ra.addFlashAttribute("errorMessage", ErrorTranslator.toFa(e.getMessage()));
        }
        return "redirect:/asset-entries";
    }

    @PostMapping("/delete-bulk")
    @PreAuthorize("hasAuthority('POST:/asset-entries/{id}/delete')")
    public String deleteBulk(@RequestParam(required = false) List<Long> ids,
                             @RequestParam(required = false) String q,
                             @RequestParam(defaultValue = "0") int page,
                             RedirectAttributes ra) {
        WebBulkDeleteSupport.applyResult(deleteService.deleteAssetEntries(ids), ra, "دارایی");
        return WebBulkDeleteSupport.listRedirect("/asset-entries", q, page);
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
            String[] cols = {"assetCode", "assetName", "assetNameFa", "nfcTagId", "nfcSerial", "subFunctionCode", "className", "active"};
            for (int i = 0; i < cols.length; i++) header.createCell(i).setCellValue(cols[i]);
            wb.write(response.getOutputStream());
        }
    }
}
