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
        applyNfcInheritance(form);
        validateAssetFields(form, null);
        long now = System.currentTimeMillis();
        form.setCreatedAt(now);
        form.setUpdatedAt(now);
        return assetEntryRepository.save(form);
    }

    @Transactional
    public void update(Long id, AssetEntry form) {
        assetEntryRepository.findById(id).ifPresent(existing -> {
            // Validate against a detached candidate first so uniqueness queries do not
            // auto-flush a dirty managed entity that would violate unique indexes.
            AssetEntry candidate = new AssetEntry();
            candidate.setAssetCode(trimToNull(form.getAssetCode()));
            candidate.setAssetName(form.getAssetName());
            candidate.setAssetNameFa(trimToNull(form.getAssetNameFa()));
            candidate.setClassId(form.getClassId());
            candidate.setSubFunctionId(form.getSubFunctionId());
            candidate.setDescription(trimToNull(form.getDescription()));
            candidate.setNfcTagId(trimToNull(form.getNfcTagId()));
            candidate.setNfcSerial(trimToNull(form.getNfcSerial()));
            candidate.setActive(form.isActive());
            normalize(candidate);
            applyNfcInheritance(candidate);
            validateAssetFields(candidate, id);

            existing.setAssetCode(candidate.getAssetCode());
            existing.setAssetName(candidate.getAssetName());
            existing.setAssetNameFa(candidate.getAssetNameFa());
            existing.setClassId(candidate.getClassId());
            existing.setSubFunctionId(candidate.getSubFunctionId());
            existing.setDescription(candidate.getDescription());
            existing.setNfcTagId(candidate.getNfcTagId());
            existing.setNfcSerial(candidate.getNfcSerial());
            existing.setActive(candidate.isActive());
            existing.setUpdatedAt(System.currentTimeMillis());
            assetEntryRepository.save(existing);
        });
    }

    /** Used by Excel import after field mapping. */
    public void prepareForImport(AssetEntry entry) {
        normalize(entry);
        applyNfcInheritance(entry);
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
        // Deliberately normalized but NOT fed into applyNfcInheritance below: the serial
        // identifies the physical chip, so it is never derived from the sub-function and
        // never released when the asset goes inactive.
        entry.setNfcSerial(trimToNull(entry.getNfcSerial()));
        entry.setDescription(trimToNull(entry.getDescription()));
        entry.setAssetNameFa(trimToNull(entry.getAssetNameFa()));
    }

    /**
     * Keeps a sub-function's NFC tag attached to whichever asset is currently <em>active</em> on it.
     *
     * <p>Active asset with no tag of its own → inherit the sub-function's tag (fallback: its code).
     *
     * <p>Inactive asset → <strong>release</strong> an inherited tag by clearing it. Several inactive
     * assets may sit on one sub-function (replaced equipment kept for history), and the successor
     * inherits the very same value, so a retired asset holding onto it would collide on
     * {@code ux_asset_entries_nfc_tag_id_lower} and block the replacement. A tag the asset owns in
     * its own right — anything that is neither the sub-function's tag nor its code — is kept,
     * because that tag is physically on that piece of equipment.
     */
    void applyNfcInheritance(AssetEntry entry) {
        if (entry.getSubFunctionId() == null) {
            return;
        }
        SubFunction sf = subFunctionRepository.findById(entry.getSubFunctionId()).orElse(null);
        if (sf == null) {
            return;
        }
        if (entry.isActive()) {
            if (ExcelUtils.isEmpty(entry.getNfcTagId())) {
                entry.setNfcTagId(AssetNfcSupport.effectiveNfcTag((String) null, sf));
            }
            return;
        }
        if (isInheritedFrom(entry.getNfcTagId(), sf)) {
            entry.setNfcTagId(null);
        }
    }

    /** True when the tag is the sub-function's own tag or code — i.e. not owned by the asset. */
    private static boolean isInheritedFrom(String nfcTagId, SubFunction sf) {
        String tag = trimToNull(nfcTagId);
        if (tag == null) {
            return false;
        }
        return tag.equalsIgnoreCase(trimToNull(sf.getTag()))
                || tag.equalsIgnoreCase(trimToNull(sf.getCode()));
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
        uniquenessValidator.validateAssetSubFunction(excludeId, entry.getSubFunctionId(), entry.isActive());
        uniquenessValidator.validateAssetEntry(excludeId, entry.getAssetCode());
        uniquenessValidator.validateAssetNfcTag(excludeId, entry.getNfcTagId());
        uniquenessValidator.validateAssetNfcSerial(excludeId, entry.getNfcSerial());
    }

    private static String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
