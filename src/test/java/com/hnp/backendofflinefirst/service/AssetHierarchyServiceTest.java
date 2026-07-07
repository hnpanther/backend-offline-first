package com.hnp.backendofflinefirst.service;

import com.hnp.backendofflinefirst.entity.AssetEntry;
import com.hnp.backendofflinefirst.entity.MainFunction;
import com.hnp.backendofflinefirst.entity.PlantSystem;
import com.hnp.backendofflinefirst.entity.SubFunction;
import com.hnp.backendofflinefirst.repository.AssetEntryRepository;
import com.hnp.backendofflinefirst.repository.LocationRepository;
import com.hnp.backendofflinefirst.repository.MainFunctionRepository;
import com.hnp.backendofflinefirst.repository.PlantSystemRepository;
import com.hnp.backendofflinefirst.repository.SubFunctionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
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
        assertThat(target.getLocationId()).isEqualTo(109L);
    }

    @Test
    void saveMainFunctionCascadesAncestryToChildSubFunctions() {
        MainFunction mf = new MainFunction();
        mf.setId(7L);
        mf.setSystemId(null);
        mf.setLocationId(88L);
        when(mainFunctionRepository.save(mf)).thenReturn(mf);

        SubFunction child = sf(301L, 7L, 3L, 5L);
        when(subFunctionRepository.findByMainFunctionId(7L)).thenReturn(List.of(child));
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

        PlantSystem prior = new PlantSystem();
        prior.setId(10L);
        prior.setLocationId(100L);
        when(plantSystemRepository.findById(10L)).thenReturn(Optional.of(prior));
        when(plantSystemRepository.save(system)).thenReturn(system);

        MainFunction mf = new MainFunction();
        mf.setId(1L);
        mf.setSystemId(10L);
        mf.setLocationId(100L);
        when(mainFunctionRepository.findBySystemId(10L)).thenReturn(List.of(mf));
        when(mainFunctionRepository.save(any(MainFunction.class))).thenAnswer(inv -> inv.getArgument(0));
        when(subFunctionRepository.findByMainFunctionId(1L)).thenReturn(List.of(sf(301L, 1L, 10L, 100L)));
        when(subFunctionRepository.findBySystemIdAndMainFunctionIdIsNull(10L)).thenReturn(List.of(sf(302L, null, 10L, 100L)));
        when(subFunctionRepository.save(any(SubFunction.class))).thenAnswer(inv -> inv.getArgument(0));
        when(assetEntryRepository.findBySubFunctionId(any())).thenReturn(List.of());

        service.savePlantSystem(system, 100L);

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

        verify(mainFunctionRepository, never()).findBySystemId(any());
        verify(subFunctionRepository, never()).findBySystemIdAndMainFunctionIdIsNull(any());
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

        service.applyMainFunctionParent(mf, AssetHierarchyService.SCOPE_LOCATION, 88L);

        assertThat(mf.getSystemId()).isNull();
        assertThat(mf.getLocationId()).isEqualTo(88L);
    }

    // ---- tree scope walk ----

    @Test
    void locationScopeIncludesSubFunctionsUnderNestedSystemsAndFunctions() {
        // loc-root(200) -> loc-child(201); sys(10) under loc-child; mf(1) under sys
        com.hnp.backendofflinefirst.entity.Location root = new com.hnp.backendofflinefirst.entity.Location();
        root.setId(200L);
        com.hnp.backendofflinefirst.entity.Location child = new com.hnp.backendofflinefirst.entity.Location();
        child.setId(201L);
        child.setParentId(200L);
        when(locationRepository.findAll()).thenReturn(List.of(root, child));

        PlantSystem sys = new PlantSystem(); sys.setId(10L); sys.setLocationId(201L);
        when(plantSystemRepository.findAll()).thenReturn(List.of(sys));

        MainFunction mf = new MainFunction(); mf.setId(1L); mf.setSystemId(10L); mf.setLocationId(201L);
        when(mainFunctionRepository.findAll()).thenReturn(List.of(mf));

        // one under mainFunction, one directly under the nested system, one under child location, one unrelated
        when(subFunctionRepository.findAll()).thenReturn(List.of(
                sf(301L, 1L, 10L, 201L),
                sf(302L, null, 10L, 201L),
                sf(303L, null, null, 201L),
                sf(304L, null, null, 250L)));

        Set<Long> ids = service.subFunctionIdsInScope(AssetHierarchyService.SCOPE_LOCATION, 200L);

        assertThat(ids).containsExactlyInAnyOrder(301L, 302L, 303L);
    }

    @Test
    void systemScopeIncludesSubFunctionsUnderItsMainFunctions() {
        lenient().when(locationRepository.findAll()).thenReturn(List.of());
        lenient().when(plantSystemRepository.findAll()).thenReturn(List.of());
        MainFunction mf = new MainFunction(); mf.setId(1L); mf.setSystemId(10L);
        when(mainFunctionRepository.findAll()).thenReturn(List.of(mf));
        when(subFunctionRepository.findAll()).thenReturn(List.of(
                sf(301L, 1L, 10L, null),
                sf(302L, null, 10L, null),
                sf(304L, null, 11L, null)));

        Set<Long> ids = service.subFunctionIdsInScope(AssetHierarchyService.SCOPE_SYSTEM, 10L);

        assertThat(ids).containsExactlyInAnyOrder(301L, 302L);
    }
}
