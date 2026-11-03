package com.hnp.backendofflinefirst.integration;

import com.hnp.backendofflinefirst.domain.GenerationMode;
import com.hnp.backendofflinefirst.domain.LogSheetDateField;
import com.hnp.backendofflinefirst.domain.LogSheetListFilter;
import com.hnp.backendofflinefirst.domain.LogSheetStatus;
import com.hnp.backendofflinefirst.entity.LogSheet;
import com.hnp.backendofflinefirst.entity.OperationalUnit;
import com.hnp.backendofflinefirst.repository.LogSheetRepository;
import com.hnp.backendofflinefirst.repository.OperationalUnitRepository;
import com.hnp.backendofflinefirst.service.LogSheetAccessService;
import com.hnp.backendofflinefirst.support.AbstractPostgresIntegrationTest;
import com.hnp.backendofflinefirst.support.WithAppUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The log-sheet list's date filter and its columns.
 *
 * <p><b>Two properties here are the ones worth guarding.</b> A date range names the column it
 * applies to, because {@code created_at}, {@code due_at} and {@code completed_at} give different
 * row sets and a range with no named column is read as whichever one the reader had in mind. And
 * the page and its Excel export narrow through the <b>same</b> {@link LogSheetListFilter} —
 * the alternative is a file that disagrees with the screen it came from, under the same filename
 * and with no error anywhere.
 */
@Transactional
@WithAppUser(roles = "ADMIN", authorities = {"GET:/log-sheets"})
class LogSheetListFilterIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired WebApplicationContext context;
    @Autowired LogSheetRepository logSheetRepository;
    @Autowired OperationalUnitRepository operationalUnitRepository;
    @Autowired LogSheetAccessService accessService;

    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    // ---------------------------------------------------------------- the range

    @Test
    void theRangeIsHalfOpenOnTheColumnTheFilterNames() {
        Fixture f = seed();

        // [20, 30) over created_at: the 20 is in, the 30 is out. That is what lets one filter's
        // «to» be reused as the next one's «from» with no overlap and no gap.
        assertThat(ids(LogSheetDateField.CREATED_AT, f.at(10), f.at(20))).containsExactly(f.middle());
        assertThat(ids(LogSheetDateField.CREATED_AT, f.at(10), null))
                .containsExactlyInAnyOrder(f.middle(), f.late());
        assertThat(ids(LogSheetDateField.CREATED_AT, f.at(0), f.at(10))).containsExactly(f.early());
    }

    @Test
    void theSameRangeOnADifferentColumnAnswersADifferentQuestion() {
        Fixture f = seed();

        // The point of naming the column: identical bounds, different rows.
        assertThat(ids(LogSheetDateField.CREATED_AT, f.at(0), null))
                .containsExactlyInAnyOrder(f.early(), f.middle(), f.late());
        assertThat(ids(LogSheetDateField.COMPLETED_AT, f.at(0), null)).containsExactly(f.late());
    }

    @Test
    void filteringOnCompletionExcludesRoundsThatAreNotFinished() {
        Fixture f = seed();

        // completed_at is null on an open round and a comparison against null is never true, so
        // «completed after X» drops them rather than keeping them. Intended, and worth pinning —
        // otherwise the open rounds get reported as missing.
        assertThat(ids(LogSheetDateField.COMPLETED_AT, f.at(0), null)).containsExactly(f.late());
        assertThat(ids(LogSheetDateField.COMPLETED_AT, f.at(0), Long.MAX_VALUE))
                .containsExactly(f.late());
    }

    @Test
    void aStatusAndADateRangeNarrowTogether() {
        Fixture f = seed();

        assertThat(accessService.findVisibleLogSheets(
                        new LogSheetListFilter(LogSheetStatus.PENDING, null,
                                LogSheetDateField.CREATED_AT, f.at(0), null),
                        PageRequest.of(0, 50))
                .getContent().stream().map(LogSheet::getId).toList())
                .containsExactlyInAnyOrder(f.early(), f.middle());
    }

    @Test
    void theSearchTermStillWorksWithNoDateBound() {
        // One method serves the searched and the unsearched case now; as JPQL that needed two,
        // because a nullable String inside LOWER(CONCAT(...)) is inferred as bytea (AGENTS.md #13).
        Fixture f = seed();
        assertThat(accessService.findVisibleLogSheets(
                        LogSheetListFilter.of(null, f.term(), null, null, null), PageRequest.of(0, 50))
                .getContent().stream().map(LogSheet::getId).toList())
                .containsExactly(f.late());
    }

    @Test
    void theSearchTermAlsoMatchesTheResponsibleUnit() {
        Fixture f = seed();

        // The unit is not a column on log_sheets — only its id is, and as a plain Long rather
        // than an association — so this match cannot be a column comparison or a join. It goes
        // through an uncorrelated subquery over operational_units, which PostgreSQL evaluates
        // once per statement rather than once per row.
        assertThat(idsMatching(f.unitName()))
                .containsExactlyInAnyOrder(f.early(), f.middle(), f.late());

        // By code as well as by name: plant-wide there are hundreds of units and the names
        // repeat, which is why the panel identifies a unit by both everywhere else.
        assertThat(idsMatching(f.unitCode()))
                .containsExactlyInAnyOrder(f.early(), f.middle(), f.late());

        // Case-insensitively, like the other two columns.
        assertThat(idsMatching(f.unitCode().toLowerCase()))
                .containsExactlyInAnyOrder(f.early(), f.middle(), f.late());
    }

    // ---------------------------------------------------------------- refusals

    @Test
    void anUnknownDateFieldIsRefusedRatherThanQuietlyDefaulted() {
        assertThatThrownBy(() -> LogSheetListFilter.of(null, null, "FINISHED_AT", 1L, 2L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("FINISHED_AT");
    }

    @Test
    void anEmptyRangeIsRefused() {
        assertThatThrownBy(() -> LogSheetListFilter.of(null, null, "CREATED_AT", 2L, 2L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("from must be before to");
    }

    @Test
    void aBlankDateFieldMeansCreatedAt() {
        assertThat(LogSheetListFilter.of(null, null, null, null, null).dateField())
                .isEqualTo(LogSheetDateField.CREATED_AT);
        assertThat(LogSheetListFilter.of(null, null, "  ", null, null).dateField())
                .isEqualTo(LogSheetDateField.CREATED_AT);
    }

    @Test
    void theListPageReportsAnUnknownDateFieldInsteadOfShowingTheWrongRows() throws Exception {
        seed();
        mockMvc.perform(get("/log-sheets").param("dateField", "FINISHED_AT"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("errorMessage"));
    }

    // ---------------------------------------------------------------- the columns

    @Test
    void theListLeadsWithTheResponsibleUnitAndStillCarriesScopeAndCompletion() throws Exception {
        Fixture f = seed();

        String html = mockMvc.perform(get("/log-sheets"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("واحد عملیاتی");
        assertThat(html).contains("زمان تکمیل");
        assertThat(html).contains("محدوده");
        // The unit name itself, resolved in one query for the whole page rather than per row.
        assertThat(html).contains(f.unitName());
    }

    @Test
    void theEmptyRowSpansEveryColumn() throws Exception {
        seed();

        // Eleven columns now. If the colspan disagrees the table misaligns the moment a filter
        // matches nothing, which is exactly when somebody is looking at it.
        String html = mockMvc.perform(get("/log-sheets").param("status", "CANCELLED"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("هیچ لاگ شیتی یافت نشد");
        assertThat(html).contains("colspan=\"11\"");
    }

    @Test
    void theFilterControlsAreRenderedExactlyOnce() throws Exception {
        seed();

        String html = mockMvc.perform(get("/log-sheets"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // The extra filters live in their own file rather than as an inline th:fragment in the
        // page, because an inline one renders TWICE — once where the toolbar includes it and
        // once in place, since th:fragment does not suppress its own element. Measured: at the
        // time of writing /asset-entries still carries that shape and draws its class filter
        // two times. A duplicated <select name="dateField"> would also submit twice.
        assertThat(html.split("name=\"dateField\"", -1).length - 1).isEqualTo(1);
        assertThat(html.split("id=\"filterStatusSelect\"", -1).length - 1).isEqualTo(1);
        // And the bar is the shared one, so it brings the result count and the page-size select.
        assertThat(html).contains("enterprise-list-filter");
    }

    @Test
    void theExportLinkCarriesEveryFilterThePageIsShowing() throws Exception {
        seed();

        String html = mockMvc.perform(get("/log-sheets")
                        .param("status", "SUBMITTED")
                        .param("dateField", "COMPLETED_AT")
                        .param("q", "pump"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // Not a detail: an export that kept only `status` would hand somebody a file that
        // disagrees with the page it came from.
        assertThat(html).contains("/log-sheets/export?");
        assertThat(html).contains("status=SUBMITTED");
        assertThat(html).contains("dateField=COMPLETED_AT");
        assertThat(html).contains("q=pump");
    }

    // ---------------------------------------------------------------- helpers

    /**
     * @param base the instant this fixture's own sheets hang off — far enough in the future
     *             that no sheet another test committed into the shared database can fall
     *             inside a range built from it. These assertions are about exactly which rows
     *             match, so they cannot be written against a clock every other test writes to.
     * @param term a token unique to this fixture, so the search assertion cannot pick up a
     *             sheet another test happened to name something similar
     */
    private record Fixture(Long early, Long middle, Long late, Long unitId, String unitName,
                           String unitCode, long base, String term) {

        /** {@code base} plus this many seconds. */
        Long at(int seconds) {
            return base + seconds * 1_000L;
        }
    }

    private List<Long> idsMatching(String term) {
        return accessService.findVisibleLogSheets(
                        LogSheetListFilter.of(null, term, null, null, null), PageRequest.of(0, 50))
                .getContent().stream().map(LogSheet::getId).toList();
    }

    private List<Long> ids(LogSheetDateField field, Long from, Long to) {
        return accessService.findVisibleLogSheets(
                        new LogSheetListFilter(null, null, field, from, to), PageRequest.of(0, 50))
                .getContent().stream().map(LogSheet::getId).toList();
    }

    /**
     * Three sheets in one unit, at three known instants, one of them completed.
     *
     * <p>The timestamps are small literals rather than offsets from {@code now} so the
     * assertions can state the boundary exactly — half-open is a statement about one
     * millisecond, and a fixture built from the clock cannot test it.
     */
    private Fixture seed() {
        long now = System.currentTimeMillis();
        long nano = System.nanoTime();
        // Year 2096-ish, offset per run. Nothing else in the suite writes a log sheet out
        // here, so a range built from it matches this fixture's rows and nothing another
        // test committed.
        long base = 4_000_000_000_000L + (nano % 1_000_000L) * 1_000L;
        String term = "lfterm" + nano;

        OperationalUnit unit = new OperationalUnit();
        String unitCode = "LF-BU-" + nano;
        unit.setCode(unitCode);
        unit.setName("ListFilterUnit" + nano);
        unit.setCreatedAt(now);
        unit.setUpdatedAt(now);
        unit = operationalUnitRepository.saveAndFlush(unit);

        Long early = sheet(unit.getId(), "LF early", base, null, LogSheetStatus.PENDING);
        Long middle = sheet(unit.getId(), "LF middle", base + 10_000L, null, LogSheetStatus.PENDING);
        Long late = sheet(unit.getId(), "LF late " + term, base + 20_000L, base + 30_000L,
                LogSheetStatus.SUBMITTED);
        return new Fixture(early, middle, late, unit.getId(), unit.getName(), unitCode,
                base, term);
    }

    private Long sheet(Long unitId, String name, long createdAt, Long completedAt, LogSheetStatus status) {
        LogSheet s = new LogSheet();
        s.setTemplateName(name);
        s.setOperationalUnitId(unitId);
        s.setStatus(status);
        s.setOrigin(GenerationMode.MANUAL);
        s.setCreatedAt(createdAt);
        s.setUpdatedAt(createdAt);
        s.setCompletedAt(completedAt);
        s.setSubmittedAt(completedAt);
        return logSheetRepository.saveAndFlush(s).getId();
    }
}
