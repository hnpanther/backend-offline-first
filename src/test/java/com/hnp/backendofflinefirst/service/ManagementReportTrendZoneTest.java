package com.hnp.backendofflinefirst.service;

import com.hnp.backendofflinefirst.repository.AssetEntryRepository;
import com.hnp.backendofflinefirst.repository.LogSheetActionLogRepository;
import com.hnp.backendofflinefirst.repository.LogSheetEntryRepository;
import com.hnp.backendofflinefirst.repository.LogSheetRepository;
import com.hnp.backendofflinefirst.repository.NfcFaultReportRepository;
import com.hnp.backendofflinefirst.repository.UserRepository;
import com.hnp.backendofflinefirst.util.DateUtils;
import com.hnp.backendofflinefirst.util.ReferenceLabelService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The twelve-month trend's month boundaries belong to the plant, not to the host.
 *
 * <p>Asserted on the windows the service asks the database for, rather than on a rendered page,
 * because that is where the defect lived: {@code monthlyTrend} is correct for whatever zone it is
 * given, and the bug was the <em>argument</em> — {@code ReportWebController} passed
 * {@code ZoneId.systemDefault()}. {@code PlantZoneIsPinnedTest} guards the argument; this guards
 * what the argument means.
 */
@ExtendWith(MockitoExtension.class)
class ManagementReportTrendZoneTest {

    @Mock LogSheetRepository logSheetRepository;
    @Mock LogSheetEntryRepository logSheetEntryRepository;
    @Mock LogSheetActionLogRepository logSheetActionLogRepository;
    @Mock NfcFaultReportRepository nfcFaultReportRepository;
    @Mock AssetEntryRepository assetEntryRepository;
    @Mock UserRepository userRepository;
    @Mock AssetAccessService assetAccessService;
    @Mock ReferenceLabelService referenceLabelService;

    ManagementReportService service;

    @BeforeEach
    void setUp() {
        service = new ManagementReportService(
                logSheetRepository, logSheetEntryRepository, logSheetActionLogRepository,
                nfcFaultReportRepository, assetEntryRepository, userRepository,
                assetAccessService, referenceLabelService);
        // null means "unrestricted" — an administrator. An empty set would mean no access at all
        // and would return before any window is computed.
        when(assetAccessService.visibleUnitIds()).thenReturn(null);
        when(logSheetRepository.complianceByUnit(any(), any(), any())).thenReturn(List.of());
    }

    @Test
    void everyBucketStartsAtMidnightTehranOnTheFirstOfAMonth() {
        service.monthlyTrend(DateUtils.TEHRAN);

        List<Long> starts = capturedWindowStarts();

        assertThat(starts).hasSize(12);
        for (Long start : starts) {
            ZonedDateTime boundary = Instant.ofEpochMilli(start).atZone(DateUtils.TEHRAN);
            assertThat(boundary.getDayOfMonth()).as("day of month at %s", boundary).isEqualTo(1);
            assertThat(boundary.getHour()).as("hour at %s", boundary).isZero();
            assertThat(boundary.getMinute()).as("minute at %s", boundary).isZero();
        }
    }

    @Test
    void theTwelfthBucketIsTheCurrentTehranMonth() {
        service.monthlyTrend(DateUtils.TEHRAN);

        ZonedDateTime last = Instant.ofEpochMilli(capturedWindowStarts().get(11))
                .atZone(DateUtils.TEHRAN);
        ZonedDateTime nowInTehran = ZonedDateTime.now(DateUtils.TEHRAN);

        // The case this pins: between 00:00 and 03:30 Tehran on the first of a month, a UTC host
        // is still in the previous month, so the trend's last column was the month before the one
        // the plant is in — the new month had no column at all.
        assertThat(last.getYear()).isEqualTo(nowInTehran.getYear());
        assertThat(last.getMonthValue()).isEqualTo(nowInTehran.getMonthValue());
    }

    @Test
    void aHostZoneWouldHaveMovedEveryBoundaryByThreeAndAHalfHours() {
        // Not a test of desired behaviour — a record of the defect, so that "it was only three and
        // a half hours" cannot be argued without facing what it did to a month boundary.
        service.monthlyTrend(ZoneId.of("UTC"));

        for (Long start : capturedWindowStarts()) {
            ZonedDateTime boundary = Instant.ofEpochMilli(start).atZone(DateUtils.TEHRAN);
            assertThat(boundary.getHour()).isEqualTo(3);
            assertThat(boundary.getMinute()).isEqualTo(30);
        }
    }

    private List<Long> capturedWindowStarts() {
        ArgumentCaptor<Long> from = ArgumentCaptor.forClass(Long.class);
        verify(logSheetRepository, times(12)).complianceByUnit(any(), from.capture(), any());
        return from.getAllValues();
    }
}
