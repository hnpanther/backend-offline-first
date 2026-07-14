package com.hnp.backendofflinefirst.integration;

import com.hnp.backendofflinefirst.entity.AssetEntry;
import com.hnp.backendofflinefirst.entity.Location;
import com.hnp.backendofflinefirst.entity.MainFunction;
import com.hnp.backendofflinefirst.entity.PlantSystem;
import com.hnp.backendofflinefirst.entity.SubFunction;
import com.hnp.backendofflinefirst.repository.AssetEntryRepository;
import com.hnp.backendofflinefirst.repository.LocationRepository;
import com.hnp.backendofflinefirst.repository.MainFunctionRepository;
import com.hnp.backendofflinefirst.repository.PlantSystemRepository;
import com.hnp.backendofflinefirst.repository.SubFunctionRepository;
import com.hnp.backendofflinefirst.service.AssetHierarchyService;
import com.hnp.backendofflinefirst.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * End-to-end cascade tests against a real PostgreSQL schema (Flyway V1 + FK constraints).
 */
@Transactional
class AssetHierarchyCascadeIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired AssetHierarchyService hierarchyService;
    @Autowired LocationRepository locationRepository;
    @Autowired PlantSystemRepository plantSystemRepository;
    @Autowired MainFunctionRepository mainFunctionRepository;
    @Autowired SubFunctionRepository subFunctionRepository;
    @Autowired AssetEntryRepository assetEntryRepository;
    @Autowired EntityManager entityManager;

    @Test
    void mainFunctionMovedFromSystemToLocationUpdatesChildSubFunctions() {
        long t0 = System.currentTimeMillis();
        Location locA = saveLocation("LOC-A", "Hall A", t0);
        Location locB = saveLocation("LOC-B", "Hall B", t0);
        PlantSystem system = saveSystem("SYS-1", "Pump system", locA.getId(), t0);

        MainFunction mf = new MainFunction();
        mf.setCode("MF-1");
        mf.setName("Main pump");
        mf.setCreatedAt(t0);
        mf.setUpdatedAt(t0);
        hierarchyService.applyMainFunctionParent(mf, AssetHierarchyService.SCOPE_SYSTEM, system.getId());
        mf = hierarchyService.saveMainFunction(mf);

        SubFunction sf = new SubFunction();
        sf.setCode("SF-1");
        sf.setName("Sub pump");
        sf.setCreatedAt(t0);
        sf.setUpdatedAt(t0);
        hierarchyService.applySubFunctionParent(sf, AssetHierarchyService.SCOPE_MAIN_FUNCTION, mf.getId());
        sf = hierarchyService.saveSubFunction(sf);

        assertThat(reload(sf).getSystemId()).isEqualTo(system.getId());
        assertThat(reload(sf).getLocationId()).isEqualTo(locA.getId());

        hierarchyService.applyMainFunctionParent(mf, AssetHierarchyService.SCOPE_LOCATION, locB.getId());
        hierarchyService.saveMainFunction(mf);

        SubFunction updated = reload(sf);
        assertThat(updated.getMainFunctionId()).isEqualTo(mf.getId());
        assertThat(updated.getSystemId()).isNull();
        assertThat(updated.getLocationId()).isEqualTo(locB.getId());
    }

    @Test
    void plantSystemLocationChangeCascadesToMainFunctionsSubFunctionsAndDirectSubFunctions() {
        long t0 = System.currentTimeMillis();
        Location locOld = saveLocation("LOC-OLD", "Old hall", t0);
        Location locNew = saveLocation("LOC-NEW", "New hall", t0);
        PlantSystem system = saveSystem("SYS-C", "Cooling", locOld.getId(), t0);

        MainFunction mf = new MainFunction();
        mf.setCode("MF-C");
        mf.setName("Cooling main");
        mf.setCreatedAt(t0);
        mf.setUpdatedAt(t0);
        hierarchyService.applyMainFunctionParent(mf, AssetHierarchyService.SCOPE_SYSTEM, system.getId());
        mf = hierarchyService.saveMainFunction(mf);

        SubFunction underMf = new SubFunction();
        underMf.setCode("SF-UNDER-MF");
        underMf.setName("Under MF");
        underMf.setCreatedAt(t0);
        underMf.setUpdatedAt(t0);
        hierarchyService.applySubFunctionParent(underMf, AssetHierarchyService.SCOPE_MAIN_FUNCTION, mf.getId());
        underMf = hierarchyService.saveSubFunction(underMf);

        SubFunction directUnderSystem = new SubFunction();
        directUnderSystem.setCode("SF-DIRECT-SYS");
        directUnderSystem.setName("Direct under system");
        directUnderSystem.setCreatedAt(t0);
        directUnderSystem.setUpdatedAt(t0);
        hierarchyService.applySubFunctionParent(directUnderSystem, AssetHierarchyService.SCOPE_SYSTEM, system.getId());
        directUnderSystem = hierarchyService.saveSubFunction(directUnderSystem);

        system.setLocationId(locNew.getId());
        system.setUpdatedAt(t0 + 1);
        hierarchyService.savePlantSystem(system, locOld.getId());

        MainFunction reloadedMf = reload(mf);
        assertThat(reloadedMf.getLocationId()).isEqualTo(locNew.getId());

        assertThat(reload(underMf).getLocationId()).isEqualTo(locNew.getId());
        assertThat(reload(directUnderSystem).getLocationId()).isEqualTo(locNew.getId());
        assertThat(reload(directUnderSystem).getSystemId()).isEqualTo(system.getId());
    }

    @Test
    void saveSubFunctionTouchesLinkedAssetUpdatedAt() {
        long t0 = System.currentTimeMillis();
        Location loc = saveLocation("LOC-AST", "Asset hall", t0);

        SubFunction sf = new SubFunction();
        sf.setCode("SF-AST");
        sf.setName("Asset scope");
        sf.setCreatedAt(t0);
        sf.setUpdatedAt(t0);
        hierarchyService.applySubFunctionParent(sf, AssetHierarchyService.SCOPE_LOCATION, loc.getId());
        sf = hierarchyService.saveSubFunction(sf);

        AssetEntry asset = new AssetEntry();
        asset.setAssetCode("AST-CASCADE-1");
        asset.setNfcTagId("NFC-CASCADE-1");
        asset.setAssetName("Pump tag");
        asset.setSubFunctionId(sf.getId());
        asset.setCreatedAt(t0);
        asset.setUpdatedAt(t0);
        asset = assetEntryRepository.save(asset);

        long before = reload(asset).getUpdatedAt();

        SubFunction toUpdate = reload(sf);
        toUpdate.setName("Asset scope renamed");
        hierarchyService.applySubFunctionParent(toUpdate, AssetHierarchyService.SCOPE_LOCATION, loc.getId());
        hierarchyService.saveSubFunction(toUpdate);

        assertThat(reload(asset).getUpdatedAt()).isGreaterThan(before);
    }

    @Test
    void plantSystemSaveWithoutLocationChangeDoesNotRewriteDescendants() {
        long t0 = System.currentTimeMillis();
        Location loc = saveLocation("LOC-SAME", "Stable hall", t0);
        PlantSystem system = saveSystem("SYS-SAME", "Stable system", loc.getId(), t0);

        SubFunction direct = new SubFunction();
        direct.setCode("SF-STABLE");
        direct.setName("Stable SF");
        direct.setCreatedAt(t0);
        direct.setUpdatedAt(t0);
        hierarchyService.applySubFunctionParent(direct, AssetHierarchyService.SCOPE_SYSTEM, system.getId());
        direct = hierarchyService.saveSubFunction(direct);
        long sfUpdatedBefore = reload(direct).getUpdatedAt();

        system.setName("Stable system renamed");
        system.setUpdatedAt(t0 + 50);
        hierarchyService.savePlantSystem(system);

        assertThat(reload(direct).getUpdatedAt()).isEqualTo(sfUpdatedBefore);
        assertThat(reload(direct).getLocationId()).isEqualTo(loc.getId());
    }

    @Test
    void mainFunctionReparentTouchesAssetsUnderChildSubFunctions() {
        long t0 = System.currentTimeMillis();
        Location locA = saveLocation("LOC-MA", "Hall MA", t0);
        Location locB = saveLocation("LOC-MB", "Hall MB", t0);
        PlantSystem system = saveSystem("SYS-MA", "System MA", locA.getId(), t0);

        MainFunction mf = new MainFunction();
        mf.setCode("MF-AST2");
        mf.setName("MF for asset cascade");
        mf.setCreatedAt(t0);
        mf.setUpdatedAt(t0);
        hierarchyService.applyMainFunctionParent(mf, AssetHierarchyService.SCOPE_SYSTEM, system.getId());
        mf = hierarchyService.saveMainFunction(mf);

        SubFunction sf = new SubFunction();
        sf.setCode("SF-AST2");
        sf.setName("SF for asset cascade");
        sf.setCreatedAt(t0);
        sf.setUpdatedAt(t0);
        hierarchyService.applySubFunctionParent(sf, AssetHierarchyService.SCOPE_MAIN_FUNCTION, mf.getId());
        sf = hierarchyService.saveSubFunction(sf);

        AssetEntry asset = new AssetEntry();
        asset.setAssetCode("AST-MF-CASCADE");
        asset.setNfcTagId("NFC-MF-CASCADE");
        asset.setAssetName("Tagged pump");
        asset.setSubFunctionId(sf.getId());
        asset.setCreatedAt(t0);
        asset.setUpdatedAt(t0);
        asset = assetEntryRepository.save(asset);
        long assetUpdatedBefore = reload(asset).getUpdatedAt();

        hierarchyService.applyMainFunctionParent(mf, AssetHierarchyService.SCOPE_LOCATION, locB.getId());
        hierarchyService.saveMainFunction(mf);

        assertThat(reload(sf).getLocationId()).isEqualTo(locB.getId());
        assertThat(reload(sf).getSystemId()).isNull();
        assertThat(reload(asset).getUpdatedAt()).isGreaterThan(assetUpdatedBefore);
    }

    @Test
    void cannotDeleteLocationWhilePlantSystemReferencesIt() {
        long t0 = System.currentTimeMillis();
        Location loc = saveLocation("LOC-FK", "FK hall", t0);
        saveSystem("SYS-FK", "FK system", loc.getId(), t0);

        assertThatThrownBy(() -> {
            locationRepository.delete(loc);
            entityManager.flush();
        }).satisfies(ex -> assertThat(ex)
                .isInstanceOfAny(DataIntegrityViolationException.class,
                        org.hibernate.exception.ConstraintViolationException.class));
    }

    @Test
    void cannotDeleteMainFunctionWhileSubFunctionReferencesIt() {
        long t0 = System.currentTimeMillis();
        Location loc = saveLocation("LOC-MF-FK", "MF FK hall", t0);

        MainFunction mf = new MainFunction();
        mf.setCode("MF-FK");
        mf.setName("MF FK");
        mf.setCreatedAt(t0);
        mf.setUpdatedAt(t0);
        hierarchyService.applyMainFunctionParent(mf, AssetHierarchyService.SCOPE_LOCATION, loc.getId());
        MainFunction savedMf = hierarchyService.saveMainFunction(mf);

        SubFunction sf = new SubFunction();
        sf.setCode("SF-FK");
        sf.setName("SF FK");
        sf.setCreatedAt(t0);
        sf.setUpdatedAt(t0);
        hierarchyService.applySubFunctionParent(sf, AssetHierarchyService.SCOPE_MAIN_FUNCTION, savedMf.getId());
        hierarchyService.saveSubFunction(sf);

        assertThatThrownBy(() -> {
            mainFunctionRepository.delete(savedMf);
            entityManager.flush();
        }).satisfies(ex -> assertThat(ex)
                .isInstanceOfAny(DataIntegrityViolationException.class,
                        org.hibernate.exception.ConstraintViolationException.class));
    }

    @Test
    void cannotDeleteSubFunctionWhileAssetReferencesIt() {
        long t0 = System.currentTimeMillis();
        Location loc = saveLocation("LOC-SF-FK", "SF FK hall", t0);

        SubFunction sf = new SubFunction();
        sf.setCode("SF-FK-AST");
        sf.setName("SF FK asset");
        sf.setCreatedAt(t0);
        sf.setUpdatedAt(t0);
        hierarchyService.applySubFunctionParent(sf, AssetHierarchyService.SCOPE_LOCATION, loc.getId());
        SubFunction savedSf = hierarchyService.saveSubFunction(sf);

        AssetEntry asset = new AssetEntry();
        asset.setAssetCode("AST-SF-FK");
        asset.setNfcTagId("NFC-SF-FK");
        asset.setAssetName("Bound asset");
        asset.setSubFunctionId(savedSf.getId());
        asset.setCreatedAt(t0);
        asset.setUpdatedAt(t0);
        assetEntryRepository.save(asset);

        assertThatThrownBy(() -> {
            subFunctionRepository.delete(savedSf);
            entityManager.flush();
        }).satisfies(ex -> assertThat(ex)
                .isInstanceOfAny(DataIntegrityViolationException.class,
                        org.hibernate.exception.ConstraintViolationException.class));
    }

    @Test
    void nestedPlantSystemInheritsLocationAndExpandsScope() {
        long t0 = System.currentTimeMillis();
        Location loc = saveLocation("LOC-NEST", "Main hall", t0);
        PlantSystem electrical = saveSystem("SYS-ELEC", "Electrical", loc.getId(), t0);
        PlantSystem hvac = saveChildSystem("SYS-HVAC", "HVAC", electrical.getId(), t0);

        assertThat(reload(hvac).getLocationId()).isEqualTo(loc.getId());

        MainFunction mf = new MainFunction();
        mf.setCode("MF-HVAC");
        mf.setName("Cooling");
        mf.setCreatedAt(t0);
        mf.setUpdatedAt(t0);
        hierarchyService.applyMainFunctionParent(mf, AssetHierarchyService.SCOPE_SYSTEM, hvac.getId());
        mf = hierarchyService.saveMainFunction(mf);

        SubFunction sf = new SubFunction();
        sf.setCode("SF-HVAC");
        sf.setName("Chiller");
        sf.setCreatedAt(t0);
        sf.setUpdatedAt(t0);
        hierarchyService.applySubFunctionParent(sf, AssetHierarchyService.SCOPE_MAIN_FUNCTION, mf.getId());
        sf = hierarchyService.saveSubFunction(sf);

        Set<Long> scoped = hierarchyService.subFunctionIdsInScope(
                AssetHierarchyService.SCOPE_SYSTEM, electrical.getId());

        assertThat(scoped).containsExactly(sf.getId());
    }

    @Test
    void cannotDeletePlantSystemWhileChildSystemReferencesIt() {
        long t0 = System.currentTimeMillis();
        Location loc = saveLocation("LOC-PS-PARENT", "Hall", t0);
        PlantSystem parent = saveSystem("SYS-PARENT", "Parent", loc.getId(), t0);
        saveChildSystem("SYS-CHILD", "Child", parent.getId(), t0);

        assertThatThrownBy(() -> {
            plantSystemRepository.delete(parent);
            entityManager.flush();
        }).satisfies(ex -> assertThat(ex)
                .isInstanceOfAny(DataIntegrityViolationException.class,
                        org.hibernate.exception.ConstraintViolationException.class));
    }

    private PlantSystem reload(PlantSystem system) {
        return plantSystemRepository.findById(system.getId()).orElseThrow();
    }

    @Test
    void nestedMainFunctionInheritsAncestryAndExpandsScopeWithAssetCascade() {
        long t0 = System.currentTimeMillis();
        Location loc = saveLocation("LOC-MF-NEST", "Plant hall", t0);
        PlantSystem system = saveSystem("SYS-MF-NEST", "Electrical", loc.getId(), t0);

        MainFunction electrical = saveMainFunction("MF-ELEC", "Electrical MF", system.getId(), null, t0);
        MainFunction hvac = saveChildMainFunction("MF-HVAC", "HVAC MF", electrical.getId(), t0);

        assertThat(reload(hvac).getSystemId()).isEqualTo(system.getId());
        assertThat(reload(hvac).getLocationId()).isEqualTo(loc.getId());

        SubFunction sf = new SubFunction();
        sf.setCode("SF-HVAC-NEST");
        sf.setName("Chiller line");
        sf.setCreatedAt(t0);
        sf.setUpdatedAt(t0);
        hierarchyService.applySubFunctionParent(sf, AssetHierarchyService.SCOPE_MAIN_FUNCTION, hvac.getId());
        sf = hierarchyService.saveSubFunction(sf);

        AssetEntry asset = new AssetEntry();
        asset.setAssetCode("AST-MF-NEST");
        asset.setNfcTagId("NFC-MF-NEST");
        asset.setAssetName("Chiller asset");
        asset.setSubFunctionId(sf.getId());
        asset.setCreatedAt(t0);
        asset.setUpdatedAt(t0);
        asset = assetEntryRepository.save(asset);
        long assetUpdatedBefore = reload(asset).getUpdatedAt();

        Location locB = saveLocation("LOC-MF-NEST-B", "Other hall", t0);
        hierarchyService.applyMainFunctionParent(electrical, AssetHierarchyService.SCOPE_LOCATION, locB.getId());
        hierarchyService.saveMainFunction(electrical);

        assertThat(reload(hvac).getLocationId()).isEqualTo(locB.getId());
        assertThat(reload(hvac).getSystemId()).isNull();
        assertThat(reload(sf).getLocationId()).isEqualTo(locB.getId());
        assertThat(reload(sf).getSystemId()).isNull();
        assertThat(reload(asset).getUpdatedAt()).isGreaterThan(assetUpdatedBefore);

        Set<Long> scoped = hierarchyService.subFunctionIdsInScope(
                AssetHierarchyService.SCOPE_MAIN_FUNCTION, electrical.getId());
        assertThat(scoped).containsExactly(sf.getId());
    }

    @Test
    void cannotDeleteMainFunctionWhileChildMainFunctionReferencesIt() {
        long t0 = System.currentTimeMillis();
        Location loc = saveLocation("LOC-MF-PARENT", "Hall", t0);
        MainFunction parent = saveMainFunction("MF-PARENT", "Parent MF", null, loc.getId(), t0);
        saveChildMainFunction("MF-CHILD", "Child MF", parent.getId(), t0);

        assertThatThrownBy(() -> {
            mainFunctionRepository.delete(parent);
            entityManager.flush();
        }).satisfies(ex -> assertThat(ex)
                .isInstanceOfAny(DataIntegrityViolationException.class,
                        org.hibernate.exception.ConstraintViolationException.class));
    }

    private MainFunction saveMainFunction(String code, String name, Long systemId, Long locationId, long now) {
        MainFunction mf = new MainFunction();
        mf.setCode(code);
        mf.setName(name);
        mf.setCreatedAt(now);
        mf.setUpdatedAt(now);
        if (systemId != null) {
            hierarchyService.applyMainFunctionParent(mf, AssetHierarchyService.SCOPE_SYSTEM, systemId);
        } else if (locationId != null) {
            hierarchyService.applyMainFunctionParent(mf, AssetHierarchyService.SCOPE_LOCATION, locationId);
        }
        return hierarchyService.saveMainFunction(mf);
    }

    private MainFunction saveChildMainFunction(String code, String name, Long parentId, long now) {
        MainFunction mf = new MainFunction();
        mf.setCode(code);
        mf.setName(name);
        mf.setCreatedAt(now);
        mf.setUpdatedAt(now);
        hierarchyService.applyMainFunctionParent(mf, AssetHierarchyService.SCOPE_MAIN_FUNCTION, parentId);
        return hierarchyService.saveMainFunction(mf);
    }

    private MainFunction reload(MainFunction mf) {
        return mainFunctionRepository.findById(mf.getId()).orElseThrow();
    }

    private Location saveLocation(String code, String name, long now) {
        Location loc = new Location();
        loc.setCode(code);
        loc.setName(name);
        loc.setCreatedAt(now);
        loc.setUpdatedAt(now);
        return locationRepository.save(loc);
    }

    private PlantSystem saveSystem(String code, String name, Long locationId, long now) {
        PlantSystem system = new PlantSystem();
        system.setCode(code);
        system.setName(name);
        system.setLocationId(locationId);
        system.setCreatedAt(now);
        system.setUpdatedAt(now);
        return hierarchyService.savePlantSystem(system);
    }

    private PlantSystem saveChildSystem(String code, String name, Long parentId, long now) {
        PlantSystem system = new PlantSystem();
        system.setCode(code);
        system.setName(name);
        system.setParentId(parentId);
        system.setCreatedAt(now);
        system.setUpdatedAt(now);
        return hierarchyService.savePlantSystem(system);
    }

    private SubFunction reload(SubFunction sf) {
        return subFunctionRepository.findById(sf.getId()).orElseThrow();
    }

    private AssetEntry reload(AssetEntry asset) {
        return assetEntryRepository.findById(asset.getId()).orElseThrow();
    }
}
