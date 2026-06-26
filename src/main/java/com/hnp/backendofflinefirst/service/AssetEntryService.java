package com.hnp.backendofflinefirst.service;

import com.hnp.backendofflinefirst.dto.AssetLookupResponse;
import com.hnp.backendofflinefirst.entity.AssetClass;
import com.hnp.backendofflinefirst.entity.AssetEntry;
import com.hnp.backendofflinefirst.repository.AssetClassRepository;
import com.hnp.backendofflinefirst.repository.AssetEntryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
@RequiredArgsConstructor
public class AssetEntryService {

    private final AssetEntryRepository assetEntryRepository;
    private final AssetClassRepository assetClassRepository;

    public Optional<AssetLookupResponse> findByNfcTag(String nfcTagId) {
        return assetEntryRepository.findByNfcTagId(nfcTagId)
                .map(entry -> {
                    AssetClass assetClass = assetClassRepository.findById(entry.getClassId()).orElse(null);
                    return new AssetLookupResponse(entry, assetClass);
                });
    }
}
