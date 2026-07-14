package com.hnp.backendofflinefirst.service;

import com.hnp.backendofflinefirst.dto.ImportResult;
import com.hnp.backendofflinefirst.repository.AssetEntryRepository;
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
 * Enforces global uniqueness of {@code code} on hierarchy master data and asset entries
 * (import + web saves). Display names/titles are not required to be unique.
 */
@Component
@RequiredArgsConstructor
public class MasterDataUniquenessValidator {

    private final LocationRepository locationRepository;
    private final PlantSystemRepository plantSystemRepository;
    private final MainFunctionRepository mainFunctionRepository;
    private final SubFunctionRepository subFunctionRepository;
    private final AssetEntryRepository assetEntryRepository;

    public void validateLocation(Long id, String code) {
        validateCode(id, code, "location", locationRepository::findByCode);
    }

    public void validatePlantSystem(Long id, String code) {
        validateCode(id, code, "plant system", plantSystemRepository::findByCode);
    }

    public void validateMainFunction(Long id, String code) {
        validateCode(id, code, "main function", mainFunctionRepository::findByCode);
    }

    public void validateSubFunction(Long id, String code) {
        validateCode(id, code, "sub function", subFunctionRepository::findByCode);
    }

    public void validateAssetEntry(Long id, String assetCode) {
        validateCode(id, assetCode, "asset", assetEntryRepository::findFirstByAssetCodeIgnoreCase);
    }

    public boolean validateLocationForImport(String code, int rowNum,
                                           ImportResult result, FileUniqueness fileUniqueness) {
        return validateCodeForImport(code, rowNum, result, fileUniqueness,
                locationRepository::findByCode, "location");
    }

    public boolean validatePlantSystemForImport(String code, int rowNum,
                                                ImportResult result, FileUniqueness fileUniqueness) {
        return validateCodeForImport(code, rowNum, result, fileUniqueness,
                plantSystemRepository::findByCode, "plant system");
    }

    public boolean validateMainFunctionForImport(String code, int rowNum,
                                                 ImportResult result, FileUniqueness fileUniqueness) {
        return validateCodeForImport(code, rowNum, result, fileUniqueness,
                mainFunctionRepository::findByCode, "main function");
    }

    public boolean validateSubFunctionForImport(String code, int rowNum,
                                                ImportResult result, FileUniqueness fileUniqueness) {
        return validateCodeForImport(code, rowNum, result, fileUniqueness,
                subFunctionRepository::findByCode, "sub function");
    }

    public boolean validateAssetEntryForImport(String assetCode, int rowNum,
                                               ImportResult result, FileUniqueness fileUniqueness) {
        return validateCodeForImport(assetCode, rowNum, result, fileUniqueness,
                assetEntryRepository::findFirstByAssetCodeIgnoreCase, "asset");
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

    /** Tracks codes already seen in the current Excel file (case-insensitive). */
    public static final class FileUniqueness {
        private final Set<String> codes = new HashSet<>();

        public boolean registerCode(String code, int rowNum, ImportResult result) {
            if (codes.add(code.trim().toLowerCase(Locale.ROOT))) {
                return true;
            }
            result.addError(rowNum, "Duplicate code in file: " + code);
            return false;
        }
    }
}
