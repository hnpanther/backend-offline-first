package com.hnp.backendofflinefirst.integration;

import com.hnp.backendofflinefirst.domain.AssetSelectionMode;
import com.hnp.backendofflinefirst.domain.GenerationMode;
import com.hnp.backendofflinefirst.entity.AssetClass;
import com.hnp.backendofflinefirst.entity.AssetEntry;
import com.hnp.backendofflinefirst.entity.Location;
import com.hnp.backendofflinefirst.entity.LogSheet;
import com.hnp.backendofflinefirst.entity.LogSheetEntry;
import com.hnp.backendofflinefirst.entity.LogSheetTemplate;
import com.hnp.backendofflinefirst.entity.LogSheetTemplateAsset;
import com.hnp.backendofflinefirst.entity.MainFunction;
import com.hnp.backendofflinefirst.entity.OperationalUnit;
import com.hnp.backendofflinefirst.entity.PlantSystem;
import com.hnp.backendofflinefirst.entity.SubFunction;
import com.hnp.backendofflinefirst.repository.AssetClassRepository;
import com.hnp.backendofflinefirst.repository.AssetEntryRepository;
import com.hnp.backendofflinefirst.repository.LogSheetEntryRepository;
import com.hnp.backendofflinefirst.repository.LogSheetTemplateAssetRepository;
import com.hnp.backendofflinefirst.repository.LogSheetTemplateRepository;
import com.hnp.backendofflinefirst.repository.OperationalUnitRepository;
import com.hnp.backendofflinefirst.service.AssetHierarchyService;
import com.hnp.backendofflinefirst.service.LogSheetGenerationService;
import com.hnp.backendofflinefirst.service.MasterDataDeleteService;
import com.hnp.backendofflinefirst.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * End-to-end behaviour of EXPLICIT ("frozen list") templates against a real database.
 *
 * <p>The contract under test: a scheduled custom log sheet must reproduce the SAME assets on
 * every run — new assets appearing in the surrounding hierarchy must never join it — and the
 * only way an asset leaves the list is by being deactivated.
 */
@Transactional
class ExplicitTemplateAssetIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired AssetHierarchyService hierarchyService;
    @Autowired LogSheetGenerationService generationService;
    @Autowired MasterDataDeleteService masterDataDeleteService;
    @Autowired AssetEntryRepository assetEntryRepository;
    @Autowired AssetClassRepository assetClassRepository;
    @Autowired LogSheetTemplateRepository templateRepository;
    @Autowired LogSheetTemplateAssetRepository templateAssetRepository;
    @Autowired LogSheetEntryRepository logSheetEntryRepository;
    @Autowired OperationalUnitRepository operationalUnitRepository;

    @Test
    void frozenListIgnoresAssetsAddedToTheSurroundingScopeAfterwards() {
        Fixture f = seed();
        LogSheetTemplate explicit = explicitTemplate(f, List.of(f.pumpA(), f.pumpB()));
        LogSheetTemplate scoped = scopeTemplate(f);

        // A new pump appears in exactly the location + class the scope template covers.
        AssetEntry newcomer = saveAsset("AST-NEW", f.pumpClass().getId(), newSubFunction(f, "SF-NEW"));

        assertThat(generatedAssetIds(explicit))
                .containsExactly(f.pumpA().getId(), f.pumpB().getId());
        // Control: the dynamic mode still behaves as before and DOES pick the newcomer up.
        assertThat(generatedAssetIds(scoped))
                .contains(newcomer.getId());
    }

    @Test
    void deactivatingAnAssetRemovesItFromTheNextGenerationOnly() {
        Fixture f = seed();
        LogSheetTemplate explicit = explicitTemplate(f, List.of(f.pumpA(), f.pumpB()));

        assertThat(generatedAssetIds(explicit)).containsExactly(f.pumpA().getId(), f.pumpB().getId());

        AssetEntry pumpA = assetEntryRepository.findById(f.pumpA().getId()).orElseThrow();
        pumpA.setActive(false);
        assetEntryRepository.save(pumpA);

        assertThat(generatedAssetIds(explicit)).containsExactly(f.pumpB().getId());
        // The membership row survives: reactivating the asset brings it back, no re-edit needed.
        assertThat(templateAssetRepository.findAssetIdsByTemplateId(explicit.getId()))
                .containsExactly(f.pumpA().getId(), f.pumpB().getId());

        pumpA.setActive(true);
        assetEntryRepository.save(pumpA);
        assertThat(generatedAssetIds(explicit)).containsExactly(f.pumpA().getId(), f.pumpB().getId());
    }

    @Test
    void frozenListMaySpanSeveralAssetClasses() {
        Fixture f = seed();
        LogSheetTemplate explicit = explicitTemplate(f, List.of(f.pumpA(), f.valve()));

        LogSheet sheet = generationService.generateFromTemplate(explicit, GenerationMode.MANUAL, null,
                System.currentTimeMillis());

        assertThat(logSheetEntryRepository.findByLogSheetId(sheet.getId()))
                .extracting(LogSheetEntry::getAssetId)
                .containsExactly(f.pumpA().getId(), f.valve().getId());
        // classId on the template stays null — an EXPLICIT template is not class-driven.
        assertThat(explicit.getClassId()).isNull();
        assertThat(sheet.getScopeSummary()).isNull();
    }

    @Test
    void anAssetFrozenIntoATemplateCannotBeHardDeleted() {
        Fixture f = seed();
        explicitTemplate(f, List.of(f.pumpA()));

        assertThatThrownBy(() -> masterDataDeleteService.deleteAssetEntry(f.pumpA().getId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("This asset entry is used by a fixed-list log sheet template and cannot be deleted.");
    }

    // ---- fixture ----

    private List<Long> generatedAssetIds(LogSheetTemplate template) {
        LogSheet sheet = generationService.generateFromTemplate(template, GenerationMode.MANUAL, null,
                System.currentTimeMillis());
        return logSheetEntryRepository.findByLogSheetId(sheet.getId()).stream()
                .map(LogSheetEntry::getAssetId)
                .toList();
    }

    private LogSheetTemplate explicitTemplate(Fixture f, List<AssetEntry> assets) {
        long now = System.currentTimeMillis();
        LogSheetTemplate t = new LogSheetTemplate();
        t.setName("Frozen round " + now);
        t.setOperationalUnitId(f.unit().getId());
        t.setAssetSelectionMode(AssetSelectionMode.EXPLICIT);
        t.setActive(true);
        t.setGenerationMode(GenerationMode.SCHEDULED);
        t.setScheduleActive(false);
        t.setRestrictScopeToUnit(true);
        t.setCompletionWindowMinutes(60);
        t.setCreatedAt(now);
        t.setUpdatedAt(now);
        t = templateRepository.save(t);
        for (AssetEntry a : assets) {
            LogSheetTemplateAsset row = new LogSheetTemplateAsset();
            row.setTemplateId(t.getId());
            row.setAssetId(a.getId());
            templateAssetRepository.save(row);
        }
        return t;
    }

    private LogSheetTemplate scopeTemplate(Fixture f) {
        long now = System.currentTimeMillis();
        LogSheetTemplate t = new LogSheetTemplate();
        t.setName("Dynamic round " + now);
        t.setOperationalUnitId(f.unit().getId());
        t.setAssetSelectionMode(AssetSelectionMode.SCOPE);
        t.setScopeType("location");
        t.setScopeId(f.location().getId());
        t.setClassId(f.pumpClass().getId());
        t.setActive(true);
        t.setGenerationMode(GenerationMode.MANUAL);
        t.setScheduleActive(false);
        t.setRestrictScopeToUnit(true);
        t.setCompletionWindowMinutes(60);
        t.setCreatedAt(now);
        t.setUpdatedAt(now);
        return templateRepository.save(t);
    }

    private Fixture seed() {
        long now = System.currentTimeMillis();

        OperationalUnit unit = new OperationalUnit();
        unit.setCode("OU-EXP");
        unit.setName("Explicit unit");
        unit.setCreatedAt(now);
        unit.setUpdatedAt(now);
        unit = operationalUnitRepository.save(unit);

        Location loc = new Location();
        loc.setCode("LOC-EXP");
        loc.setName("Explicit location");
        loc.setCreatedAt(now);
        loc.setUpdatedAt(now);
        loc = hierarchyService.saveLocation(loc, List.of(unit.getId()));

        PlantSystem system = new PlantSystem();
        system.setCode("SYS-EXP");
        system.setName("Explicit system");
        system.setLocationId(loc.getId());
        system.setCreatedAt(now);
        system.setUpdatedAt(now);
        system = hierarchyService.savePlantSystem(system);

        MainFunction mf = new MainFunction();
        mf.setCode("MF-EXP");
        mf.setName("Explicit main");
        mf.setCreatedAt(now);
        mf.setUpdatedAt(now);
        hierarchyService.applyMainFunctionParent(mf, AssetHierarchyService.SCOPE_SYSTEM, system.getId());
        mf = hierarchyService.saveMainFunction(mf);

        AssetClass pumpClass = new AssetClass();
        pumpClass.setName("ExpPumpClass");
        pumpClass.setCreatedAt(now);
        pumpClass.setUpdatedAt(now);
        pumpClass = assetClassRepository.save(pumpClass);

        AssetClass valveClass = new AssetClass();
        valveClass.setName("ExpValveClass");
        valveClass.setCreatedAt(now);
        valveClass.setUpdatedAt(now);
        valveClass = assetClassRepository.save(valveClass);

        // asset_entries.sub_function_id is UNIQUE — one asset per sub-function.
        AssetEntry pumpA = saveAsset("AST-EXP-A", pumpClass.getId(), subFunctionUnder(mf.getId(), "SF-EXP-A"));
        AssetEntry pumpB = saveAsset("AST-EXP-B", pumpClass.getId(), subFunctionUnder(mf.getId(), "SF-EXP-B"));
        AssetEntry valve = saveAsset("AST-EXP-V", valveClass.getId(), subFunctionUnder(mf.getId(), "SF-EXP-V"));

        return new Fixture(unit, loc, mf, pumpClass, pumpA, pumpB, valve);
    }

    private Long newSubFunction(Fixture f, String code) {
        return subFunctionUnder(f.mainFunction().getId(), code);
    }

    private Long subFunctionUnder(Long mainFunctionId, String code) {
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

    private AssetEntry saveAsset(String code, Long classId, Long subFunctionId) {
        long now = System.currentTimeMillis();
        AssetEntry ae = new AssetEntry();
        ae.setAssetCode(code);
        ae.setAssetName(code);
        ae.setClassId(classId);
        ae.setSubFunctionId(subFunctionId);
        ae.setCreatedAt(now);
        ae.setUpdatedAt(now);
        return assetEntryRepository.save(ae);
    }

    private record Fixture(
            OperationalUnit unit,
            Location location,
            MainFunction mainFunction,
            AssetClass pumpClass,
            AssetEntry pumpA,
            AssetEntry pumpB,
            AssetEntry valve) {}
}
