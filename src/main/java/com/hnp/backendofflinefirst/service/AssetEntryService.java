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
    private final MasterDataUniquenessValidator uniquenessValidator;

    public Optional<AssetLookupResponse> findByNfcTag(String nfcTagId) {
        if (nfcTagId == null || nfcTagId.isBlank()) {
            return Optional.empty();
        }
        return assetEntryRepository.findByNfcTagIdIgnoreCase(nfcTagId.trim())
                .map(entry -> {
                    AssetClass assetClass = entry.getClassId() == null
                            ? null
                            : assetClassRepository.findById(entry.getClassId()).orElse(null);
                    return new AssetLookupResponse(entry, assetClass);
                });
    }

    @Transactional
    public AssetEntry create(AssetEntry form) {
        normalize(form);
        resolveNfcFromSubFunction(form);
        validateAssetFields(form, null);
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
            existing.setActive(form.isActive());
            normalize(existing);
            resolveNfcFromSubFunction(existing);
            validateAssetFields(existing, id);
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
        return code == null || !assetEntryRepository.existsByAssetCodeIgnoreCase(code);
    }

    public boolean isNfcAvailable(String nfcTagId) {
        String nfc = trimToNull(nfcTagId);
        return nfc == null || !assetEntryRepository.existsByNfcTagIdIgnoreCase(nfc);
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

    private void validateAssetFields(AssetEntry entry, Long excludeId) {
        if (entry.getAssetCode() == null) {
            throw new IllegalArgumentException("Asset code is required.");
        }
        if (entry.getAssetName() == null || entry.getAssetName().isBlank()) {
            throw new IllegalArgumentException("Asset name is required.");
        }
        if (entry.getSubFunctionId() == null) {
            throw new IllegalArgumentException("Sub function is required.");
        }
        if (!subFunctionRepository.existsById(entry.getSubFunctionId())) {
            throw new IllegalArgumentException("Sub function not found.");
        }
        uniquenessValidator.validateAssetEntry(excludeId, entry.getAssetCode());
        uniquenessValidator.validateAssetNfcTag(excludeId, entry.getNfcTagId());
    }

    private static String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
