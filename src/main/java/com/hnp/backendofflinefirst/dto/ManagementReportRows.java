package com.hnp.backendofflinefirst.dto;

import com.hnp.backendofflinefirst.domain.FieldValidationSeverity;

/**
 * Row shapes for the management report pages.
 *
 * <p>Grouped in one file because they are small, purely presentational, and only ever
 * produced by {@code ManagementReportService} — keeping them together makes the shape of
 * each report readable in one place rather than spread over a dozen one-record files.
 */
public final class ManagementReportRows {

    private ManagementReportRows() {}

    /**
     * One row of the compliance table: how a unit's (or template's) work ended up.
     *
     * <p>{@code onTime} / {@code late} only count SUBMITTED sheets that actually had a
     * deadline — a sheet with no {@code dueAt} can be neither early nor late, so counting it
     * either way would quietly distort the rate.
     */
    public record ComplianceRow(
            Long groupId,
            String groupLabel,
            long total,
            long submitted,
            long onTime,
            long late,
            long expired,
            long cancelled,
            long voided,
            long open,
            Double avgLatenessMs,
            Long medianLatenessMs,
            Long p90LatenessMs) {

        /** Share of all finished work that was submitted before its deadline, 0–100. */
        public double complianceRate() {
            long finished = submitted + expired + cancelled;
            return finished == 0 ? 0d : (onTime * 100d) / finished;
        }

        public double submissionRate() {
            return total == 0 ? 0d : (submitted * 100d) / total;
        }
    }

    /** A single reading that fell outside its configured warning or danger range. */
    public record OutOfRangeRow(
            Long logSheetId,
            Long assetId,
            String assetCode,
            String assetName,
            String subFunctionTag,
            String fieldKey,
            String fieldLabel,
            String unit,
            Double value,
            FieldValidationSeverity severity,
            String rangeSummary,
            Long readingAt,
            Long operationalUnitId,
            String operatorName) {

        public boolean isDanger() {
            return severity == FieldValidationSeverity.DANGER;
        }
    }

    /** Manual-vs-scanned split for one operational unit. */
    public record EntrySourceRow(
            Long unitId,
            String unitLabel,
            long total,
            long manual,
            long scanned) {

        public double manualRate() {
            return total == 0 ? 0d : (manual * 100d) / total;
        }
    }

    /** An asset carrying at least one unresolved NFC fault report. */
    public record NfcHealthRow(
            Long assetId,
            String assetCode,
            String assetName,
            long openReports,
            Long oldestReportedAt,
            String lastReason) {}

    /** An asset that has not been read within the reporting window. */
    public record SilentAssetRow(
            Long assetId,
            String assetCode,
            String assetName,
            String subFunctionTag,
            Long lastReadingAt) {}

    /** Per-operator throughput and timeliness. */
    public record OperatorRow(
            Long userId,
            String username,
            String fullName,
            String personnelCode,
            String shift,
            long submitted,
            long late,
            Long avgHandlingMs) {

        public double lateRate() {
            return submitted == 0 ? 0d : (late * 100d) / submitted;
        }
    }

    /** Per-unit workload, including how work reaches operators. */
    public record UnitWorkloadRow(
            Long unitId,
            String unitLabel,
            long totalSheets,
            long activeOperators,
            long claimed,
            long assigned) {

        public double sheetsPerOperator() {
            return activeOperators == 0 ? 0d : (double) totalSheets / activeOperators;
        }

        public double selfServeRate() {
            long routed = claimed + assigned;
            return routed == 0 ? 0d : (claimed * 100d) / routed;
        }
    }

    /** One lifecycle action with the free-text reason its actor supplied. */
    public record ActionReasonRow(
            Long logSheetId,
            String templateName,
            String action,
            String actorName,
            Long actionAt,
            String comment) {}

    /** The executive summary numbers. */
    public record OverviewSummary(
            long generated,
            long submitted,
            long onTime,
            long expired,
            long voided,
            long openNow,
            long overdueNow,
            long dangerReadings,
            long warningReadings,
            long openNfcFaults,
            long manualEntries,
            long totalEntries) {

        public double complianceRate() {
            long finished = submitted + expired;
            return finished == 0 ? 0d : (onTime * 100d) / finished;
        }

        public double manualRate() {
            return totalEntries == 0 ? 0d : (manualEntries * 100d) / totalEntries;
        }
    }

    /** One bucket of the 12-month trend chart. */
    public record TrendPoint(
            String label,
            long generated,
            long submitted,
            long onTime) {

        public double complianceRate() {
            return submitted == 0 ? 0d : (onTime * 100d) / submitted;
        }
    }
}
