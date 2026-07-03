package com.hnp.backendofflinefirst.service;

import com.hnp.backendofflinefirst.entity.AssetEntry;
import com.hnp.backendofflinefirst.entity.Location;
import com.hnp.backendofflinefirst.entity.SubFunction;
import com.hnp.backendofflinefirst.repository.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AssetReportServiceTest {

    @Mock AssetEntryRepository assetEntryRepository;
    @Mock AssetClassRepository assetClassRepository;
    @Mock SubFunctionRepository subFunctionRepository;
    @Mock MainFunctionRepository mainFunctionRepository;
    @Mock PlantSystemRepository plantSystemRepository;
    @Mock LocationRepository locationRepository;

    @InjectMocks AssetReportService assetReportService;

    @Test
    void buildAssetInventoryResolvesHierarchyCodes() {
        Location loc = new Location();
        loc.setId(1L);
        loc.setCode("LOC-1");
        when(locationRepository.findAll()).thenReturn(List.of(loc));

        SubFunction sf = new SubFunction();
        sf.setId(10L);
        sf.setCode("SF-1");
        sf.setLocationId(1L);
        when(subFunctionRepository.findAll()).thenReturn(List.of(sf));
        when(mainFunctionRepository.findAll()).thenReturn(List.of());
        when(plantSystemRepository.findAll()).thenReturn(List.of());
        when(assetClassRepository.findAll()).thenReturn(List.of());

        AssetEntry ae = new AssetEntry();
        ae.setId(5L);
        ae.setAssetCode("AST-1");
        ae.setAssetName("پمپ");
        ae.setSubFunctionId(10L);
        when(assetEntryRepository.findAllByOrderByIdDesc()).thenReturn(List.of(ae));

        var rows = assetReportService.buildAssetInventory();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getLocationCode()).isEqualTo("LOC-1");
        assertThat(rows.get(0).getSubFunctionCode()).isEqualTo("SF-1");
    }
}
