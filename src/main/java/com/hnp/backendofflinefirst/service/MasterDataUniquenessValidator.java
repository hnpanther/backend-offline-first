package com.hnp.backendofflinefirst.service;

import com.hnp.backendofflinefirst.dto.ImportResult;
import com.hnp.backendofflinefirst.repository.AssetClassRepository;
import com.hnp.backendofflinefirst.repository.AssetEntryRepository;
import com.hnp.backendofflinefirst.repository.FieldDefinitionRepository;
import com.hnp.backendofflinefirst.repository.LocationRepository;
import com.hnp.backendofflinefirst.repository.MainFunctionRepository;
import com.hnp.backendofflinefirst.repository.PlantSystemRepository;
import com.hnp.backendofflinefirst.repository.SubFunctionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Enforces uniqueness of master-data identifiers (codes, tags, asset-class names,
 * field keys within a class) for import and web saves.
 */
@Component
@RequiredArgsConstructor
public class MasterDataUniquenessValidator {

    private final LocationRepository locationRepository;
    private final PlantSystemRepository plantSystemRepository;
    private final MainFunctionRepository mainFunctionRepository;
    private final SubFunctionRepository subFunctionRepository;
    private final AssetEntryRepository assetEntryRepository;
    private final AssetClassRepository assetClassRepository;
    private final FieldDefinitionRepository fieldDefinitionRepository;

    public void validateLocation(Long id, String code) {
        validateCode(id, code, "location", locationRepository::findByCodeIgnoreCase);
    }

    public void validatePlantSystem(Long id, String code, String name) {
        requireNonBlank(name, "plant system name is required.");
        validateCode(id, code, "plant system", plantSystemRepository::findByCodeIgnoreCase);
    }

    public void validateMainFunction(Long id, String code, String name) {
        requireNonBlank(name, "main function name is required.");
        validateCode(id, code, "main function", mainFunctionRepository::findByCodeIgnoreCase);
    }

    public void validateSubFunction(Long id, String code, String tag) {
        validateCode(id, code, "sub function", subFunctionRepository::findByCodeIgnoreCase);
        assertNotTaken(id, requireNonBlank(tag, "sub function tag is required."),
                "sub function", "tag", subFunctionRepository::findByTagIgnoreCase);
    }

    public void validateAssetClass(Long id, String name) {
        assertNotTaken(id, requireNonBlank(name, "asset class name is required."),
                "asset class", "name", assetClassRepository::findByNameIgnoreCase);
    }

    public void validateFieldDefinition(Long id, Long classId, String key) {
        if (classId == null) {
            throw new IllegalArgumentException("field definition class is required.");
        }
        String trimmedKey = requireNonBlank(key, "field key is required.");
        fieldDefinitionRepository.findByClassIdAndKeyIgnoreCase(classId, trimmedKey).ifPresent(existing -> {
            if (!Objects.equals(id, existing.getId())) {
                throw new IllegalArgumentException("Duplicate field key: " + trimmedKey);
            }
        });
    }

    public void validateAssetEntry(Long id, String assetCode) {
        validateCode(id, assetCode, "asset", assetEntryRepository::findFirstByAssetCodeIgnoreCase);
    }

    public void validateAssetNfcTag(Long id, String nfcTagId) {
        if (nfcTagId == null || nfcTagId.isBlank()) {
            return;
        }
        String trimmed = nfcTagId.trim();
        assetEntryRepository.findByNfcTagIdIgnoreCase(trimmed).ifPresent(existing -> {
            if (!Objects.equals(id, existing.getId())) {
                throw new IllegalArgumentException("Duplicate NFC tag: " + trimmed);
            }
        });
    }

    /** Each sub-function may be linked to at most one asset entry. */
    public void validateAssetSubFunction(Long assetId, Long subFunctionId) {
        if (subFunctionId == null) {
            return;
        }
        assetEntryRepository.findFirstBySubFunctionId(subFunctionId).ifPresent(existing -> {
            if (!Objects.equals(assetId, existing.getId())) {
                throw new IllegalArgumentException(
                        "This sub function is already assigned to another asset.");
            }
        });
    }

    public boolean validateLocationForImport(String code, int rowNum,
                                           ImportResult result, FileUniqueness fileUniqueness) {
        return validateCodeForImport(code, rowNum, result, fileUniqueness,
                locationRepository::findByCodeIgnoreCase, "location");
    }

    public boolean validatePlantSystemForImport(String code, int rowNum,
                                                ImportResult result, FileUniqueness fileUniqueness) {
        return validateCodeForImport(code, rowNum, result, fileUniqueness,
                plantSystemRepository::findByCodeIgnoreCase, "plant system");
    }

    public boolean validateMainFunctionForImport(String code, int rowNum,
                                                 ImportResult result, FileUniqueness fileUniqueness) {
        return validateCodeForImport(code, rowNum, result, fileUniqueness,
                mainFunctionRepository::findByCodeIgnoreCase, "main function");
    }

    public boolean validateSubFunctionForImport(String code, String tag, int rowNum,
                                                ImportResult result, FileUniqueness fileUniqueness) {
        if (tag == null || tag.isBlank()) {
            result.addError(rowNum, "Tag is required.");
            return false;
        }
        if (!validateCodeForImport(code, rowNum, result, fileUniqueness,
                subFunctionRepository::findByCodeIgnoreCase, "sub function")) {
            return false;
        }
        return validateTagForImport(tag, rowNum, result, fileUniqueness);
    }

    public boolean validateAssetEntryForImport(String assetCode, int rowNum,
                                               ImportResult result, FileUniqueness fileUniqueness) {
        return validateCodeForImport(assetCode, rowNum, result, fileUniqueness,
                assetEntryRepository::findFirstByAssetCodeIgnoreCase, "asset");
    }

    public boolean validateAssetNfcForImport(String nfcTagId, int rowNum,
                                             ImportResult result, FileUniqueness fileUniqueness) {
        if (nfcTagId == null || nfcTagId.isBlank()) {
            return true;
        }
        if (!fileUniqueness.registerNfc(nfcTagId, rowNum, result)) {
            return false;
        }
        if (assetEntryRepository.findByNfcTagIdIgnoreCase(nfcTagId.trim()).isPresent()) {
            result.addError(rowNum, "Duplicate NFC tag: " + nfcTagId);
            return false;
        }
        return true;
    }

    public boolean validateAssetSubFunctionForImport(Long subFunctionId, String subFunctionCode, int rowNum,
                                                     ImportResult result, FileUniqueness fileUniqueness) {
        if (subFunctionId == null) {
            result.addError(rowNum, "Sub function is required.");
            return false;
        }
        if (!fileUniqueness.registerSubFunctionId(subFunctionId, subFunctionCode, rowNum, result)) {
            return false;
        }
        if (assetEntryRepository.existsBySubFunctionId(subFunctionId)) {
            result.addError(rowNum, "This sub function is already assigned to another asset: " + subFunctionCode);
            return false;
        }
        return true;
    }

    private void validateCode(Long id, String code, String entityLabel,
                            Function<String, Optional<?>> findByCode) {
        assertNotTaken(id, requireNonBlank(code, entityLabel + " code is required."),
                entityLabel, "code", findByCode);
    }

    private boolean validateCodeForImport(String code, int rowNum, ImportResult result,
                                          FileUniqueness fileUniqueness,
                                          Function<String, Optional<?>> findByCode,
                                          String entityLabel) {
        if (!fileUniqueness.registerCode(code, rowNum, result)) {
            return false;
        }
        if (findByCode.apply(trim(code)).isPresent()) {
            result.addError(rowNum, "Duplicate " + entityLabel + " code: " + code);
            return false;
        }
        return true;
    }

    private boolean validateTagForImport(String tag, int rowNum, ImportResult result,
                                         FileUniqueness fileUniqueness) {
        if (!fileUniqueness.registerTag(tag, rowNum, result)) {
            return false;
        }
        if (subFunctionRepository.findByTagIgnoreCase(tag.trim()).isPresent()) {
            result.addError(rowNum, "Duplicate sub function tag: " + tag);
            return false;
        }
        return true;
    }

    private void assertNotTaken(Long id, String value, String entityLabel, String field,
                                Function<String, Optional<?>> lookup) {
        lookup.apply(value).ifPresent(existing -> {
            Long existingId = extractId(existing);
            if (!Objects.equals(id, existingId)) {
                throw new IllegalArgumentException(
                        "Duplicate " + entityLabel + " " + field + ": " + value);
            }
        });
    }

    private static Long extractId(Object entity) {
        if (entity instanceof com.hnp.backendofflinefirst.entity.Location loc) {
            return loc.getId();
        }
        if (entity instanceof com.hnp.backendofflinefirst.entity.PlantSystem ps) {
            return ps.getId();
        }
        if (entity instanceof com.hnp.backendofflinefirst.entity.MainFunction mf) {
            return mf.getId();
        }
        if (entity instanceof com.hnp.backendofflinefirst.entity.SubFunction sf) {
            return sf.getId();
        }
        if (entity instanceof com.hnp.backendofflinefirst.entity.AssetEntry ae) {
            return ae.getId();
        }
        if (entity instanceof com.hnp.backendofflinefirst.entity.AssetClass ac) {
            return ac.getId();
        }
        if (entity instanceof com.hnp.backendofflinefirst.entity.FieldDefinition fd) {
            return fd.getId();
        }
        throw new IllegalStateException("Unsupported entity type: " + entity.getClass().getName());
    }

    private static String requireNonBlank(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }

    /** Tracks codes/tags/NFC/sub-functions already seen in the current Excel file. */
    public static final class FileUniqueness {
        private final Set<String> codes = new HashSet<>();
        private final Set<String> tags = new HashSet<>();
        private final Set<String> nfcs = new HashSet<>();
        private final Set<Long> subFunctionIds = new HashSet<>();

        public boolean registerCode(String code, int rowNum, ImportResult result) {
            if (codes.add(code.trim().toLowerCase(Locale.ROOT))) {
                return true;
            }
            result.addError(rowNum, "Duplicate code in file: " + code);
            return false;
        }

        public boolean registerTag(String tag, int rowNum, ImportResult result) {
            if (tags.add(tag.trim().toLowerCase(Locale.ROOT))) {
                return true;
            }
            result.addError(rowNum, "Duplicate tag in file: " + tag);
            return false;
        }

        public boolean registerNfc(String nfcTagId, int rowNum, ImportResult result) {
            if (nfcs.add(nfcTagId.trim().toLowerCase(Locale.ROOT))) {
                return true;
            }
            result.addError(rowNum, "Duplicate NFC tag in file: " + nfcTagId);
            return false;
        }

        public boolean registerSubFunctionId(Long subFunctionId, String subFunctionCode,
                                            int rowNum, ImportResult result) {
            if (subFunctionIds.add(subFunctionId)) {
                return true;
            }
            result.addError(rowNum, "Duplicate sub function in file: " + subFunctionCode);
            return false;
        }
    }
}
