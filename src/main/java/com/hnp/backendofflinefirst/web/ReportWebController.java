package com.hnp.backendofflinefirst.web;

import org.springframework.data.domain.PageRequest;
import com.hnp.backendofflinefirst.repository.AssetEntryRepository;
import com.hnp.backendofflinefirst.repository.DataRecordRepository;
import com.hnp.backendofflinefirst.service.AssetAccessService;
import com.hnp.backendofflinefirst.service.AssetParameterReportService;
import com.hnp.backendofflinefirst.service.AssetReportService;
import com.hnp.backendofflinefirst.service.ExcelExportService;
import com.hnp.backendofflinefirst.service.LogSheetAccessService;
import com.hnp.backendofflinefirst.ui.WebListSupport;
import com.hnp.backendofflinefirst.util.DateUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

import java.util.Collection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Controller
@RequestMapping("/reports")
@RequiredArgsConstructor
public class ReportWebController {

    private static final ZoneId TEHRAN = ZoneId.of("Asia/Tehran");
    private static final DateTimeFormatter INPUT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");

    private final DataRecordRepository dataRecordRepository;
    private final AssetEntryRepository assetEntryRepository;
    private final AssetReportService assetReportService;
    private final AssetParameterReportService assetParameterReportService;
    private final AssetAccessService assetAccessService;
    private final LogSheetAccessService logSheetAccessService;
    private final ExcelExportService excelExportService;
    private final DateUtils dateUtils;

    @GetMapping
    @PreAuthorize("hasAuthority('GET:/reports')")
    public String reports(@RequestParam(required = false) String q,
                          @RequestParam(defaultValue = "0") int page,
                          @RequestParam(required = false) Integer size,
                          Model model) {
        model.addAttribute("activePage", "reports");

        Map<String, Long> recordsByStatus = new LinkedHashMap<>();
        for (Object[] row : dataRecordRepository.countGroupedByRecordStatus()) {
            recordsByStatus.put((String) row[0], (Long) row[1]);
        }
        model.addAttribute("recordsByStatus", recordsByStatus);

        Map<String, Long> recordsByAsset = new LinkedHashMap<>();
        for (Object[] row : dataRecordRepository.countTopAssetsByName(PageRequest.of(0, 10))) {
            recordsByAsset.put((String) row[0], (Long) row[1]);
        }
        model.addAttribute("recordsByAsset", recordsByAsset);

        model.addAttribute("logSheetsByStatus", logSheetAccessService.countVisibleByStatus());
        model.addAttribute("logSheetsByTemplate", logSheetAccessService.countVisibleByTemplateName());

        model.addAttribute("totalRecords", dataRecordRepository.count());
        model.addAttribute("totalLogSheets", logSheetAccessService.countVisible());

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

    @GetMapping("/asset-parameters")
    @PreAuthorize("hasAuthority('GET:/reports')")
    public String assetParameters(@RequestParam(required = false) Long assetId,
                                    @RequestParam(required = false) String fieldKey,
                                    @RequestParam(required = false) String from,
                                    @RequestParam(required = false) String to,
                                    @RequestParam(required = false) String q,
                                    @RequestParam(defaultValue = "0") int page,
                                    @RequestParam(required = false) Integer size,
                                    Model model) {
        model.addAttribute("activePage", "reports-asset-parameters");

        String assetQuery = WebListSupport.normalizeQuery(q);
        Long resolvedAssetId = resolveAssetId(assetId, assetQuery, model);
        if (assetId == null && resolvedAssetId != null) {
            page = 0;
        }

        Long fromMs = parseDateTimeParam(from);
        Long toMs = parseDateTimeParam(to);
        if (fromMs == null && toMs == null && resolvedAssetId != null) {
            fromMs = Instant.now().minus(90, ChronoUnit.DAYS).toEpochMilli();
        }

        model.addAttribute("selectedAssetId", resolvedAssetId);
        model.addAttribute("selectedFieldKey", fieldKey != null ? fieldKey : "");
        model.addAttribute("fromInput", from != null ? from : dateUtils.formatInput(fromMs));
        model.addAttribute("toInput", to != null ? to : dateUtils.formatInput(toMs));
        model.addAttribute("assetSearch", assetQuery);
        model.addAttribute("assetOptions", loadAssetOptions(assetQuery, resolvedAssetId));

        var asset = assetParameterReportService.findAsset(resolvedAssetId);
        asset.ifPresent(a -> {
            model.addAttribute("selectedAsset", a);
            model.addAttribute("fieldDefinitions", assetParameterReportService.fieldDefinitionsForAsset(a));
        });

        if (resolvedAssetId != null && asset.isPresent()) {
            int pageSize = size != null ? size : WebListSupport.DEFAULT_SIZE;
            var historyPage = assetParameterReportService.buildValueHistoryPage(
                    resolvedAssetId, fieldKey, fromMs, toMs, WebListSupport.unsortedPageable(page, pageSize));
            model.addAttribute("valueHistory", historyPage.getContent());
            WebListSupport.addPagination(model, historyPage, q, page, pageSize);
            model.addAttribute("readingCount", assetParameterReportService.countSubmittedReadings(resolvedAssetId, fromMs, toMs));

            var chartSeries = assetParameterReportService.buildChartSeries(resolvedAssetId, fieldKey, fromMs, toMs);
            chartSeries.ifPresent(series -> model.addAttribute("chartSeries", series));
            model.addAttribute("hasChart", chartSeries.isPresent());
        } else {
            model.addAttribute("valueHistory", List.of());
            model.addAttribute("readingCount", 0L);
            model.addAttribute("hasChart", false);
        }

        return "reports/asset-parameters";
    }

    private Long resolveAssetId(Long assetId, String assetQuery, Model model) {
        Collection<Long> subFunctionIds = assetAccessService.visibleSubFunctionIds();
        if (subFunctionIds != null && subFunctionIds.isEmpty()) {
            model.addAttribute("assetAccessDenied", true);
            return null;
        }
        if (assetId != null) {
            if (assetAccessService.findVisible(assetId).isEmpty()) {
                model.addAttribute("assetAccessDenied", true);
                return null;
            }
            return assetId;
        }
        if (assetQuery.isEmpty()) {
            return null;
        }
        var exact = assetEntryRepository.findVisibleByAssetCodeIgnoreCase(subFunctionIds, assetQuery);
        if (exact.isPresent()) {
            return exact.get().getId();
        }
        var searchPage = assetEntryRepository.searchVisible(subFunctionIds, assetQuery, PageRequest.of(0, 2));
        if (searchPage.getTotalElements() == 1) {
            return searchPage.getContent().getFirst().getId();
        }
        if (searchPage.getTotalElements() > 1) {
            model.addAttribute("assetPickRequired", true);
            model.addAttribute("assetMatchCount", searchPage.getTotalElements());
        } else {
            model.addAttribute("assetNotFound", true);
        }
        return null;
    }

    private List<com.hnp.backendofflinefirst.entity.AssetEntry> loadAssetOptions(String assetQuery, Long selectedAssetId) {
        Collection<Long> subFunctionIds = assetAccessService.visibleSubFunctionIds();
        if (subFunctionIds != null && subFunctionIds.isEmpty()) {
            return List.of();
        }
        List<com.hnp.backendofflinefirst.entity.AssetEntry> options = assetQuery.isEmpty()
                ? new ArrayList<>(assetEntryRepository.findVisible(subFunctionIds, PageRequest.of(0, 30)).getContent())
                : new ArrayList<>(assetEntryRepository.searchVisible(subFunctionIds, assetQuery, PageRequest.of(0, 30)).getContent());
        if (selectedAssetId != null && options.stream().noneMatch(a -> selectedAssetId.equals(a.getId()))) {
            assetAccessService.findVisible(selectedAssetId).ifPresent(a -> options.add(0, a));
        }
        return options;
    }

    private Long parseDateTimeParam(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        ZonedDateTime zdt = java.time.LocalDateTime.parse(value, INPUT_FMT).atZone(TEHRAN);
        return zdt.toInstant().toEpochMilli();
    }
}
