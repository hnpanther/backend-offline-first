package com.hnp.backendofflinefirst.integration;

import com.hnp.backendofflinefirst.domain.GenerationMode;
import com.hnp.backendofflinefirst.domain.LogSheetEntrySource;
import com.hnp.backendofflinefirst.domain.LogSheetStatus;
import com.hnp.backendofflinefirst.entity.AssetEntry;
import com.hnp.backendofflinefirst.entity.Location;
import com.hnp.backendofflinefirst.entity.LogSheet;
import com.hnp.backendofflinefirst.entity.LogSheetEntry;
import com.hnp.backendofflinefirst.entity.OperationalUnit;
import com.hnp.backendofflinefirst.entity.SubFunction;
import com.hnp.backendofflinefirst.repository.AssetEntryRepository;
import com.hnp.backendofflinefirst.repository.LogSheetEntryRepository;
import com.hnp.backendofflinefirst.repository.LogSheetRepository;
import com.hnp.backendofflinefirst.repository.OperationalUnitRepository;
import com.hnp.backendofflinefirst.service.AssetHierarchyService;
import com.hnp.backendofflinefirst.dto.ManagementReportRows;
import com.hnp.backendofflinefirst.service.ManagementReportService;
import com.hnp.backendofflinefirst.support.AbstractPostgresIntegrationTest;
import com.hnp.backendofflinefirst.support.WithAppUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The data-quality report's two counting rules, both of which were wrong.
 *
 * <p>A log sheet is raised with <b>one entry per asset</b> and submitted whether or not every
 * asset was reached. Both sections used to treat those untouched entries as readings:
 *
 * <ul>
 *   <li>the manual-vs-scanned split divided by every entry, so on live data a unit with 94
 *       entries of which 3 held a reading showed an all-manual round as 2%;</li>
 *   <li>silent-asset detection treated "appeared on a submitted sheet" as "was read", so an
 *       asset nobody had inspected looked freshly checked — the one error this section exists
 *       to prevent. Live, it reported zero silent assets while 46 had never been read.</li>
 * </ul>
 *
 * <p>Both now key on {@code max_severity IS NOT NULL}, which is exact: the severity evaluator
 * nulls it when form data is empty and always writes at least {@code OK} when it is not.
 */
@WithAppUser(roles = "ADMIN")
@Transactional
class DataQualityReportIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired ManagementReportService managementReportService;
    @Autowired AssetEntryRepository assetEntryRepository;
    @Autowired LogSheetRepository logSheetRepository;
    @Autowired LogSheetEntryRepository logSheetEntryRepository;
    @Autowired OperationalUnitRepository operationalUnitRepository;
    @Autowired AssetHierarchyService hierarchyService;

    private Long unitId;
    private Long locationId;
    private long nano;
    private long now;

    @BeforeEach
    void setUp() {
        now = System.currentTimeMillis();
        nano = System.nanoTime();

        OperationalUnit unit = new OperationalUnit();
        unit.setCode("DQ-U-" + nano);
        unit.setName("Data Quality Unit");
        unit.setCreatedAt(now);
        unit.setUpdatedAt(now);
        unitId = operationalUnitRepository.save(unit).getId();

        Location location = new Location();
        location.setCode("DQ-L-" + nano);
        location.setName("DQ Hall");
        location.setCreatedAt(now);
        location.setUpdatedAt(now);
        location = hierarchyService.saveLocation(location);

        locationId = location.getId();
    }

    // -----------------------------------------------------------------------
    // Manual-vs-scanned split
    // -----------------------------------------------------------------------

    @Test
    void theManualRateDividesByReadingsTakenNotByAssetsOnTheSheet() {
        AssetEntry manualAsset = asset("DQ-M", true);
        AssetEntry scannedAsset = asset("DQ-S", true);
        AssetEntry skippedAsset = asset("DQ-X", true);

        LogSheet sheet = submittedSheet();
        filledEntry(sheet, manualAsset, LogSheetEntrySource.PWA_MANUAL);
        filledEntry(sheet, scannedAsset, LogSheetEntrySource.PWA_NFC);
        untouchedEntry(sheet, skippedAsset);

        var rows = managementReportService.entrySourceSplit(null, null).stream()
                .filter(r -> unitId.equals(r.unitId()))
                .toList();

        assertThat(rows).hasSize(1);
        // Two readings were taken, not three: the skipped asset produced no reading at all.
        assertThat(rows.get(0).total()).isEqualTo(2);
        assertThat(rows.get(0).manual()).isEqualTo(1);
        // manualRate() is a percentage, not a fraction.
        assertThat(rows.get(0).manualRate()).isEqualTo(50.0);
    }

    @Test
    void aSheetWhereNothingWasFilledContributesNoRowRatherThanAZeroPercentOne() {
        LogSheet sheet = submittedSheet();
        untouchedEntry(sheet, asset("DQ-N1", true));
        untouchedEntry(sheet, asset("DQ-N2", true));

        // A unit with no readings has no manual rate — reporting 0% would read as "all scanned",
        // which is a claim about work that never happened.
        assertThat(managementReportService.entrySourceSplit(null, null))
                .noneMatch(r -> unitId.equals(r.unitId()));
    }

    @Test
    void aFilledEntryWithNoRecordedSourceCountsAsScannedNotManual() {
        AssetEntry legacy = asset("DQ-LEG", true);
        LogSheet sheet = submittedSheet();
        filledEntry(sheet, legacy, null);

        var row = managementReportService.entrySourceSplit(null, null).stream()
                .filter(r -> unitId.equals(r.unitId())).findFirst().orElseThrow();

        // Rows predating entry_source must not invent a data-quality problem.
        assertThat(row.total()).isEqualTo(1);
        assertThat(row.manual()).isZero();
        assertThat(row.scanned()).isEqualTo(1);
    }

    // -----------------------------------------------------------------------
    // Silent assets
    // -----------------------------------------------------------------------

    @Test
    void anAssetOnASubmittedSheetThatNobodyFilledInIsStillSilent() {
        AssetEntry skipped = asset("DQ-SIL", true);
        untouchedEntry(submittedSheet(), skipped);

        var silent = managementReportService.assetsWithoutRecentReadings(now - 86_400_000L, 100);

        // The bug this replaces: appearing on a submitted sheet looked like being read, which
        // hid exactly the equipment this section exists to surface.
        assertThat(silent).extracting(ManagementReportRows.SilentAssetRow::assetId)
                .contains(skipped.getId());
        assertThat(silent).filteredOn(r -> r.assetId().equals(skipped.getId()))
                .singleElement()
                .satisfies(r -> assertThat(r.lastReadingAt()).isNull());
    }

    @Test
    void anAssetReadInsideTheWindowIsNotListed() {
        AssetEntry read = asset("DQ-READ", true);
        filledEntry(submittedSheet(), read, LogSheetEntrySource.PWA_NFC);

        assertThat(managementReportService.assetsWithoutRecentReadings(now - 86_400_000L, 100))
                .extracting(ManagementReportRows.SilentAssetRow::assetId)
                .doesNotContain(read.getId());
    }

    @Test
    void anInactiveAssetIsNotReportedAsSilent() {
        AssetEntry retired = asset("DQ-OFF", false);

        // An inactive asset is excluded from generation, so it cannot have readings. Listing it
        // would be a permanent false positive burying the real ones.
        assertThat(managementReportService.assetsWithoutRecentReadings(now - 86_400_000L, 100))
                .extracting(ManagementReportRows.SilentAssetRow::assetId)
                .doesNotContain(retired.getId());
    }

    @Test
    void neverReadAssetsSortAheadOfStaleOnesAndTheLimitIsHonoured() {
        AssetEntry never = asset("DQ-NEVER", true);
        AssetEntry stale = asset("DQ-STALE", true);
        // Read, but long before the window opens.
        LogSheet old = submittedSheet(now - 90L * 86_400_000L);
        filledEntry(old, stale, LogSheetEntrySource.PWA_NFC);
        untouchedEntry(submittedSheet(), never);

        var silent = managementReportService.assetsWithoutRecentReadings(now - 86_400_000L, 500);
        var ids = silent.stream().map(ManagementReportRows.SilentAssetRow::assetId).toList();

        assertThat(ids).contains(never.getId(), stale.getId());
        // Never-read first: it is the most urgent row, not the least.
        assertThat(ids.indexOf(never.getId())).isLessThan(ids.indexOf(stale.getId()));
        assertThat(managementReportService.assetsWithoutRecentReadings(now - 86_400_000L, 1))
                .hasSize(1);
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * One sub-function per asset: a partial unique index allows only one <em>active</em> asset
     * per sub-function (gotcha #6), so sharing one would fail on the second active asset.
     */
    private AssetEntry asset(String prefix, boolean active) {
        long tick = System.nanoTime();
        SubFunction sf = new SubFunction();
        sf.setCode("DQ-SF-" + tick);
        sf.setName("DQ Sub " + prefix);
        sf.setTag("NFC-DQ-" + tick);
        sf.setCreatedAt(now);
        sf.setUpdatedAt(now);
        hierarchyService.applySubFunctionParent(sf, AssetHierarchyService.SCOPE_LOCATION, locationId);
        sf = hierarchyService.saveSubFunction(sf);

        AssetEntry a = new AssetEntry();
        a.setAssetCode(prefix + "-" + nano + "-" + tick);
        a.setAssetName(prefix);
        a.setSubFunctionId(sf.getId());
        a.setActive(active);
        a.setCreatedAt(now);
        a.setUpdatedAt(now);
        return assetEntryRepository.save(a);
    }

    private LogSheet submittedSheet() {
        return submittedSheet(now);
    }

    private LogSheet submittedSheet(long completedAt) {
        LogSheet s = new LogSheet();
        s.setTemplateName("DQ Sheet");
        s.setOperationalUnitId(unitId);
        s.setStatus(LogSheetStatus.SUBMITTED);
        s.setOrigin(GenerationMode.MANUAL);
        s.setCompletedAt(completedAt);
        s.setSubmittedAt(completedAt);
        s.setCreatedAt(completedAt);
        s.setUpdatedAt(completedAt);
        return logSheetRepository.save(s);
    }

    /** An entry carrying a reading — max_severity stamped exactly as the write paths do. */
    private void filledEntry(LogSheet sheet, AssetEntry asset, LogSheetEntrySource source) {
        LogSheetEntry e = new LogSheetEntry();
        e.setLogSheetId(sheet.getId());
        e.setAssetId(asset.getId());
        e.setFormData(Map.of("temp", "42"));
        e.setMaxSeverity("OK");
        e.setEntrySource(source);
        e.setCreatedAt(now);
        e.setUpdatedAt(now);
        logSheetEntryRepository.save(e);
    }

    /** An asset the operator never reached: the entry exists, the reading does not. */
    private void untouchedEntry(LogSheet sheet, AssetEntry asset) {
        LogSheetEntry e = new LogSheetEntry();
        e.setLogSheetId(sheet.getId());
        e.setAssetId(asset.getId());
        e.setFormData(null);
        e.setMaxSeverity(null);
        e.setCreatedAt(now);
        e.setUpdatedAt(now);
        logSheetEntryRepository.save(e);
    }
}
