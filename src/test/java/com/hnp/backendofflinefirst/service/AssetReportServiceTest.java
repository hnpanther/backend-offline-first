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
    @Mock AssetAccessService assetAccessService;

    @InjectMocks AssetReportService assetReportService;

    @Test
    void buildAssetInventoryResolvesHierarchyCodes() {
        when(assetAccessService.visibleSubFunctionIds()).thenReturn(null);

        Location loc = new Location();
        loc.setId(1L);
        loc.setCode("LOC-1");
        loc.setName("سالن اصلی");

        SubFunction sf = new SubFunction();
        sf.setId(10L);
        sf.setCode("SF-1");
        sf.setLocationId(1L);

        AssetEntry ae = new AssetEntry();
        ae.setId(5L);
        ae.setAssetCode("AST-1");
        ae.setAssetName("پمپ");
        ae.setSubFunctionId(10L);

        when(assetEntryRepository.findAllByOrderByIdDesc()).thenReturn(List.of(ae));
        when(subFunctionRepository.findAllById(Set.of(10L))).thenReturn(List.of(sf));
        when(locationRepository.findAllById(Set.of(1L))).thenReturn(List.of(loc));

        var rows = assetReportService.buildAssetInventory();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getLocationCode()).isEqualTo("LOC-1 - سالن اصلی");
        assertThat(rows.get(0).getSubFunctionCode()).isEqualTo("SF-1");
    }

    @Test
    void buildAssetInventoryForExportUsesPagedVisibleQuery() {
        when(assetAccessService.visibleSubFunctionIds()).thenReturn(null);

        AssetEntry ae = new AssetEntry();
        ae.setId(1L);
        ae.setAssetCode("A-1");
        ae.setSubFunctionId(null);

        when(assetEntryRepository.findVisible(org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(ae)));

        var rows = assetReportService.buildAssetInventoryForExport(10);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getAssetCode()).isEqualTo("A-1");

        org.mockito.ArgumentCaptor<org.springframework.data.domain.Pageable> captor =
                org.mockito.ArgumentCaptor.forClass(org.springframework.data.domain.Pageable.class);
        org.mockito.Mockito.verify(assetEntryRepository).findVisible(
                org.mockito.ArgumentMatchers.isNull(), captor.capture());
        assertThat(captor.getValue().getPageSize()).isEqualTo(11);
    }
}
