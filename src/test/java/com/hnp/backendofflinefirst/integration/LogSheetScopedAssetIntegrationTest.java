package com.hnp.backendofflinefirst.integration;

import com.hnp.backendofflinefirst.domain.GenerationMode;
import com.hnp.backendofflinefirst.dto.LogSheetBundleDto;
import com.hnp.backendofflinefirst.entity.AssetClass;
import com.hnp.backendofflinefirst.entity.AssetEntry;
import com.hnp.backendofflinefirst.entity.Location;
import com.hnp.backendofflinefirst.entity.LogSheet;
import com.hnp.backendofflinefirst.entity.LogSheetEntry;
import com.hnp.backendofflinefirst.entity.LogSheetTemplate;
import com.hnp.backendofflinefirst.entity.MainFunction;
import com.hnp.backendofflinefirst.entity.OperationalUnit;
import com.hnp.backendofflinefirst.entity.PlantSystem;
import com.hnp.backendofflinefirst.entity.SubFunction;
import com.hnp.backendofflinefirst.repository.AssetClassRepository;
import com.hnp.backendofflinefirst.repository.AssetEntryRepository;
import com.hnp.backendofflinefirst.repository.LogSheetEntryRepository;
import com.hnp.backendofflinefirst.repository.LogSheetTemplateRepository;
import com.hnp.backendofflinefirst.repository.OperationalUnitRepository;
import com.hnp.backendofflinefirst.service.AssetHierarchyService;
import com.hnp.backendofflinefirst.service.LogSheetBundleService;
import com.hnp.backendofflinefirst.service.LogSheetGenerationService;
import com.hnp.backendofflinefirst.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end: CTE asset scope = Java scope ∩ class, preview = generation = PWA bundle entries.
 */
@Transactional
class LogSheetScopedAssetIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired AssetHierarchyService hierarchyService;
    @Autowired LogSheetGenerationService generationService;
    @Autowired LogSheetBundleService bundleService;
    @Autowired AssetEntryRepository assetEntryRepository;
    @Autowired AssetClassRepository assetClassRepository;
    @Autowired LogSheetTemplateRepository templateRepository;
    @Autowired LogSheetEntryRepository logSheetEntryRepository;
    @Autowired OperationalUnitRepository operationalUnitRepository;

    @Test
    void findAssetsInScopeMatchesClassFilteredSubFunctionScopeForAllScopeTypes() {
        Fixture f = seedFixture();

        assertScopeMatches("location", f.rootLocation().getId(), f.pumpClass().getId(),
                Set.of(f.pumpUnderChild().getId(), f.pumpUnderNestedMf().getId(), f.pumpUnderNestedSf().getId()));
        assertScopeMatches("system", f.system().getId(), f.pumpClass().getId(),
                Set.of(f.pumpUnderChild().getId(), f.pumpUnderNestedMf().getId(), f.pumpUnderNestedSf().getId()));
        assertScopeMatches("mainFunction", f.mainFunction().getId(), f.pumpClass().getId(),
                Set.of(f.pumpUnderChild().getId(), f.pumpUnderNestedMf().getId(), f.pumpUnderNestedSf().getId()));
        assertScopeMatches("subFunction", f.subFunction().getId(), f.pumpClass().getId(),
                Set.of(f.pumpUnderChild().getId(), f.pumpUnderNestedSf().getId()));

        // Wrong class → empty
        assertThat(hierarchyService.findAssetsInScope("location", f.rootLocation().getId(), f.valveClass().getId()))
                .extracting(AssetEntry::getId)
                .containsExactly(f.valveElsewhere().getId());
        assertThat(hierarchyService.findAssetsInScope("location", f.rootLocation().getId(), f.pumpClass().getId()))
                .extracting(AssetEntry::getId)
                .doesNotContain(f.valveElsewhere().getId(), f.pumpOutside().getId());
    }

    @Test
    void inactiveAssetsAreExcludedFromPreviewAndGeneration() {
        Fixture f = seedFixture();
        AssetEntry inactive = f.pumpUnderChild();
        inactive.setActive(false);
        assetEntryRepository.saveAndFlush(inactive);

        assertThat(hierarchyService.findAssetsInScope("location", f.rootLocation().getId(), f.pumpClass().getId()))
                .extracting(AssetEntry::getId)
                .containsExactlyInAnyOrder(f.pumpUnderNestedMf().getId(), f.pumpUnderNestedSf().getId())
                .doesNotContain(inactive.getId());

        LogSheetTemplate template = saveTemplate("location", f.rootLocation().getId(), f.pumpClass().getId(), f.unit().getId());
        assertThat(generationService.listAssetsInScope(template))
                .extracting(r -> r.getAssetCode())
                .containsExactlyInAnyOrder(
                        f.pumpUnderNestedMf().getAssetCode(),
                        f.pumpUnderNestedSf().getAssetCode())
                .doesNotContain(inactive.getAssetCode());

        LogSheet sheet = generationService.generateFromTemplate(
                template, GenerationMode.MANUAL, null, System.currentTimeMillis());
        assertThat(logSheetEntryRepository.findByLogSheetId(sheet.getId()))
                .extracting(LogSheetEntry::getAssetId)
                .containsExactlyInAnyOrder(f.pumpUnderNestedMf().getId(), f.pumpUnderNestedSf().getId())
                .doesNotContain(inactive.getId());

        inactive.setActive(true);
        assetEntryRepository.saveAndFlush(inactive);
        assertThat(hierarchyService.findAssetsInScope("location", f.rootLocation().getId(), f.pumpClass().getId()))
                .extracting(AssetEntry::getId)
                .contains(inactive.getId());
    }

    @Test
    void previewGenerateAndBundleShareExactAssetSetForLocationTemplate() {
        Fixture f = seedFixture();
        LogSheetTemplate template = saveTemplate("location", f.rootLocation().getId(), f.pumpClass().getId(), f.unit().getId());

        var preview = generationService.listAssetsInScope(template);
        assertThat(preview).extracting(r -> r.getAssetCode())
                .containsExactlyInAnyOrder(
                        f.pumpUnderChild().getAssetCode(),
                        f.pumpUnderNestedMf().getAssetCode(),
                        f.pumpUnderNestedSf().getAssetCode());

        LogSheet sheet = generationService.generateFromTemplate(
                template, GenerationMode.MANUAL, null, System.currentTimeMillis());
        List<LogSheetEntry> entries = logSheetEntryRepository.findByLogSheetId(sheet.getId());
        assertThat(entries).extracting(LogSheetEntry::getAssetId)
                .containsExactlyInAnyOrder(
                        f.pumpUnderChild().getId(),
                        f.pumpUnderNestedMf().getId(),
                        f.pumpUnderNestedSf().getId());
        assertThat(entries).allMatch(e -> e.getClassId().equals(f.pumpClass().getId()));
        assertThat(entries).extracting(LogSheetEntry::getNfcTagId)
                .containsExactlyInAnyOrder("TAG-SF", "TAG-NEST-MF", "TAG-NEST-SF");

        LogSheetBundleDto bundle = bundleService.buildFullBundle(sheet);
        assertThat(bundle.getEntries()).extracting(e -> e.getAssetId())
                .containsExactlyInAnyOrderElementsOf(
                        entries.stream().map(LogSheetEntry::getAssetId).toList());
        assertThat(bundle.getContext().getAssetEntries()).extracting(AssetEntry::getId)
                .containsExactlyInAnyOrderElementsOf(
                        entries.stream().map(LogSheetEntry::getAssetId).toList());
        assertThat(bundle.getContext().getSubFunctions()).extracting(SubFunction::getId)
                .containsExactlyInAnyOrder(
                        f.subFunction().getId(),
                        f.nestedSubFunction().getId(),
                        f.sfUnderNestedMf().getId());
        assertThat(bundle.getContext().getAssetClasses()).extracting(AssetClass::getId)
                .containsExactly(f.pumpClass().getId());
        assertThat(bundle.getContext().getLocations()).extracting(Location::getId)
                .contains(f.rootLocation().getId(), f.childLocation().getId());
    }

    @Test
    void systemAndMainFunctionTemplatesSelectNestedAssetsCorrectly() {
        Fixture f = seedFixture();

        LogSheetTemplate systemTemplate = saveTemplate("system", f.system().getId(), f.pumpClass().getId(), f.unit().getId());
        LogSheet systemSheet = generationService.generateFromTemplate(
                systemTemplate, GenerationMode.MANUAL, null, System.currentTimeMillis());
        assertThat(logSheetEntryRepository.findByLogSheetId(systemSheet.getId()))
                .extracting(LogSheetEntry::getAssetId)
                .containsExactlyInAnyOrder(
                        f.pumpUnderChild().getId(),
                        f.pumpUnderNestedMf().getId(),
                        f.pumpUnderNestedSf().getId());

        LogSheetTemplate mfTemplate = saveTemplate("mainFunction", f.nestedMainFunction().getId(),
                f.pumpClass().getId(), f.unit().getId());
        LogSheet mfSheet = generationService.generateFromTemplate(
                mfTemplate, GenerationMode.MANUAL, null, System.currentTimeMillis());
        assertThat(logSheetEntryRepository.findByLogSheetId(mfSheet.getId()))
                .extracting(LogSheetEntry::getAssetId)
                .containsExactly(f.pumpUnderNestedMf().getId());
    }

    private void assertScopeMatches(String scopeType, Long scopeId, Long classId, Set<Long> expectedAssetIds) {
        List<AssetEntry> viaCte = hierarchyService.findAssetsInScope(scopeType, scopeId, classId);
        Set<Long> sfIds = hierarchyService.subFunctionIdsInScope(scopeType, scopeId);
        Set<Long> viaJava = assetEntryRepository.findByClassIdAndSubFunctionIdIn(classId, sfIds).stream()
                .map(AssetEntry::getId)
                .collect(Collectors.toSet());

        assertThat(viaCte.stream().map(AssetEntry::getId).collect(Collectors.toSet()))
                .as("CTE vs Java for %s/%s class=%s", scopeType, scopeId, classId)
                .isEqualTo(viaJava)
                .isEqualTo(expectedAssetIds);
    }

    private LogSheetTemplate saveTemplate(String scopeType, Long scopeId, Long classId, Long unitId) {
        long now = System.currentTimeMillis();
        LogSheetTemplate t = new LogSheetTemplate();
        t.setName("T-" + scopeType + "-" + scopeId);
        t.setScopeType(scopeType);
        t.setScopeId(scopeId);
        t.setClassId(classId);
        t.setOperationalUnitId(unitId);
        t.setActive(true);
        t.setScheduleActive(false);
        t.setGenerationMode(GenerationMode.MANUAL);
        t.setCreatedAt(now);
        t.setUpdatedAt(now);
        return templateRepository.save(t);
    }

    private Fixture seedFixture() {
        long now = System.currentTimeMillis();

        OperationalUnit unit = new OperationalUnit();
        unit.setCode("OU-SCOPE");
        unit.setName("Unit");
        unit.setCreatedAt(now);
        unit.setUpdatedAt(now);
        unit = operationalUnitRepository.save(unit);

        Location root = new Location();
        root.setCode("LOC-ROOT-S");
        root.setName("Root");
        root.setUnitId(unit.getId());
        root.setCreatedAt(now);
        root.setUpdatedAt(now);
        root = hierarchyService.saveLocation(root);

        Location child = new Location();
        child.setCode("LOC-CHILD-S");
        child.setName("Child");
        child.setParentId(root.getId());
        child.setUnitId(unit.getId());
        child.setCreatedAt(now);
        child.setUpdatedAt(now);
        child = hierarchyService.saveLocation(child);

        Location outside = new Location();
        outside.setCode("LOC-OUT-S");
        outside.setName("Outside");
        outside.setCreatedAt(now);
        outside.setUpdatedAt(now);
        outside = hierarchyService.saveLocation(outside);

        PlantSystem system = new PlantSystem();
        system.setCode("SYS-S");
        system.setName("System");
        system.setLocationId(child.getId());
        system.setCreatedAt(now);
        system.setUpdatedAt(now);
        system = hierarchyService.savePlantSystem(system);

        MainFunction mf = new MainFunction();
        mf.setCode("MF-S");
        mf.setName("Main");
        mf.setCreatedAt(now);
        mf.setUpdatedAt(now);
        hierarchyService.applyMainFunctionParent(mf, AssetHierarchyService.SCOPE_SYSTEM, system.getId());
        mf = hierarchyService.saveMainFunction(mf);

        MainFunction nestedMf = new MainFunction();
        nestedMf.setCode("MF-NEST-S");
        nestedMf.setName("Nested main");
        nestedMf.setCreatedAt(now);
        nestedMf.setUpdatedAt(now);
        hierarchyService.applyMainFunctionParent(nestedMf, AssetHierarchyService.SCOPE_MAIN_FUNCTION, mf.getId());
        nestedMf = hierarchyService.saveMainFunction(nestedMf);

        SubFunction sf = new SubFunction();
        sf.setCode("SF-S");
        sf.setName("Sub");
        sf.setTag("TAG-SF");
        sf.setCreatedAt(now);
        sf.setUpdatedAt(now);
        hierarchyService.applySubFunctionParent(sf, AssetHierarchyService.SCOPE_MAIN_FUNCTION, mf.getId());
        sf = hierarchyService.saveSubFunction(sf);

        SubFunction nestedSf = new SubFunction();
        nestedSf.setCode("SF-NEST-S");
        nestedSf.setName("Nested sub");
        nestedSf.setTag("TAG-NEST-SF");
        nestedSf.setCreatedAt(now);
        nestedSf.setUpdatedAt(now);
        hierarchyService.applySubFunctionParent(nestedSf, AssetHierarchyService.SCOPE_SUB_FUNCTION, sf.getId());
        nestedSf = hierarchyService.saveSubFunction(nestedSf);

        SubFunction sfUnderNestedMf = new SubFunction();
        sfUnderNestedMf.setCode("SF-UNDER-NEST-MF");
        sfUnderNestedMf.setName("Under nested MF");
        sfUnderNestedMf.setTag("TAG-NEST-MF");
        sfUnderNestedMf.setCreatedAt(now);
        sfUnderNestedMf.setUpdatedAt(now);
        hierarchyService.applySubFunctionParent(sfUnderNestedMf, AssetHierarchyService.SCOPE_MAIN_FUNCTION, nestedMf.getId());
        sfUnderNestedMf = hierarchyService.saveSubFunction(sfUnderNestedMf);

        SubFunction outsideSf = new SubFunction();
        outsideSf.setCode("SF-OUT");
        outsideSf.setName("Outside SF");
        outsideSf.setTag("TAG-OUT");
        outsideSf.setCreatedAt(now);
        outsideSf.setUpdatedAt(now);
        hierarchyService.applySubFunctionParent(outsideSf, AssetHierarchyService.SCOPE_LOCATION, outside.getId());
        outsideSf = hierarchyService.saveSubFunction(outsideSf);

        AssetClass pumpClass = new AssetClass();
        pumpClass.setName("PumpClass");
        pumpClass.setCreatedAt(now);
        pumpClass.setUpdatedAt(now);
        pumpClass = assetClassRepository.save(pumpClass);

        AssetClass valveClass = new AssetClass();
        valveClass.setName("ValveClass");
        valveClass.setCreatedAt(now);
        valveClass.setUpdatedAt(now);
        valveClass = assetClassRepository.save(valveClass);

        AssetEntry pumpUnderChild = saveAsset("AST-PUMP-1", "Pump 1", pumpClass.getId(), sf.getId(), now);
        AssetEntry pumpUnderNestedSf = saveAsset("AST-PUMP-2", "Pump 2", pumpClass.getId(), nestedSf.getId(), now);
        AssetEntry pumpUnderNestedMf = saveAsset("AST-PUMP-3", "Pump 3", pumpClass.getId(), sfUnderNestedMf.getId(), now);
        AssetEntry valveElsewhere = saveAsset("AST-VALVE-1", "Valve 1", valveClass.getId(), sf.getId(), now);
        AssetEntry pumpOutside = saveAsset("AST-PUMP-OUT", "Pump out", pumpClass.getId(), outsideSf.getId(), now);

        return new Fixture(unit, root, child, system, mf, nestedMf, sf, nestedSf, sfUnderNestedMf,
                pumpClass, valveClass, pumpUnderChild, pumpUnderNestedSf, pumpUnderNestedMf,
                valveElsewhere, pumpOutside);
    }

    private AssetEntry saveAsset(String code, String name, Long classId, Long sfId, long now) {
        AssetEntry ae = new AssetEntry();
        ae.setAssetCode(code);
        ae.setAssetName(name);
        ae.setClassId(classId);
        ae.setSubFunctionId(sfId);
        ae.setCreatedAt(now);
        ae.setUpdatedAt(now);
        return assetEntryRepository.save(ae);
    }

    private record Fixture(
            OperationalUnit unit,
            Location rootLocation,
            Location childLocation,
            PlantSystem system,
            MainFunction mainFunction,
            MainFunction nestedMainFunction,
            SubFunction subFunction,
            SubFunction nestedSubFunction,
            SubFunction sfUnderNestedMf,
            AssetClass pumpClass,
            AssetClass valveClass,
            AssetEntry pumpUnderChild,
            AssetEntry pumpUnderNestedSf,
            AssetEntry pumpUnderNestedMf,
            AssetEntry valveElsewhere,
            AssetEntry pumpOutside) {}
}
