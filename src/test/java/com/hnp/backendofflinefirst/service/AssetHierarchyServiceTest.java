package com.hnp.backendofflinefirst.service;

import com.hnp.backendofflinefirst.entity.Location;
import com.hnp.backendofflinefirst.entity.MainFunction;
import com.hnp.backendofflinefirst.entity.PlantSystem;
import com.hnp.backendofflinefirst.entity.SubFunction;
import com.hnp.backendofflinefirst.repository.LocationRepository;
import com.hnp.backendofflinefirst.repository.MainFunctionRepository;
import com.hnp.backendofflinefirst.repository.PlantSystemRepository;
import com.hnp.backendofflinefirst.repository.SubFunctionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AssetHierarchyServiceTest {

    @Mock LocationRepository locationRepository;
    @Mock PlantSystemRepository plantSystemRepository;
    @Mock MainFunctionRepository mainFunctionRepository;
    @Mock SubFunctionRepository subFunctionRepository;

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

    // ---- tree scope walk ----

    @Test
    void locationScopeIncludesSubFunctionsUnderNestedSystemsAndFunctions() {
        // loc-root(200) -> loc-child(201); sys(10) under loc-child; mf(1) under sys
        Location root = new Location(); root.setId(200L);
        Location child = new Location(); child.setId(201L); child.setParentId(200L);
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
