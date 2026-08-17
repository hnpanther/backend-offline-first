package com.hnp.backendofflinefirst.web;

import org.springframework.data.domain.PageRequest;
import com.hnp.backendofflinefirst.service.AssetAccessService;
import com.hnp.backendofflinefirst.service.AssetHistoryViewService;
import com.hnp.backendofflinefirst.service.AssetParameterReportService;
import com.hnp.backendofflinefirst.service.AssetReportService;
import com.hnp.backendofflinefirst.service.ExcelExportService;
import com.hnp.backendofflinefirst.service.ManagementReportService;
import com.hnp.backendofflinefirst.service.LogSheetAccessService;
import com.hnp.backendofflinefirst.ui.WebListSupport;
import com.hnp.backendofflinefirst.util.DateUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import com.hnp.backendofflinefirst.repository.OperationalUnitRepository;
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
    private final AssetHistoryViewService assetHistoryViewService;
    private final LogSheetAccessService logSheetAccessService;
    private final ManagementReportService managementReportService;
    private final ExcelExportService excelExportService;
    private final OperationalUnitRepository operationalUnitRepository;
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
     * One asset's status and activation history, merged into a single timeline.
     *
     * <p>Access is deliberately identical to {@code /reports/asset-parameters}: the same
     * {@code GET:/reports} authority to reach the page, and the same <em>reporting</em> scope
     * (responsibility through a log sheet, not location ownership) to reach a given asset. That
     * is what lets a supervisor open the history of an asset from a sheet they are responsible
     * for, exactly as they already open its parameter report — and what stops them opening the
     * history of an asset that is none of their business.
     *
     * <p>{@code statusLimit} caps the status rows only. Activation rows are unbounded because
     * they are written when someone deliberately switches an asset on or off — a handful over
     * an asset's life, against one status row per completed sheet.
     */
    @GetMapping("/asset-history")
    @PreAuthorize("hasAuthority('GET:/reports')")
    public String assetHistory(@RequestParam(required = false) Long assetId,
                               @RequestParam(required = false) String q,
                               @RequestParam(defaultValue = "200") int statusLimit,
                               Model model) {
        model.addAttribute("activePage", "reports-asset-history");

        String assetQuery = WebListSupport.normalizeQuery(q);
        Long resolvedAssetId = resolveAssetId(assetId, assetQuery, model);

        model.addAttribute("selectedAssetId", resolvedAssetId);
        model.addAttribute("assetSearch", assetQuery);
        model.addAttribute("assetOptions", loadAssetOptions(assetQuery, resolvedAssetId));

        int limit = Math.min(Math.max(statusLimit, 1), 1000);
        model.addAttribute("statusLimit", limit);

        var asset = assetParameterReportService.findAsset(resolvedAssetId);
        asset.ifPresent(a -> model.addAttribute("selectedAsset", a));

        List<AssetHistoryViewService.HistoryEvent> events = resolvedAssetId != null && asset.isPresent()
                ? assetHistoryViewService.timeline(resolvedAssetId, limit)
                : List.of();
        model.addAttribute("events", events);
        model.addAttribute("statusCount",
                AssetHistoryViewService.countOf(events, AssetHistoryViewService.EventKind.STATUS));
        model.addAttribute("activationCount",
                AssetHistoryViewService.countOf(events, AssetHistoryViewService.EventKind.ACTIVATION));
        // The cap is only worth mentioning when it actually bit; saying "showing the latest 200"
        // on a 3-row timeline is noise.
        model.addAttribute("statusTruncated",
                AssetHistoryViewService.countOf(events, AssetHistoryViewService.EventKind.STATUS) >= limit);

        return "reports/asset-history";
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
                             @RequestParam(required = false) Long unitId,
                             @RequestParam(defaultValue = "0") int page,
                             @RequestParam(defaultValue = "50") int size,
                             Model model) {
        long from = ManagementReportService.defaultWindowStart(clampDays(days));
        ManagementReportService.OutOfRangePage result =
                managementReportService.outOfRangePage(from, null, dangerOnly, unitId, page, size);

        model.addAttribute("activePage", "reports-exceptions");
        model.addAttribute("days", clampDays(days));
        model.addAttribute("dangerOnly", dangerOnly);
        model.addAttribute("unitId", unitId);
        model.addAttribute("rows", result.rows());
        model.addAttribute("resultPage", result);
        model.addAttribute("pageSize", result.size());
        // Only units this user can actually see — the filter narrows, it never widens. A null
        // set means "no unit restriction applies to this user" (admin), so offer them all.
        Set<Long> visible = assetAccessService.visibleUnitIds();
        model.addAttribute("units", visible == null
                ? operationalUnitRepository.findAll()
                : operationalUnitRepository.findAllById(visible));
        return "reports/exceptions";
    }

    /** Manual-vs-scanned ratio, NFC tag health, and assets nobody has read. */
    @GetMapping("/data-quality")
    @PreAuthorize("hasAuthority('GET:/reports')")
    public String dataQuality(@RequestParam(defaultValue = "30") int days,
                              @RequestParam(defaultValue = "0") int page,
                              @RequestParam(defaultValue = "50") int size,
                              Model model) {
        int window = clampDays(days);
        long from = ManagementReportService.defaultWindowStart(window);
        // Only the silent-asset section pages: it is the one that grows with the registry, and it
        // used to stop dead at a hundred rows — on a plant with more silent assets than that, the
        // equipment past the cap was invisible in the very report meant to surface it.
        ManagementReportService.SilentAssetPage silent =
                managementReportService.assetsWithoutRecentReadingsPage(from, page, size);
        model.addAttribute("activePage", "reports-data-quality");
        model.addAttribute("days", window);
        model.addAttribute("entrySources", managementReportService.entrySourceSplit(from, null));
        model.addAttribute("nfcFaults", managementReportService.openNfcFaults());
        model.addAttribute("silentAssets", silent.rows());
        model.addAttribute("silentPage", silent);
        model.addAttribute("pageSize", silent.size());
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
