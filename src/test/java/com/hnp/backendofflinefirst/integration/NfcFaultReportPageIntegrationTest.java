package com.hnp.backendofflinefirst.integration;

import com.hnp.backendofflinefirst.domain.ActionSource;
import com.hnp.backendofflinefirst.domain.GenerationMode;
import com.hnp.backendofflinefirst.domain.LogSheetStatus;
import com.hnp.backendofflinefirst.domain.NfcFaultReportStatus;
import com.hnp.backendofflinefirst.entity.AssetClass;
import com.hnp.backendofflinefirst.entity.AssetEntry;
import com.hnp.backendofflinefirst.entity.LogSheet;
import com.hnp.backendofflinefirst.entity.Location;
import com.hnp.backendofflinefirst.entity.NfcFaultReport;
import com.hnp.backendofflinefirst.entity.SubFunction;
import com.hnp.backendofflinefirst.repository.AssetClassRepository;
import com.hnp.backendofflinefirst.repository.AssetEntryRepository;
import com.hnp.backendofflinefirst.repository.LogSheetRepository;
import com.hnp.backendofflinefirst.repository.NfcFaultReportRepository;
import com.hnp.backendofflinefirst.service.AssetHierarchyService;
import com.hnp.backendofflinefirst.support.AbstractPostgresIntegrationTest;
import com.hnp.backendofflinefirst.support.WithAppUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * The NFC fault report queue at scale.
 *
 * <p><b>What was wrong.</b> {@code GET /nfc-fault-reports} called {@code findVisible()}, which
 * read <em>every report ever filed</em> and handed the lot to the template. One row is written
 * per broken or missing tag and nothing deletes them, so the page grew with the plant's whole NFC
 * history — and it is read most on exactly the days that history is longest. There was no filter
 * either: a reviewer looking for one asset had to fall back on the browser's own find.
 *
 * <p><b>And the pager it now uses was itself broken.</b> {@code fragments/list-toolbar ::
 * pagination} built its links from a hard-coded list of parameter names — {@code q},
 * {@code status}, {@code asset}, {@code classId}. Any page whose filter was called anything else
 * lost it on «بعدی»: the reviewer saw a filtered first page, clicked through, and silently got
 * page two of the <em>unfiltered</em> list. {@code ListFilterAdvice} replaces the enumeration
 * with the request's own query string, and the paging cases below are what pin that.
 *
 * <p>Every case runs as an unrestricted admin so unit scope never enters into it, and asserts
 * against rows carrying this test's own {@code nano} suffix — the Postgres container is shared
 * and other classes commit reports of their own.
 */
@Transactional
class NfcFaultReportPageIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired WebApplicationContext context;
    @Autowired NfcFaultReportRepository reportRepository;
    @Autowired AssetEntryRepository assetEntryRepository;
    @Autowired AssetClassRepository assetClassRepository;
    @Autowired AssetHierarchyService hierarchyService;
    @Autowired LogSheetRepository logSheetRepository;

    MockMvc mockMvc;

    long nano;
    Long pumpId;
    Long valveId;
    Long sheetId;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        nano = System.nanoTime();
        seed();
    }

    // -- filtering -----------------------------------------------------------

    @Test
    @WithAppUser(username = "nfc-all", roles = "ADMIN", authorities = {"GET:/nfc-fault-reports"})
    void withNoStatusFilterEveryReportInScopeIsListed() throws Exception {
        String html = mockMvc.perform(get("/nfc-fault-reports").param("q", "NFCP-" + nano))
                .andExpect(status().isOk())
                .andExpect(view().name("nfc-fault-reports"))
                .andReturn().getResponse().getContentAsString();

        assertThat(ownRows(html)).hasSize(3);
    }

    /** The status dropdown. Two of this fixture's three reports are open. */
    @Test
    @WithAppUser(username = "nfc-open", roles = "ADMIN", authorities = {"GET:/nfc-fault-reports"})
    void theStatusFilterNarrowsToOpenReports() throws Exception {
        String html = mockMvc.perform(get("/nfc-fault-reports")
                        .param("q", "NFCP-" + nano)
                        .param("status", "OPEN"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(ownRows(html)).hasSize(2);
        assertThat(html).doesNotContain("چسب کنده شده");
    }

    /**
     * The search runs across the report's own text <b>and</b> the asset's code and name. A
     * reviewer hunting for one machine knows its code, not the wording somebody typed.
     */
    @Test
    @WithAppUser(username = "nfc-q-asset", roles = "ADMIN", authorities = {"GET:/nfc-fault-reports"})
    void theSearchMatchesTheAssetCodeAndName() throws Exception {
        String byCode = mockMvc.perform(get("/nfc-fault-reports").param("q", "NFCP-" + nano + "-VALVE"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(ownRows(byCode)).hasSize(1);

        // Case-insensitively, and on the name too.
        String byName = mockMvc.perform(get("/nfc-fault-reports").param("q", "gate valve " + nano))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(ownRows(byName)).hasSize(1);
    }

    @Test
    @WithAppUser(username = "nfc-q-reason", roles = "ADMIN", authorities = {"GET:/nfc-fault-reports"})
    void theSearchMatchesTheReasonAndTheReporter() throws Exception {
        String byReason = mockMvc.perform(get("/nfc-fault-reports").param("q", "چسب کنده"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(ownRows(byReason)).hasSize(1);

        String byReporter = mockMvc.perform(get("/nfc-fault-reports").param("q", "Reporter-" + nano))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(ownRows(byReporter)).hasSize(3);
    }

    /** A nonsense status must widen to "all", not produce an error page. */
    @Test
    @WithAppUser(username = "nfc-bad", roles = "ADMIN", authorities = {"GET:/nfc-fault-reports"})
    void anUnknownStatusParameterIsIgnoredRatherThanFatal() throws Exception {
        String html = mockMvc.perform(get("/nfc-fault-reports")
                        .param("q", "NFCP-" + nano)
                        .param("status", "BANANA"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(ownRows(html)).hasSize(3);
    }

    // -- paging --------------------------------------------------------------

    @Test
    @WithAppUser(username = "nfc-page", roles = "ADMIN", authorities = {"GET:/nfc-fault-reports"})
    void thePageSizeIsHonouredAndTheRestIsOnTheNextPage() throws Exception {
        String first = mockMvc.perform(get("/nfc-fault-reports")
                        .param("q", "NFCP-" + nano).param("size", "2"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(ownRows(first)).hasSize(2);

        String second = mockMvc.perform(get("/nfc-fault-reports")
                        .param("q", "NFCP-" + nano).param("size", "2").param("page", "1"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(ownRows(second)).hasSize(1);
    }

    /**
     * The defect {@code ListFilterAdvice} exists for: every pagination link must carry the
     * filters that produced the list. Before, «بعدی» dropped {@code status} and {@code q}
     * silently and the next page was of a different list altogether.
     */
    @Test
    @WithAppUser(username = "nfc-links", roles = "ADMIN", authorities = {"GET:/nfc-fault-reports"})
    void everyPagerLinkCarriesTheFiltersThatProducedTheList() throws Exception {
        String html = mockMvc.perform(get("/nfc-fault-reports")
                        .param("q", "NFCP-" + nano)
                        .param("status", "OPEN")
                        .param("size", "1"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        List<String> links = pagerLinks(html);
        assertThat(links).isNotEmpty();
        assertThat(links).allSatisfy(href -> {
            assertThat(href).contains("status=OPEN");
            assertThat(href).contains("size=1");
            assertThat(href).contains("q=NFCP-" + nano);
            // ...and exactly one page, or the link navigates to where you already are.
            assertThat(href.split("page=", -1).length - 1).isEqualTo(1);
        });
    }

    /**
     * The header counts the whole queue, not the page. It said «۲۵ گزارش» on every page of a
     * paginated list, which is precisely the number a reviewer opens the page to learn.
     */
    @Test
    @WithAppUser(username = "nfc-count", roles = "ADMIN", authorities = {"GET:/nfc-fault-reports"})
    void theHeaderCountsTheQueueRatherThanThePage() throws Exception {
        String html = mockMvc.perform(get("/nfc-fault-reports")
                        .param("q", "NFCP-" + nano).param("size", "1"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(ownRows(html)).hasSize(1);
        assertThat(html).contains(">3</span> گزارش");
    }

    /** A filter that matches nothing says so, rather than claiming nothing was ever reported. */
    @Test
    @WithAppUser(username = "nfc-empty", roles = "ADMIN", authorities = {"GET:/nfc-fault-reports"})
    void anEmptyResultDistinguishesAFilterMissFromAnEmptyQueue() throws Exception {
        String html = mockMvc.perform(get("/nfc-fault-reports").param("q", "no-such-asset-" + nano))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("گزارشی با این فیلترها یافت نشد");
        assertThat(html).doesNotContain("هیچ گزارش خرابی NFC ثبت نشده است");
    }

    /**
     * A reviewed report with no reviewer on it must render, not 500 the page.
     *
     * <p>{@code reviewed_by_user_id} is nullable and the status column read
     * {@code userById[r.reviewedByUserId]} unguarded — and SpEL <b>throws</b> on a null map index
     * rather than yielding null (AGENTS.md's own trap list). One such row took out the entire
     * queue for everybody, and the message named a template line rather than the data.
     *
     * <p>The fixture's REVIEWED row carries no reviewer, so every case in this class exercises it;
     * this one states the claim so a future edit that drops the guard fails with the reason.
     */
    @Test
    @WithAppUser(username = "nfc-null-reviewer", roles = "ADMIN", authorities = {"GET:/nfc-fault-reports"})
    void aReviewedReportWithNoReviewerRendersInsteadOfBreakingThePage() throws Exception {
        String html = mockMvc.perform(get("/nfc-fault-reports")
                        .param("q", "NFCP-" + nano).param("status", "REVIEWED"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(ownRows(html)).hasSize(1);
        assertThat(html).contains("بررسی شده");
    }

    // -- helpers -------------------------------------------------------------

    /**
     * Rendered rows belonging to this test's assets and nothing else.
     *
     * <p>The container is shared and other classes commit their own reports, so a page-wide count
     * would be a function of execution order — green under Maven, red in an IDE, and impossible
     * to diagnose from "expected 3 but was 11".
     */
    private List<String> ownRows(String html) {
        String marker = "NFCP-" + nano;
        return Arrays.stream(html.split("<tr", -1))
                // Everything before the first <tr> is the header and the filter bar — and the
                // filter bar echoes the search term back into an input, so without this drop a
                // search FOR the marker counted the search box as a row.
                .skip(1)
                .filter(row -> row.contains(marker))
                .toList();
    }

    /** The hrefs of the pagination footer's links. */
    private static List<String> pagerLinks(String html) {
        return Arrays.stream(html.split("class=\"page-link\"", -1))
                .skip(1)
                .map(chunk -> {
                    int start = chunk.indexOf("href=\"");
                    if (start < 0) return "";
                    int from = start + 6;
                    int end = chunk.indexOf('"', from);
                    return end < 0 ? "" : chunk.substring(from, end);
                })
                .filter(href -> href.contains("page="))
                .toList();
    }

    private void seed() {
        long now = System.currentTimeMillis();

        Location location = new Location();
        location.setCode("NFCP-LOC-" + nano);
        location.setName("NFC Hall");
        location.setCreatedAt(now);
        location.setUpdatedAt(now);
        location = hierarchyService.saveLocation(location);

        AssetClass assetClass = new AssetClass();
        assetClass.setName("NFC Class " + nano);
        assetClass.setCreatedAt(now);
        assetClass.setUpdatedAt(now);
        assetClass = assetClassRepository.saveAndFlush(assetClass);

        // One sub-function each: `ux_asset_entries_active_sub_function` holds at most one active
        // asset per sub-function, which is the placement rule in docs/hierarchy.md.
        Long pumpSf = subFunction(location.getId(), "PUMP", now);
        Long valveSf = subFunction(location.getId(), "VALVE", now);

        // The run marker comes first in the code so `NFCP-<nano>` is a prefix of both assets and
        // `NFCP-<nano>-VALVE` picks out exactly one.
        pumpId = asset(pumpSf, assetClass.getId(), "NFCP-" + nano + "-PUMP", "Feed Pump " + nano, now);
        valveId = asset(valveSf, assetClass.getId(), "NFCP-" + nano + "-VALVE", "Gate Valve " + nano, now);

        // Every report belongs to the round it was filed during — `log_sheet_id` is NOT NULL,
        // because a fault report exists to unlock manual entry for one asset on one sheet.
        LogSheet sheet = new LogSheet();
        sheet.setTemplateName("NFC Round " + nano);
        sheet.setStatus(LogSheetStatus.IN_PROGRESS);
        sheet.setOrigin(GenerationMode.MANUAL);
        sheet.setCreatedAt(now);
        sheet.setUpdatedAt(now);
        sheetId = logSheetRepository.saveAndFlush(sheet).getId();

        // Two open, one already reviewed - so the status filter has something to exclude.
        report(pumpId, "تگ پاسخ نمی‌دهد", NfcFaultReportStatus.OPEN, now - 3_000);
        report(pumpId, "تگ اصلاً نصب نشده", NfcFaultReportStatus.OPEN, now - 2_000);
        report(valveId, "چسب کنده شده", NfcFaultReportStatus.REVIEWED, now - 1_000);
    }

    private Long subFunction(Long locationId, String suffix, long now) {
        SubFunction sf = new SubFunction();
        sf.setCode("NFCP-SF-" + suffix + "-" + nano);
        sf.setName("NFC Sub " + suffix);
        sf.setTag("NFC-NFCP-" + suffix + "-" + nano);
        sf.setCreatedAt(now);
        sf.setUpdatedAt(now);
        hierarchyService.applySubFunctionParent(sf, AssetHierarchyService.SCOPE_LOCATION, locationId);
        return hierarchyService.saveSubFunction(sf).getId();
    }

    private Long asset(Long subFunctionId, Long classId, String code, String name, long now) {
        AssetEntry asset = new AssetEntry();
        asset.setAssetCode(code);
        asset.setAssetName(name);
        asset.setClassId(classId);
        asset.setSubFunctionId(subFunctionId);
        asset.setCreatedAt(now);
        asset.setUpdatedAt(now);
        return assetEntryRepository.saveAndFlush(asset).getId();
    }

    private void report(Long assetId, String reason, NfcFaultReportStatus status, long createdAt) {
        NfcFaultReport r = new NfcFaultReport();
        r.setAssetId(assetId);
        r.setLogSheetId(sheetId);
        r.setReason(reason);
        r.setStatus(status);
        r.setSource(ActionSource.WEB);
        r.setReportedByName("Reporter-" + nano);
        r.setCreatedAt(createdAt);
        reportRepository.saveAndFlush(r);
    }
}
