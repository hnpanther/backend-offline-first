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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MasterDataOptionsServiceTest {

    @Mock SubFunctionRepository subFunctionRepository;
    @Mock MainFunctionRepository mainFunctionRepository;
    @Mock PlantSystemRepository plantSystemRepository;
    @Mock LocationRepository locationRepository;
    @Mock AssetHierarchyService assetHierarchyService;
    @Mock com.hnp.backendofflinefirst.repository.OperationalUnitRepository operationalUnitRepository;
    @Mock com.hnp.backendofflinefirst.repository.AssetEntryRepository assetEntryRepository;
    @InjectMocks MasterDataOptionsService service;

    @Test
    void searchSubFunctionsCapsLimitAndUsesPage() {
        SubFunction sf = new SubFunction();
        sf.setId(1L);
        sf.setCode("SF-1");
        sf.setName("پمپ");
        when(subFunctionRepository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(sf)));

        var options = service.searchSubFunctions(null, 500);
        assertThat(options).hasSize(1);
        assertThat(options.get(0).value()).isEqualTo("1");
        assertThat(options.get(0).label()).contains("SF-1");

        verify(subFunctionRepository).findAll(any(Pageable.class));
    }

    @Test
    void searchSubFunctionsUsesSearchWhenQueryPresent() {
        when(subFunctionRepository.search(eq("پمپ"), any(Pageable.class)))
                .thenReturn(Page.empty());
        service.searchSubFunctions("پمپ", 10);
        verify(subFunctionRepository).search(eq("پمپ"), any(Pageable.class));
    }

    @Test
    void hierarchyParentOptionResolvesTypedRefs() {
        SubFunction sf = new SubFunction();
        sf.setId(9L);
        sf.setCode("SF-9");
        sf.setName("فرعی");
        when(subFunctionRepository.findById(9L)).thenReturn(Optional.of(sf));

        var opt = service.hierarchyParentOption("subFunction:9");
        assertThat(opt).isNotNull();
        assertThat(opt.value()).isEqualTo("subFunction:9");
        assertThat(opt.group()).isEqualTo("تابع فرعی");
    }

    @Test
    void searchHierarchyParentsReturnsGroupedOptions() {
        SubFunction sf = new SubFunction();
        sf.setId(1L);
        sf.setCode("SF");
        sf.setName("فرعی");
        MainFunction mf = new MainFunction();
        mf.setId(2L);
        mf.setCode("MF");
        mf.setName("اصلی");
        PlantSystem ps = new PlantSystem();
        ps.setId(3L);
        ps.setCode("SYS");
        ps.setName("سیستم");
        Location loc = new Location();
        loc.setId(4L);
        loc.setCode("LOC");
        loc.setName("مکان");

        when(subFunctionRepository.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(sf)));
        when(mainFunctionRepository.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(mf)));
        when(plantSystemRepository.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(ps)));
        when(locationRepository.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(loc)));

        var options = service.searchHierarchyParents(null, 30);
        assertThat(options).extracting(o -> o.value())
                .contains("subFunction:1", "mainFunction:2", "system:3", "location:4");
        assertThat(options).extracting(o -> o.group())
                .contains("تابع فرعی", "تابع اصلی", "سیستم واحد", "مکان");
    }

    @Test
    void scopeOptionDelegatesByType() {
        Location loc = new Location();
        loc.setId(7L);
        loc.setCode("L7");
        loc.setName("سالن");
        when(locationRepository.findById(7L)).thenReturn(Optional.of(loc));

        var opt = service.scopeOption("location", 7L);
        assertThat(opt.value()).isEqualTo("7");
        assertThat(opt.label()).contains("L7");
    }

    private static com.hnp.backendofflinefirst.entity.OperationalUnit unit(long id, String name, String code) {
        var u = new com.hnp.backendofflinefirst.entity.OperationalUnit();
        u.setId(id);
        u.setName(name);
        u.setCode(code);
        return u;
    }

    @Test
    void searchOperationalUnitsLabelsWithNameAndCodeAndCapsTheLimit() {
        when(operationalUnitRepository.search(eq("cal"), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(unit(1L, "Calibration", "DEP-1"))));

        var out = service.searchOperationalUnits("cal", 9999);

        assertThat(out).singleElement().satisfies(o -> {
            assertThat(o.value()).isEqualTo("1");
            assertThat(o.label()).isEqualTo("Calibration (DEP-1)");
        });
        verify(operationalUnitRepository).search(eq("cal"), argThat(p -> p.getPageSize() <= 100));
    }

    @Test
    void searchOperationalUnitsWithoutAQueryStillPagesInsteadOfDumping() {
        when(operationalUnitRepository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(unit(1L, "A", "C1"))));

        assertThat(service.searchOperationalUnits("  ", 30)).hasSize(1);
        verify(operationalUnitRepository).findAll(any(Pageable.class));
    }

    @Test
    void searchOperationalUnitsInIdsConfinesTheSearchToTheGivenIds() {
        when(operationalUnitRepository.searchInIds(eq("a"), eq(List.of(7L)), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(unit(7L, "Mine", "DEP-7"))));

        assertThat(service.searchOperationalUnitsInIds("a", List.of(7L), 30))
                .singleElement().satisfies(o -> assertThat(o.value()).isEqualTo("7"));
    }

    @Test
    void searchOperationalUnitsInIdsShortCircuitsOnAnEmptyScope() {
        // A user with no visible units must get nothing without hitting the database at all.
        assertThat(service.searchOperationalUnitsInIds("a", List.of(), 30)).isEmpty();
        assertThat(service.searchOperationalUnitsInIds("a", null, 30)).isEmpty();
        verifyNoInteractions(operationalUnitRepository);
    }

    @Test
    void operationalUnitOptionsByIdsPreservesTheGivenOrderAndDropsMissingIds() {
        when(operationalUnitRepository.findAllById(List.of(2L, 1L, 404L)))
                .thenReturn(List.of(unit(1L, "One", "C1"), unit(2L, "Two", "C2")));

        assertThat(service.operationalUnitOptionsByIds(List.of(2L, 1L, 404L)))
                .extracting(o -> o.value())
                .containsExactly("2", "1");
    }

    @Test
    void operationalUnitOptionsByIdsIsEmptyForNoIds() {
        assertThat(service.operationalUnitOptionsByIds(List.of())).isEmpty();
        assertThat(service.operationalUnitOptionsByIds(null)).isEmpty();
        verifyNoInteractions(operationalUnitRepository);
    }
}
