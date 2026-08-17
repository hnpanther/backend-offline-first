package com.hnp.backendofflinefirst.integration;

import com.hnp.backendofflinefirst.domain.GenerationMode;
import com.hnp.backendofflinefirst.domain.LogSheetEntrySource;
import com.hnp.backendofflinefirst.domain.LogSheetStatus;
import com.hnp.backendofflinefirst.entity.AssetEntry;
import com.hnp.backendofflinefirst.entity.Location;
import com.hnp.backendofflinefirst.domain.NfcFaultReportStatus;
import com.hnp.backendofflinefirst.entity.NfcFaultReport;
import com.hnp.backendofflinefirst.repository.NfcFaultReportRepository;
import java.util.List;
import java.util.Set;
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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
    @Autowired NfcFaultReportRepository nfcFaultReportRepository;
    @Autowired AssetEntryRepository assetEntryRepository;
    @Autowired LogSheetRepository logSheetRepository;
    @Autowired LogSheetEntryRepository logSheetEntryRepository;
    @Autowired OperationalUnitRepository operationalUnitRepository;
    @Autowired AssetHierarchyService hierarchyService;
    @Autowired WebApplicationContext context;

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
    // Paging the silent-asset list
    //
    // The section had a hard cap of a hundred rows and no way past it, which on a plant with more
    // silent assets than that made the equipment beyond the cap invisible — in the report whose
    // whole purpose is to surface equipment nobody has read.
    // -----------------------------------------------------------------------

    @Test
    void everySilentAssetIsCountedEvenWhenThePageShowsFewOfThem() {
        for (int i = 0; i < 5; i++) {
            untouchedEntry(submittedSheet(), asset("DQ-PAGE-" + i, true));
        }

        var firstPage = managementReportService.assetsWithoutRecentReadingsPage(
                now - 86_400_000L, 0, 2);

        assertThat(firstPage.rows()).hasSize(2);
        // The count is the whole filtered set, not what fits on the page. Counting fetched rows
        // is the mistake this report family has made before, and it stops growing at the cap.
        assertThat(firstPage.totalElements()).isGreaterThanOrEqualTo(5);
        assertThat(firstPage.totalPages()).isGreaterThanOrEqualTo(3);
        assertThat(firstPage.hasPrevious()).isFalse();
        assertThat(firstPage.hasNext()).isTrue();
    }

    @Test
    void laterPagesContinueTheSameRankingInsteadOfRepeatingIt() {
        for (int i = 0; i < 5; i++) {
            untouchedEntry(submittedSheet(), asset("DQ-SEQ-" + i, true));
        }

        var first = managementReportService.assetsWithoutRecentReadingsPage(now - 86_400_000L, 0, 2);
        var second = managementReportService.assetsWithoutRecentReadingsPage(now - 86_400_000L, 1, 2);

        var firstIds = first.rows().stream().map(ManagementReportRows.SilentAssetRow::assetId).toList();
        var secondIds = second.rows().stream().map(ManagementReportRows.SilentAssetRow::assetId).toList();
        assertThat(secondIds).doesNotContainAnyElementsOf(firstIds);
        assertThat(second.page()).isEqualTo(1);
        assertThat(second.hasPrevious()).isTrue();
    }

    @Test
    void theUrgentRowsStayFirstAcrossPaging() {
        // Ranking has to happen in the database. Ranking a page that was already fetched would
        // answer a different question on every page.
        AssetEntry never = asset("DQ-RANK-NEVER", true);
        AssetEntry stale = asset("DQ-RANK-STALE", true);
        LogSheet old = submittedSheet(now - 90L * 86_400_000L);
        filledEntry(old, stale, LogSheetEntrySource.PWA_NFC);
        untouchedEntry(submittedSheet(), never);

        var all = managementReportService.assetsWithoutRecentReadingsPage(now - 86_400_000L, 0, 250);
        var ids = all.rows().stream().map(ManagementReportRows.SilentAssetRow::assetId).toList();

        assertThat(ids.indexOf(never.getId())).isLessThan(ids.indexOf(stale.getId()));
    }

    @Test
    void anAbsurdPageSizeIsCappedRatherThanHonoured() {
        // The pager is how you see more, not a bigger page: an unbounded size would let one
        // request pull the whole registry into memory.
        untouchedEntry(submittedSheet(), asset("DQ-CAP", true));

        assertThat(managementReportService.assetsWithoutRecentReadingsPage(now - 86_400_000L, 0, 100_000)
                .size()).isEqualTo(250);
        assertThat(managementReportService.assetsWithoutRecentReadingsPage(now - 86_400_000L, -3, 0)
                .page()).isZero();
    }

    @Test
    void apageBeyondTheEndIsEmptyRatherThanAnError() {
        untouchedEntry(submittedSheet(), asset("DQ-BEYOND", true));

        var page = managementReportService.assetsWithoutRecentReadingsPage(now - 86_400_000L, 9_999, 25);

        assertThat(page.rows()).isEmpty();
        assertThat(page.totalElements()).isPositive();
    }

    // -----------------------------------------------------------------------
    // The scoped half of the paged query
    //
    // The unrestricted branch (an admin, `visibleUnitIds() == null`) is what every case above
    // exercises. These cover the other one, where a mistake shows up as either another unit's
    // equipment appearing in somebody's report, or a total that disagrees with the rows under it.
    // -----------------------------------------------------------------------

    @Test
    void aScopedViewerSeesOnlyAssetsReachableThroughTheirUnit() {
        // Unreachable means unreachable *both* ways: no unit owns its location, and no log sheet
        // of this unit ever carried it.
        AssetEntry mine = assetUnderUnit("DQ-SCOPED-MINE");
        AssetEntry unreachable = asset("DQ-SCOPED-OUTSIDE", true);
        untouchedEntry(submittedSheet(), mine);

        var ids = assetEntryRepository.findSilentAssetsPage(Set.of(unitId), now - 86_400_000L, 250, 0)
                .stream().map(r -> ((Number) r[0]).longValue()).toList();

        assertThat(ids).contains(mine.getId());
        assertThat(ids).doesNotContain(unreachable.getId());
    }

    @Test
    void anAssetReachedOnlyThroughALogSheetIsStillYoursToWatch() {
        // Reporting scope, not ownership — and the difference is deliberate. An asset that only
        // ever appears on this unit's rounds is exactly the blind spot the section exists to
        // surface, so an ownership-only filter would hide the very rows worth seeing.
        AssetEntry borrowed = asset("DQ-SCOPED-BORROWED", true);
        untouchedEntry(submittedSheet(), borrowed);

        var ids = assetEntryRepository.findSilentAssetsPage(Set.of(unitId), now - 86_400_000L, 250, 0)
                .stream().map(r -> ((Number) r[0]).longValue()).toList();

        assertThat(ids).contains(borrowed.getId());
    }

    @Test
    void theScopedCountAgreesWithTheScopedRows() {
        // The paging bug that hides best: a count taken over everything and rows taken over one
        // unit. The pager then offers pages that are empty, or hides rows that exist.
        for (int i = 0; i < 4; i++) {
            untouchedEntry(submittedSheet(), assetUnderUnit("DQ-AGREE-" + i));
        }
        asset("DQ-AGREE-OUTSIDE", true);

        long total = assetEntryRepository.countSilentAssets(Set.of(unitId), now - 86_400_000L);
        var everything = assetEntryRepository.findSilentAssetsPage(
                Set.of(unitId), now - 86_400_000L, 1000, 0);

        assertThat(total).isEqualTo(everything.size());
        assertThat(total).isGreaterThanOrEqualTo(4);
    }

    @Test
    void pagingAScopedViewRevisitsNothingAndSkipsNothing() {
        for (int i = 0; i < 5; i++) {
            untouchedEntry(submittedSheet(), assetUnderUnit("DQ-WALK-" + i));
        }
        long total = assetEntryRepository.countSilentAssets(Set.of(unitId), now - 86_400_000L);

        java.util.Set<Long> walked = new java.util.LinkedHashSet<>();
        for (int page = 0; page * 2 < total; page++) {
            assetEntryRepository.findSilentAssetsPage(Set.of(unitId), now - 86_400_000L, 2, page * 2)
                    .forEach(r -> walked.add(((Number) r[0]).longValue()));
        }

        // Every row exactly once: the ranking is stable because it breaks ties on `a.id`.
        assertThat(walked).hasSize((int) total);
    }

    /** An asset whose location really is linked to this test's unit, so the scope CTE reaches it. */
    private AssetEntry assetUnderUnit(String prefix) {
        long tick = System.nanoTime();
        Location scoped = new Location();
        scoped.setCode("DQ-LU-" + tick);
        scoped.setName("DQ Scoped Hall");
        scoped.setCreatedAt(now);
        scoped.setUpdatedAt(now);
        scoped = hierarchyService.saveLocation(scoped, List.of(unitId));

        SubFunction sf = new SubFunction();
        sf.setCode("DQ-SFU-" + tick);
        sf.setName("DQ Scoped Sub");
        sf.setTag("NFC-DQU-" + tick);
        sf.setCreatedAt(now);
        sf.setUpdatedAt(now);
        hierarchyService.applySubFunctionParent(sf, AssetHierarchyService.SCOPE_LOCATION, scoped.getId());
        sf = hierarchyService.saveSubFunction(sf);

        AssetEntry a = new AssetEntry();
        a.setAssetCode(prefix + "-" + tick);
        a.setAssetName(prefix);
        a.setSubFunctionId(sf.getId());
        a.setActive(true);
        a.setCreatedAt(now);
        a.setUpdatedAt(now);
        return assetEntryRepository.save(a);
    }

    // -----------------------------------------------------------------------
    // Paging the NFC maintenance queue
    //
    // The last unbounded list on this page: it loaded every unresolved report in the caller's
    // scope and grouped them in Java to render a handful of rows. Bounded only by how diligently
    // somebody works the queue — which is exactly the thing that fails when it matters.
    // -----------------------------------------------------------------------

    @Test
    void theQueueCountsAssetsNotReportsSoThePagerOffersRealPages() {
        AssetEntry a = asset("DQ-NFC-A", true);
        AssetEntry b = asset("DQ-NFC-B", true);
        openFault(a, now - 5_000, "تگ کنده شده");
        openFault(a, now - 4_000, "دوباره خراب شد");
        openFault(b, now - 3_000, "خوانده نمی‌شود");

        var page = managementReportService.openNfcFaultsPage(0, 25);

        // Three reports, two assets, two rows — counting reports would offer a page that is empty.
        assertThat(page.totalElements()).isEqualTo(2);
        assertThat(page.rows()).hasSize(2);
        assertThat(page.rows().getFirst().openReports()).isEqualTo(2);
    }

    @Test
    void theOldestUnresolvedAssetComesFirstAndStaysFirstAcrossPages() {
        AssetEntry oldest = asset("DQ-NFC-OLD", true);
        AssetEntry newer = asset("DQ-NFC-NEW", true);
        openFault(newer, now - 1_000, "تازه");
        openFault(oldest, now - 900_000, "قدیمی");

        var first = managementReportService.openNfcFaultsPage(0, 1);
        var second = managementReportService.openNfcFaultsPage(1, 1);

        assertThat(first.rows().getFirst().assetId()).isEqualTo(oldest.getId());
        assertThat(second.rows().getFirst().assetId()).isEqualTo(newer.getId());
        assertThat(second.rows().getFirst().assetId()).isNotEqualTo(first.rows().getFirst().assetId());
    }

    @Test
    void aFullPageIsOrderedByAgeNotByWhateverTheGroupingProduced() {
        // The previous case used a page of one, where any implementation looks ordered. Here the
        // oldest fault belongs to the asset created *last*, so grouping by a hash map — which
        // tends to iterate ascending ids — puts it in the wrong place. The queue is only useful
        // if the top of it is the thing that has been waiting longest.
        AssetEntry first = asset("DQ-NFC-ORD-1", true);
        AssetEntry second = asset("DQ-NFC-ORD-2", true);
        AssetEntry third = asset("DQ-NFC-ORD-3", true);
        openFault(first, now - 10_000, "جدیدترین");
        openFault(second, now - 500_000, "متوسط");
        openFault(third, now - 900_000, "قدیمی‌ترین");

        var rows = managementReportService.openNfcFaultsPage(0, 250).rows().stream()
                .filter(r -> List.of(first.getId(), second.getId(), third.getId()).contains(r.assetId()))
                .toList();

        assertThat(rows).extracting(ManagementReportRows.NfcHealthRow::assetId)
                .containsExactly(third.getId(), second.getId(), first.getId());
        assertThat(rows).extracting(ManagementReportRows.NfcHealthRow::oldestReportedAt)
                .isSorted();
    }

    @Test
    void eachRowKeepsItsCountOldestTimeAndLatestReason() {
        // The row content is what the maintenance queue is for; paging must not thin it out.
        AssetEntry a = asset("DQ-NFC-DETAIL", true);
        openFault(a, now - 60_000, "اولین گزارش");
        openFault(a, now - 10_000, "آخرین گزارش");

        var row = managementReportService.openNfcFaultsPage(0, 25).rows().stream()
                .filter(r -> r.assetId().equals(a.getId()))
                .findFirst().orElseThrow();

        assertThat(row.openReports()).isEqualTo(2);
        assertThat(row.oldestReportedAt()).isEqualTo(now - 60_000);
        assertThat(row.lastReason()).isEqualTo("آخرین گزارش");
        assertThat(row.assetCode()).startsWith("DQ-NFC-DETAIL");
    }

    @Test
    void aResolvedReportLeavesTheQueue() {
        AssetEntry a = asset("DQ-NFC-RESOLVED", true);
        NfcFaultReport report = openFault(a, now - 1_000, "رسیدگی شد");
        report.setStatus(NfcFaultReportStatus.REVIEWED);
        nfcFaultReportRepository.saveAndFlush(report);

        assertThat(managementReportService.openNfcFaultsPage(0, 25).rows())
                .extracting(ManagementReportRows.NfcHealthRow::assetId)
                .doesNotContain(a.getId());
    }

    @Test
    void anAbsurdPageSizeIsCappedForTheQueueToo() {
        assertThat(managementReportService.openNfcFaultsPage(0, 100_000).size()).isEqualTo(250);
        assertThat(managementReportService.openNfcFaultsPage(-2, 0).page()).isZero();
    }

    @Test
    @WithAppUser(roles = "ADMIN", authorities = "GET:/reports")
    void theQueuesPagerActuallyReachesTheBrowser() throws Exception {
        // The pager sits inside the card, and the layout copies only `#pageContent` — markup
        // placed outside it is dropped at render time with no error at all (gotcha #4), which has
        // already killed two features here. Two faulty assets and a page of one force the pager to
        // render, so this fails if it is ever moved out or its accessors are mistyped.
        openFault(asset("DQ-NFC-RENDER-A", true), now - 20_000, "الف");
        openFault(asset("DQ-NFC-RENDER-B", true), now - 10_000, "ب");

        MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build()
                .perform(get("/reports/data-quality").param("size", "1"))
                .andExpect(status().isOk())
                // Its own parameter, not the silent-asset list's `page`.
                .andExpect(content().string(containsString("nfcPage=1")));
    }

    private NfcFaultReport openFault(AssetEntry asset, long createdAt, String reason) {
        NfcFaultReport r = new NfcFaultReport();
        r.setAssetId(asset.getId());
        // NOT NULL: a fault report is always filed against an asset on a specific sheet.
        r.setLogSheetId(submittedSheet().getId());
        r.setOperationalUnitId(unitId);
        r.setSource(com.hnp.backendofflinefirst.domain.ActionSource.MOBILE);
        r.setStatus(NfcFaultReportStatus.OPEN);
        r.setReason(reason);
        r.setCreatedAt(createdAt);
        return nfcFaultReportRepository.saveAndFlush(r);
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
