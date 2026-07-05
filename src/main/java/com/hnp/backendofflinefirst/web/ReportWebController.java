package com.hnp.backendofflinefirst.web;

import com.hnp.backendofflinefirst.repository.DataRecordRepository;
import com.hnp.backendofflinefirst.repository.LogSheetRepository;
import com.hnp.backendofflinefirst.service.AssetReportService;
import com.hnp.backendofflinefirst.service.ExcelExportService;
import com.hnp.backendofflinefirst.ui.FaMessages;
import com.hnp.backendofflinefirst.ui.WebListSupport;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

@Controller
@RequestMapping("/reports")
@RequiredArgsConstructor
public class ReportWebController {

    private final DataRecordRepository dataRecordRepository;
    private final LogSheetRepository logSheetRepository;
    private final AssetReportService assetReportService;
    private final ExcelExportService excelExportService;

    @GetMapping
    @PreAuthorize("hasAuthority('GET:/reports')")
    public String reports(@RequestParam(required = false) String q,
                          @RequestParam(defaultValue = "0") int page,
                          @RequestParam(required = false) Integer size,
                          Model model) {
        model.addAttribute("activePage", "reports");

        Map<String, Long> recordsByStatus = dataRecordRepository.findAll().stream()
                .collect(Collectors.groupingBy(
                        r -> r.getRecordStatus() == null ? FaMessages.UNKNOWN : r.getRecordStatus(),
                        Collectors.counting()
                ));
        model.addAttribute("recordsByStatus", recordsByStatus);

        Map<String, Long> recordsByAsset = dataRecordRepository.findAll().stream()
                .filter(r -> r.getAssetName() != null)
                .collect(Collectors.groupingBy(
                        r -> r.getAssetName(),
                        Collectors.counting()
                ))
                .entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(10)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue,
                        (e1, e2) -> e1, LinkedHashMap::new));
        model.addAttribute("recordsByAsset", recordsByAsset);

        Map<String, Long> logSheetsByStatus = logSheetRepository.findAll().stream()
                .collect(Collectors.groupingBy(
                        s -> s.getStatus() == null ? FaMessages.UNKNOWN : s.getStatus().name(),
                        Collectors.counting()
                ));
        model.addAttribute("logSheetsByStatus", logSheetsByStatus);

        Map<String, Long> logSheetsByTemplate = logSheetRepository.findAll().stream()
                .filter(s -> s.getTemplateName() != null)
                .collect(Collectors.groupingBy(
                        s -> s.getTemplateName(),
                        Collectors.counting()
                ))
                .entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue,
                        (e1, e2) -> e1, LinkedHashMap::new));
        model.addAttribute("logSheetsByTemplate", logSheetsByTemplate);

        model.addAttribute("totalRecords", dataRecordRepository.count());
        model.addAttribute("totalLogSheets", logSheetRepository.count());

        int pageSize = size != null ? size : WebListSupport.DEFAULT_SIZE;
        var assetPage = assetReportService.buildAssetInventoryPage(q, WebListSupport.pageable(page, pageSize));
        model.addAttribute("assetInventory", assetPage.getContent());
        WebListSupport.addPagination(model, assetPage, q, page, pageSize);
        return "reports";
    }

    @GetMapping("/asset-inventory/export")
    @PreAuthorize("hasAuthority('GET:/reports')")
    public void exportAssetInventory(HttpServletResponse response) throws IOException {
        excelExportService.exportAssetInventoryReport(response);
    }
}
