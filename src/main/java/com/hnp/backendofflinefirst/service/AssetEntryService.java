package com.hnp.backendofflinefirst.service;

import com.hnp.backendofflinefirst.dto.AssetLookupResponse;
import com.hnp.backendofflinefirst.entity.AssetClass;
import com.hnp.backendofflinefirst.entity.AssetEntry;
import com.hnp.backendofflinefirst.entity.SubFunction;
import com.hnp.backendofflinefirst.repository.AssetClassRepository;
import com.hnp.backendofflinefirst.repository.AssetEntryRepository;
import com.hnp.backendofflinefirst.repository.SubFunctionRepository;
import com.hnp.backendofflinefirst.util.AssetNfcSupport;
import com.hnp.backendofflinefirst.util.ExcelUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
@RequiredArgsConstructor
public class AssetEntryService {

    private final AssetEntryRepository assetEntryRepository;
    private final AssetClassRepository assetClassRepository;
    private final SubFunctionRepository subFunctionRepository;

    public Optional<AssetLookupResponse> findByNfcTag(String nfcTagId) {
        return assetEntryRepository.findByNfcTagId(nfcTagId)
                .map(entry -> {
                    AssetClass assetClass = assetClassRepository.findById(entry.getClassId()).orElse(null);
                    return new AssetLookupResponse(entry, assetClass);
                });
    }

    @Transactional
    public AssetEntry create(AssetEntry form) {
        normalize(form);
        resolveNfcFromSubFunction(form);
        validateAssetCode(form, null);
        long now = System.currentTimeMillis();
        form.setCreatedAt(now);
        form.setUpdatedAt(now);
        return assetEntryRepository.save(form);
    }

    @Transactional
    public void update(Long id, AssetEntry form) {
        assetEntryRepository.findById(id).ifPresent(existing -> {
            existing.setAssetCode(trimToNull(form.getAssetCode()));
            existing.setAssetName(form.getAssetName());
            existing.setClassId(form.getClassId());
            existing.setSubFunctionId(form.getSubFunctionId());
            existing.setDescription(trimToNull(form.getDescription()));
            existing.setNfcTagId(trimToNull(form.getNfcTagId()));
            normalize(existing);
            resolveNfcFromSubFunction(existing);
            validateAssetCode(existing, id);
            existing.setUpdatedAt(System.currentTimeMillis());
            assetEntryRepository.save(existing);
        });
    }

    /** Used by Excel import after field mapping. */
    public void prepareForImport(AssetEntry entry) {
        normalize(entry);
        resolveNfcFromSubFunction(entry);
    }

    public boolean isAssetCodeAvailable(String assetCode) {
        String code = trimToNull(assetCode);
        return code == null || !assetEntryRepository.existsByAssetCode(code);
    }

    public boolean isNfcAvailable(String nfcTagId) {
        String nfc = trimToNull(nfcTagId);
        return nfc == null || !assetEntryRepository.existsByNfcTagId(nfc);
    }

    private void normalize(AssetEntry entry) {
        entry.setAssetCode(trimToNull(entry.getAssetCode()));
        entry.setNfcTagId(trimToNull(entry.getNfcTagId()));
        entry.setDescription(trimToNull(entry.getDescription()));
    }

    /** If NFC is empty, use SubFunction tag (fallback: sub-function code). */
    void resolveNfcFromSubFunction(AssetEntry entry) {
        if (!ExcelUtils.isEmpty(entry.getNfcTagId()) || entry.getSubFunctionId() == null) {
            return;
        }
        subFunctionRepository.findById(entry.getSubFunctionId()).ifPresent(sf ->
                entry.setNfcTagId(AssetNfcSupport.effectiveNfcTag((String) null, sf)));
    }

    private void validateAssetCode(AssetEntry entry, Long excludeId) {
        if (entry.getAssetCode() == null) {
            throw new IllegalArgumentException("Asset code is required.");
        }
        if (excludeId == null) {
            if (assetEntryRepository.existsByAssetCode(entry.getAssetCode())) {
                throw new IllegalArgumentException("Duplicate asset code: " + entry.getAssetCode());
            }
        } else if (assetEntryRepository.existsByAssetCodeAndIdNot(entry.getAssetCode(), excludeId)) {
            throw new IllegalArgumentException("Duplicate asset code: " + entry.getAssetCode());
        }
        if (entry.getNfcTagId() != null) {
            if (excludeId == null) {
                if (assetEntryRepository.existsByNfcTagId(entry.getNfcTagId())) {
                    throw new IllegalArgumentException("Duplicate NFC tag: " + entry.getNfcTagId());
                }
            } else if (assetEntryRepository.existsByNfcTagIdAndIdNot(entry.getNfcTagId(), excludeId)) {
                throw new IllegalArgumentException("Duplicate NFC tag: " + entry.getNfcTagId());
            }
        }
    }

    private static String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
