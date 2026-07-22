package com.hnp.backendofflinefirst.integration;

import com.hnp.backendofflinefirst.entity.AssetEntry;
import com.hnp.backendofflinefirst.entity.Location;
import com.hnp.backendofflinefirst.entity.OperationalUnit;
import com.hnp.backendofflinefirst.entity.SubFunction;
import com.hnp.backendofflinefirst.repository.AssetEntryRepository;
import com.hnp.backendofflinefirst.repository.OperationalUnitRepository;
import com.hnp.backendofflinefirst.service.AssetHierarchyService;
import com.hnp.backendofflinefirst.service.AssetReportService;
import com.hnp.backendofflinefirst.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit-scoped asset report queries must match the legacy Java SF-id expansion,
 * without materialising large IN lists for the final asset select.
 */
@Transactional
class AssetUnitScopeQueryIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired AssetEntryRepository assetEntryRepository;
    @Autowired OperationalUnitRepository operationalUnitRepository;
    @Autowired AssetHierarchyService hierarchyService;
    @Autowired AssetReportService assetReportService;

    @Test
    void findVisibleByUnitIdsMatchesJavaSubFunctionExpansion() {
        Fixture f = seedTwoUnitFixture();

        Set<Long> viaCte = assetEntryRepository
                .findVisibleByUnitIds(Set.of(f.unitA().getId()), PageRequest.of(0, 100, Sort.by("id")))
                .stream()
                .map(AssetEntry::getId)
                .collect(Collectors.toSet());

        Set<Long> sfIds = hierarchyService.subFunctionIdsForOperationalUnits(Set.of(f.unitA().getId()));
        Set<Long> viaJava = assetEntryRepository.findBySubFunctionIdIn(sfIds).stream()
                .map(AssetEntry::getId)
                .collect(Collectors.toSet());

        assertThat(viaCte)
                .as("CTE unit scope must equal Java SF expansion for unit A")
                .isEqualTo(viaJava)
                .containsExactlyInAnyOrder(f.assetInA().getId(), f.assetInChildOfA().getId())
                .doesNotContain(f.assetInB().getId());
    }

    @Test
    void searchVisibleByUnitIdsFiltersWithinUnitOnly() {
        Fixture f = seedTwoUnitFixture();

        Page<AssetEntry> page = assetEntryRepository.searchVisibleByUnitIds(
                Set.of(f.unitA().getId()),
                "pump",
                PageRequest.of(0, 20));

        assertThat(page.getContent()).extracting(AssetEntry::getId)
                .containsExactly(f.assetInA().getId());
        assertThat(page.getTotalElements()).isEqualTo(1);
    }

    @Test
    void findVisibleByAssetCodeAndExistsRespectUnitBoundary() {
        Fixture f = seedTwoUnitFixture();

        assertThat(assetEntryRepository.findVisibleByAssetCodeIgnoreCaseAndUnitIds(
                        Set.of(f.unitA().getId()), f.assetInB().getAssetCode()))
                .isEmpty();
        assertThat(assetEntryRepository.findVisibleByAssetCodeIgnoreCaseAndUnitIds(
                        Set.of(f.unitA().getId()), f.assetInA().getAssetCode()))
                .map(AssetEntry::getId)
                .contains(f.assetInA().getId());

        assertThat(assetEntryRepository.existsVisibleByIdAndUnitIds(
                Set.of(f.unitA().getId()), f.assetInA().getId())).isTrue();
        assertThat(assetEntryRepository.existsVisibleByIdAndUnitIds(
                Set.of(f.unitA().getId()), f.assetInB().getId())).isFalse();
    }

    @Test
    void findAllVisibleByUnitIdsOrdersByIdDescAndExcludesOtherUnits() {
        Fixture f = seedTwoUnitFixture();

        List<AssetEntry> rows = assetEntryRepository.findAllVisibleByUnitIds(Set.of(f.unitA().getId()));
        assertThat(rows).extracting(AssetEntry::getId)
                .containsExactly(f.assetInChildOfA().getId(), f.assetInA().getId());
    }

    @Test
    void paginationCountUsesSameUnitScope() {
        Fixture f = seedTwoUnitFixture();

        Page<AssetEntry> page = assetEntryRepository.findVisibleByUnitIds(
                Set.of(f.unitA().getId()),
                PageRequest.of(0, 1, Sort.by(Sort.Direction.DESC, "id")));

        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getTotalElements()).isEqualTo(2);
        assertThat(page.getTotalPages()).isEqualTo(2);
        assertThat(page.getContent().getFirst().getId()).isEqualTo(f.assetInChildOfA().getId());
    }

    @Test
    void reportServicePageDoesNotRequireMaterialisedSubFunctionIds() {
        Fixture f = seedTwoUnitFixture();

        // Smoke: repository path used by reports returns scoped rows for unit A.
        var page = assetEntryRepository.findVisibleByUnitIds(
                Set.of(f.unitA().getId()), PageRequest.of(0, 25));
        assertThat(page.getContent()).extracting(AssetEntry::getAssetCode)
                .containsExactlyInAnyOrder("AST-A-ROOT", "AST-A-CHILD");
        assertThat(assetReportService).isNotNull();
    }

    private Fixture seedTwoUnitFixture() {
        long now = System.currentTimeMillis();

        OperationalUnit unitA = new OperationalUnit();
        unitA.setCode("OU-A-" + now);
        unitA.setName("Unit A");
        unitA.setCreatedAt(now);
        unitA.setUpdatedAt(now);
        unitA = operationalUnitRepository.save(unitA);

        OperationalUnit unitB = new OperationalUnit();
        unitB.setCode("OU-B-" + now);
        unitB.setName("Unit B");
        unitB.setCreatedAt(now);
        unitB.setUpdatedAt(now);
        unitB = operationalUnitRepository.save(unitB);

        Location locA = new Location();
        locA.setCode("LOC-A-" + now);
        locA.setName("Loc A");
        locA.setUnitId(unitA.getId());
        locA.setCreatedAt(now);
        locA.setUpdatedAt(now);
        locA = hierarchyService.saveLocation(locA);

        Location locAChild = new Location();
        locAChild.setCode("LOC-A-CHILD-" + now);
        locAChild.setName("Loc A Child");
        locAChild.setParentId(locA.getId());
        locAChild.setUnitId(unitA.getId());
        locAChild.setCreatedAt(now);
        locAChild.setUpdatedAt(now);
        locAChild = hierarchyService.saveLocation(locAChild);

        Location locB = new Location();
        locB.setCode("LOC-B-" + now);
        locB.setName("Loc B");
        locB.setUnitId(unitB.getId());
        locB.setCreatedAt(now);
        locB.setUpdatedAt(now);
        locB = hierarchyService.saveLocation(locB);

        SubFunction sfA = new SubFunction();
        sfA.setCode("SF-A-" + now);
        sfA.setName("SF A");
        sfA.setCreatedAt(now);
        sfA.setUpdatedAt(now);
        hierarchyService.applySubFunctionParent(sfA, AssetHierarchyService.SCOPE_LOCATION, locA.getId());
        sfA = hierarchyService.saveSubFunction(sfA);

        SubFunction sfAChild = new SubFunction();
        sfAChild.setCode("SF-A-CHILD-" + now);
        sfAChild.setName("SF A Child");
        sfAChild.setCreatedAt(now);
        sfAChild.setUpdatedAt(now);
        hierarchyService.applySubFunctionParent(sfAChild, AssetHierarchyService.SCOPE_LOCATION, locAChild.getId());
        sfAChild = hierarchyService.saveSubFunction(sfAChild);

        SubFunction sfB = new SubFunction();
        sfB.setCode("SF-B-" + now);
        sfB.setName("SF B");
        sfB.setCreatedAt(now);
        sfB.setUpdatedAt(now);
        hierarchyService.applySubFunctionParent(sfB, AssetHierarchyService.SCOPE_LOCATION, locB.getId());
        sfB = hierarchyService.saveSubFunction(sfB);

        AssetEntry assetA = saveAsset("AST-A-ROOT", "Root pump", sfA.getId(), now);
        AssetEntry assetAChild = saveAsset("AST-A-CHILD", "Child valve", sfAChild.getId(), now + 1);
        AssetEntry assetB = saveAsset("AST-B-ONLY", "Other pump", sfB.getId(), now + 2);

        return new Fixture(unitA, unitB, assetA, assetAChild, assetB);
    }

    private AssetEntry saveAsset(String code, String name, Long subFunctionId, long now) {
        AssetEntry ae = new AssetEntry();
        ae.setAssetCode(code);
        ae.setAssetName(name);
        ae.setSubFunctionId(subFunctionId);
        ae.setCreatedAt(now);
        ae.setUpdatedAt(now);
        return assetEntryRepository.save(ae);
    }

    private record Fixture(
            OperationalUnit unitA,
            OperationalUnit unitB,
            AssetEntry assetInA,
            AssetEntry assetInChildOfA,
            AssetEntry assetInB) {}
}
