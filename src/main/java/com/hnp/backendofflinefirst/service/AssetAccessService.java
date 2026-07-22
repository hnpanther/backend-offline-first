package com.hnp.backendofflinefirst.service;

import com.hnp.backendofflinefirst.entity.AssetEntry;
import com.hnp.backendofflinefirst.repository.AssetEntryRepository;
import com.hnp.backendofflinefirst.security.SecurityUtils;
import com.hnp.backendofflinefirst.ui.WebListSupport;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Enforces operational-unit scope on asset visibility (reports, parameter history).
 * Unit-scoped users are filtered via SQL CTEs keyed by unit ids — never by materialising
 * tens of thousands of sub-function ids in the JVM.
 */
@Service
@RequiredArgsConstructor
public class AssetAccessService {

    private final AssetEntryRepository assetEntryRepository;
    private final OperationalUnitScopeService unitScopeService;
    private final AssetHierarchyService hierarchyService;

    /**
     * Operational units the current user may see.
     * {@code null} = unrestricted (ADMIN / HIGH_USER); empty = none.
     */
    public Set<Long> visibleUnitIds() {
        if (!SecurityUtils.isUnitScopedOnly()) {
            return null;
        }
        Long userId = SecurityUtils.currentUserId();
        if (userId == null) {
            return Set.of();
        }
        return unitScopeService.getAccessibleUnitIds(userId);
    }

    /**
     * Sub-function IDs the current user may see.
     * {@code null} = unrestricted; empty = none.
     * <p>
     * Prefer {@link #visibleUnitIds()} + unit-scoped repository queries for list/search
     * endpoints. This method still materialises the full SF set and is only for small
     * scopes or legacy callers.
     */
    public Set<Long> visibleSubFunctionIds() {
        Set<Long> unitIds = visibleUnitIds();
        if (unitIds == null) {
            return null;
        }
        if (unitIds.isEmpty()) {
            return Set.of();
        }
        return hierarchyService.subFunctionIdsForOperationalUnits(unitIds);
    }

    public boolean canView(AssetEntry asset) {
        if (asset == null || asset.getId() == null) {
            return false;
        }
        Set<Long> unitIds = visibleUnitIds();
        if (unitIds == null) {
            return true;
        }
        if (unitIds.isEmpty()) {
            return false;
        }
        return assetEntryRepository.existsVisibleByIdAndUnitIds(unitIds, asset.getId());
    }

    public Optional<AssetEntry> findVisible(Long assetId) {
        if (assetId == null) {
            return Optional.empty();
        }
        Set<Long> unitIds = visibleUnitIds();
        if (unitIds == null) {
            return assetEntryRepository.findById(assetId);
        }
        if (unitIds.isEmpty()) {
            return Optional.empty();
        }
        return assetEntryRepository.findVisibleByIdAndUnitIds(unitIds, assetId);
    }

    public AssetEntry requireVisible(Long assetId) {
        return findVisible(assetId)
                .orElseThrow(() -> new AccessDeniedException("Access to this asset is not allowed."));
    }

    public Page<AssetEntry> findVisibleAssets(String q, Pageable pageable) {
        Set<Long> unitIds = visibleUnitIds();
        if (unitIds != null && unitIds.isEmpty()) {
            return Page.empty(pageable);
        }
        if (WebListSupport.hasSearch(q)) {
            String term = WebListSupport.searchTerm(q);
            if (unitIds == null) {
                return assetEntryRepository.searchVisible(null, term, pageable);
            }
            return assetEntryRepository.searchVisibleByUnitIds(unitIds, term, pageable);
        }
        if (unitIds == null) {
            return assetEntryRepository.findVisible(null, pageable);
        }
        return assetEntryRepository.findVisibleByUnitIds(unitIds, pageable);
    }

    public Optional<AssetEntry> findVisibleByAssetCode(String assetCode) {
        if (assetCode == null || assetCode.isBlank()) {
            return Optional.empty();
        }
        Set<Long> unitIds = visibleUnitIds();
        if (unitIds != null && unitIds.isEmpty()) {
            return Optional.empty();
        }
        if (unitIds == null) {
            return assetEntryRepository.findVisibleByAssetCodeIgnoreCase(null, assetCode);
        }
        return assetEntryRepository.findVisibleByAssetCodeIgnoreCaseAndUnitIds(unitIds, assetCode);
    }

    public List<AssetEntry> findAllVisibleAssets() {
        Set<Long> unitIds = visibleUnitIds();
        if (unitIds != null && unitIds.isEmpty()) {
            return List.of();
        }
        if (unitIds == null) {
            return assetEntryRepository.findAllByOrderByIdDesc();
        }
        return assetEntryRepository.findAllVisibleByUnitIds(unitIds);
    }
}
