package com.hnp.backendofflinefirst.web;

import org.springframework.data.domain.PageRequest;
import com.hnp.backendofflinefirst.service.AssetAccessService;
import com.hnp.backendofflinefirst.service.AssetParameterReportService;
import com.hnp.backendofflinefirst.service.AssetReportService;
import com.hnp.backendofflinefirst.service.ExcelExportService;
import com.hnp.backendofflinefirst.service.ManagementReportService;
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
import java.time.temporal.ChronoUnit;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Controller
@RequestMapping("/reports")
@RequiredArgsConstructor
public class ReportWebController {

    private final AssetReportService assetReportService;
    private final AssetParameterReportService assetParameterReportService;
    private final AssetAccessService assetAccessService;
    private final LogSheetAccessService logSheetAccessService;
    private final ManagementReportService managementReportService;
    private final ExcelExportService excelExportService;
    private final DateUtils dateUtils;

    @GetMapping
    @PreAuthorize("hasAuthority('GET:/reports')")
    public String reports(@RequestParam(required = false) String q,
                          @RequestParam(defaultValue = "0") int page,
                          @RequestParam(required = false) Integer size,
                          Model model) {
        model.addAttribute("activePage", "reports");

        model.addAttribute("logSheetsByStatus", logSheetAccessService.countVisibleByStatus());
        model.addAttribute("logSheetsByTemplate", logSheetAccessService.countVisibleByTemplateName());

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
        model.addAttribute("fromInput", from != null ? from : dateUtils.formatInputHidden(fromMs));
        model.addAttribute("toInput", to != null ? to : dateUtils.formatInputHidden(toMs));
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

    /**
     * Both helpers below use the <em>reporting</em> scope, not the registry scope: an asset
     * this user is responsible for through a log sheet must be reportable even when its
     * location belongs to another unit (or to none). Used only by the asset-parameters
     * report — the inventory report deliberately keeps ownership semantics.
     */
    // ── Management reports ────────────────────────────────────────────────────
    //
    // All of these are guarded by the existing GET:/reports authority and read through
    // ManagementReportService, which applies the caller's unit scope. They share one
    // window convention: `days` back from now, defaulting to 30 (365 for the overview's
    // trend, which is fixed at 12 months).

    /** Executive summary: the six numbers plus a 12-month compliance trend. */
    @GetMapping("/overview")
    @PreAuthorize("hasAuthority('GET:/reports')")
    public String overview(@RequestParam(defaultValue = "30") int days, Model model) {
        long from = ManagementReportService.defaultWindowStart(clampDays(days));
        model.addAttribute("activePage", "reports-overview");
        model.addAttribute("days", clampDays(days));
        model.addAttribute("summary", managementReportService.overview(from, null));
        model.addAttribute("trend", managementReportService.monthlyTrend(ZoneId.systemDefault()));
        return "reports/overview";
    }

    /** Compliance and lateness, by unit and by template. */
    @GetMapping("/compliance")
    @PreAuthorize("hasAuthority('GET:/reports')")
    public String compliance(@RequestParam(defaultValue = "30") int days, Model model) {
        long from = ManagementReportService.defaultWindowStart(clampDays(days));
        model.addAttribute("activePage", "reports-compliance");
        model.addAttribute("days", clampDays(days));
        model.addAttribute("byUnit", managementReportService.complianceByUnit(from, null));
        model.addAttribute("byTemplate", managementReportService.complianceByTemplate(from, null));
        return "reports/compliance";
    }

    /** Readings that breached a warning or danger range. */
    @GetMapping("/exceptions")
    @PreAuthorize("hasAuthority('GET:/reports')")
    public String exceptions(@RequestParam(defaultValue = "7") int days,
                             @RequestParam(defaultValue = "false") boolean dangerOnly,
                             Model model) {
        long from = ManagementReportService.defaultWindowStart(clampDays(days));
        model.addAttribute("activePage", "reports-exceptions");
        model.addAttribute("days", clampDays(days));
        model.addAttribute("dangerOnly", dangerOnly);
        model.addAttribute("rows", managementReportService.outOfRangeReadings(from, null, dangerOnly));
        model.addAttribute("scanLimit", ManagementReportService.OUT_OF_RANGE_SHEET_SCAN_LIMIT);
        return "reports/exceptions";
    }

    /** Manual-vs-scanned ratio, NFC tag health, and assets nobody has read. */
    @GetMapping("/data-quality")
    @PreAuthorize("hasAuthority('GET:/reports')")
    public String dataQuality(@RequestParam(defaultValue = "30") int days, Model model) {
        int window = clampDays(days);
        long from = ManagementReportService.defaultWindowStart(window);
        model.addAttribute("activePage", "reports-data-quality");
        model.addAttribute("days", window);
        model.addAttribute("entrySources", managementReportService.entrySourceSplit(from, null));
        model.addAttribute("nfcFaults", managementReportService.openNfcFaults());
        model.addAttribute("silentAssets", managementReportService.assetsWithoutRecentReadings(from, 100));
        return "reports/data-quality";
    }

    /** Operator throughput and unit workload balance. */
    @GetMapping("/workforce")
    @PreAuthorize("hasAuthority('GET:/reports')")
    public String workforce(@RequestParam(defaultValue = "30") int days, Model model) {
        long from = ManagementReportService.defaultWindowStart(clampDays(days));
        model.addAttribute("activePage", "reports-workforce");
        model.addAttribute("days", clampDays(days));
        model.addAttribute("operators", managementReportService.operatorProductivity(from, null));
        model.addAttribute("units", managementReportService.unitWorkload(from, null));
        return "reports/workforce";
    }

    /** Extend / cancel / void / unvoid / reopen actions together with their stated reason. */
    @GetMapping("/actions")
    @PreAuthorize("hasAuthority('GET:/reports')")
    public String actions(@RequestParam(defaultValue = "90") int days, Model model) {
        long from = ManagementReportService.defaultWindowStart(clampDays(days));
        model.addAttribute("activePage", "reports-actions");
        model.addAttribute("days", clampDays(days));
        model.addAttribute("rows", managementReportService.actionReasons(from, null, 300));
        return "reports/actions";
    }

    /** Keeps a hand-edited query string from turning a report into a full-table scan. */
    private static int clampDays(int days) {
        return Math.max(1, Math.min(days, 365));
    }

    private Long resolveAssetId(Long assetId, String assetQuery, Model model) {
        Set<Long> unitIds = assetAccessService.visibleUnitIds();
        if (unitIds != null && unitIds.isEmpty()) {
            model.addAttribute("assetAccessDenied", true);
            return null;
        }
        if (assetId != null) {
            if (assetAccessService.findReportable(assetId).isEmpty()) {
                model.addAttribute("assetAccessDenied", true);
                return null;
            }
            return assetId;
        }
        if (assetQuery.isEmpty()) {
            return null;
        }
        var exact = assetAccessService.findVisibleByAssetCode(assetQuery);
        if (exact.isPresent()) {
            return exact.get().getId();
        }
        var searchPage = assetAccessService.findReportableAssets(assetQuery, PageRequest.of(0, 2));
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
        Set<Long> unitIds = assetAccessService.visibleUnitIds();
        if (unitIds != null && unitIds.isEmpty()) {
            return List.of();
        }
        List<com.hnp.backendofflinefirst.entity.AssetEntry> options = new ArrayList<>(
                assetAccessService.findReportableAssets(assetQuery, PageRequest.of(0, 30)).getContent());
        if (selectedAssetId != null && options.stream().noneMatch(a -> selectedAssetId.equals(a.getId()))) {
            assetAccessService.findReportable(selectedAssetId).ifPresent(a -> options.add(0, a));
        }
        return options;
    }

    private Long parseDateTimeParam(String value) {
        return dateUtils.parseInput(value);
    }
}
