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

    // ── Reporting scope ───────────────────────────────────────────────────────
    //
    // Reports ask a different question than the registry methods above, and must not
    // reuse their answer.
    //
    //   findVisible*  → "which assets sit in locations this unit owns"
    //   findReportable* → "which assets is this user responsible for"
    //
    // Responsibility arrives through the log sheet, not through location ownership:
    // a sheet is reachable via log_sheets.operational_unit_id alone, and a template
    // with restrict_scope_to_unit = false deliberately puts out-of-unit assets on it.
    // Judging reports by location ownership hid the readings of work the user had
    // just been required to perform — and where location_units is unpopulated it hid
    // every reading from every unit-scoped user, supervisors included.
    //
    // Kept as separate methods rather than widening findVisible* so that master-data
    // listings, Excel exports and the asset registry keep their existing, narrower
    // ownership semantics.

    public Optional<AssetEntry> findReportable(Long assetId) {
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
        return assetEntryRepository.findReportableByIdAndUnitIds(unitIds, assetId);
    }


    public AssetEntry requireReportable(Long assetId) {
        return findReportable(assetId)
                .orElseThrow(() -> new AccessDeniedException("Access to this asset is not allowed."));
    }

    public Page<AssetEntry> findReportableAssets(String q, Pageable pageable) {
        Set<Long> unitIds = visibleUnitIds();
        if (unitIds != null && unitIds.isEmpty()) {
            return Page.empty(pageable);
        }
        if (WebListSupport.hasSearch(q)) {
            String term = WebListSupport.searchTerm(q);
            if (unitIds == null) {
                return assetEntryRepository.searchVisible(null, term, pageable);
            }
            return assetEntryRepository.searchReportableByUnitIds(unitIds, term, pageable);
        }
        if (unitIds == null) {
            return assetEntryRepository.findVisible(null, pageable);
        }
        return assetEntryRepository.findReportableByUnitIds(unitIds, pageable);
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
