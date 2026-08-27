package com.hnp.backendofflinefirst.integration;

import com.hnp.backendofflinefirst.entity.Location;
import com.hnp.backendofflinefirst.entity.MainFunction;
import com.hnp.backendofflinefirst.entity.OperationalUnit;
import com.hnp.backendofflinefirst.entity.PlantSystem;
import com.hnp.backendofflinefirst.entity.SubFunction;
import com.hnp.backendofflinefirst.repository.LocationRepository;
import com.hnp.backendofflinefirst.repository.MainFunctionRepository;
import com.hnp.backendofflinefirst.repository.OperationalUnitRepository;
import com.hnp.backendofflinefirst.repository.PlantSystemRepository;
import com.hnp.backendofflinefirst.repository.SubFunctionRepository;
import com.hnp.backendofflinefirst.support.AbstractPostgresIntegrationTest;
import com.hnp.backendofflinefirst.support.WithAppUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The five hierarchy list pages render their parent column from a preloaded map instead of a
 * per-row {@code @labels.*} call. These tests render the real HTML and check the text that comes
 * out, because that is what the change put at risk.
 *
 * <p>{@link com.hnp.backendofflinefirst.util.ReferenceLabelBatchEquivalenceTest} already pins the
 * map builders against the per-row helpers in isolation. What it cannot see is the wiring: a
 * controller that forgets an attribute, a template indexing the wrong map, or — the specific trap
 * here — a map keyed by the parent id rather than the row id, which works for every row that has
 * a parent and throws a SpEL exception on the first row that does not.
 *
 * <p>So each page below is seeded with a root row (no parent) alongside a child row, and asserted
 * on both. A page that only ever had children would pass while broken.
 */
class HierarchyListLabelsIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired WebApplicationContext context;
    @Autowired LocationRepository locationRepository;
    @Autowired PlantSystemRepository plantSystemRepository;
    @Autowired MainFunctionRepository mainFunctionRepository;
    @Autowired SubFunctionRepository subFunctionRepository;
    @Autowired OperationalUnitRepository operationalUnitRepository;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    MockMvc mockMvc;
    long now;
    String stamp;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        now = System.currentTimeMillis();
        stamp = String.valueOf(now);
    }

    // ── /locations ──────────────────────────────────────────────────────────────────────────

    @Test
    @WithAppUser(authorities = "GET:/locations")
    void locationsPageRendersParentNamesAndADashForARootRow() throws Exception {
        Location parent = location("LOC-P-" + stamp, "نیروگاه‌مرکزی" + stamp, null);
        location("LOC-C-" + stamp, "واحدبخار" + stamp, parent.getId());

        mockMvc.perform(get("/locations").param("size", "250"))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("parentLabels"))
                .andExpect(content().string(containsString("نیروگاه‌مرکزی" + stamp)))
                .andExpect(content().string(containsString("واحدبخار" + stamp)));
    }

    @Test
    void aDanglingParentCannotExistBecauseTheDatabaseRefusesOne() {
        // Worth pinning, because it decides how much the label fallback has to carry. Every parent
        // reference in the hierarchy is a foreign key — locations.parent_id, plant_systems.parent_id
        // and .location_id, main_functions.parent_id/.system_id/.location_id, and all four on
        // sub_functions. So the «parent id that resolves to nothing» branch in the batch builders
        // is unreachable through normal operation.
        //
        // It is kept anyway, and kept identical to `findById(...).orElse(String.valueOf(id))`: the
        // per-row helpers it replaces behave that way, and a batch builder that quietly rendered
        // «—» instead would turn a broken reference into the claim that a row is at the top of the
        // hierarchy. ReferenceLabelBatchEquivalenceTest covers the branch itself.
        Location orphan = new Location();
        orphan.setCode("LOC-D-" + stamp);
        orphan.setName("یتیم" + stamp);
        orphan.setParentId(987654321L);
        orphan.setCreatedAt(now);
        orphan.setUpdatedAt(now);

        assertThatThrownBy(() -> locationRepository.saveAndFlush(orphan))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("fk_locations_parent");
    }

    // ── /plant-systems: two columns, two formats ────────────────────────────────────────────

    @Test
    @WithAppUser(authorities = "GET:/plant-systems")
    void plantSystemsPageRendersScopeLabelsWithTheirPersianTypePrefix() throws Exception {
        Location loc = location("LOC-S-" + stamp, "سایت‌غربی" + stamp, null);
        PlantSystem parent = system("SYS-P-" + stamp, "آب‌خام" + stamp, null, loc.getId());
        system("SYS-C-" + stamp, "بخارفشارقوی" + stamp, parent.getId(), loc.getId());

        mockMvc.perform(get("/plant-systems").param("size", "250"))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("parentSystemLabels", "locationLabels"))
                // the location column keeps «مکان: code - name», not the bare name
                .andExpect(content().string(containsString(
                        "مکان: LOC-S-" + stamp + " - سایت‌غربی" + stamp)))
                // and the parent column keeps «سیستم: code - name»
                .andExpect(content().string(containsString(
                        "سیستم: SYS-P-" + stamp + " - آب‌خام" + stamp)));
    }

    @Test
    @WithAppUser(authorities = "GET:/plant-systems")
    void aPlantSystemWithNoLocationRendersADashInsteadOfThrowing() throws Exception {
        // Keyed by the parent id this row would be a null map index, which SpEL refuses.
        system("SYS-N-" + stamp, "بی‌مکان" + stamp, null, null);

        mockMvc.perform(get("/plant-systems").param("size", "250"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("بی‌مکان" + stamp)));
    }

    // ── /main-functions ─────────────────────────────────────────────────────────────────────

    @Test
    @WithAppUser(authorities = "GET:/main-functions")
    void mainFunctionsPageResolvesParentThenSystemThenLocation() throws Exception {
        Location loc = location("LOC-M-" + stamp, "محوطه" + stamp, null);
        PlantSystem sys = system("SYS-M-" + stamp, "تهویه" + stamp, null, loc.getId());
        MainFunction parent = mainFunction("MF-P-" + stamp, "پایش‌کلی" + stamp, null, null, loc.getId());
        mainFunction("MF-S-" + stamp, "ازطریق‌سیستم" + stamp, null, sys.getId(), null);
        mainFunction("MF-C-" + stamp, "ازطریق‌والد" + stamp, parent.getId(), sys.getId(), loc.getId());

        mockMvc.perform(get("/main-functions").param("size", "250"))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("parentLabels"))
                .andExpect(content().string(containsString(
                        "تابع اصلی: MF-P-" + stamp + " - پایش‌کلی" + stamp)))
                .andExpect(content().string(containsString(
                        "سیستم: SYS-M-" + stamp + " - تهویه" + stamp)))
                .andExpect(content().string(containsString(
                        "مکان: LOC-M-" + stamp + " - محوطه" + stamp)));
    }

    @Test
    @WithAppUser(authorities = "GET:/main-functions")
    void aMainFunctionWithNoParentSystemOrLocationRendersADash() throws Exception {
        mainFunction("MF-R-" + stamp, "کاملابی‌والد" + stamp, null, null, null);

        mockMvc.perform(get("/main-functions").param("size", "250"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("کاملابی‌والد" + stamp)));
    }

    // ── /sub-functions ──────────────────────────────────────────────────────────────────────

    @Test
    @WithAppUser(authorities = "GET:/sub-functions")
    void subFunctionsPageResolvesAllFourFallbackLevels() throws Exception {
        Location loc = location("LOC-F-" + stamp, "بخش‌شرقی" + stamp, null);
        MainFunction mf = mainFunction("MF-F-" + stamp, "کنترل‌دما" + stamp, null, null, loc.getId());
        SubFunction parent = subFunction("SF-P-" + stamp, "دما" + stamp, null, mf.getId(), null, null);
        subFunction("SF-C-" + stamp, "ازطریق‌والد" + stamp, parent.getId(), null, null, null);
        subFunction("SF-L-" + stamp, "ازطریق‌مکان" + stamp, null, null, null, loc.getId());

        mockMvc.perform(get("/sub-functions").param("size", "250"))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("parentLabels"))
                .andExpect(content().string(containsString(
                        "تابع فرعی: SF-P-" + stamp + " - دما" + stamp)))
                .andExpect(content().string(containsString(
                        "تابع اصلی: MF-F-" + stamp + " - کنترل‌دما" + stamp)))
                .andExpect(content().string(containsString(
                        "مکان: LOC-F-" + stamp + " - بخش‌شرقی" + stamp)));
    }

    // ── /operational-units ──────────────────────────────────────────────────────────────────

    @Test
    @WithAppUser(authorities = "GET:/operational-units")
    void operationalUnitsPageRendersParentNames() throws Exception {
        OperationalUnit parent = unit("DEP-P-" + stamp, "تعمیرات‌مرکزی" + stamp, null);
        unit("DEP-C-" + stamp, "برق‌سایت" + stamp, parent.getId());

        mockMvc.perform(get("/operational-units").param("size", "250"))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("parentLabels"))
                .andExpect(content().string(containsString("تعمیرات‌مرکزی" + stamp)))
                .andExpect(content().string(containsString("برق‌سایت" + stamp)));
    }

    // ── the scope column on /log-sheets and /my-inbox ───────────────────────────────────────

    @Test
    @WithAppUser(authorities = {"GET:/log-sheets", "CAP:SCOPE_PLANT_WIDE"})
    void theLogSheetScopeColumnKeepsItsPersianTypePrefix() throws Exception {
        // The column resolved one location per row before this — 115 queries on a full page.
        Location loc = location("LOC-LS-" + stamp, "محوطه‌لاگ" + stamp, null);
        insertLogSheet("location:" + loc.getId());

        mockMvc.perform(get("/log-sheets").param("size", "250"))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("scopeLabels"))
                .andExpect(content().string(containsString(
                        "مکان: LOC-LS-" + stamp + " - محوطه‌لاگ" + stamp)));
    }

    @Test
    @WithAppUser(authorities = {"GET:/log-sheets", "CAP:SCOPE_PLANT_WIDE"})
    void aLogSheetWithNoScopeRendersADashRatherThanFailingThePage() throws Exception {
        // scope_summary is nullable, and a null map index is what SpEL refuses. The template
        // decides the null case before it indexes, which is what this proves.
        insertLogSheet(null);

        mockMvc.perform(get("/log-sheets").param("size", "250"))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("scopeLabels"));
    }

    @Test
    @WithAppUser(authorities = {"GET:/my-inbox", "CAP:SCOPE_PLANT_WIDE"})
    void theInboxRendersItsTwoListsFromOneSharedScopeMap() throws Exception {
        Location loc = location("LOC-IN-" + stamp, "انباری" + stamp, null);
        insertLogSheet("location:" + loc.getId());

        mockMvc.perform(get("/my-inbox"))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("scopeLabels"));
    }

    // ── the templates must no longer reach for the per-row bean ─────────────────────────────

    @Test
    @WithAppUser(authorities = {"GET:/locations", "GET:/plant-systems", "GET:/main-functions",
            "GET:/sub-functions", "GET:/operational-units"})
    void noHierarchyPageStillRendersAnUnresolvedLabelExpression() throws Exception {
        // A Thymeleaf expression that fails to resolve can surface as literal text rather than an
        // error. If «@labels.» ever appears in the output, the template was not rewired.
        location("LOC-X-" + stamp, "نمونه" + stamp, null);
        for (String path : new String[]{"/locations", "/plant-systems", "/main-functions",
                "/sub-functions", "/operational-units"}) {
            mockMvc.perform(get(path).param("size", "250"))
                    .andExpect(status().isOk())
                    .andExpect(content().string(not(containsString("@labels."))));
        }
    }

    // ── fixtures ────────────────────────────────────────────────────────────────────────────

    /** A log sheet is only needed here for its scope column, so only the NOT NULL columns are set. */
    private void insertLogSheet(String scopeSummary) {
        jdbcTemplate.update(
                "INSERT INTO log_sheets (status, origin, scope_summary, template_name, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                "ASSIGNED", "MANUAL", scopeSummary, "قالب-" + stamp, now, now);
    }

    private Location location(String code, String name, Long parentId) {
        Location l = new Location();
        l.setCode(code);
        l.setName(name);
        l.setParentId(parentId);
        l.setCreatedAt(now);
        l.setUpdatedAt(now);
        return locationRepository.saveAndFlush(l);
    }

    private PlantSystem system(String code, String name, Long parentId, Long locationId) {
        PlantSystem s = new PlantSystem();
        s.setCode(code);
        s.setName(name);
        s.setParentId(parentId);
        s.setLocationId(locationId);
        s.setCreatedAt(now);
        s.setUpdatedAt(now);
        return plantSystemRepository.saveAndFlush(s);
    }

    private MainFunction mainFunction(String code, String name, Long parentId,
                                      Long systemId, Long locationId) {
        MainFunction mf = new MainFunction();
        mf.setCode(code);
        mf.setName(name);
        mf.setParentId(parentId);
        mf.setSystemId(systemId);
        mf.setLocationId(locationId);
        mf.setCreatedAt(now);
        mf.setUpdatedAt(now);
        return mainFunctionRepository.saveAndFlush(mf);
    }

    private SubFunction subFunction(String code, String name, Long parentId, Long mainFunctionId,
                                    Long systemId, Long locationId) {
        SubFunction sf = new SubFunction();
        sf.setCode(code);
        sf.setName(name);
        sf.setTag("TAG-" + code);
        sf.setParentId(parentId);
        sf.setMainFunctionId(mainFunctionId);
        sf.setSystemId(systemId);
        sf.setLocationId(locationId);
        sf.setCreatedAt(now);
        sf.setUpdatedAt(now);
        return subFunctionRepository.saveAndFlush(sf);
    }

    private OperationalUnit unit(String code, String name, Long parentId) {
        OperationalUnit u = new OperationalUnit();
        u.setCode(code);
        u.setName(name);
        u.setParentId(parentId);
        u.setCreatedAt(now);
        u.setUpdatedAt(now);
        return operationalUnitRepository.saveAndFlush(u);
    }
}
