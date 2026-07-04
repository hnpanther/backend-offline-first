package com.hnp.backendofflinefirst.web;

import com.hnp.backendofflinefirst.repository.DataRecordRepository;
import com.hnp.backendofflinefirst.service.ExcelExportService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import com.hnp.backendofflinefirst.ui.WebListSupport;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
@RequestMapping("/records")
@RequiredArgsConstructor
public class RecordWebController {

    private final DataRecordRepository dataRecordRepository;
    private final ObjectMapper objectMapper;
    private final ExcelExportService excelExportService;

    @GetMapping
    @PreAuthorize("hasAuthority('GET:/records')")
    public String list(@RequestParam(required = false) String status,
                       @RequestParam(required = false) String asset,
                       @RequestParam(required = false) String q,
                       @RequestParam(defaultValue = "0") int page,
                       @RequestParam(required = false) Integer size,
                       Model model) {
        int pageSize = size != null ? size : WebListSupport.DEFAULT_SIZE;
        Pageable pageable = WebListSupport.pageable(page, pageSize);
        String statusFilter = status != null && !status.isBlank() ? status : null;
        String assetFilter = asset != null && !asset.isBlank() ? asset : null;
        var result = WebListSupport.hasSearch(q)
                ? dataRecordRepository.searchWithTerm(WebListSupport.searchTerm(q), statusFilter, assetFilter, pageable)
                : (statusFilter != null || assetFilter != null
                    ? dataRecordRepository.filter(statusFilter, assetFilter, pageable)
                    : dataRecordRepository.findAll(pageable));
        model.addAttribute("activePage", "records");
        model.addAttribute("records", result.getContent());
        WebListSupport.addPagination(model, result, q, page, pageSize);
        model.addAttribute("filterStatus", status);
        model.addAttribute("filterAsset", asset);
        return "records";
    }

    @GetMapping("/export")
    @PreAuthorize("hasAuthority('GET:/records')")
    public void export(HttpServletResponse response) throws java.io.IOException {
        excelExportService.exportRecords(response);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('GET:/records/{id}')")
    public String detail(@PathVariable Long id, Model model) {
        model.addAttribute("activePage", "records");
        dataRecordRepository.findById(id).ifPresent(r -> {
            model.addAttribute("record", r);
            try {
                String pretty = objectMapper.writerWithDefaultPrettyPrinter()
                        .writeValueAsString(r.getFormData());
                model.addAttribute("formDataJson", pretty);
            } catch (Exception e) {
                model.addAttribute("formDataJson", "{}");
            }
        });
        return "record-detail";
    }
}
