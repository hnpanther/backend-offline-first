package com.hnp.backendofflinefirst.service;

import com.hnp.backendofflinefirst.entity.AssetEntry;
import com.hnp.backendofflinefirst.entity.Location;
import com.hnp.backendofflinefirst.entity.MainFunction;
import com.hnp.backendofflinefirst.entity.PlantSystem;
import com.hnp.backendofflinefirst.entity.SubFunction;
import com.hnp.backendofflinefirst.repository.AssetEntryRepository;
import com.hnp.backendofflinefirst.repository.LocationRepository;
import com.hnp.backendofflinefirst.repository.MainFunctionRepository;
import com.hnp.backendofflinefirst.repository.PlantSystemAncestry;
import com.hnp.backendofflinefirst.repository.PlantSystemRepository;
import com.hnp.backendofflinefirst.repository.SubFunctionAncestry;
import com.hnp.backendofflinefirst.repository.SubFunctionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AssetHierarchyServiceTest {

    @Mock LocationRepository locationRepository;
    @Mock PlantSystemRepository plantSystemRepository;
    @Mock MainFunctionRepository mainFunctionRepository;
    @Mock SubFunctionRepository subFunctionRepository;
    @Mock AssetEntryRepository assetEntryRepository;
    @Mock MasterDataUniquenessValidator uniquenessValidator;

    @InjectMocks AssetHierarchyService service;

    private SubFunction sf(Long id, Long mfId, Long sysId, Long locId) {
        SubFunction s = new SubFunction();
        s.setId(id);
        s.setMainFunctionId(mfId);
        s.setSystemId(sysId);
        s.setLocationId(locId);
        return s;
    }

    // ---- denormalization ----

    @Test
    void applySubFunctionParentFillsAncestryFromMainFunction() {
        MainFunction mf = new MainFunction();
        mf.setId(1L);
        mf.setSystemId(10L);
        mf.setLocationId(100L);
        when(mainFunctionRepository.findById(1L)).thenReturn(Optional.of(mf));

        SubFunction target = sf(500L, null, null, null);
        service.applySubFunctionParent(target, AssetHierarchyService.SCOPE_MAIN_FUNCTION, 1L);

        assertThat(target.getMainFunctionId()).isEqualTo(1L);
        assertThat(target.getSystemId()).isEqualTo(10L);
        assertThat(target.getLocationId()).isEqualTo(100L);
    }

    @Test
    void applySubFunctionParentToLocationClearsOtherAxes() {
        SubFunction target = sf(500L, 900L, 901L, 902L);
        service.applySubFunctionParent(target, AssetHierarchyService.SCOPE_LOCATION, 109L);

        assertThat(target.getMainFunctionId()).isNull();
        assertThat(target.getSystemId()).isNull();
        assertThat(target.getParentId()).isNull();
        assertThat(target.getLocationId()).isEqualTo(109L);
    }

    @Test
    void applySubFunctionParentFillsAncestryFromSubFunction() {
        SubFunction parent = sf(20L, 1L, 10L, 100L);
        when(subFunctionRepository.findById(20L)).thenReturn(Optional.of(parent));

        SubFunction target = sf(500L, null, null, null);
        service.applySubFunctionParent(target, AssetHierarchyService.SCOPE_SUB_FUNCTION, 20L);

        assertThat(target.getParentId()).isEqualTo(20L);
        assertThat(target.getMainFunctionId()).isEqualTo(1L);
        assertThat(target.getSystemId()).isEqualTo(10L);
        assertThat(target.getLocationId()).isEqualTo(100L);
    }

    @Test
    void saveMainFunctionCascadesAncestryToChildSubFunctions() {
        MainFunction mf = new MainFunction();
        mf.setId(7L);
        mf.setSystemId(null);
        mf.setLocationId(88L);
        when(mainFunctionRepository.save(mf)).thenReturn(mf);
        when(mainFunctionRepository.findByParentId(7L)).thenReturn(List.of());

        SubFunction child = sf(301L, 7L, 3L, 5L);
        when(subFunctionRepository.findByMainFunctionIdAndParentIdIsNull(7L)).thenReturn(List.of(child));
        when(subFunctionRepository.findByParentId(301L)).thenReturn(List.of());
        when(subFunctionRepository.save(any(SubFunction.class))).thenAnswer(inv -> inv.getArgument(0));
        when(assetEntryRepository.findBySubFunctionId(301L)).thenReturn(List.of());

        service.saveMainFunction(mf);

        ArgumentCaptor<SubFunction> captor = ArgumentCaptor.forClass(SubFunction.class);
        verify(subFunctionRepository).save(captor.capture());
        assertThat(captor.getValue().getMainFunctionId()).isEqualTo(7L);
        assertThat(captor.getValue().getSystemId()).isNull();
        assertThat(captor.getValue().getLocationId()).isEqualTo(88L);
    }

    @Test
    void savePlantSystemCascadesLocationToMainFunctionsAndDirectSubFunctions() {
        PlantSystem system = new PlantSystem();
        system.setId(10L);
        system.setLocationId(200L);

        PlantSystemAncestry persisted = mock(PlantSystemAncestry.class);
        when(persisted.getParentId()).thenReturn(null);
        when(plantSystemRepository.findPersistedAncestryById(10L)).thenReturn(Optional.of(persisted));
        when(plantSystemRepository.save(system)).thenReturn(system);

        MainFunction mf = new MainFunction();
        mf.setId(1L);
        mf.setSystemId(10L);
        mf.setLocationId(100L);
        when(mainFunctionRepository.findBySystemIdAndParentIdIsNull(10L)).thenReturn(List.of(mf));
        when(mainFunctionRepository.save(any(MainFunction.class))).thenAnswer(inv -> inv.getArgument(0));
        when(subFunctionRepository.findByMainFunctionIdAndParentIdIsNull(1L)).thenReturn(List.of(sf(301L, 1L, 10L, 100L)));
        when(subFunctionRepository.findByParentId(301L)).thenReturn(List.of());
        when(subFunctionRepository.findBySystemIdAndMainFunctionIdIsNullAndParentIdIsNull(10L)).thenReturn(List.of(sf(302L, null, 10L, 100L)));
        when(subFunctionRepository.findByParentId(302L)).thenReturn(List.of());
        when(subFunctionRepository.save(any(SubFunction.class))).thenAnswer(inv -> inv.getArgument(0));
        when(assetEntryRepository.findBySubFunctionId(any())).thenReturn(List.of());

        service.savePlantSystem(system, 100L);

        ArgumentCaptor<MainFunction> mfCaptor = ArgumentCaptor.forClass(MainFunction.class);
        verify(mainFunctionRepository).save(mfCaptor.capture());
        assertThat(mfCaptor.getValue().getLocationId()).isEqualTo(200L);
    }

    @Test
    void savePlantSystemCascadesWhenPriorReadFromPersistedAncestry() {
        PlantSystem system = new PlantSystem();
        system.setId(10L);
        system.setLocationId(200L);

        PlantSystemAncestry persisted = mock(PlantSystemAncestry.class);
        when(persisted.getLocationId()).thenReturn(100L);
        when(persisted.getParentId()).thenReturn(null);
        when(plantSystemRepository.findPersistedAncestryById(10L)).thenReturn(Optional.of(persisted));
        when(plantSystemRepository.save(system)).thenReturn(system);

        MainFunction mf = new MainFunction();
        mf.setId(1L);
        mf.setSystemId(10L);
        mf.setLocationId(100L);
        when(mainFunctionRepository.findBySystemIdAndParentIdIsNull(10L)).thenReturn(List.of(mf));
        when(mainFunctionRepository.save(any(MainFunction.class))).thenAnswer(inv -> inv.getArgument(0));
        when(subFunctionRepository.findByMainFunctionIdAndParentIdIsNull(1L)).thenReturn(List.of());
        when(subFunctionRepository.findBySystemIdAndMainFunctionIdIsNullAndParentIdIsNull(10L)).thenReturn(List.of());

        service.savePlantSystem(system);

        ArgumentCaptor<MainFunction> mfCaptor = ArgumentCaptor.forClass(MainFunction.class);
        verify(mainFunctionRepository).save(mfCaptor.capture());
        assertThat(mfCaptor.getValue().getLocationId()).isEqualTo(200L);
    }

    @Test
    void savePlantSystemSkipsCascadeWhenLocationUnchanged() {
        PlantSystem system = new PlantSystem();
        system.setId(10L);
        system.setLocationId(100L);

        PlantSystem prior = new PlantSystem();
        prior.setId(10L);
        prior.setLocationId(100L);
        when(plantSystemRepository.save(system)).thenReturn(system);

        service.savePlantSystem(system, 100L);

        verify(mainFunctionRepository, never()).findBySystemIdAndParentIdIsNull(any());
        verify(subFunctionRepository, never()).findBySystemIdAndMainFunctionIdIsNullAndParentIdIsNull(any());
    }

    @Test
    void saveLocationRejectsParentCycle() {
        Location root = new Location();
        root.setId(1L);
        root.setParentId(2L);

        Location parent = new Location();
        parent.setId(2L);
        parent.setParentId(1L);
        when(locationRepository.findById(2L)).thenReturn(Optional.of(parent));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.saveLocation(root))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cycle");
    }

    @Test
    void saveSubFunctionTouchesLinkedAssets() {
        SubFunction sf = sf(55L, null, null, 9L);
        when(subFunctionRepository.save(sf)).thenReturn(sf);

        AssetEntry asset = new AssetEntry();
        asset.setId(99L);
        asset.setUpdatedAt(1L);
        when(assetEntryRepository.findBySubFunctionId(55L)).thenReturn(List.of(asset));
        when(assetEntryRepository.save(any(AssetEntry.class))).thenAnswer(inv -> inv.getArgument(0));

        service.saveSubFunction(sf);

        ArgumentCaptor<AssetEntry> captor = ArgumentCaptor.forClass(AssetEntry.class);
        verify(assetEntryRepository).save(captor.capture());
        assertThat(captor.getValue().getUpdatedAt()).isGreaterThan(1L);
    }

    @Test
    void applyMainFunctionParentToLocationClearsSystemId() {
        MainFunction mf = new MainFunction();
        mf.setSystemId(10L);
        mf.setLocationId(5L);
        mf.setParentId(99L);

        service.applyMainFunctionParent(mf, AssetHierarchyService.SCOPE_LOCATION, 88L);

        assertThat(mf.getSystemId()).isNull();
        assertThat(mf.getParentId()).isNull();
        assertThat(mf.getLocationId()).isEqualTo(88L);
    }

    @Test
    void applyMainFunctionParentUnderMainFunctionInheritsAncestry() {
        MainFunction parent = new MainFunction();
        parent.setId(5L);
        parent.setSystemId(10L);
        parent.setLocationId(200L);
        when(mainFunctionRepository.findById(5L)).thenReturn(Optional.of(parent));

        MainFunction child = new MainFunction();
        service.applyMainFunctionParent(child, AssetHierarchyService.SCOPE_MAIN_FUNCTION, 5L);

        assertThat(child.getParentId()).isEqualTo(5L);
        assertThat(child.getSystemId()).isEqualTo(10L);
        assertThat(child.getLocationId()).isEqualTo(200L);
    }

    @Test
    void mainFunctionScopeIncludesSubFunctionsUnderChildMainFunctions() {
        when(mainFunctionRepository.findDescendantIdsIncludingRoots(List.of(10L)))
                .thenReturn(List.of(10L, 11L));
        when(subFunctionRepository.findIdsByMainFunctionIdIn(Set.of(10L, 11L)))
                .thenReturn(List.of(301L));

        Set<Long> ids = service.subFunctionIdsInScope(AssetHierarchyService.SCOPE_MAIN_FUNCTION, 10L);

        assertThat(ids).containsExactly(301L);
    }

    @Test
    void saveMainFunctionCascadesAncestryToChildMainFunctionsAndSubFunctions() {
        MainFunction parent = new MainFunction();
        parent.setId(10L);
        parent.setSystemId(5L);
        parent.setLocationId(200L);
        when(mainFunctionRepository.save(parent)).thenReturn(parent);

        MainFunction child = new MainFunction();
        child.setId(11L);
        child.setParentId(10L);
        child.setSystemId(5L);
        child.setLocationId(100L);
        when(mainFunctionRepository.findByParentId(10L)).thenReturn(List.of(child));
        when(mainFunctionRepository.save(child)).thenReturn(child);
        when(mainFunctionRepository.findByParentId(11L)).thenReturn(List.of());

        SubFunction underChild = sf(301L, 11L, 5L, 100L);
        when(subFunctionRepository.findByMainFunctionIdAndParentIdIsNull(10L)).thenReturn(List.of());
        when(subFunctionRepository.findByMainFunctionIdAndParentIdIsNull(11L)).thenReturn(List.of(underChild));
        when(subFunctionRepository.findByParentId(301L)).thenReturn(List.of());
        when(subFunctionRepository.save(any(SubFunction.class))).thenAnswer(inv -> inv.getArgument(0));
        when(assetEntryRepository.findBySubFunctionId(301L)).thenReturn(List.of());

        service.saveMainFunction(parent, 5L, 100L, null);

        ArgumentCaptor<MainFunction> mfCaptor = ArgumentCaptor.forClass(MainFunction.class);
        verify(mainFunctionRepository, org.mockito.Mockito.atLeastOnce()).save(mfCaptor.capture());
        assertThat(mfCaptor.getAllValues()).anyMatch(mf -> mf.getId() != null && mf.getId().equals(11L)
                && Objects.equals(mf.getLocationId(), 200L));
    }

    @Test
    void locationScopeIncludesSubFunctionsUnderNestedSystemsAndFunctions() {
        when(locationRepository.findDescendantIdsIncludingRoots(List.of(200L)))
                .thenReturn(List.of(200L, 201L));
        when(plantSystemRepository.findIdsByLocationIdIn(Set.of(200L, 201L)))
                .thenReturn(List.of(10L));
        when(mainFunctionRepository.findIdsByLocationIdIn(Set.of(200L, 201L)))
                .thenReturn(List.of());
        when(mainFunctionRepository.findIdsBySystemIdIn(Set.of(10L)))
                .thenReturn(List.of(1L));
        when(mainFunctionRepository.findDescendantIdsIncludingRoots(Set.of(1L)))
                .thenReturn(List.of(1L));
        when(subFunctionRepository.findIdsByLocationIdIn(Set.of(200L, 201L)))
                .thenReturn(List.of(303L));
        when(subFunctionRepository.findIdsBySystemIdIn(Set.of(10L)))
                .thenReturn(List.of(302L));
        when(subFunctionRepository.findIdsByMainFunctionIdIn(Set.of(1L)))
                .thenReturn(List.of(301L));

        Set<Long> ids = service.subFunctionIdsInScope(AssetHierarchyService.SCOPE_LOCATION, 200L);

        assertThat(ids).containsExactlyInAnyOrder(301L, 302L, 303L);
    }

    @Test
    void systemScopeIncludesSubFunctionsUnderChildSystems() {
        when(plantSystemRepository.findDescendantIdsIncludingRoots(List.of(10L)))
                .thenReturn(List.of(10L, 11L));
        when(mainFunctionRepository.findIdsBySystemIdIn(Set.of(10L, 11L)))
                .thenReturn(List.of(1L));
        when(mainFunctionRepository.findDescendantIdsIncludingRoots(Set.of(1L)))
                .thenReturn(List.of(1L));
        when(subFunctionRepository.findIdsBySystemIdIn(Set.of(10L, 11L)))
                .thenReturn(List.of());
        when(subFunctionRepository.findIdsByMainFunctionIdIn(Set.of(1L)))
                .thenReturn(List.of(301L));

        Set<Long> ids = service.subFunctionIdsInScope(AssetHierarchyService.SCOPE_SYSTEM, 10L);

        assertThat(ids).containsExactly(301L);
    }

    @Test
    void applyPlantSystemAncestryInheritsLocationFromParent() {
        PlantSystem parent = new PlantSystem();
        parent.setId(10L);
        parent.setLocationId(200L);
        when(plantSystemRepository.findById(10L)).thenReturn(Optional.of(parent));

        PlantSystem child = new PlantSystem();
        child.setParentId(10L);
        service.applyPlantSystemAncestry(child);

        assertThat(child.getLocationId()).isEqualTo(200L);
    }

    @Test
    void descendantSystemIdsIncludesNestedSystems() {
        when(plantSystemRepository.findDescendantIdsIncludingRoots(List.of(10L)))
                .thenReturn(List.of(10L, 11L, 12L));

        assertThat(service.descendantSystemIds(10L)).containsExactlyInAnyOrder(10L, 11L, 12L);
    }

    @Test
    void savePlantSystemCascadesLocationToChildSystems() {
        PlantSystem root = new PlantSystem();
        root.setId(10L);
        root.setLocationId(200L);

        PlantSystemAncestry persisted = mock(PlantSystemAncestry.class);
        when(persisted.getParentId()).thenReturn(null);
        when(plantSystemRepository.findPersistedAncestryById(10L)).thenReturn(Optional.of(persisted));
        when(plantSystemRepository.save(root)).thenReturn(root);

        PlantSystem child = new PlantSystem();
        child.setId(11L);
        child.setParentId(10L);
        child.setLocationId(100L);
        when(plantSystemRepository.findByParentId(10L)).thenReturn(List.of(child));
        when(plantSystemRepository.save(child)).thenReturn(child);

        when(mainFunctionRepository.findBySystemIdAndParentIdIsNull(any())).thenReturn(List.of());
        when(subFunctionRepository.findBySystemIdAndMainFunctionIdIsNullAndParentIdIsNull(any())).thenReturn(List.of());
        when(plantSystemRepository.findByParentId(11L)).thenReturn(List.of());

        service.savePlantSystem(root, 100L);

        ArgumentCaptor<PlantSystem> captor = ArgumentCaptor.forClass(PlantSystem.class);
        verify(plantSystemRepository, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        assertThat(captor.getAllValues()).anyMatch(ps -> ps.getId() != null && ps.getId().equals(11L)
                && ps.getLocationId().equals(200L));
    }

    @Test
    void systemScopeIncludesSubFunctionsUnderItsMainFunctions() {
        when(plantSystemRepository.findDescendantIdsIncludingRoots(List.of(10L)))
                .thenReturn(List.of(10L));
        when(mainFunctionRepository.findIdsBySystemIdIn(Set.of(10L)))
                .thenReturn(List.of(1L));
        when(mainFunctionRepository.findDescendantIdsIncludingRoots(Set.of(1L)))
                .thenReturn(List.of(1L));
        when(subFunctionRepository.findIdsBySystemIdIn(Set.of(10L)))
                .thenReturn(List.of(302L));
        when(subFunctionRepository.findIdsByMainFunctionIdIn(Set.of(1L)))
                .thenReturn(List.of(301L));

        Set<Long> ids = service.subFunctionIdsInScope(AssetHierarchyService.SCOPE_SYSTEM, 10L);

        assertThat(ids).containsExactlyInAnyOrder(301L, 302L);
    }

    @Test
    void subFunctionScopeIncludesNestedDescendants() {
        when(subFunctionRepository.findDescendantIdsIncludingRoots(List.of(10L)))
                .thenReturn(List.of(10L, 11L));

        assertThat(service.descendantSubFunctionIds(10L)).containsExactlyInAnyOrder(10L, 11L);
        assertThat(service.subFunctionIdsInScope(AssetHierarchyService.SCOPE_SUB_FUNCTION, 10L))
                .containsExactlyInAnyOrder(10L, 11L);
    }

    @Test
    void saveSubFunctionCascadesAncestryToChildSubFunctionsAndTouchesAssets() {
        SubFunction parent = new SubFunction();
        parent.setId(10L);
        parent.setMainFunctionId(1L);
        parent.setSystemId(5L);
        parent.setLocationId(200L);
        when(subFunctionRepository.save(parent)).thenReturn(parent);

        SubFunction child = new SubFunction();
        child.setId(11L);
        child.setParentId(10L);
        child.setMainFunctionId(1L);
        child.setSystemId(5L);
        child.setLocationId(100L);
        when(subFunctionRepository.findByParentId(10L)).thenReturn(List.of(child));
        when(subFunctionRepository.save(child)).thenAnswer(inv -> inv.getArgument(0));
        when(subFunctionRepository.findByParentId(11L)).thenReturn(List.of());

        AssetEntry asset = new AssetEntry();
        asset.setId(99L);
        asset.setUpdatedAt(1L);
        when(assetEntryRepository.findBySubFunctionId(11L)).thenReturn(List.of(asset));
        when(assetEntryRepository.save(any(AssetEntry.class))).thenAnswer(inv -> inv.getArgument(0));

        service.saveSubFunction(parent, 1L, 5L, 100L, null);

        ArgumentCaptor<SubFunction> sfCaptor = ArgumentCaptor.forClass(SubFunction.class);
        verify(subFunctionRepository, org.mockito.Mockito.atLeastOnce()).save(sfCaptor.capture());
        assertThat(sfCaptor.getAllValues()).anyMatch(sf -> sf.getId() != null && sf.getId().equals(11L)
                && Objects.equals(sf.getLocationId(), 200L));
        assertThat(asset.getUpdatedAt()).isGreaterThan(1L);
    }

    @Test
    void saveSubFunctionRejectsParentCycle() {
        SubFunction root = new SubFunction();
        root.setId(1L);
        root.setParentId(2L);

        SubFunction parent = new SubFunction();
        parent.setId(2L);
        parent.setParentId(1L);
        when(subFunctionRepository.findById(2L)).thenReturn(Optional.of(parent));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.saveSubFunction(root))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cycle");
    }

    @Test
    void savePlantSystemRejectsParentCycle() {
        PlantSystem root = new PlantSystem();
        root.setId(1L);
        root.setParentId(2L);

        PlantSystem parent = new PlantSystem();
        parent.setId(2L);
        parent.setParentId(1L);
        when(plantSystemRepository.findById(2L)).thenReturn(Optional.of(parent));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.savePlantSystem(root))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cycle");
    }

    @Test
    void saveMainFunctionRejectsParentCycle() {
        MainFunction root = new MainFunction();
        root.setId(1L);
        root.setParentId(2L);

        MainFunction parent = new MainFunction();
        parent.setId(2L);
        parent.setParentId(1L);
        when(mainFunctionRepository.findById(2L)).thenReturn(Optional.of(parent));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.saveMainFunction(root))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cycle");
    }

    @Test
    void saveMainFunctionSkipsCascadeWhenAncestryUnchanged() {
        MainFunction mf = new MainFunction();
        mf.setId(7L);
        mf.setSystemId(10L);
        mf.setLocationId(100L);
        when(mainFunctionRepository.save(mf)).thenReturn(mf);

        service.saveMainFunction(mf, 10L, 100L, null);

        verify(subFunctionRepository, never()).findByMainFunctionIdAndParentIdIsNull(any());
        verify(mainFunctionRepository, never()).findByParentId(any());
    }

    @Test
    void saveSubFunctionSkipsDescendantCascadeWhenAncestryUnchangedButTouchesAssets() {
        SubFunction sf = sf(55L, 1L, 10L, 100L);
        when(subFunctionRepository.save(sf)).thenReturn(sf);

        AssetEntry asset = new AssetEntry();
        asset.setId(99L);
        asset.setUpdatedAt(1L);
        when(assetEntryRepository.findBySubFunctionId(55L)).thenReturn(List.of(asset));
        when(assetEntryRepository.save(any(AssetEntry.class))).thenAnswer(inv -> inv.getArgument(0));

        service.saveSubFunction(sf, 1L, 10L, 100L, null);

        verify(subFunctionRepository, never()).findByParentId(any());
        verify(assetEntryRepository).save(any(AssetEntry.class));
    }

    @Test
    void descendantMainFunctionIdsIncludesNestedMainFunctions() {
        when(mainFunctionRepository.findDescendantIdsIncludingRoots(List.of(10L)))
                .thenReturn(List.of(10L, 11L, 12L));

        assertThat(service.descendantMainFunctionIds(10L)).containsExactlyInAnyOrder(10L, 11L, 12L);
    }

    @Test
    void subFunctionIdsInScopeReturnsEmptyForNullOrUnknown() {
        assertThat(service.subFunctionIdsInScope(null, 1L)).isEmpty();
        assertThat(service.subFunctionIdsInScope(AssetHierarchyService.SCOPE_SYSTEM, null)).isEmpty();
        assertThat(service.subFunctionIdsInScope("unknown", 1L)).isEmpty();
    }

    @Test
    void applyMainFunctionParentFillsAncestryFromSystem() {
        PlantSystem sys = new PlantSystem();
        sys.setId(10L);
        sys.setLocationId(200L);
        when(plantSystemRepository.findById(10L)).thenReturn(Optional.of(sys));

        MainFunction mf = new MainFunction();
        service.applyMainFunctionParent(mf, AssetHierarchyService.SCOPE_SYSTEM, 10L);

        assertThat(mf.getSystemId()).isEqualTo(10L);
        assertThat(mf.getLocationId()).isEqualTo(200L);
        assertThat(mf.getParentId()).isNull();
    }

    @Test
    void applySubFunctionParentFillsAncestryFromSystem() {
        PlantSystem sys = new PlantSystem();
        sys.setId(10L);
        sys.setLocationId(200L);
        when(plantSystemRepository.findById(10L)).thenReturn(Optional.of(sys));

        SubFunction sf = new SubFunction();
        service.applySubFunctionParent(sf, AssetHierarchyService.SCOPE_SYSTEM, 10L);

        assertThat(sf.getSystemId()).isEqualTo(10L);
        assertThat(sf.getLocationId()).isEqualTo(200L);
        assertThat(sf.getParentId()).isNull();
    }

    @Test
    void applySubFunctionAncestryInheritsFromParent() {
        SubFunction parent = sf(20L, 1L, 10L, 100L);
        when(subFunctionRepository.findById(20L)).thenReturn(Optional.of(parent));

        SubFunction child = new SubFunction();
        child.setParentId(20L);
        service.applySubFunctionAncestry(child);

        assertThat(child.getMainFunctionId()).isEqualTo(1L);
        assertThat(child.getSystemId()).isEqualTo(10L);
        assertThat(child.getLocationId()).isEqualTo(100L);
    }
}
