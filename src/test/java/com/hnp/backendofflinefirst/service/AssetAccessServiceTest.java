package com.hnp.backendofflinefirst.service;

import com.hnp.backendofflinefirst.entity.AssetEntry;
import com.hnp.backendofflinefirst.repository.AssetEntryRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AssetAccessServiceTest {

    @Mock AssetEntryRepository assetEntryRepository;
    @Mock OperationalUnitScopeService unitScopeService;
    @Mock AssetHierarchyService hierarchyService;

    @InjectMocks AssetAccessService assetAccessService;

    @Test
    void canView_allowsAssetInAccessibleUnitsViaSqlExists() {
        when(unitScopeService.getAccessibleUnitIds(1L)).thenReturn(Set.of(10L));
        when(assetEntryRepository.existsVisibleByIdAndUnitIds(Set.of(10L), 5L)).thenReturn(true);

        AssetEntry asset = new AssetEntry();
        asset.setId(5L);
        asset.setSubFunctionId(100L);

        try (var security = org.mockito.Mockito.mockStatic(com.hnp.backendofflinefirst.security.SecurityUtils.class)) {
            security.when(com.hnp.backendofflinefirst.security.SecurityUtils::isUnitScopedOnly).thenReturn(true);
            security.when(com.hnp.backendofflinefirst.security.SecurityUtils::currentUserId).thenReturn(1L);
            assertThat(assetAccessService.canView(asset)).isTrue();
            verify(hierarchyService, never()).subFunctionIdsForOperationalUnits(any());
        }
    }

    @Test
    void findVisible_rejectsAssetOutsideScopeViaSql() {
        when(unitScopeService.getAccessibleUnitIds(1L)).thenReturn(Set.of(10L));
        when(assetEntryRepository.findVisibleByIdAndUnitIds(Set.of(10L), 5L)).thenReturn(Optional.empty());

        try (var security = org.mockito.Mockito.mockStatic(com.hnp.backendofflinefirst.security.SecurityUtils.class)) {
            security.when(com.hnp.backendofflinefirst.security.SecurityUtils::isUnitScopedOnly).thenReturn(true);
            security.when(com.hnp.backendofflinefirst.security.SecurityUtils::currentUserId).thenReturn(1L);
            assertThat(assetAccessService.findVisible(5L)).isEmpty();
            verify(hierarchyService, never()).subFunctionIdsForOperationalUnits(any());
        }
    }

    @Test
    void findVisibleAssets_forUnitScopedUserUsesUnitIdQueryNotSubFunctionList() {
        when(unitScopeService.getAccessibleUnitIds(1L)).thenReturn(Set.of(10L, 11L));
        AssetEntry ae = new AssetEntry();
        ae.setId(7L);
        when(assetEntryRepository.findVisibleByUnitIds(eq(Set.of(10L, 11L)), any()))
                .thenReturn(new PageImpl<>(List.of(ae)));

        try (var security = org.mockito.Mockito.mockStatic(com.hnp.backendofflinefirst.security.SecurityUtils.class)) {
            security.when(com.hnp.backendofflinefirst.security.SecurityUtils::isUnitScopedOnly).thenReturn(true);
            security.when(com.hnp.backendofflinefirst.security.SecurityUtils::currentUserId).thenReturn(1L);

            Page<AssetEntry> page = assetAccessService.findVisibleAssets(null, PageRequest.of(0, 25));
            assertThat(page.getContent()).containsExactly(ae);
            verify(assetEntryRepository).findVisibleByUnitIds(eq(Set.of(10L, 11L)), any());
            verify(assetEntryRepository, never()).findVisible(any(), any());
            verify(hierarchyService, never()).subFunctionIdsForOperationalUnits(any());
        }
    }

    @Test
    void findVisibleAssets_forAdminUsesUnrestrictedQuery() {
        AssetEntry ae = new AssetEntry();
        ae.setId(1L);
        when(assetEntryRepository.findVisible(isNull(), any()))
                .thenReturn(new PageImpl<>(List.of(ae)));

        try (var security = org.mockito.Mockito.mockStatic(com.hnp.backendofflinefirst.security.SecurityUtils.class)) {
            security.when(com.hnp.backendofflinefirst.security.SecurityUtils::isUnitScopedOnly).thenReturn(false);

            Page<AssetEntry> page = assetAccessService.findVisibleAssets(null, PageRequest.of(0, 10));
            assertThat(page.getContent()).containsExactly(ae);
            verify(assetEntryRepository).findVisible(isNull(), any());
            verify(assetEntryRepository, never()).findVisibleByUnitIds(any(), any());
        }
    }

    @Test
    void findVisibleAssets_emptyUnitScopeReturnsEmptyWithoutQuery() {
        when(unitScopeService.getAccessibleUnitIds(1L)).thenReturn(Set.of());

        try (var security = org.mockito.Mockito.mockStatic(com.hnp.backendofflinefirst.security.SecurityUtils.class)) {
            security.when(com.hnp.backendofflinefirst.security.SecurityUtils::isUnitScopedOnly).thenReturn(true);
            security.when(com.hnp.backendofflinefirst.security.SecurityUtils::currentUserId).thenReturn(1L);

            Page<AssetEntry> page = assetAccessService.findVisibleAssets("pump", PageRequest.of(0, 10));
            assertThat(page).isEmpty();
            verify(assetEntryRepository, never()).searchVisibleByUnitIds(any(), any(), any());
        }
    }

    @Test
    void findVisibleByAssetCode_usesUnitScopedNativeLookup() {
        when(unitScopeService.getAccessibleUnitIds(1L)).thenReturn(Set.of(10L));
        AssetEntry ae = new AssetEntry();
        ae.setId(9L);
        ae.setAssetCode("P-1");
        when(assetEntryRepository.findVisibleByAssetCodeIgnoreCaseAndUnitIds(Set.of(10L), "P-1"))
                .thenReturn(Optional.of(ae));

        try (var security = org.mockito.Mockito.mockStatic(com.hnp.backendofflinefirst.security.SecurityUtils.class)) {
            security.when(com.hnp.backendofflinefirst.security.SecurityUtils::isUnitScopedOnly).thenReturn(true);
            security.when(com.hnp.backendofflinefirst.security.SecurityUtils::currentUserId).thenReturn(1L);

            assertThat(assetAccessService.findVisibleByAssetCode("P-1")).contains(ae);
        }
    }
}
