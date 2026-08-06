package com.hnp.backendofflinefirst.dto;

import com.hnp.backendofflinefirst.dto.ManagementReportRows.ComplianceRow;
import com.hnp.backendofflinefirst.dto.ManagementReportRows.EntrySourceRow;
import com.hnp.backendofflinefirst.dto.ManagementReportRows.OperatorRow;
import com.hnp.backendofflinefirst.dto.ManagementReportRows.OverviewSummary;
import com.hnp.backendofflinefirst.dto.ManagementReportRows.UnitWorkloadRow;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The derived rates on the report rows.
 *
 * <p>These are the numbers a manager acts on, and every one of them has a denominator that
 * is easy to get subtly wrong — so each case here pins down <em>what is being divided by
 * what</em>, not just that the arithmetic runs.
 */
class ManagementReportRowsTest {

    private static ComplianceRow compliance(long total, long submitted, long onTime,
                                            long late, long expired, long cancelled,
                                            long voided, long open) {
        return new ComplianceRow(1L, "واحد", total, submitted, onTime, late,
                expired, cancelled, voided, open, null, null, null);
    }

    @Test
    void complianceRateDividesOnTimeByFinishedWorkOnly() {
        // 10 raised: 6 on time, 2 late, 1 expired, 1 still open.
        // Finished = 8 submitted + 1 expired + 0 cancelled = 9 → 6/9.
        ComplianceRow row = compliance(10, 8, 6, 2, 1, 0, 0, 1);

        assertThat(row.complianceRate())
                .as("open work has not had its chance yet and must not count against the rate")
                .isEqualTo(6 * 100d / 9);
    }

    @Test
    void complianceRateCountsCancelledAgainstTheUnit() {
        // Cancelling work is a failure to perform it, so it belongs in the denominator —
        // otherwise a unit could cancel everything it could not finish and score 100%.
        ComplianceRow withCancellations = compliance(10, 5, 5, 0, 0, 5, 0, 0);

        assertThat(withCancellations.complianceRate()).isEqualTo(50d);
    }

    @Test
    void ratesAreZeroRatherThanNaNWhenNothingHappened() {
        ComplianceRow empty = compliance(0, 0, 0, 0, 0, 0, 0, 0);

        assertThat(empty.complianceRate()).isZero();
        assertThat(empty.submissionRate()).isZero();
    }

    @Test
    void submissionRateIsOutOfEverythingRaisedIncludingStillOpen() {
        ComplianceRow row = compliance(10, 4, 4, 0, 0, 0, 0, 6);

        assertThat(row.submissionRate()).isEqualTo(40d);
    }

    @Test
    void manualRateReportsTheShareOfReadingsTakenWithoutAScan() {
        EntrySourceRow row = new EntrySourceRow(1L, "واحد", 200, 50, 150);

        assertThat(row.manualRate()).isEqualTo(25d);
        assertThat(new EntrySourceRow(1L, "واحد", 0, 0, 0).manualRate()).isZero();
    }

    @Test
    void operatorLateRateIsOutOfTheirOwnCompletedWork() {
        OperatorRow row = new OperatorRow(1L, "op", "اپراتور", "EMP-1", "A", 20, 5, 3_600_000L);

        assertThat(row.lateRate()).isEqualTo(25d);
        assertThat(new OperatorRow(2L, "idle", null, null, null, 0, 0, null).lateRate())
                .as("an operator who completed nothing is not 100% late")
                .isZero();
    }

    @Test
    void workloadPerOperatorAndSelfServeShareHandleEmptyUnits() {
        UnitWorkloadRow staffed = new UnitWorkloadRow(1L, "واحد", 30, 3, 18, 6);
        assertThat(staffed.sheetsPerOperator()).isEqualTo(10d);
        // Self-serve is measured against routed work (claimed + assigned), not the total —
        // sheets nobody has picked up yet say nothing about how work reaches people.
        assertThat(staffed.selfServeRate()).isEqualTo(18 * 100d / 24);

        UnitWorkloadRow unstaffed = new UnitWorkloadRow(2L, "خالی", 5, 0, 0, 0);
        assertThat(unstaffed.sheetsPerOperator()).as("no division by zero").isZero();
        assertThat(unstaffed.selfServeRate()).isZero();
    }

    @Test
    void overviewComplianceIgnoresWorkThatIsStillOpen() {
        OverviewSummary summary = new OverviewSummary(
                100, 80, 60, 10, 5, 10, 3, 4, 9, 2, 30, 300);

        assertThat(summary.complianceRate()).isEqualTo(60 * 100d / 90);
        assertThat(summary.manualRate()).isEqualTo(10d);
    }

    @Test
    void overviewRatesStayZeroOnAnEmptySystem() {
        OverviewSummary empty = new OverviewSummary(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);

        assertThat(empty.complianceRate()).isZero();
        assertThat(empty.manualRate()).isZero();
    }
}
