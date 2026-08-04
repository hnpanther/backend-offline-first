package com.hnp.backendofflinefirst.integration;

import com.hnp.backendofflinefirst.domain.AssetSelectionMode;
import com.hnp.backendofflinefirst.entity.AssetClass;
import com.hnp.backendofflinefirst.entity.AssetEntry;
import com.hnp.backendofflinefirst.entity.Location;
import com.hnp.backendofflinefirst.entity.LogSheetTemplate;
import com.hnp.backendofflinefirst.entity.MainFunction;
import com.hnp.backendofflinefirst.entity.OperationalUnit;
import com.hnp.backendofflinefirst.entity.PlantSystem;
import com.hnp.backendofflinefirst.entity.SubFunction;
import com.hnp.backendofflinefirst.repository.AssetClassRepository;
import com.hnp.backendofflinefirst.repository.AssetEntryRepository;
import com.hnp.backendofflinefirst.repository.LogSheetTemplateAssetRepository;
import com.hnp.backendofflinefirst.repository.LogSheetTemplateRepository;
import com.hnp.backendofflinefirst.repository.OperationalUnitRepository;
import com.hnp.backendofflinefirst.service.AssetHierarchyService;
import com.hnp.backendofflinefirst.support.AbstractPostgresIntegrationTest;
import com.hnp.backendofflinefirst.support.WithAppUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The template form POST for EXPLICIT mode. The browser omits scope/class entirely (those
 * inputs are disabled), so this exercises exactly that shape: name + unit + mode + assetIds
 * and nothing else. Complements {@code ExplicitTemplateAssetIntegrationTest}, which covers
 * generation once the template exists.
 */
class ExplicitTemplateWebFormIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired WebApplicationContext context;
    @Autowired AssetHierarchyService hierarchyService;
    @Autowired AssetEntryRepository assetEntryRepository;
    @Autowired AssetClassRepository assetClassRepository;
    @Autowired OperationalUnitRepository operationalUnitRepository;
    @Autowired LogSheetTemplateRepository templateRepository;
    @Autowired LogSheetTemplateAssetRepository templateAssetRepository;

    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    @WithAppUser(authorities = "POST:/log-sheet-templates", roles = "ADMIN")
    void postingTheExplicitFormWithoutScopeOrClassCreatesAFrozenTemplate() throws Exception {
        Fixture f = seed();
        String name = "Explicit web form " + System.nanoTime();

        mockMvc.perform(post("/log-sheet-templates").with(csrf())
                        .param("name", name)
                        .param("operationalUnitId", String.valueOf(f.unitId()))
                        .param("assetSelectionMode", "EXPLICIT")
                        .param("assetIds", String.valueOf(f.assetB()))
                        .param("assetIds", String.valueOf(f.assetA()))
                        .param("active", "true")
                        .param("generationMode", "MANUAL")
                        .param("scheduleActive", "false"))
                .andExpect(status().is3xxRedirection());

        LogSheetTemplate saved = templateRepository.findByNameIgnoreCase(name).orElseThrow();
        assertThat(saved.getAssetSelectionMode()).isEqualTo(AssetSelectionMode.EXPLICIT);
        assertThat(saved.getScopeType()).isNull();
        assertThat(saved.getScopeId()).isNull();
        assertThat(saved.getClassId()).isNull();
        // Order follows the submitted parameter order, not the ids' natural order.
        assertThat(templateAssetRepository.findAssetIdsByTemplateId(saved.getId()))
                .containsExactly(f.assetB(), f.assetA());
    }

    @Test
    @WithAppUser(authorities = "POST:/log-sheet-templates", roles = "ADMIN")
    void postingExplicitWithNoAssetsIsRejectedAndCreatesNothing() throws Exception {
        Fixture f = seed();
        String name = "Explicit empty " + System.nanoTime();

        mockMvc.perform(post("/log-sheet-templates").with(csrf())
                        .param("name", name)
                        .param("operationalUnitId", String.valueOf(f.unitId()))
                        .param("assetSelectionMode", "EXPLICIT")
                        .param("active", "true")
                        .param("generationMode", "MANUAL")
                        .param("scheduleActive", "false"))
                .andExpect(status().is3xxRedirection());

        assertThat(templateRepository.findByNameIgnoreCase(name)).isEmpty();
    }

    @Test
    @WithAppUser(authorities = "POST:/log-sheet-templates", roles = "ADMIN")
    void postingExplicitWithAnInactiveAssetIsRejected() throws Exception {
        Fixture f = seed();
        AssetEntry inactive = assetEntryRepository.findById(f.assetA()).orElseThrow();
        inactive.setActive(false);
        assetEntryRepository.saveAndFlush(inactive);

        String name = "Explicit inactive " + System.nanoTime();
        mockMvc.perform(post("/log-sheet-templates").with(csrf())
                        .param("name", name)
                        .param("operationalUnitId", String.valueOf(f.unitId()))
                        .param("assetSelectionMode", "EXPLICIT")
                        .param("assetIds", String.valueOf(f.assetA()))
                        .param("active", "true")
                        .param("generationMode", "MANUAL")
                        .param("scheduleActive", "false"))
                .andExpect(status().is3xxRedirection());

        assertThat(templateRepository.findByNameIgnoreCase(name)).isEmpty();
    }

    @Test
    @WithAppUser(authorities = "POST:/log-sheet-templates", roles = "SUPERVISOR")
    void supervisorPostingTheTemplateFormIsRejectedEvenHoldingTheEndpointPermission() throws Exception {
        // The endpoint grant was removed from the SUPERVISOR seed, but the permission set is
        // user-editable — the service guard is what actually stops them.
        Fixture f = seed();
        String name = "Supervisor attempt " + System.nanoTime();

        mockMvc.perform(post("/log-sheet-templates").with(csrf())
                        .param("name", name)
                        .param("operationalUnitId", String.valueOf(f.unitId()))
                        .param("assetSelectionMode", "EXPLICIT")
                        .param("assetIds", String.valueOf(f.assetA()))
                        .param("active", "true")
                        .param("generationMode", "MANUAL")
                        .param("scheduleActive", "false"))
                .andExpect(status().is3xxRedirection());

        assertThat(templateRepository.findByNameIgnoreCase(name)).isEmpty();
    }

    // ---- fixture ----

    private record Fixture(Long unitId, Long assetA, Long assetB) {}

    private Fixture seed() {
        long t = System.nanoTime();
        long now = System.currentTimeMillis();

        OperationalUnit unit = new OperationalUnit();
        unit.setCode("OU-EXPWEB-" + t);
        unit.setName("Explicit web unit");
        unit.setCreatedAt(now);
        unit.setUpdatedAt(now);
        unit = operationalUnitRepository.saveAndFlush(unit);

        Location loc = new Location();
        loc.setCode("LOC-EXPWEB-" + t);
        loc.setName("Explicit web location");
        loc.setCreatedAt(now);
        loc.setUpdatedAt(now);
        loc = hierarchyService.saveLocation(loc, List.of(unit.getId()));

        PlantSystem sys = new PlantSystem();
        sys.setCode("SYS-EXPWEB-" + t);
        sys.setName("Explicit web system");
        sys.setLocationId(loc.getId());
        sys.setCreatedAt(now);
        sys.setUpdatedAt(now);
        sys = hierarchyService.savePlantSystem(sys);

        MainFunction mf = new MainFunction();
        mf.setCode("MF-EXPWEB-" + t);
        mf.setName("Explicit web main");
        mf.setCreatedAt(now);
        mf.setUpdatedAt(now);
        hierarchyService.applyMainFunctionParent(mf, AssetHierarchyService.SCOPE_SYSTEM, sys.getId());
        mf = hierarchyService.saveMainFunction(mf);

        AssetClass cls = new AssetClass();
        cls.setName("ExpWebClass-" + t);
        cls.setCreatedAt(now);
        cls.setUpdatedAt(now);
        cls = assetClassRepository.saveAndFlush(cls);

        Long a = saveAsset("AST-EXPWEB-A-" + t, cls.getId(), subFunction(mf.getId(), "SF-EXPWEB-A-" + t));
        Long b = saveAsset("AST-EXPWEB-B-" + t, cls.getId(), subFunction(mf.getId(), "SF-EXPWEB-B-" + t));
        return new Fixture(unit.getId(), a, b);
    }

    private Long subFunction(Long mainFunctionId, String code) {
        long now = System.currentTimeMillis();
        SubFunction sf = new SubFunction();
        sf.setCode(code);
        sf.setName(code);
        sf.setTag("TAG-" + code);
        sf.setCreatedAt(now);
        sf.setUpdatedAt(now);
        hierarchyService.applySubFunctionParent(sf, AssetHierarchyService.SCOPE_MAIN_FUNCTION, mainFunctionId);
        return hierarchyService.saveSubFunction(sf).getId();
    }

    private Long saveAsset(String code, Long classId, Long subFunctionId) {
        long now = System.currentTimeMillis();
        AssetEntry ae = new AssetEntry();
        ae.setAssetCode(code);
        ae.setAssetName(code);
        ae.setClassId(classId);
        ae.setSubFunctionId(subFunctionId);
        ae.setCreatedAt(now);
        ae.setUpdatedAt(now);
        return assetEntryRepository.saveAndFlush(ae).getId();
    }
}
