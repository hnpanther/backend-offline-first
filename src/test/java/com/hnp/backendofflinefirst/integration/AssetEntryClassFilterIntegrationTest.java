package com.hnp.backendofflinefirst.integration;

import com.hnp.backendofflinefirst.entity.AssetClass;
import com.hnp.backendofflinefirst.entity.AssetEntry;
import com.hnp.backendofflinefirst.entity.SubFunction;
import com.hnp.backendofflinefirst.repository.AssetClassRepository;
import com.hnp.backendofflinefirst.repository.AssetEntryRepository;
import com.hnp.backendofflinefirst.repository.SubFunctionRepository;
import com.hnp.backendofflinefirst.support.AbstractPostgresIntegrationTest;
import com.hnp.backendofflinefirst.support.WithAppUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Narrowing the asset registry to one class — "show me only the pumps".
 *
 * <p>The filter is applied <b>in SQL</b>, which is the part worth pinning. On a registry of a few
 * thousand assets, filtering the page that was already loaded would answer a different question:
 * it would show the pumps among the twenty-five rows on screen rather than the pumps in the plant,
 * and the result would change depending on which page happened to be open.
 */
class AssetEntryClassFilterIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired WebApplicationContext context;
    @Autowired AssetEntryRepository assetEntryRepository;
    @Autowired AssetClassRepository assetClassRepository;
    @Autowired SubFunctionRepository subFunctionRepository;

    MockMvc mockMvc;
    long stamp;
    AssetClass pumps;
    AssetClass motors;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        stamp = System.nanoTime();
        pumps = assetClass("کلاس-پمپ-" + stamp);
        motors = assetClass("کلاس-موتور-" + stamp);
        asset("FLT-PUMP-A-" + stamp, "Pump A", pumps);
        asset("FLT-PUMP-B-" + stamp, "Pump B", pumps);
        asset("FLT-MOTOR-A-" + stamp, "Motor A", motors);
    }

    @Test
    @WithAppUser(authorities = "GET:/asset-entries")
    void filteringByClassShowsOnlyThatClass() throws Exception {
        mockMvc.perform(get("/asset-entries")
                        .param("q", "FLT-")
                        .param("classId", String.valueOf(pumps.getId())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("FLT-PUMP-A-" + stamp)))
                .andExpect(content().string(containsString("FLT-PUMP-B-" + stamp)))
                .andExpect(content().string(not(containsString("FLT-MOTOR-A-" + stamp))));
    }

    @Test
    @WithAppUser(authorities = "GET:/asset-entries")
    void withoutTheFilterEveryClassIsListed() throws Exception {
        mockMvc.perform(get("/asset-entries").param("q", "FLT-"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("filterClassId", (Object) null))
                .andExpect(content().string(containsString("FLT-PUMP-A-" + stamp)))
                .andExpect(content().string(containsString("FLT-MOTOR-A-" + stamp)));
    }

    @Test
    @WithAppUser(authorities = "GET:/asset-entries")
    void theClassFilterAndTheSearchBoxNarrowTogether() throws Exception {
        // Both or neither — a class filter that ignored the search term, or the other way round,
        // would quietly show rows the operator has excluded. Asserted on the rows the page was
        // given rather than on its HTML: the search box echoes the term back into the markup, so
        // the text appears on the page whether or not anything matched.
        mockMvc.perform(get("/asset-entries")
                        .param("q", "FLT-PUMP-A-" + stamp)
                        .param("classId", String.valueOf(pumps.getId())))
                .andExpect(status().isOk())
                .andExpect(model().attribute("assetEntries", hasSize(1)));

        // Same search, wrong class: nothing matches, rather than the search winning.
        mockMvc.perform(get("/asset-entries")
                        .param("q", "FLT-PUMP-A-" + stamp)
                        .param("classId", String.valueOf(motors.getId())))
                .andExpect(status().isOk())
                .andExpect(model().attribute("assetEntries", hasSize(0)));
    }

    @Test
    @WithAppUser(authorities = "GET:/asset-entries")
    void theChosenClassStaysSelectedAndThePagingLinksKeepIt() throws Exception {
        // Losing the filter on the way to page two is the same defect as losing it on save.
        mockMvc.perform(get("/asset-entries").param("classId", String.valueOf(pumps.getId())))
                .andExpect(status().isOk())
                .andExpect(model().attribute("filterClassId", pumps.getId()))
                .andExpect(content().string(containsString("کلاس-پمپ-" + stamp)));
    }

    @Test
    void theFilterReachesTheWholeRegistryNotJustTheLoadedPage() {
        // Straight at the query, with a page far too small to hold the answer by accident.
        var firstPumpOnly = assetEntryRepository.filterList("FLT-", pumps.getId(), PageRequest.of(0, 1));

        assertThat(firstPumpOnly.getTotalElements()).isEqualTo(2);
        assertThat(firstPumpOnly.getContent()).hasSize(1);
        assertThat(assetEntryRepository.filterList("FLT-", motors.getId(), PageRequest.of(0, 25))
                .getTotalElements()).isEqualTo(1);
        assertThat(assetEntryRepository.filterList("FLT-", null, PageRequest.of(0, 25))
                .getTotalElements()).isEqualTo(3);
    }

    @Test
    @WithAppUser(authorities = "GET:/asset-entries")
    void thePageOpensWithNoFiltersAtAll() throws Exception {
        // The default load, and the case every other test here happened to miss: one covered a
        // search with no class, another a class with no search, none covered neither. With both
        // parameters absent PostgreSQL had no type to infer for the search parameter and the
        // whole page failed to plan — `function lower(bytea) does not exist`.
        mockMvc.perform(get("/asset-entries"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("FLT-PUMP-A-" + stamp)));
    }

    @Test
    void bothFiltersAbsentReturnsTheWholeRegistry() {
        assertThat(assetEntryRepository.filterList(null, null, PageRequest.of(0, 250))
                .getTotalElements()).isGreaterThanOrEqualTo(3);
    }

    @Test
    void aBlankSearchWithAClassStillNarrowsToTheClass() {
        // The list page passes null for an empty search box; the class must still apply.
        assertThat(assetEntryRepository.filterList(null, pumps.getId(), PageRequest.of(0, 100))
                .getContent())
                .allSatisfy(a -> assertThat(a.getClassId()).isEqualTo(pumps.getId()));
    }

    // ---------------------------------------------------------------- fixture

    private AssetClass assetClass(String name) {
        long now = System.currentTimeMillis();
        AssetClass c = new AssetClass();
        c.setName(name);
        c.setCreatedAt(now);
        c.setUpdatedAt(now);
        return assetClassRepository.saveAndFlush(c);
    }

    private void asset(String code, String name, AssetClass cls) {
        long now = System.currentTimeMillis();
        SubFunction sf = new SubFunction();
        sf.setCode("SF-" + code);
        sf.setName("SF " + name);
        sf.setTag("TAG-" + code);
        sf.setCreatedAt(now);
        sf.setUpdatedAt(now);
        sf = subFunctionRepository.saveAndFlush(sf);

        AssetEntry a = new AssetEntry();
        a.setAssetCode(code);
        a.setAssetName(name);
        a.setClassId(cls.getId());
        a.setSubFunctionId(sf.getId());
        a.setActive(true);
        a.setCreatedAt(now);
        a.setUpdatedAt(now);
        assetEntryRepository.saveAndFlush(a);
    }
}
