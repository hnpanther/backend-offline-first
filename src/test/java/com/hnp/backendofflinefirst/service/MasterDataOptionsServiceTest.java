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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MasterDataOptionsServiceTest {

    @Mock SubFunctionRepository subFunctionRepository;
    @Mock MainFunctionRepository mainFunctionRepository;
    @Mock PlantSystemRepository plantSystemRepository;
    @Mock LocationRepository locationRepository;
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
}
