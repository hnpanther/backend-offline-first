package com.hnp.backendofflinefirst.service;

import com.hnp.backendofflinefirst.domain.FieldValidationSeverity;
import com.hnp.backendofflinefirst.domain.FieldValidationSupport;
import com.hnp.backendofflinefirst.domain.FieldDefinitionSnapshot;
import com.hnp.backendofflinefirst.dto.ManagementReportRows.ActionReasonRow;
import com.hnp.backendofflinefirst.dto.ManagementReportRows.ComplianceRow;
import com.hnp.backendofflinefirst.dto.ManagementReportRows.EntrySourceRow;
import com.hnp.backendofflinefirst.dto.ManagementReportRows.NfcHealthRow;
import com.hnp.backendofflinefirst.dto.ManagementReportRows.OperatorRow;
import com.hnp.backendofflinefirst.dto.ManagementReportRows.OutOfRangeRow;
import com.hnp.backendofflinefirst.dto.ManagementReportRows.OverviewSummary;
import com.hnp.backendofflinefirst.dto.ManagementReportRows.SilentAssetRow;
import com.hnp.backendofflinefirst.dto.ManagementReportRows.TrendPoint;
import com.hnp.backendofflinefirst.dto.ManagementReportRows.UnitWorkloadRow;
import com.hnp.backendofflinefirst.entity.AssetEntry;
import com.hnp.backendofflinefirst.entity.LogSheet;
import com.hnp.backendofflinefirst.entity.LogSheetEntry;
import com.hnp.backendofflinefirst.entity.NfcFaultReport;
import com.hnp.backendofflinefirst.entity.User;
import com.hnp.backendofflinefirst.repository.AssetEntryRepository;
import com.hnp.backendofflinefirst.repository.LogSheetActionLogRepository;
import com.hnp.backendofflinefirst.repository.LogSheetEntryRepository;
import com.hnp.backendofflinefirst.repository.LogSheetRepository;
import com.hnp.backendofflinefirst.repository.NfcFaultReportRepository;
import com.hnp.backendofflinefirst.repository.UserRepository;
import com.hnp.backendofflinefirst.util.ReferenceLabelService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Aggregations behind the management report pages.
 *
 * <p>Everything here is <strong>read-only and unit-scoped</strong>: each method takes the
 * caller's accessible unit ids from {@link AssetAccessService#visibleUnitIds()}, where
 * {@code null} means unrestricted (ADMIN / HIGH_USER) and an empty set means no access.
 * That is the same convention the rest of the reporting layer uses, and it already carries
 * the downward expansion of a supervisor's branch, so a parent-unit manager sees their
 * children's numbers without any extra work here.
 *
 * <p>Counting windows use {@code createdAt} — a sheet belongs to the period it was
 * <em>raised</em> in, not the period it happened to be closed in. Otherwise a batch of old
 * overdue work finished today would flatter today's compliance and hollow out last month's.
 */
@Service
@RequiredArgsConstructor
public class ManagementReportService {

    /**
     * Page size for the exception list.
     *
     * <p>Only a display cap now: severity is read from an indexed column rather than
     * evaluated, so this bounds how much a single page renders, not how much work the query
     * does. A person triaging cannot act on thousands of lines at once anyway.
     */
    public static final int OUT_OF_RANGE_ROW_LIMIT = 1000;

    private final LogSheetRepository logSheetRepository;
    private final LogSheetEntryRepository logSheetEntryRepository;
    private final LogSheetActionLogRepository logSheetActionLogRepository;
    private final NfcFaultReportRepository nfcFaultReportRepository;
    private final AssetEntryRepository assetEntryRepository;
    private final UserRepository userRepository;
    private final AssetAccessService assetAccessService;
    private final ReferenceLabelService referenceLabelService;

    // ── Compliance & lateness ─────────────────────────────────────────────────

    public List<ComplianceRow> complianceByUnit(Long from, Long to) {
        Set<Long> unitIds = assetAccessService.visibleUnitIds();
        if (isNoAccess(unitIds)) return List.of();

        Map<Long, String> unitLabels = referenceLabelService.operationalUnitLabels();
        Map<Long, LatenessStats> lateness = latenessByUnit(unitIds, from, to);

        return logSheetRepository.complianceByUnit(unitIds, from, to).stream()
                .map(r -> {
                    Long unitId = (Long) r[0];
                    LatenessStats st = lateness.getOrDefault(unitId, LatenessStats.EMPTY);
                    return new ComplianceRow(
                            unitId,
                            unitId == null ? "—" : unitLabels.getOrDefault(unitId, "#" + unitId),
                            num(r[1]), num(r[2]), num(r[3]), num(r[4]),
                            num(r[5]), num(r[6]), num(r[7]), num(r[8]),
                            st.average(), st.median(), st.p90());
                })
                .sorted(Comparator.comparingLong(ComplianceRow::total).reversed())
                .toList();
    }

    public List<ComplianceRow> complianceByTemplate(Long from, Long to) {
        Set<Long> unitIds = assetAccessService.visibleUnitIds();
        if (isNoAccess(unitIds)) return List.of();

        return logSheetRepository.complianceByTemplate(unitIds, from, to).stream()
                .map(r -> new ComplianceRow(
                        null,
                        (String) r[0],
                        num(r[1]), num(r[2]), num(r[3]), num(r[4]),
                        num(r[5]), num(r[6]), num(r[7]), num(r[8]),
                        null, null, null))
                .sorted(Comparator.comparingLong(ComplianceRow::total).reversed())
                .toList();
    }

    private Map<Long, LatenessStats> latenessByUnit(Set<Long> unitIds, Long from, Long to) {
        Map<Long, List<Long>> byUnit = new HashMap<>();
        for (Object[] r : logSheetRepository.latenessSamples(unitIds, from, to)) {
            Long unitId = (Long) r[0];
            Number delta = (Number) r[1];
            if (delta == null) continue;
            // Only overshoot counts. Finishing early is not "negative lateness" — averaging
            // it in would let punctual work cancel out overdue work and hide the problem.
            long ms = delta.longValue();
            if (ms <= 0) continue;
            byUnit.computeIfAbsent(unitId, k -> new ArrayList<>()).add(ms);
        }
        Map<Long, LatenessStats> out = new HashMap<>();
        byUnit.forEach((unitId, samples) -> out.put(unitId, LatenessStats.of(samples)));
        return out;
    }

    /** Percentiles computed in Java because the sample sets are small and already fetched. */
    private record LatenessStats(Double average, Long median, Long p90) {
        static final LatenessStats EMPTY = new LatenessStats(null, null, null);

        static LatenessStats of(List<Long> samples) {
            if (samples.isEmpty()) return EMPTY;
            List<Long> sorted = samples.stream().sorted().toList();
            double avg = sorted.stream().mapToLong(Long::longValue).average().orElse(0);
            return new LatenessStats(avg, percentile(sorted, 0.5), percentile(sorted, 0.9));
        }

        private static Long percentile(List<Long> sorted, double p) {
            if (sorted.isEmpty()) return null;
            int idx = (int) Math.ceil(p * sorted.size()) - 1;
            return sorted.get(Math.max(0, Math.min(idx, sorted.size() - 1)));
        }
    }

    // ── Out-of-range exceptions ───────────────────────────────────────────────

    /**
     * Every submitted reading in the window that breached its warning or danger range.
     *
     * <p>Reads {@code log_sheet_entries.max_severity} / {@code breached_fields}, which are
     * computed by {@code EntrySeverityEvaluator} at write time. Nothing is re-evaluated here:
     * one indexed query replaces what used to be a bounded scan that deserialised up to 500
     * sheets, and it means an external poller can ask the same question at the same cost.
     *
     * <p>Rows written before the flag existed have {@code max_severity = NULL} and are
     * invisible here until backfilled — a null means "never evaluated", deliberately distinct
     * from {@code OK}.
     */
    public List<OutOfRangeRow> outOfRangeReadings(Long from, Long to, boolean dangerOnly) {
        return outOfRangePage(from, to, dangerOnly, null, 0, OUT_OF_RANGE_ROW_LIMIT).rows();
    }

    /**
     * One page of breached readings, plus what the pager needs to draw itself.
     *
     * <p>Paging is on the <b>entry</b>, because that is what the indexed query returns and what
     * gives stable page boundaries. An entry breaching two parameters still renders as two
     * lines, so a page can show slightly more lines than its size — reported honestly by
     * {@link OutOfRangePage#totalEntries()} rather than papered over.
     *
     * @param unitId optional single-unit narrowing, always intersected with what the caller may
     *               actually see — a filter must never widen access
     */
    public OutOfRangePage outOfRangePage(Long from, Long to, boolean dangerOnly, Long unitId,
                                         int page, int size) {
        Set<Long> unitIds = assetAccessService.visibleUnitIds();
        if (isNoAccess(unitIds)) return OutOfRangePage.empty(page, size);

        Set<Long> effectiveUnits = unitIds;
        if (unitId != null) {
            // Intersect, never replace: a chosen unit the caller cannot see must yield nothing,
            // not a widened query.
            if (unitIds != null && !unitIds.contains(unitId)) {
                return OutOfRangePage.empty(page, size);
            }
            effectiveUnits = Set.of(unitId);
        }

        int safeSize = Math.min(Math.max(size, 1), OUT_OF_RANGE_ROW_LIMIT);
        int safePage = Math.max(page, 0);

        long totalEntries = logSheetEntryRepository.countBreachedEntries(
                effectiveUnits, from, to, dangerOnly);

        List<Object[]> raw = logSheetEntryRepository.findBreachedEntries(
                effectiveUnits, from, to, dangerOnly, PageRequest.of(safePage, safeSize));
        List<OutOfRangeRow> rows = expandBreachRows(raw, dangerOnly);
        return new OutOfRangePage(rows, totalEntries, safePage, safeSize);
    }

    /** A page of breach lines with the counts a pager needs. */
    public record OutOfRangePage(List<OutOfRangeRow> rows, long totalEntries, int page, int size) {
        public static OutOfRangePage empty(int page, int size) {
            return new OutOfRangePage(List.of(), 0, Math.max(page, 0), Math.max(size, 1));
        }

        public int totalPages() {
            return size <= 0 ? 1 : (int) Math.max(1, Math.ceil(totalEntries / (double) size));
        }

        public boolean hasPrevious() {
            return page > 0;
        }

        public boolean hasNext() {
            return page + 1 < totalPages();
        }
    }

    private List<OutOfRangeRow> expandBreachRows(List<Object[]> raw, boolean dangerOnly) {
        if (raw.isEmpty()) return List.of();

        List<LogSheetEntry> entries = raw.stream().map(r -> (LogSheetEntry) r[0]).toList();
        Map<Long, AssetEntry> assets = assetsByIdFor(entries);

        List<OutOfRangeRow> rows = new ArrayList<>();
        for (Object[] r : raw) {
            LogSheetEntry entry = (LogSheetEntry) r[0];
            LogSheet sheet = (LogSheet) r[1];
            FieldValidationSeverity severity = severityOf(entry);
            if (severity == null) continue;

            Map<String, FieldDefinitionSnapshot> defs = definitionsFor(sheet, entry.getClassId());
            AssetEntry asset = assets.get(entry.getAssetId());
            Long readingAt = sheet.getCompletedAt() != null ? sheet.getCompletedAt() : sheet.getSubmittedAt();

            // One row per offending key, so a single entry breaching two parameters shows up
            // as two lines — that is what a person triaging needs to see.
            for (String key : entry.getBreachedFields() == null ? List.<String>of() : entry.getBreachedFields()) {
                FieldDefinitionSnapshot def = defs.get(key);
                Object value = entry.getFormData() == null ? null : entry.getFormData().get(key);
                FieldValidationSeverity keySeverity = def != null && def.getValidation() != null
                        ? FieldValidationSupport.evaluateNumericValue(value, def.getValidation())
                        : severity;
                if (dangerOnly && keySeverity != FieldValidationSeverity.DANGER) continue;

                rows.add(new OutOfRangeRow(
                        sheet.getId(),
                        entry.getAssetId(),
                        asset != null ? asset.getAssetCode() : null,
                        entry.getAssetName(),
                        entry.getSubFunctionTag(),
                        key,
                        def != null && def.getLabel() != null ? def.getLabel() : key,
                        def != null ? def.getUnit() : null,
                        toDouble(value),
                        keySeverity,
                        def != null ? FieldValidationSupport.summaryFa(def.getValidation()) : null,
                        readingAt,
                        sheet.getOperationalUnitId(),
                        sheet.getOperatorName()));
            }
        }
        return rows;
    }

    /** Parses the stored severity string; unknown or OK values yield null (nothing to show). */
    private static FieldValidationSeverity severityOf(LogSheetEntry entry) {
        String stored = entry.getMaxSeverity();
        if (stored == null) return null;
        try {
            FieldValidationSeverity severity = FieldValidationSeverity.valueOf(stored);
            return severity == FieldValidationSeverity.OK ? null : severity;
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private Map<String, FieldDefinitionSnapshot> definitionsFor(LogSheet sheet, Long classId) {
        List<FieldDefinitionSnapshot> snapshot = sheet.getFieldDefinitionsSnapshot();
        if (snapshot == null || snapshot.isEmpty()) return Map.of();
        Map<String, FieldDefinitionSnapshot> out = new LinkedHashMap<>();
        for (FieldDefinitionSnapshot def : snapshot) {
            // A multi-class sheet snapshots definitions for every class it covers, so filter
            // to the entry's own class — otherwise a key shared between two classes could be
            // judged against the wrong ranges.
            if (classId != null && def.getClassId() != null && !classId.equals(def.getClassId())) continue;
            if (def.getKey() != null) out.putIfAbsent(def.getKey(), def);
        }
        return out;
    }

    // ── Data quality ──────────────────────────────────────────────────────────

    public List<EntrySourceRow> entrySourceSplit(Long from, Long to) {
        Set<Long> unitIds = assetAccessService.visibleUnitIds();
        if (isNoAccess(unitIds)) return List.of();

        Map<Long, String> unitLabels = referenceLabelService.operationalUnitLabels();
        return logSheetEntryRepository.entrySourceSplitByUnit(unitIds, from, to).stream()
                .map(r -> {
                    Long unitId = (Long) r[0];
                    long total = num(r[1]);
                    long manual = num(r[2]);
                    return new EntrySourceRow(
                            unitId,
                            unitId == null ? "—" : unitLabels.getOrDefault(unitId, "#" + unitId),
                            total, manual, total - manual);
                })
                .sorted(Comparator.comparingDouble(EntrySourceRow::manualRate).reversed())
                .toList();
    }

    public List<NfcHealthRow> openNfcFaults() {
        Set<Long> unitIds = assetAccessService.visibleUnitIds();
        if (isNoAccess(unitIds)) return List.of();

        List<NfcFaultReport> reports = nfcFaultReportRepository.findOpenForUnits(unitIds);
        if (reports.isEmpty()) return List.of();

        Map<Long, AssetEntry> assets = assetEntryRepository.findAllById(
                        reports.stream().map(NfcFaultReport::getAssetId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(AssetEntry::getId, a -> a, (a, b) -> a));

        Map<Long, List<NfcFaultReport>> byAsset = reports.stream()
                .collect(Collectors.groupingBy(NfcFaultReport::getAssetId));

        return byAsset.entrySet().stream()
                .map(e -> {
                    List<NfcFaultReport> list = e.getValue();
                    AssetEntry asset = assets.get(e.getKey());
                    Long oldest = list.stream()
                            .map(NfcFaultReport::getCreatedAt)
                            .filter(Objects::nonNull)
                            .min(Long::compareTo)
                            .orElse(null);
                    String lastReason = list.stream()
                            .max(Comparator.comparing(r -> r.getCreatedAt() == null ? 0L : r.getCreatedAt()))
                            .map(NfcFaultReport::getReason)
                            .orElse(null);
                    return new NfcHealthRow(
                            e.getKey(),
                            asset != null ? asset.getAssetCode() : null,
                            asset != null ? asset.getAssetName() : null,
                            list.size(), oldest, lastReason);
                })
                // Oldest unresolved first: this is a maintenance queue, not a leaderboard.
                .sorted(Comparator.comparing(r -> r.oldestReportedAt() == null ? Long.MAX_VALUE : r.oldestReportedAt()))
                .toList();
    }

    /**
     * Assets with no submitted reading since {@code since}.
     *
     * <p>Uses the reporting scope, so an asset reached only through a log sheet still counts
     * as the caller's to watch — the blind spots this is meant to surface are exactly the
     * ones an ownership-only filter would hide.
     */
    public List<SilentAssetRow> assetsWithoutRecentReadings(long since, int limit) {
        Set<Long> unitIds = assetAccessService.visibleUnitIds();
        if (isNoAccess(unitIds)) return List.of();

        // One scoped query, ordered and limited in the database. The previous shape pulled
        // `limit * 4` assets and filtered them in Java: fine at a hundred assets, and quietly
        // wrong at ten thousand, where it reported whichever slice the paging query returned
        // rather than the plant's actual worst offenders. Ranking only works where the whole
        // set is visible.
        int safeLimit = Math.min(Math.max(limit, 1), 500);
        // null means unrestricted, and the scoped query's CTE cannot express that — binding a
        // null unit list matches nothing, which showed an admin an empty section.
        List<Object[]> rows = unitIds == null
                ? assetEntryRepository.findSilentAssetsUnrestricted(since, safeLimit)
                : assetEntryRepository.findSilentAssets(unitIds, since, safeLimit);
        return rows.stream()
                .map(r -> new SilentAssetRow(
                        r[0] == null ? null : ((Number) r[0]).longValue(),
                        (String) r[1],
                        (String) r[2],
                        r[3] == null ? null : referenceLabelService.subFunctionLabel(((Number) r[3]).longValue()),
                        r[4] == null ? null : ((Number) r[4]).longValue()))
                .toList();
    }

    // ── Workforce ─────────────────────────────────────────────────────────────

    public List<OperatorRow> operatorProductivity(Long from, Long to) {
        Set<Long> unitIds = assetAccessService.visibleUnitIds();
        if (isNoAccess(unitIds)) return List.of();

        List<Object[]> raw = logSheetRepository.operatorThroughput(unitIds, from, to);
        if (raw.isEmpty()) return List.of();

        Map<Long, User> users = userRepository.findAllById(
                        raw.stream().map(r -> (Long) r[0]).filter(Objects::nonNull).toList())
                .stream().collect(Collectors.toMap(User::getId, u -> u, (a, b) -> a));

        return raw.stream()
                .map(r -> {
                    User u = users.get((Long) r[0]);
                    Number avg = (Number) r[3];
                    return new OperatorRow(
                            (Long) r[0],
                            u != null ? u.getUsername() : "—",
                            u != null ? u.getFullName() : null,
                            u != null ? u.getPersonnelCode() : null,
                            u != null ? u.getShift() : null,
                            num(r[1]), num(r[2]),
                            avg == null ? null : avg.longValue());
                })
                .sorted(Comparator.comparingLong(OperatorRow::submitted).reversed())
                .toList();
    }

    public List<UnitWorkloadRow> unitWorkload(Long from, Long to) {
        Set<Long> unitIds = assetAccessService.visibleUnitIds();
        if (isNoAccess(unitIds)) return List.of();

        Map<Long, String> unitLabels = referenceLabelService.operationalUnitLabels();
        Map<Long, Long> operatorsPerUnit = new HashMap<>();
        for (Object[] r : logSheetRepository.operatorCountPerUnit(unitIds)) {
            operatorsPerUnit.put((Long) r[0], num(r[1]));
        }

        return logSheetRepository.unitWorkload(unitIds, from, to).stream()
                .map(r -> {
                    Long unitId = (Long) r[0];
                    return new UnitWorkloadRow(
                            unitId,
                            unitId == null ? "—" : unitLabels.getOrDefault(unitId, "#" + unitId),
                            num(r[1]),
                            operatorsPerUnit.getOrDefault(unitId, 0L),
                            num(r[2]), num(r[3]));
                })
                .sorted(Comparator.comparingLong(UnitWorkloadRow::totalSheets).reversed())
                .toList();
    }

    // ── Action reasons ────────────────────────────────────────────────────────

    /**
     * Lifecycle actions that carry an explanation.
     *
     * <p>Only EXTEND / CANCEL / VOID / UNVOID / ADMIN_REOPEN ever record a comment, and it is
     * optional even there — this is the only place in the system where the <em>why</em> behind
     * a deadline change or an invalidation is written down, which is what makes it worth a page.
     */
    public List<ActionReasonRow> actionReasons(Long from, Long to, int limit) {
        Set<Long> unitIds = assetAccessService.visibleUnitIds();
        if (isNoAccess(unitIds)) return List.of();

        List<Object[]> raw = logSheetActionLogRepository.findExplainedActions(
                unitIds, from, to, PageRequest.of(0, limit));

        Map<Long, User> actors = userRepository.findAllById(
                        raw.stream().map(r -> (Long) r[3]).filter(Objects::nonNull).toList())
                .stream().collect(Collectors.toMap(User::getId, u -> u, (a, b) -> a));

        return raw.stream()
                .map(r -> {
                    User actor = actors.get((Long) r[3]);
                    return new ActionReasonRow(
                            (Long) r[0],
                            (String) r[1],
                            String.valueOf(r[2]),
                            actor != null ? (actor.getFullName() != null ? actor.getFullName() : actor.getUsername()) : "—",
                            r[4] == null ? null : ((Number) r[4]).longValue(),
                            (String) r[5]);
                })
                .toList();
    }

    // ── Executive overview ────────────────────────────────────────────────────

    public OverviewSummary overview(Long from, Long to) {
        Set<Long> unitIds = assetAccessService.visibleUnitIds();
        if (isNoAccess(unitIds)) {
            return new OverviewSummary(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        }

        long generated = 0, submitted = 0, onTime = 0, expired = 0, voided = 0;
        for (ComplianceRow row : complianceByUnit(from, to)) {
            generated += row.total();
            submitted += row.submitted();
            onTime += row.onTime();
            expired += row.expired();
            voided += row.voided();
        }

        long openNow = 0, overdueNow = 0;
        List<Object[]> open = logSheetRepository.openWorkloadNow(unitIds, System.currentTimeMillis());
        if (!open.isEmpty() && open.get(0) != null) {
            openNow = num(open.get(0)[0]);
            overdueNow = num(open.get(0)[1]);
        }

        // Counted in SQL, not by fetching rows: the row query is page-capped, so counting
        // what it returned under-reported any period busier than that cap.
        long danger = 0, warning = 0;
        for (Object[] r : logSheetEntryRepository.countBreachesBySeverity(unitIds, from, to)) {
            if (FieldValidationSeverity.DANGER.name().equals(r[0])) {
                danger = num(r[1]);
            } else {
                warning += num(r[1]);
            }
        }

        long manual = 0, totalEntries = 0;
        for (EntrySourceRow row : entrySourceSplit(from, to)) {
            manual += row.manual();
            totalEntries += row.total();
        }

        return new OverviewSummary(
                generated, submitted, onTime, expired, voided,
                openNow, overdueNow,
                danger, warning,
                openNfcFaults().stream().mapToLong(NfcHealthRow::openReports).sum(),
                manual, totalEntries);
    }

    /** Twelve calendar months ending with the current one. */
    public List<TrendPoint> monthlyTrend(ZoneId zone) {
        Set<Long> unitIds = assetAccessService.visibleUnitIds();
        if (isNoAccess(unitIds)) return List.of();

        List<TrendPoint> points = new ArrayList<>();
        ZonedDateTime cursor = ZonedDateTime.now(zone).withDayOfMonth(1)
                .truncatedTo(java.time.temporal.ChronoUnit.DAYS).minusMonths(11);
        for (int i = 0; i < 12; i++) {
            long start = cursor.toInstant().toEpochMilli();
            long end = cursor.plusMonths(1).toInstant().toEpochMilli() - 1;
            long generated = 0, submitted = 0, onTime = 0;
            for (Object[] r : logSheetRepository.complianceByUnit(unitIds, start, end)) {
                generated += num(r[1]);
                submitted += num(r[2]);
                onTime += num(r[3]);
            }
            points.add(new TrendPoint(monthLabel(cursor), generated, submitted, onTime));
            cursor = cursor.plusMonths(1);
        }
        return points;
    }

    private static String monthLabel(ZonedDateTime month) {
        return month.getYear() + "-" + String.format("%02d", month.getMonthValue());
    }

    // ── Shared helpers ────────────────────────────────────────────────────────

    /** Empty set means "scoped user with no units" — no rows, as opposed to null = admin. */
    private static boolean isNoAccess(Set<Long> unitIds) {
        return unitIds != null && unitIds.isEmpty();
    }

    private Map<Long, AssetEntry> assetsByIdFor(List<LogSheetEntry> entries) {
        Set<Long> ids = entries.stream()
                .map(LogSheetEntry::getAssetId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (ids.isEmpty()) return Map.of();
        return assetEntryRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(AssetEntry::getId, a -> a, (a, b) -> a));
    }

    private static long num(Object value) {
        return value instanceof Number n ? n.longValue() : 0L;
    }

    private static Double toDouble(Object value) {
        if (value instanceof Number n) return n.doubleValue();
        if (value instanceof String str) {
            try {
                return Double.parseDouble(str.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    public static long defaultWindowStart(int days) {
        return Instant.now().minus(days, java.time.temporal.ChronoUnit.DAYS).toEpochMilli();
    }
}
