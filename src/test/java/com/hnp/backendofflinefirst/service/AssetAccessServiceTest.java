package com.hnp.backendofflinefirst.service;

import com.hnp.backendofflinefirst.entity.AssetEntry;
import com.hnp.backendofflinefirst.entity.Location;
import com.hnp.backendofflinefirst.entity.SubFunction;
import com.hnp.backendofflinefirst.repository.AssetEntryRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AssetAccessServiceTest {

    @Mock AssetEntryRepository assetEntryRepository;
    @Mock OperationalUnitScopeService unitScopeService;
    @Mock AssetHierarchyService hierarchyService;

    @InjectMocks AssetAccessService assetAccessService;

    @Test
    void canView_allowsAssetInAccessibleSubFunction() {
        when(unitScopeService.getAccessibleUnitIds(1L)).thenReturn(Set.of(10L));
        when(hierarchyService.subFunctionIdsForOperationalUnits(Set.of(10L))).thenReturn(Set.of(100L));

        AssetEntry asset = new AssetEntry();
        asset.setId(5L);
        asset.setSubFunctionId(100L);

        try (var security = org.mockito.Mockito.mockStatic(com.hnp.backendofflinefirst.security.SecurityUtils.class)) {
            security.when(com.hnp.backendofflinefirst.security.SecurityUtils::isUnitScopedOnly).thenReturn(true);
            security.when(com.hnp.backendofflinefirst.security.SecurityUtils::currentUserId).thenReturn(1L);
            assertThat(assetAccessService.canView(asset)).isTrue();
        }
    }

    @Test
    void findVisible_rejectsAssetOutsideScope() {
        when(unitScopeService.getAccessibleUnitIds(1L)).thenReturn(Set.of(10L));
        when(hierarchyService.subFunctionIdsForOperationalUnits(Set.of(10L))).thenReturn(Set.of(100L));

        AssetEntry asset = new AssetEntry();
        asset.setId(5L);
        asset.setSubFunctionId(200L);
        when(assetEntryRepository.findById(5L)).thenReturn(Optional.of(asset));

        try (var security = org.mockito.Mockito.mockStatic(com.hnp.backendofflinefirst.security.SecurityUtils.class)) {
            security.when(com.hnp.backendofflinefirst.security.SecurityUtils::isUnitScopedOnly).thenReturn(true);
            security.when(com.hnp.backendofflinefirst.security.SecurityUtils::currentUserId).thenReturn(1L);
            assertThat(assetAccessService.findVisible(5L)).isEmpty();
        }
    }
}
