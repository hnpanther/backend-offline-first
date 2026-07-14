package com.hnp.backendofflinefirst.service;

import com.hnp.backendofflinefirst.dto.AssetInventoryRow;
import com.hnp.backendofflinefirst.entity.*;
import com.hnp.backendofflinefirst.repository.*;
import com.hnp.backendofflinefirst.ui.WebListSupport;
import com.hnp.backendofflinefirst.util.ReferenceLabelService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AssetReportService {

    private final AssetEntryRepository assetEntryRepository;
    private final AssetClassRepository assetClassRepository;
    private final SubFunctionRepository subFunctionRepository;
    private final MainFunctionRepository mainFunctionRepository;
    private final PlantSystemRepository plantSystemRepository;
    private final LocationRepository locationRepository;
    private final AssetAccessService assetAccessService;

    public List<AssetInventoryRow> buildAssetInventory() {
        LookupMaps maps = loadLookupMaps();
        Set<Long> subFunctionIds = assetAccessService.visibleSubFunctionIds();
        return assetEntryRepository.findAllByOrderByIdDesc().stream()
                .filter(ae -> isVisible(ae, subFunctionIds))
                .map(ae -> toRow(ae, maps))
                .toList();
    }

    public Page<AssetInventoryRow> buildAssetInventoryPage(String q, Pageable pageable) {
        Collection<Long> subFunctionIds = assetAccessService.visibleSubFunctionIds();
        if (subFunctionIds != null && subFunctionIds.isEmpty()) {
            return Page.empty(pageable);
        }
        LookupMaps maps = loadLookupMaps();
        Page<AssetEntry> page = WebListSupport.hasSearch(q)
                ? assetEntryRepository.searchVisible(subFunctionIds, WebListSupport.searchTerm(q), pageable)
                : assetEntryRepository.findVisible(subFunctionIds, pageable);
        return page.map(ae -> toRow(ae, maps));
    }

    private static boolean isVisible(AssetEntry asset, Set<Long> subFunctionIds) {
        if (subFunctionIds == null) {
            return true;
        }
        if (subFunctionIds.isEmpty()) {
            return false;
        }
        return asset.getSubFunctionId() != null && subFunctionIds.contains(asset.getSubFunctionId());
    }

    private LookupMaps loadLookupMaps() {
        Map<Long, SubFunction> subById = subFunctionRepository.findAll().stream()
                .collect(Collectors.toMap(SubFunction::getId, sf -> sf, (a, b) -> a));
        Map<Long, String> mainCodes = mainFunctionRepository.findAll().stream()
                .filter(mf -> mf.getCode() != null)
                .collect(Collectors.toMap(MainFunction::getId, MainFunction::getCode, (a, b) -> a));
        Map<Long, String> systemCodes = plantSystemRepository.findAll().stream()
                .filter(ps -> ps.getCode() != null)
                .collect(Collectors.toMap(PlantSystem::getId, PlantSystem::getCode, (a, b) -> a));
        Map<Long, String> locationCodes = locationRepository.findAll().stream()
                .collect(Collectors.toMap(
                        Location::getId,
                        l -> ReferenceLabelService.codeAndTitle(l.getCode(), l.getName(), l.getId()),
                        (a, b) -> a));
        Map<Long, String> classNames = assetClassRepository.findAll().stream()
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
