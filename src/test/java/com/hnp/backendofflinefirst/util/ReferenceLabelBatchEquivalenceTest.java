package com.hnp.backendofflinefirst.util;

import com.hnp.backendofflinefirst.entity.Location;
import com.hnp.backendofflinefirst.entity.MainFunction;
import com.hnp.backendofflinefirst.entity.OperationalUnit;
import com.hnp.backendofflinefirst.entity.PlantSystem;
import com.hnp.backendofflinefirst.entity.SubFunction;
import com.hnp.backendofflinefirst.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The batch parent-label builders must render <em>exactly</em> what the per-row helpers render.
 *
 * <p>That is the whole risk of this optimisation, and it is not visible in the code: two label
 * formats live side by side in this service. {@code parentLabelForLocation} yields «نیروگاه» —
 * name, else code, else id. {@code parentLabelForSystem}, given that same location, yields
 * «مکان: LOC-01 - نیروگاه» — a Persian type prefix over {@code code - name}. Reaching for the
 * wrong one changes what every row of a page says, and nothing would fail on its own: both are
 * non-empty strings of the right type.
 *
 * <p>So rather than assert literal strings — which would only pin today's formatting twice and
 * drift the moment either side changed — most tests here drive <strong>both paths against one
 * fake database</strong> and assert they agree, row by row.
 *
 * <p>The stub is shaped as a real table: {@code findById} and {@code findAllById} read the same
 * map, so a row missing from one is missing from the other, and a dangling parent id behaves the
 * way it does in production rather than the way a hand-written stub happened to be set up.
 *
 * <p>Flat, not {@code @Nested}, deliberately: nothing else in this suite nests, and a nested class
 * is skipped outright by a {@code -Dtest=} filter — a test that silently runs zero assertions is
 * worse than no test at all.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReferenceLabelBatchEquivalenceTest {

    @Mock LocationRepository locationRepository;
    @Mock PlantSystemRepository plantSystemRepository;
    @Mock MainFunctionRepository mainFunctionRepository;
    @Mock SubFunctionRepository subFunctionRepository;
    @Mock OperationalUnitRepository operationalUnitRepository;
    @Mock AssetClassRepository assetClassRepository;
    @Mock UserRepository userRepository;

    ReferenceLabelService labels;

    private final Map<Long, Location> locations = new LinkedHashMap<>();
    private final Map<Long, PlantSystem> systems = new LinkedHashMap<>();
    private final Map<Long, MainFunction> mains = new LinkedHashMap<>();
    private final Map<Long, SubFunction> subs = new LinkedHashMap<>();
    private final Map<Long, OperationalUnit> units = new LinkedHashMap<>();

    @BeforeEach
    void setUp() {
        labels = new ReferenceLabelService(locationRepository, plantSystemRepository,
                mainFunctionRepository, subFunctionRepository, operationalUnitRepository,
                assetClassRepository, userRepository);
        table(locationRepository, locations);
        table(plantSystemRepository, systems);
        table(mainFunctionRepository, mains);
        table(subFunctionRepository, subs);
        table(operationalUnitRepository, units);
    }

    /** Makes a mock repository read from {@code rows} through both access paths. */
    private <T> void table(JpaRepository<T, Long> repo, Map<Long, T> rows) {
        when(repo.findById(anyLong()))
                .thenAnswer(inv -> Optional.ofNullable(rows.get(inv.<Long>getArgument(0))));
        when(repo.findAllById(any())).thenAnswer(inv -> {
            List<T> found = new ArrayList<>();
            for (Long id : inv.<Iterable<Long>>getArgument(0)) {
                T row = rows.get(id);
                if (row != null) {
                    found.add(row);
                }
            }
            return found;
        });
    }

    // ── locations ───────────────────────────────────────────────────────────────────────────

    @Test
    void everyLocationRowRendersWhatThePerRowHelperWouldRender() {
        Location root = location(1L, "LOC-01", "نیروگاه", null);
        Location child = location(2L, "LOC-02", "واحد بخار", 1L);
        Location orphan = location(3L, "LOC-03", "انبار", 999L);      // parent no longer exists
        Location codeOnly = location(4L, "LOC-04", null, 1L);
        Location childOfCodeOnly = location(5L, "LOC-05", "کارگاه", 4L);

        List<Location> page = List.of(root, child, orphan, codeOnly, childOfCodeOnly);
        Map<Long, String> batch = labels.parentLabelsForLocations(page);

        for (Location row : page) {
            assertThat(batch.get(row.getId()))
                    .as("row %d (parentId=%s)", row.getId(), row.getParentId())
                    .isEqualTo(labels.parentLabelForLocation(row.getParentId()));
        }
    }

    @Test
    void aLocationWithNoParentReadsAsADashRatherThanAnEmptyCell() {
        Location root = location(1L, "LOC-01", "نیروگاه", null);
        assertThat(labels.parentLabelsForLocations(List.of(root))).containsEntry(1L, "—");
    }

    @Test
    void aDanglingParentKeepsShowingTheIdRatherThanClaimingThereIsNoParent() {
        // «—» would read as "top of the hierarchy", which is a different and wrong statement.
        Location orphan = location(3L, "LOC-03", "انبار", 999L);
        assertThat(labels.parentLabelsForLocations(List.of(orphan))).containsEntry(3L, "999");
    }

    @Test
    void aParentNameWinsOverItsCodeAndTheCodeStandsInWhenTheNameIsMissing() {
        location(1L, "LOC-01", "نیروگاه", null);
        location(4L, "LOC-04", null, null);
        Location a = location(10L, "A", "a", 1L);
        Location b = location(11L, "B", "b", 4L);

        assertThat(labels.parentLabelsForLocations(List.of(a, b)))
                .containsEntry(10L, "نیروگاه")
                .containsEntry(11L, "LOC-04");
    }

    @Test
    void aPageOfLocationsCostsOneQueryRegardlessOfHowManyRowsItHas() {
        location(1L, "LOC-01", "نیروگاه", null);
        List<Location> page = new ArrayList<>();
        for (long i = 10; i < 210; i++) {
            page.add(location(i, "L-" + i, "مکان " + i, 1L));
        }

        labels.parentLabelsForLocations(page);

        verify(locationRepository, times(1)).findAllById(any());
        verify(locationRepository, never()).findById(anyLong());
    }

    @Test
    void aPageWhereEveryRowSharesOneParentStillIssuesOneQuery() {
        location(1L, "LOC-01", "نیروگاه", null);
        List<Location> page = List.of(
                location(10L, "a", "a", 1L),
                location(11L, "b", "b", 1L),
                location(12L, "c", "c", 1L));

        assertThat(labels.parentLabelsForLocations(page))
                .containsValues("نیروگاه", "نیروگاه", "نیروگاه");
        verify(locationRepository, times(1)).findAllById(any());
    }

    // ── plant systems: two label columns, two different formats ─────────────────────────────

    @Test
    void bothPlantSystemColumnsRenderWhatTheirPerRowHelpersWouldRender() {
        location(1L, "LOC-01", "نیروگاه", null);
        PlantSystem parent = system(10L, "SYS-01", "آب", null, 1L);
        PlantSystem child = system(11L, "SYS-02", "بخار", 10L, 1L);
        PlantSystem noLocation = system(12L, "SYS-03", "برق", null, null);
        PlantSystem dangling = system(13L, "SYS-04", "سوخت", 888L, 777L);

        List<PlantSystem> page = List.of(parent, child, noLocation, dangling);
        Map<Long, String> parents = labels.parentSystemLabelsForPlantSystems(page);
        Map<Long, String> locs = labels.locationLabelsForPlantSystems(page);

        for (PlantSystem row : page) {
            assertThat(parents.get(row.getId()))
                    .as("parent column, row %d", row.getId())
                    .isEqualTo(labels.parentLabelForPlantSystemParent(row.getParentId()));
            assertThat(locs.get(row.getId()))
                    .as("location column, row %d", row.getId())
                    .isEqualTo(labels.parentLabelForSystem(row.getLocationId()));
        }
    }

    @Test
    void aScopeLabelAndAPlainLabelAreNotInterchangeable() {
        // The regression this whole file exists to prevent: the location column on /plant-systems
        // carries a Persian type prefix and «code - name»; the parent column on /locations
        // carries neither — for the very same location row.
        location(1L, "LOC-01", "نیروگاه", null);
        PlantSystem s = system(10L, "SYS-01", "آب", null, 1L);

        assertThat(labels.locationLabelsForPlantSystems(List.of(s)))
                .containsEntry(10L, "مکان: LOC-01 - نیروگاه");
        assertThat(labels.parentLabelsForLocations(List.of(location(2L, "x", "y", 1L))))
                .containsEntry(2L, "نیروگاه");
    }

    @Test
    void aPageOfPlantSystemsCostsOneQueryPerReferencedTable() {
        location(1L, "LOC-01", "نیروگاه", null);
        system(10L, "SYS-01", "آب", null, 1L);
        List<PlantSystem> page = new ArrayList<>();
        for (long i = 20; i < 220; i++) {
            page.add(system(i, "S-" + i, "سیستم " + i, 10L, 1L));
        }

        labels.parentSystemLabelsForPlantSystems(page);
        labels.locationLabelsForPlantSystems(page);

        verify(plantSystemRepository, times(1)).findAllById(any());
        verify(locationRepository, times(1)).findAllById(any());
        verify(plantSystemRepository, never()).findById(anyLong());
        verify(locationRepository, never()).findById(anyLong());
    }

    // ── main functions: parent, then system, then location ──────────────────────────────────

    @Test
    void everyMainFunctionBranchRendersWhatThePerRowHelperWouldRender() {
        location(1L, "LOC-01", "نیروگاه", null);
        system(10L, "SYS-01", "آب", null, 1L);
        MainFunction viaParent = main(100L, "MF-01", "پایش", 101L, 10L, 1L);
        MainFunction parentRow = main(101L, "MF-02", "کنترل", null, null, 1L);
        MainFunction viaSystem = main(102L, "MF-03", "تهویه", null, 10L, 1L);
        MainFunction viaLocation = main(103L, "MF-04", "روشنایی", null, null, 1L);
        MainFunction rootless = main(104L, "MF-05", "متفرقه", null, null, null);
        MainFunction dangling = main(105L, "MF-06", "خراب", 555L, null, null);

        List<MainFunction> page =
                List.of(viaParent, parentRow, viaSystem, viaLocation, rootless, dangling);
        Map<Long, String> batch = labels.parentLabelsForMainFunctions(page);

        for (MainFunction row : page) {
            assertThat(batch.get(row.getId()))
                    .as("row %d", row.getId())
                    .isEqualTo(labels.parentLabelForMainFunction(row));
        }
    }

    @Test
    void aMainFunctionParentBeatsItsSystemWhichBeatsItsLocation() {
        // Precedence is the part a rewrite silently gets wrong: this row has all three set.
        location(1L, "LOC-01", "نیروگاه", null);
        system(10L, "SYS-01", "آب", null, 1L);
        main(101L, "MF-02", "کنترل", null, null, 1L);
        MainFunction all = main(100L, "MF-01", "پایش", 101L, 10L, 1L);

        assertThat(labels.parentLabelsForMainFunctions(List.of(all)))
                .containsEntry(100L, "تابع اصلی: MF-02 - کنترل");
    }

    @Test
    void aMainFunctionWithNoParentAtAllReadsAsADash() {
        MainFunction rootless = main(104L, "MF-05", "متفرقه", null, null, null);
        assertThat(labels.parentLabelsForMainFunctions(List.of(rootless))).containsEntry(104L, "—");
    }

    @Test
    void aPageOfMainFunctionsCostsThreeQueriesNotOnePerRow() {
        location(1L, "LOC-01", "نیروگاه", null);
        system(10L, "SYS-01", "آب", null, 1L);
        main(101L, "MF-02", "کنترل", null, null, 1L);
        List<MainFunction> page = new ArrayList<>();
        for (long i = 200; i < 450; i++) {
            page.add(main(i, "M-" + i, "تابع " + i, 101L, 10L, 1L));
        }

        labels.parentLabelsForMainFunctions(page);

        verify(mainFunctionRepository, times(1)).findAllById(any());
        verify(plantSystemRepository, times(1)).findAllById(any());
        verify(locationRepository, times(1)).findAllById(any());
        verify(mainFunctionRepository, never()).findById(anyLong());
    }

    // ── sub functions: four levels of fallback ──────────────────────────────────────────────

    @Test
    void everySubFunctionBranchRendersWhatThePerRowHelperWouldRender() {
        location(1L, "LOC-01", "نیروگاه", null);
        system(10L, "SYS-01", "آب", null, 1L);
        main(100L, "MF-01", "پایش", null, 10L, 1L);
        SubFunction parentRow = sub(200L, "SF-01", "دما", null, 100L, 10L, 1L);
        SubFunction viaParent = sub(201L, "SF-02", "فشار", 200L, 100L, 10L, 1L);
        SubFunction viaMain = sub(202L, "SF-03", "دبی", null, 100L, 10L, 1L);
        SubFunction viaSystem = sub(203L, "SF-04", "سطح", null, null, 10L, 1L);
        SubFunction viaLocation = sub(204L, "SF-05", "لرزش", null, null, null, 1L);
        SubFunction rootless = sub(205L, "SF-06", "سایر", null, null, null, null);
        SubFunction dangling = sub(206L, "SF-07", "خراب", null, 444L, null, null);

        List<SubFunction> page = List.of(parentRow, viaParent, viaMain, viaSystem,
                viaLocation, rootless, dangling);
        Map<Long, String> batch = labels.parentLabelsForSubFunctions(page);

        for (SubFunction row : page) {
            assertThat(batch.get(row.getId()))
                    .as("row %d", row.getId())
                    .isEqualTo(labels.parentLabelForSubFunction(row));
        }
    }

    @Test
    void subFunctionPrecedenceRunsParentThenMainThenSystemThenLocation() {
        location(1L, "LOC-01", "نیروگاه", null);
        system(10L, "SYS-01", "آب", null, 1L);
        main(100L, "MF-01", "پایش", null, 10L, 1L);
        sub(200L, "SF-01", "دما", null, 100L, 10L, 1L);
        SubFunction all = sub(201L, "SF-02", "فشار", 200L, 100L, 10L, 1L);

        assertThat(labels.parentLabelsForSubFunctions(List.of(all)))
                .containsEntry(201L, "تابع فرعی: SF-01 - دما");
    }

    @Test
    void aPageOfSubFunctionsCostsFourQueriesNotOnePerRow() {
        location(1L, "LOC-01", "نیروگاه", null);
        system(10L, "SYS-01", "آب", null, 1L);
        main(100L, "MF-01", "پایش", null, 10L, 1L);
        sub(200L, "SF-01", "دما", null, 100L, 10L, 1L);
        List<SubFunction> page = new ArrayList<>();
        for (long i = 300; i < 500; i++) {
            page.add(sub(i, "S-" + i, "زیرتابع " + i, 200L, 100L, 10L, 1L));
        }

        labels.parentLabelsForSubFunctions(page);

        verify(subFunctionRepository, times(1)).findAllById(any());
        verify(mainFunctionRepository, times(1)).findAllById(any());
        verify(plantSystemRepository, times(1)).findAllById(any());
        verify(locationRepository, times(1)).findAllById(any());
    }

    // ── operational units ───────────────────────────────────────────────────────────────────

    @Test
    void everyOperationalUnitRowRendersWhatThePerRowHelperWouldRender() {
        OperationalUnit root = unit(1L, "DEP-01", "تعمیرات", null);
        OperationalUnit child = unit(2L, "DEP-02", "برق", 1L);
        OperationalUnit orphan = unit(3L, "DEP-03", "ابزار دقیق", 777L);

        List<OperationalUnit> page = List.of(root, child, orphan);
        Map<Long, String> batch = labels.parentLabelsForOperationalUnits(page);

        for (OperationalUnit row : page) {
            assertThat(batch.get(row.getId()))
                    .as("row %d", row.getId())
                    .isEqualTo(labels.parentLabelForOperationalUnit(row.getParentId()));
        }
    }

    @Test
    void theNestedUnitBadgeLoopResolvesEveryUnitInOneQuery() {
        unit(1L, "DEP-01", "تعمیرات", null);
        unit(2L, "DEP-02", "برق", 1L);

        Map<Long, String> batch = labels.operationalUnitLabelsFor(List.of(1L, 2L, 1L, 2L, 99L));

        assertThat(batch)
                .containsEntry(1L, "تعمیرات")
                .containsEntry(2L, "برق")
                .containsEntry(99L, "99");           // dangling stays visible as its id
        verify(operationalUnitRepository, times(1)).findAllById(any());
        verify(operationalUnitRepository, never()).findById(anyLong());
    }

    @Test
    void everyUnitBadgeMatchesThePerRowHelperItReplaces() {
        unit(1L, "DEP-01", "تعمیرات", null);
        unit(2L, "DEP-02", null, 1L);

        Map<Long, String> batch = labels.operationalUnitLabelsFor(List.of(1L, 2L, 99L));

        for (Long id : List.of(1L, 2L, 99L)) {
            assertThat(batch.get(id)).as("unit %d", id).isEqualTo(labels.operationalUnitLabel(id));
        }
    }

    // ── scope summaries (/log-sheets, /my-inbox) ────────────────────────────────────────────

    @Test
    void everyScopeSummaryRendersWhatFormatScopeSummaryWouldRender() {
        location(1L, "LOC-01", "نیروگاه", null);
        system(10L, "SYS-01", "آب", null, 1L);
        main(100L, "MF-01", "پایش", null, 10L, 1L);
        sub(200L, "SF-01", "دما", null, 100L, 10L, 1L);

        List<String> summaries = List.of(
                "location:1", "system:10", "mainFunction:100", "subFunction:200",
                "location:99999",        // id that resolves to nothing
                "location",              // no colon at all
                "location:abc",          // id that is not a number
                "weird:1",               // a scope type nobody defined
                "");                     // stored but empty

        Map<String, String> batch = labels.scopeSummaryLabels(summaries);

        for (String summary : summaries) {
            assertThat(batch.get(summary))
                    .as("summary %s", summary)
                    .isEqualTo(labels.formatScopeSummary(summary));
        }
    }

    @Test
    void aScopeSummaryCarriesThePersianTypePrefixAndCodeAndTitle() {
        location(1L, "LOC-01", "نیروگاه", null);
        assertThat(labels.scopeSummaryLabels(List.of("location:1")))
                .containsEntry("location:1", "مکان: LOC-01 - نیروگاه");
    }

    @Test
    void aMalformedSummaryIsShownVerbatimRatherThanSwallowed() {
        // Whatever is in the column is what an operator has to debug from.
        Map<String, String> batch = labels.scopeSummaryLabels(List.of("location", "location:abc"));
        assertThat(batch)
                .containsEntry("location", "location")
                .containsEntry("location:abc", "location:abc");
    }

    @Test
    void aPageOfLogSheetsCostsOneQueryPerScopeTypeNotOnePerRow() {
        location(1L, "LOC-01", "نیروگاه", null);
        location(2L, "LOC-02", "انبار", null);
        system(10L, "SYS-01", "آب", null, 1L);
        List<String> summaries = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            summaries.add(i % 3 == 0 ? "location:1" : i % 3 == 1 ? "location:2" : "system:10");
        }

        Map<String, String> batch = labels.scopeSummaryLabels(summaries);

        assertThat(batch).hasSize(3);               // 200 rows collapse to 3 distinct scopes
        verify(locationRepository, times(1)).findAllById(any());
        verify(plantSystemRepository, times(1)).findAllById(any());
        verify(locationRepository, never()).findById(anyLong());
        verify(plantSystemRepository, never()).findById(anyLong());
    }

    @Test
    void anUnusedScopeTypeIssuesNoQueryForItsTable() {
        location(1L, "LOC-01", "نیروگاه", null);

        labels.scopeSummaryLabels(List.of("location:1"));

        verify(locationRepository, times(1)).findAllById(any());
        verify(plantSystemRepository, never()).findAllById(any());
        verify(mainFunctionRepository, never()).findAllById(any());
        verify(subFunctionRepository, never()).findAllById(any());
    }

    @Test
    void everyNonNullSummaryComesBackAsAKeySoALookupNeverYieldsNull() {
        // The template indexes this map directly. A missing key would render an empty cell where
        // the per-row helper rendered text.
        List<String> summaries = List.of("location:1", "", "garbage", "system:404");
        Map<String, String> batch = labels.scopeSummaryLabels(summaries);
        assertThat(batch).containsKeys("location:1", "", "garbage", "system:404");
        assertThat(batch.values()).doesNotContainNull();
    }

    @Test
    void aListOfOnlyNullSummariesIsEmptyRatherThanThrowing() {
        List<String> nulls = new ArrayList<>();
        nulls.add(null);
        nulls.add(null);
        assertThat(labels.scopeSummaryLabels(nulls)).isEmpty();
        assertThat(labels.scopeSummaryLabels(null)).isEmpty();
    }

    // ── degenerate input ────────────────────────────────────────────────────────────────────

    @Test
    void anEmptyPageIssuesNoQueriesAtAll() {
        assertThat(labels.parentLabelsForLocations(List.of())).isEmpty();
        assertThat(labels.parentSystemLabelsForPlantSystems(List.of())).isEmpty();
        assertThat(labels.locationLabelsForPlantSystems(List.of())).isEmpty();
        assertThat(labels.parentLabelsForMainFunctions(List.of())).isEmpty();
        assertThat(labels.parentLabelsForSubFunctions(List.of())).isEmpty();
        assertThat(labels.parentLabelsForOperationalUnits(List.of())).isEmpty();
        assertThat(labels.operationalUnitLabelsFor(List.of())).isEmpty();

        verify(locationRepository, never()).findAllById(any());
        verify(plantSystemRepository, never()).findAllById(any());
        verify(mainFunctionRepository, never()).findAllById(any());
        verify(subFunctionRepository, never()).findAllById(any());
        verify(operationalUnitRepository, never()).findAllById(any());
    }

    @Test
    void aNullPageIsTreatedAsAnEmptyOne() {
        // The controllers never pass null, but a missing model attribute must not blow up a whole
        // page inside a Thymeleaf loop.
        assertThat(labels.parentLabelsForLocations(null)).isEmpty();
        assertThat(labels.parentLabelsForMainFunctions(null)).isEmpty();
        assertThat(labels.operationalUnitLabelsFor(null)).isEmpty();
    }

    // ── fixtures ────────────────────────────────────────────────────────────────────────────

    private Location location(long id, String code, String name, Long parentId) {
        Location l = new Location();
        l.setId(id);
        l.setCode(code);
        l.setName(name);
        l.setParentId(parentId);
        locations.put(id, l);
        return l;
    }

    private PlantSystem system(long id, String code, String name, Long parentId, Long locationId) {
        PlantSystem s = new PlantSystem();
        s.setId(id);
        s.setCode(code);
        s.setName(name);
        s.setParentId(parentId);
        s.setLocationId(locationId);
        systems.put(id, s);
        return s;
    }

    private MainFunction main(long id, String code, String name,
                              Long parentId, Long systemId, Long locationId) {
        MainFunction mf = new MainFunction();
        mf.setId(id);
        mf.setCode(code);
        mf.setName(name);
        mf.setParentId(parentId);
        mf.setSystemId(systemId);
        mf.setLocationId(locationId);
        mains.put(id, mf);
        return mf;
    }

    private SubFunction sub(long id, String code, String name,
                            Long parentId, Long mainId, Long systemId, Long locationId) {
        SubFunction sf = new SubFunction();
        sf.setId(id);
        sf.setCode(code);
        sf.setName(name);
        sf.setParentId(parentId);
        sf.setMainFunctionId(mainId);
        sf.setSystemId(systemId);
        sf.setLocationId(locationId);
        subs.put(id, sf);
        return sf;
    }

    private OperationalUnit unit(long id, String code, String name, Long parentId) {
        OperationalUnit u = new OperationalUnit();
        u.setId(id);
        u.setCode(code);
        u.setName(name);
        u.setParentId(parentId);
        units.put(id, u);
        return u;
    }
}
