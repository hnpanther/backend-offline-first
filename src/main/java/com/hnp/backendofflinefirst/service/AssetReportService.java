package com.hnp.backendofflinefirst.service;

import com.hnp.backendofflinefirst.dto.AssetInventoryRow;
import com.hnp.backendofflinefirst.entity.*;
import com.hnp.backendofflinefirst.repository.AssetClassRepository;
import com.hnp.backendofflinefirst.repository.LocationRepository;
import com.hnp.backendofflinefirst.repository.MainFunctionRepository;
import com.hnp.backendofflinefirst.repository.PlantSystemRepository;
import com.hnp.backendofflinefirst.repository.SubFunctionRepository;
import com.hnp.backendofflinefirst.util.ReferenceLabelService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AssetReportService {

    private final AssetClassRepository assetClassRepository;
    private final SubFunctionRepository subFunctionRepository;
    private final MainFunctionRepository mainFunctionRepository;
    private final PlantSystemRepository plantSystemRepository;
    private final LocationRepository locationRepository;
    private final AssetAccessService assetAccessService;

    public List<AssetInventoryRow> buildAssetInventory() {
        List<AssetEntry> assets = assetAccessService.findAllVisibleAssets();
        LookupMaps maps = loadLookupMapsFor(assets);
        return assets.stream().map(ae -> toRow(ae, maps)).toList();
    }

    /**
     * Export-oriented inventory: loads at most {@code maxRows + 1} rows from the DB
     * so callers can detect truncation without materializing the full table.
     */
    public List<AssetInventoryRow> buildAssetInventoryForExport(int maxRows) {
        int limit = Math.max(1, maxRows) + 1;
        Pageable pageable = org.springframework.data.domain.PageRequest.of(
                0, limit, org.springframework.data.domain.Sort.by(
                        org.springframework.data.domain.Sort.Direction.DESC, "id"));
        return buildAssetInventoryPage(null, pageable).getContent();
    }

    public Page<AssetInventoryRow> buildAssetInventoryPage(String q, Pageable pageable) {
        Page<AssetEntry> page = assetAccessService.findVisibleAssets(q, pageable);
        LookupMaps maps = loadLookupMapsFor(page.getContent());
        return page.map(ae -> toRow(ae, maps));
    }

    /** Loads only hierarchy rows referenced by the given assets (not the full master tables). */
    private LookupMaps loadLookupMapsFor(List<AssetEntry> assets) {
        Set<Long> sfIds = assets.stream()
                .map(AssetEntry::getSubFunctionId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, SubFunction> subById = sfIds.isEmpty()
                ? Map.of()
                : subFunctionRepository.findAllById(sfIds).stream()
                        .collect(Collectors.toMap(SubFunction::getId, sf -> sf, (a, b) -> a));

        Set<Long> mainIds = new HashSet<>();
        Set<Long> systemIds = new HashSet<>();
        Set<Long> locationIds = new HashSet<>();
        for (SubFunction sf : subById.values()) {
            if (sf.getMainFunctionId() != null) mainIds.add(sf.getMainFunctionId());
            if (sf.getSystemId() != null) systemIds.add(sf.getSystemId());
            if (sf.getLocationId() != null) locationIds.add(sf.getLocationId());
        }

        Map<Long, String> mainCodes = mainIds.isEmpty()
                ? Map.of()
                : mainFunctionRepository.findAllById(mainIds).stream()
                        .filter(mf -> mf.getCode() != null)
                        .collect(Collectors.toMap(MainFunction::getId, MainFunction::getCode, (a, b) -> a));
        Map<Long, String> systemCodes = systemIds.isEmpty()
                ? Map.of()
                : plantSystemRepository.findAllById(systemIds).stream()
                        .filter(ps -> ps.getCode() != null)
                        .collect(Collectors.toMap(PlantSystem::getId, PlantSystem::getCode, (a, b) -> a));
        Map<Long, String> locationCodes = locationIds.isEmpty()
                ? Map.of()
                : locationRepository.findAllById(locationIds).stream()
                        .collect(Collectors.toMap(
                                Location::getId,
                                l -> ReferenceLabelService.codeAndTitle(l.getCode(), l.getName(), l.getId()),
                                (a, b) -> a));

        Set<Long> classIds = assets.stream()
                .map(AssetEntry::getClassId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, String> classNames = classIds.isEmpty()
                ? Map.of()
                : assetClassRepository.findAllById(classIds).stream()
                        .collect(Collectors.toMap(AssetClass::getId, AssetClass::getName, (a, b) -> a));

        return new LookupMaps(subById, mainCodes, systemCodes, locationCodes, classNames);
    }

    private AssetInventoryRow toRow(AssetEntry ae, LookupMaps maps) {
        SubFunction sf = ae.getSubFunctionId() != null ? maps.subById().get(ae.getSubFunctionId()) : null;
        Long mfId = sf != null ? sf.getMainFunctionId() : null;
        Long sysId = sf != null ? sf.getSystemId() : null;
        Long locId = sf != null ? sf.getLocationId() : null;

        return new AssetInventoryRow(
                ae.getId(),
                ae.getAssetCode(),
                ae.getAssetName(),
                ae.getNfcTagId(),
                locId != null ? maps.locationCodes().get(locId) : null,
                sysId != null ? maps.systemCodes().get(sysId) : null,
                mfId != null ? maps.mainCodes().get(mfId) : null,
                sf != null ? sf.getCode() : null,
                ae.getClassId() != null ? maps.classNames().get(ae.getClassId()) : null
        );
    }

    private record LookupMaps(
            Map<Long, SubFunction> subById,
            Map<Long, String> mainCodes,
            Map<Long, String> systemCodes,
            Map<Long, String> locationCodes,
            Map<Long, String> classNames) {}
}
