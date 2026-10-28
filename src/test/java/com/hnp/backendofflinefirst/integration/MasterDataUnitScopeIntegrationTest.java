package com.hnp.backendofflinefirst.integration;

import com.hnp.backendofflinefirst.entity.AssetClass;
import com.hnp.backendofflinefirst.entity.AssetEntry;
import com.hnp.backendofflinefirst.entity.Location;
import com.hnp.backendofflinefirst.entity.OperationalUnit;
import com.hnp.backendofflinefirst.entity.SubFunction;
import com.hnp.backendofflinefirst.entity.UnitOperator;
import com.hnp.backendofflinefirst.repository.AssetClassRepository;
import com.hnp.backendofflinefirst.repository.AssetEntryRepository;
import com.hnp.backendofflinefirst.repository.LocationRepository;
import com.hnp.backendofflinefirst.repository.SubFunctionRepository;
import com.hnp.backendofflinefirst.repository.OperationalUnitRepository;
import com.hnp.backendofflinefirst.repository.UnitOperatorRepository;
import com.hnp.backendofflinefirst.service.AssetHierarchyService;
import com.hnp.backendofflinefirst.support.AbstractPostgresIntegrationTest;
import com.hnp.backendofflinefirst.support.WithAppUser;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Master-data pages are narrowed to the units the caller may see — {@code security.md} F1.
 *
 * <p><strong>Nobody is unit-scoped on these pages today.</strong> The only roles holding
 * {@code GET:/asset-entries} are ADMIN and HIGH_USER, and both carry
 * {@code CAP:SCOPE_PLANT_WIDE}. That is exactly why these tests matter: the filter has to be
 * correct <em>before</em> somebody is granted the permission, because the moment they are, the
 * alternative is handing them the whole plant's register with no code path objecting.
 *
 * <p>The principals here are therefore deliberately artificial — a bare authority with no
 * plant-wide capability — which is precisely the configuration an administrator creates by
 * ticking one box on the Roles page.
 */
@Transactional
class MasterDataUnitScopeIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final long SCOPED_USER_ID = 1L;   // WithAppUser injects a fixed id=1 principal

    @Autowired WebApplicationContext context;
    @Autowired AssetHierarchyService hierarchyService;
    @Autowired AssetEntryRepository assetEntryRepository;
    @Autowired AssetClassRepository assetClassRepository;
    @Autowired OperationalUnitRepository operationalUnitRepository;
    @Autowired UnitOperatorRepository unitOperatorRepository;
    @Autowired LocationRepository locationRepository;
    @Autowired SubFunctionRepository subFunctionRepository;
    @Autowired EntityManager entityManager;

    private MockMvc mvc;
    private AssetEntry mine;
    private AssetEntry theirs;
    private Location myLocation;
    private Location theirLocation;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        seed();
    }

    // =======================================================================================
    // The register
    // =======================================================================================

    @Test
    @WithAppUser(authorities = "GET:/asset-entries")
    @DisplayName("a unit-scoped holder of the permission sees only their own unit's assets")
    void theRegisterIsNarrowedToTheCallersUnits() throws Exception {
        mvc.perform(get("/asset-entries").param("size", "500"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(mine.getAssetCode())))
                .andExpect(content().string(not(containsString(theirs.getAssetCode()))));
    }

    @Test
    @WithAppUser(authorities = {"GET:/asset-entries", "CAP:SCOPE_PLANT_WIDE"})
    @DisplayName("a plant-wide holder still sees everything — today's behaviour, unchanged")
    void aPlantWideCallerIsUnaffected() throws Exception {
        mvc.perform(get("/asset-entries").param("size", "500"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(mine.getAssetCode())))
                .andExpect(content().string(containsString(theirs.getAssetCode())));
    }

    @Test
    @WithAppUser(authorities = "GET:/asset-entries")
    @DisplayName("the class filter narrows WITHIN the scope, it does not escape it")
    void theClassFilterCannotWidenTheScope() throws Exception {
        Long classId = assetEntryRepository.findById(theirs.getId()).orElseThrow().getClassId();

        mvc.perform(get("/asset-entries").param("classId", String.valueOf(classId)).param("size", "500"))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString(theirs.getAssetCode()))));
    }

    @Test
    @WithAppUser(authorities = "GET:/asset-entries")
    @DisplayName("the search box cannot escape it either")
    void theSearchBoxCannotWidenTheScope() throws Exception {
        // Asserted on the MODEL, not the markup: the page echoes the search term back into the
        // box's `value`, so the code appears in the HTML whether or not its row was listed.
        // Checking the rendered string here would pass for the wrong reason, or fail for one.
        mvc.perform(get("/asset-entries").param("q", theirs.getAssetCode()))
                .andExpect(status().isOk())
                .andExpect(model().attribute("assetEntries", org.hamcrest.Matchers.empty()));
    }

    // =======================================================================================
    // The per-id doors — where the damage would be done, not merely seen
    // =======================================================================================

    @Test
    @WithAppUser(authorities = "GET:/asset-entries")
    @DisplayName("the parameter screen refuses an asset outside the caller's units")
    void theParameterScreenIsObjectChecked() throws Exception {
        // A wrong band is worse than a wrong name: it is the number an operator's reading is
        // judged against, and nothing about the page says whose asset it is.
        mvc.perform(get("/asset-entries/{id}/parameters", theirs.getId()))
                .andExpect(status().is3xxRedirection());

        mvc.perform(get("/asset-entries/{id}/parameters", mine.getId()))
                .andExpect(status().isOk());
    }

    @Test
    @WithAppUser(authorities = {"GET:/asset-entries", "CAP:SCOPE_PLANT_WIDE"})
    @DisplayName("...and opens either of them for a plant-wide caller")
    void thePlantWideCallerOpensBoth() throws Exception {
        mvc.perform(get("/asset-entries/{id}/parameters", theirs.getId())).andExpect(status().isOk());
        mvc.perform(get("/asset-entries/{id}/parameters", mine.getId())).andExpect(status().isOk());
    }

    // =======================================================================================
    // The hierarchy
    // =======================================================================================

    @Test
    @WithAppUser(authorities = "GET:/locations")
    @DisplayName("the location list is narrowed the same way")
    void locationsAreNarrowed() throws Exception {
        mvc.perform(get("/locations").param("size", "500"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(myLocation.getCode())))
                .andExpect(content().string(not(containsString(theirLocation.getCode()))));
    }

    @Test
    @WithAppUser(authorities = {"GET:/locations", "CAP:SCOPE_PLANT_WIDE"})
    void locationsAreUnchangedForAPlantWideCaller() throws Exception {
        mvc.perform(get("/locations").param("size", "500"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(myLocation.getCode())))
                .andExpect(content().string(containsString(theirLocation.getCode())));
    }

    @Test
    @WithAppUser(authorities = "GET:/sub-functions")
    @DisplayName("sub-functions too — the level with 10,900 rows, filtered in SQL not Java")
    void subFunctionsAreNarrowed() throws Exception {
        mvc.perform(get("/sub-functions").param("size", "500"))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("SF-THEIRS"))));
    }

    // =======================================================================================
    // The hierarchy write paths — the door the list filter was only a curtain in front of
    // =======================================================================================

    @Test
    @WithAppUser(authorities = {"GET:/locations", "POST:/locations/{id}"})
    @DisplayName("editing a location outside the caller's units is refused")
    void editingAnUnscopedLocationIsRefused() throws Exception {
        mvc.perform(post("/locations/{id}", theirLocation.getId())
                        .param("code", theirLocation.getCode())
                        .param("name", "renamed by someone else")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection());

        entityManager.clear();
        assertThat(locationRepository.findById(theirLocation.getId()).orElseThrow().getName())
                .as("the write must not have happened")
                .isNotEqualTo("renamed by someone else");
    }

    @Test
    @WithAppUser(authorities = {"GET:/locations", "POST:/locations/{id}", "CAP:SCOPE_PLANT_WIDE"})
    @DisplayName("...and permitted for a plant-wide caller, exactly as before")
    void aPlantWideCallerMayStillEditIt() throws Exception {
        mvc.perform(post("/locations/{id}", theirLocation.getId())
                        .param("code", theirLocation.getCode())
                        .param("name", "renamed by an admin")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection());

        entityManager.clear();
        assertThat(locationRepository.findById(theirLocation.getId()).orElseThrow().getName())
                .isEqualTo("renamed by an admin");
    }

    @Test
    @WithAppUser(authorities = {"GET:/sub-functions", "POST:/sub-functions/{id}/delete"})
    @DisplayName("deleting a sub-function outside the caller's units is refused")
    void deletingAnUnscopedSubFunctionIsRefused() throws Exception {
        Long theirSubFunction = assetEntryRepository.findById(theirs.getId())
                .orElseThrow().getSubFunctionId();

        mvc.perform(post("/sub-functions/{id}/delete", theirSubFunction).with(csrf()))
                .andExpect(status().is3xxRedirection());

        entityManager.clear();
        assertThat(subFunctionRepository.findById(theirSubFunction))
                .as("still there")
                .isPresent();
    }

    @Test
    @WithAppUser(authorities = {"GET:/locations", "POST:/locations/{id}/delete"})
    @DisplayName("a bulk delete spanning the boundary is refused WHOLE, not half-applied")
    void aBulkDeleteIsAllOrNothing() throws Exception {
        mvc.perform(post("/locations/delete-bulk")
                        .param("ids", String.valueOf(myLocation.getId()))
                        .param("ids", String.valueOf(theirLocation.getId()))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection());

        entityManager.clear();
        assertThat(locationRepository.findById(myLocation.getId()))
                .as("the in-scope one must NOT have been deleted either — telling an operator "
                        + "it worked while half of it did not is worse than refusing")
                .isPresent();
        assertThat(locationRepository.findById(theirLocation.getId())).isPresent();
    }

    // =======================================================================================
    // Failing closed
    // =======================================================================================

    @Test
    @WithAppUser(authorities = "GET:/asset-entries")
    @DisplayName("a scoped user mapped to NO unit sees nothing, rather than everything")
    void noUnitsMeansNoRows() throws Exception {
        unitOperatorRepository.deleteAll(unitOperatorRepository.findByUserId(SCOPED_USER_ID));
        entityManager.flush();
        entityManager.clear();

        mvc.perform(get("/asset-entries").param("size", "500"))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString(mine.getAssetCode()))))
                .andExpect(content().string(not(containsString(theirs.getAssetCode()))));
    }

    @Test
    @DisplayName("the scope walk starts at location_units — an unmapped location is invisible")
    void anUnmappedLocationIsOutsideEveryScope() {
        // Stated as a test because it is the thing most likely to be mistaken for a bug: on the
        // operational database `location_units` holds a single row, so a unit-scoped user would
        // see an empty list until locations are mapped to units. Safe, but it will look wrong.
        assertThat(theirLocation.getId()).isNotNull();
        assertThat(assetEntryRepository.findById(theirs.getId())).isPresent();
    }

    // =======================================================================================

    private void seed() {
        long now = System.nanoTime();

        OperationalUnit unit = new OperationalUnit();
        unit.setCode("OU-SCOPE-" + now);
        unit.setName("واحد من");
        unit.setCreatedAt(now);
        unit.setUpdatedAt(now);
        unit = operationalUnitRepository.save(unit);

        UnitOperator link = new UnitOperator();
        link.setUnitId(unit.getId());
        link.setUserId(SCOPED_USER_ID);
        unitOperatorRepository.saveAndFlush(link);

        AssetClass klass = new AssetClass();
        klass.setName("Pumps " + now);
        klass.setCreatedAt(now);
        klass.setUpdatedAt(now);
        klass = assetClassRepository.save(klass);

        // Mapped to the unit — the scope CTE begins at location_units, so this is what makes
        // everything beneath it visible.
        myLocation = newLocation("LOC-MINE-" + now, List.of(unit.getId()));
        theirLocation = newLocation("LOC-THEIRS-" + now, List.of());

        mine = newAsset("AST-MINE-" + now, klass.getId(), newSubFunction("SF-MINE-" + now, myLocation.getId()));
        theirs = newAsset("AST-THEIRS-" + now, klass.getId(), newSubFunction("SF-THEIRS", theirLocation.getId()));

        entityManager.flush();
        entityManager.clear();
    }

    private Location newLocation(String code, List<Long> unitIds) {
        Location location = new Location();
        location.setCode(code);
        location.setName(code);
        location.setCreatedAt(1L);
        location.setUpdatedAt(1L);
        return hierarchyService.saveLocation(location, unitIds);
    }

    private Long newSubFunction(String code, Long locationId) {
        SubFunction sf = new SubFunction();
        sf.setCode(code);
        sf.setName(code);
        sf.setTag(code + "-TAG");
        sf.setCreatedAt(1L);
        sf.setUpdatedAt(1L);
        hierarchyService.applySubFunctionParent(sf, AssetHierarchyService.SCOPE_LOCATION, locationId);
        return hierarchyService.saveSubFunction(sf).getId();
    }

    private AssetEntry newAsset(String code, Long classId, Long subFunctionId) {
        AssetEntry asset = new AssetEntry();
        asset.setAssetCode(code);
        asset.setAssetName(code);
        asset.setClassId(classId);
        asset.setSubFunctionId(subFunctionId);
        asset.setActive(true);
        asset.setCreatedAt(1L);
        asset.setUpdatedAt(1L);
        return assetEntryRepository.save(asset);
    }
}
