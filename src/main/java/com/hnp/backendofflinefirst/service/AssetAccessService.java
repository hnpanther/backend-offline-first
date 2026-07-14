package com.hnp.backendofflinefirst.service;

import com.hnp.backendofflinefirst.entity.AssetEntry;
import com.hnp.backendofflinefirst.repository.AssetEntryRepository;
import com.hnp.backendofflinefirst.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.Set;

/** Enforces operational-unit scope on asset visibility (reports, parameter history). */
@Service
@RequiredArgsConstructor
public class AssetAccessService {

    private final AssetEntryRepository assetEntryRepository;
    private final OperationalUnitScopeService unitScopeService;
    private final AssetHierarchyService hierarchyService;

    /**
     * Sub-function IDs the current user may see.
     * {@code null} = unrestricted (ADMIN / HIGH_USER); empty = none.
     */
    public Set<Long> visibleSubFunctionIds() {
        if (!SecurityUtils.isUnitScopedOnly()) {
            return null;
        }
        Set<Long> unitIds = unitScopeService.getAccessibleUnitIds(SecurityUtils.currentUserId());
        if (unitIds.isEmpty()) {
            return Set.of();
        }
        return hierarchyService.subFunctionIdsForOperationalUnits(unitIds);
    }

    public boolean canView(AssetEntry asset) {
        if (asset == null) {
            return false;
        }
        Set<Long> allowed = visibleSubFunctionIds();
        if (allowed == null) {
            return true;
        }
        if (allowed.isEmpty()) {
            return false;
        }
        return asset.getSubFunctionId() != null && allowed.contains(asset.getSubFunctionId());
    }

    public Optional<AssetEntry> findVisible(Long assetId) {
        if (assetId == null) {
            return Optional.empty();
        }
        return assetEntryRepository.findById(assetId).filter(this::canView);
    }

    public AssetEntry requireVisible(Long assetId) {
        return findVisible(assetId)
                .orElseThrow(() -> new AccessDeniedException("Access to this asset is not allowed."));
    }
}
