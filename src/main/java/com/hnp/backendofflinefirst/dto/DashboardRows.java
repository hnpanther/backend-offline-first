package com.hnp.backendofflinefirst.dto;

import com.hnp.backendofflinefirst.domain.DashboardPeriod;

import java.util.List;

/**
 * Row shapes for the landing dashboard.
 *
 * <p>Grouped in one file for the same reason as {@link ManagementReportRows}: they are small,
 * purely presentational, and only ever produced by {@code DashboardService}.
 *
 * <p>Deliberately <b>not</b> a reuse of {@code ManagementReportRows.ComplianceRow}. That record
 * answers «was the work delivered on time», and its {@code onTime} / {@code late} columns are
 * meaningless for a round with no deadline. This one answers «what happened to the rounds raised
 * in this period», which needs the two halves of completion kept apart — approved and awaiting
 * review — and needs the readings those rounds produced. Widening {@code ComplianceRow} with
 * four more columns would have changed every number on {@code /reports/compliance} the day
 * somebody reordered its constructor.
 */
public final class DashboardRows {

    private DashboardRows() {}

    /**
     * One operational unit's census for the window, plus the readings its rounds produced.
     *
     * <p><b>Every counter here describes the same set of rounds</b> — the ones whose
     * {@code created_at} falls in the window — classified by the status they are in <em>now</em>.
     * That is what makes a row add up: {@code generated = completed + open + expired + cancelled
     * + voided}, always, with no round counted twice and none missing. It is also why the
     * readings are counted over the window's rounds rather than over their own reading time: a
     * row whose breach count came from a different set of rounds than its completion count would
     * be two rows printed as one.
     *
     * @param unitId          the unit, or {@code null} for the rounds that belong to no unit.
     *                        Only a plant-wide viewer ever sees that row — a scoped query filters
     *                        on {@code operational_unit_id IN (…)}, and {@code NULL IN (…)} is
     *                        never true
     * @param unitLabel       the unit's «code — name», or {@code null} on the no-unit row and on
     *                        the totals line. The Persian words for those two cases belong to the
     *                        template, not here: the service layer is English-only, and a label
     *                        the page owns is a label the page can reword
     * @param overdueOpen     of {@code open}, how many are already past {@code due_at} as of now.
     *                        A live figure inside a historical window, and the one number on the
     *                        row that changes between two reloads with no new work
     * @param warningReadings readings on this unit's completed rounds in the window whose stored
     *                        {@code max_severity} is {@code WARNING}
     * @param dangerReadings  the same for {@code DANGER}
     */
    public record UnitPeriodRow(
            Long unitId,
            String unitLabel,
            long generated,
            long completed,
            long approved,
            long open,
            long overdueOpen,
            long expired,
            long cancelled,
            long voided,
            long warningReadings,
            long dangerReadings) {

        /**
         * Completed but not yet signed off — the supervisor's queue.
         *
         * <p>Derived rather than counted, because {@code completed} already counts both
         * {@code SUBMITTED} and {@code APPROVED}: a separate SQL counter for {@code SUBMITTED}
         * alone would be a second definition of the same quantity, free to disagree with the
         * first the day somebody adds a third completed status.
         */
        public long awaitingApproval() {
            return completed - approved;
        }

        /**
         * Share of the period's rounds that have been delivered, 0–100.
         *
         * <p><b>Not the compliance rate</b>, which is {@code onTime / (submitted + expired +
         * cancelled)} and answers a question about deadlines ({@code docs/reports.md} §2). This
         * one is {@code completed / generated}: of everything raised in the window, how much is
         * done. Open work <em>is</em> in the denominator here, unlike there, and that is the
         * point — a period census that excluded work still in progress would read 100% on a day
         * whose rounds have barely started.
         */
        public double completionRate() {
            return generated == 0 ? 0d : (completed * 100d) / generated;
        }

        /** The totals line, summed from the rows already fetched rather than queried again. */
        public static UnitPeriodRow totalOf(List<UnitPeriodRow> rows) {
            long generated = 0, completed = 0, approved = 0, open = 0, overdueOpen = 0;
            long expired = 0, cancelled = 0, voided = 0, warning = 0, danger = 0;
            for (UnitPeriodRow row : rows) {
                generated += row.generated();
                completed += row.completed();
                approved += row.approved();
                open += row.open();
                overdueOpen += row.overdueOpen();
                expired += row.expired();
                cancelled += row.cancelled();
                voided += row.voided();
                warning += row.warningReadings();
                danger += row.dangerReadings();
            }
            return new UnitPeriodRow(null, null, generated, completed, approved, open,
                    overdueOpen, expired, cancelled, voided, warning, danger);
        }
    }

    /**
     * Everything the dashboard renders: the window it describes, the per-unit rows, their total,
     * and how much of the viewer's scope produced no rounds at all.
     *
     * @param rows              one per unit that raised at least one round in the window, busiest
     *                          first
     * @param total             the sum of {@code rows} — also the source of the KPI cards, so a
     *                          card and the table's last line cannot disagree
     * @param unitsInScope      how many operational units this viewer may see. {@code null} scope
     *                          (a plant-wide viewer) counts every unit; a scoped viewer counts
     *                          their own branch
     * @param unitsWithRounds   how many of those appear in {@code rows}
     * @param unassignedRounds  whether a row for «no unit» is present. Worth saying out loud
     *                          because such a round is invisible to every scoped viewer, so an
     *                          administrator seeing one is the only person who can act on it
     */
    public record PeriodSummary(
            DashboardPeriod period,
            long fromAt,
            long toAt,
            long lastIncludedAt,
            List<UnitPeriodRow> rows,
            UnitPeriodRow total,
            long unitsInScope,
            long unitsWithRounds,
            boolean unassignedRounds) {

        /**
         * Units the viewer may see that raised nothing in this window.
         *
         * <p>Reported rather than silently omitted. A unit with no rounds has no row — listing
         * every unit of a plant with hundreds of them would bury the ones that did work — but
         * «nothing was raised here» is exactly the fact a periodic review exists to surface, and
         * a table that simply lacked the row would say it only to a reader who already knew the
         * unit should be there. {@code docs/reports.md} records the same mistake made the other
         * way round: the data-quality report once reported zero silent assets on a plant where 46
         * had never been read.
         */
        public long unitsWithoutRounds() {
            return Math.max(0, unitsInScope - unitsWithRounds);
        }

        /** No units at all: a scoped user who belongs to nothing, before any SQL is run. */
        public static PeriodSummary noAccess(DashboardPeriod period, DashboardPeriod.Window window) {
            return new PeriodSummary(period, window.fromAt(), window.toAt(),
                    window.lastIncludedAt(), List.of(),
                    UnitPeriodRow.totalOf(List.of()), 0, 0, false);
        }
    }
}
