package com.hnp.backendofflinefirst.integration;

import com.hnp.backendofflinefirst.dto.BulkDeleteResult;
import com.hnp.backendofflinefirst.entity.AssetEntry;
import com.hnp.backendofflinefirst.entity.Location;
import com.hnp.backendofflinefirst.entity.SubFunction;
import com.hnp.backendofflinefirst.repository.AssetEntryRepository;
import com.hnp.backendofflinefirst.repository.LocationRepository;
import com.hnp.backendofflinefirst.repository.SubFunctionRepository;
import com.hnp.backendofflinefirst.service.AssetHierarchyService;
import com.hnp.backendofflinefirst.service.MasterDataDeleteService;
import com.hnp.backendofflinefirst.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Transactional
class MasterDataDeleteIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired MasterDataDeleteService deleteService;
    @Autowired AssetHierarchyService hierarchyService;
    @Autowired LocationRepository locationRepository;
    @Autowired SubFunctionRepository subFunctionRepository;
    @Autowired AssetEntryRepository assetEntryRepository;

    @Test
    void bulkDeleteRemovesIndependentLocations() {
        long t0 = System.currentTimeMillis();
        Location a = saveLocation("LOC-DEL-A", "Hall A", t0);
        Location b = saveLocation("LOC-DEL-B", "Hall B", t0);

        BulkDeleteResult result = deleteService.deleteLocations(List.of(a.getId(), b.getId()));

        assertThat(result.getSuccessCount()).isEqualTo(2);
        assertThat(result.getErrorCount()).isZero();
        assertThat(locationRepository.findById(a.getId())).isEmpty();
        assertThat(locationRepository.findById(b.getId())).isEmpty();
    }

    @Test
    void bulkDeleteSkipsParentLocationAndDeletesLeaf() {
        long t0 = System.currentTimeMillis();
        Location parent = saveLocation("LOC-P-DEL", "Parent", t0);
        Location child = saveLocation("LOC-C-DEL", "Child", t0);
        child.setParentId(parent.getId());
        hierarchyService.saveLocation(child);

        BulkDeleteResult result = deleteService.deleteLocations(List.of(parent.getId(), child.getId()));

        assertThat(result.getSuccessCount()).isEqualTo(1);
        assertThat(result.getErrorCount()).isEqualTo(1);
        assertThat(locationRepository.findById(parent.getId())).isPresent();
        assertThat(locationRepository.findById(child.getId())).isEmpty();
        assertThat(result.getErrors().getFirst().message()).contains("زیرمکان");
    }

    @Test
    void bulkDeleteRemovesUnreferencedAssets() {
        long t0 = System.currentTimeMillis();
        Location loc = saveLocation("LOC-AST-DEL", "Asset hall", t0);

        SubFunction sf = new SubFunction();
        sf.setCode("SF-AST-DEL");
        sf.setName("Scope");
        sf.setCreatedAt(t0);
        sf.setUpdatedAt(t0);
        hierarchyService.applySubFunctionParent(sf, AssetHierarchyService.SCOPE_LOCATION, loc.getId());
        sf = hierarchyService.saveSubFunction(sf);

        AssetEntry asset = new AssetEntry();
        asset.setAssetCode("AST-BULK-DEL");
        asset.setNfcTagId("NFC-BULK-DEL");
        asset.setAssetName("Pump");
        asset.setSubFunctionId(sf.getId());
        asset.setCreatedAt(t0);
        asset.setUpdatedAt(t0);
        asset = assetEntryRepository.save(asset);

        BulkDeleteResult result = deleteService.deleteAssetEntries(List.of(asset.getId()));

        assertThat(result.getSuccessCount()).isEqualTo(1);
        assertThat(assetEntryRepository.findById(asset.getId())).isEmpty();
    }

    @Test
    void bulkDeleteSubFunctionBlockedWhileAssetExists() {
        long t0 = System.currentTimeMillis();
        Location loc = saveLocation("LOC-SF-BLOCK", "Block hall", t0);

        SubFunction sf = new SubFunction();
        sf.setCode("SF-BLOCK");
        sf.setName("Blocked scope");
        sf.setCreatedAt(t0);
        sf.setUpdatedAt(t0);
        hierarchyService.applySubFunctionParent(sf, AssetHierarchyService.SCOPE_LOCATION, loc.getId());
        sf = hierarchyService.saveSubFunction(sf);

        AssetEntry asset = new AssetEntry();
        asset.setAssetCode("AST-BLOCK-SF");
        asset.setNfcTagId("NFC-BLOCK-SF");
        asset.setAssetName("Bound pump");
        asset.setSubFunctionId(sf.getId());
        asset.setCreatedAt(t0);
        asset.setUpdatedAt(t0);
        assetEntryRepository.save(asset);

        BulkDeleteResult result = deleteService.deleteSubFunctions(List.of(sf.getId()));

        assertThat(result.getSuccessCount()).isZero();
        assertThat(result.getErrorCount()).isEqualTo(1);
        assertThat(result.getErrors().getFirst().message()).contains("دارایی");
        assertThat(subFunctionRepository.findById(sf.getId())).isPresent();
    }

    private Location saveLocation(String code, String name, long now) {
        Location loc = new Location();
        loc.setCode(code);
        loc.setName(name);
        loc.setCreatedAt(now);
        loc.setUpdatedAt(now);
        return hierarchyService.saveLocation(loc);
    }
}
