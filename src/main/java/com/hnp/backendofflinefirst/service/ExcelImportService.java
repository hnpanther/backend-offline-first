package com.hnp.backendofflinefirst.service;

import com.hnp.backendofflinefirst.domain.ImportEntityType;
import com.hnp.backendofflinefirst.dto.ImportResult;
import com.hnp.backendofflinefirst.service.importjob.ImportProgressListener;
import com.hnp.backendofflinefirst.config.ImportStorageProperties;
import com.hnp.backendofflinefirst.entity.*;
import com.hnp.backendofflinefirst.logging.BusinessEventLogger;
import com.hnp.backendofflinefirst.repository.*;
import com.hnp.backendofflinefirst.util.ExcelUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class ExcelImportService {

    private final LocationRepository locationRepository;
    private final PlantSystemRepository plantSystemRepository;
    private final MainFunctionRepository mainFunctionRepository;
    private final SubFunctionRepository subFunctionRepository;
    private final AssetEntryRepository assetEntryRepository;
    private final AssetClassRepository assetClassRepository;
    private final AssetEntryService assetEntryService;
    private final AssetHierarchyService hierarchyService;
    private final OperationalUnitRepository operationalUnitRepository;
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final UserRoleRepository userRoleRepository;
    private final UnitSupervisorRepository unitSupervisorRepository;
    private final UnitOperatorRepository unitOperatorRepository;
    private final PasswordEncoder passwordEncoder;
    private final BusinessEventLogger businessEventLogger;
    private final UserService userService;
    private final MasterDataUniquenessValidator uniquenessValidator;
    private final ImportStorageProperties importStorageProperties;

    public ImportResult importEntity(ImportEntityType type, MultipartFile file, ImportProgressListener listener)
            throws IOException {
        return switch (type) {
            case LOCATIONS -> importLocations(file, listener);
            case PLANT_SYSTEMS -> importPlantSystems(file, listener);
            case MAIN_FUNCTIONS -> importMainFunctions(file, listener);
            case SUB_FUNCTIONS -> importSubFunctions(file, listener);
            case ASSET_ENTRIES -> importAssetEntries(file, listener);
            case USERS -> importUsers(file, listener);
            case OPERATIONAL_UNITS -> importOperationalUnits(file, listener);
            case UNIT_STAFF -> importUnitStaff(file, listener);
        };
    }

    // ── Location ──────────────────────────────────────────────────────────────
    // Columns: code | name | parentCode | unitCode
    public ImportResult importLocations(MultipartFile file) throws IOException {
        return importLocations(file, null);
    }

    public ImportResult importLocations(MultipartFile file, ImportProgressListener listener) throws IOException {
        ImportStats stats = new ImportStats("locations", file, listener);
        ImportResult result = new ImportResult();
        MasterDataUniquenessValidator.FileUniqueness fileUniq = new MasterDataUniquenessValidator.FileUniqueness();
        try (Workbook wb = new XSSFWorkbook(file.getInputStream())) {
            Sheet sheet = requireSheetWithinLimit(wb);
            stats.sheetRows = sheet.getLastRowNum();
            businessEventLogger.importStarted(stats.entityType, stats.fileName, stats.fileSize, stats.sheetRows);
            log.info("[IMPORT] ExcelImportService.importLocations file={} sheetRows={} → LocationRepository.save",
                    stats.fileName, stats.sheetRows);

            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (isBlankRow(row, 4)) {
                    stats.blankSkipped++;
                    continue;
                }
                stats.rowsRead++;
                stats.tickProgress();

                String code = cellStr(row, 0);
                String name = cellStr(row, 1);
                if (isEmpty(code) || isEmpty(name)) {
                    result.addError(i + 1, "Code and name are required.");
                    continue;
                }
                code = code.trim();
                if (!uniquenessValidator.validateLocationForImport(code, i + 1, result, fileUniq)) {
                    continue;
                }

                String parentCode = cellStr(row, 2);
                Long parentId = null;
                if (!isEmpty(parentCode)) {
                    Optional<Location> parent = locationRepository.findByCodeIgnoreCase(parentCode);
                    if (parent.isEmpty()) {
                        result.addError(i + 1, "Parent location not found: " + parentCode);
                        continue;
                    }
                    parentId = parent.get().getId();
                }

                String unitCode = cellStr(row, 3);
                Long unitId = null;
                if (!isEmpty(unitCode)) {
                    Optional<OperationalUnit> unit = operationalUnitRepository.findByCodeIgnoreCase(unitCode);
                    if (unit.isEmpty()) {
                        result.addError(i + 1, "Operational unit not found: " + unitCode);
                        continue;
                    }
                    unitId = unit.get().getId();
                }

                long now = System.currentTimeMillis();
                Location loc = new Location();
                loc.setCode(code);
                loc.setName(name);
                loc.setParentId(parentId);
                loc.setUnitId(unitId);
                loc.setCreatedAt(now);
                loc.setUpdatedAt(now);
                hierarchyService.saveLocation(loc);
                result.addSuccess();
                log.debug("[IMPORT] locations row={} code={} saved via LocationRepository", i + 1, code);
            }
        }
        stats.tickFinal();
        finishImport(stats, result);
        return result;
    }

    // ── PlantSystem ───────────────────────────────────────────────────────────
    // Columns: code | name | locationCode
    public ImportResult importPlantSystems(MultipartFile file) throws IOException {
        return importPlantSystems(file, null);
    }

    public ImportResult importPlantSystems(MultipartFile file, ImportProgressListener listener) throws IOException {
        ImportStats stats = new ImportStats("plant-systems", file, listener);
        ImportResult result = new ImportResult();
        MasterDataUniquenessValidator.FileUniqueness fileUniq = new MasterDataUniquenessValidator.FileUniqueness();
        try (Workbook wb = new XSSFWorkbook(file.getInputStream())) {
            Sheet sheet = requireSheetWithinLimit(wb);
            stats.sheetRows = sheet.getLastRowNum();
            businessEventLogger.importStarted(stats.entityType, stats.fileName, stats.fileSize, stats.sheetRows);
            log.info("[IMPORT] ExcelImportService.importPlantSystems file={} sheetRows={} → PlantSystemRepository.save",
                    stats.fileName, stats.sheetRows);

            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (isBlankRow(row, 4)) {
                    stats.blankSkipped++;
                    continue;
                }
                stats.rowsRead++;
                stats.tickProgress();

                String code = cellStr(row, 0);
                String name = cellStr(row, 1);
                if (isEmpty(code) || isEmpty(name)) {
                    result.addError(i + 1, "Code and name are required.");
                    continue;
                }
                code = code.trim();
                name = name.trim();
                if (!uniquenessValidator.validatePlantSystemForImport(code, i + 1, result, fileUniq)) {
                    continue;
                }

                String parentSystemCode = cellStr(row, 2);
                Long parentId = null;
                if (!isEmpty(parentSystemCode)) {
                    Optional<PlantSystem> parent = plantSystemRepository.findByCodeIgnoreCase(parentSystemCode);
                    if (parent.isEmpty()) {
                        result.addError(i + 1, "Parent system not found: " + parentSystemCode);
                        continue;
                    }
                    parentId = parent.get().getId();
                }

                String locationCode = cellStr(row, 3);
                Long locationId = null;
                if (!isEmpty(locationCode)) {
                    Optional<Location> loc = locationRepository.findByCodeIgnoreCase(locationCode);
                    if (loc.isEmpty()) {
                        result.addError(i + 1, "Location not found: " + locationCode);
                        continue;
                    }
                    locationId = loc.get().getId();
                }

                long now = System.currentTimeMillis();
                PlantSystem ps = new PlantSystem();
                ps.setCode(code);
                ps.setName(name);
                ps.setParentId(parentId);
                ps.setLocationId(locationId);
                ps.setCreatedAt(now);
                ps.setUpdatedAt(now);
                hierarchyService.savePlantSystem(ps);
                result.addSuccess();
            }
        }
        stats.tickFinal();
        finishImport(stats, result);
        return result;
    }

    // ── MainFunction ──────────────────────────────────────────────────────────
    // Columns: code | name | parentMainFunctionCode | systemCode | locationCode
    public ImportResult importMainFunctions(MultipartFile file) throws IOException {
        return importMainFunctions(file, null);
    }

    public ImportResult importMainFunctions(MultipartFile file, ImportProgressListener listener) throws IOException {
        ImportStats stats = new ImportStats("main-functions", file, listener);
        ImportResult result = new ImportResult();
        MasterDataUniquenessValidator.FileUniqueness fileUniq = new MasterDataUniquenessValidator.FileUniqueness();
        try (Workbook wb = new XSSFWorkbook(file.getInputStream())) {
            Sheet sheet = requireSheetWithinLimit(wb);
            stats.sheetRows = sheet.getLastRowNum();
            businessEventLogger.importStarted(stats.entityType, stats.fileName, stats.fileSize, stats.sheetRows);
            log.info("[IMPORT] ExcelImportService.importMainFunctions file={} sheetRows={} → MainFunctionRepository.save",
                    stats.fileName, stats.sheetRows);

            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (isBlankRow(row, 5)) {
                    stats.blankSkipped++;
                    continue;
                }
                stats.rowsRead++;
                stats.tickProgress();

                String code = cellStr(row, 0);
                String name = cellStr(row, 1);
                if (isEmpty(code) || isEmpty(name)) {
                    result.addError(i + 1, "Code and name are required.");
                    continue;
                }
                code = code.trim();
                name = name.trim();
                if (!uniquenessValidator.validateMainFunctionForImport(code, i + 1, result, fileUniq)) {
                    continue;
                }

                String parentMainFunctionCode = cellStr(row, 2);
                String systemCode = cellStr(row, 3);
                String locationCode = cellStr(row, 4);

                long now = System.currentTimeMillis();
                MainFunction mf = new MainFunction();
                mf.setCode(code);
                mf.setName(name);
                mf.setCreatedAt(now);
                mf.setUpdatedAt(now);

                if (!isEmpty(parentMainFunctionCode)) {
                    Optional<MainFunction> parent = mainFunctionRepository.findByCodeIgnoreCase(parentMainFunctionCode);
                    if (parent.isEmpty()) {
                        result.addError(i + 1, "Parent main function not found: " + parentMainFunctionCode);
                        continue;
                    }
                    hierarchyService.applyMainFunctionParent(
                            mf, AssetHierarchyService.SCOPE_MAIN_FUNCTION, parent.get().getId());
                } else if (!isEmpty(systemCode)) {
                    Optional<PlantSystem> sys = plantSystemRepository.findByCodeIgnoreCase(systemCode);
                    if (sys.isEmpty()) {
                        result.addError(i + 1, "Plant system not found: " + systemCode);
                        continue;
                    }
                    hierarchyService.applyMainFunctionParent(mf, AssetHierarchyService.SCOPE_SYSTEM, sys.get().getId());
                } else if (!isEmpty(locationCode)) {
                    Optional<Location> loc = locationRepository.findByCodeIgnoreCase(locationCode);
                    if (loc.isEmpty()) {
                        result.addError(i + 1, "Location not found: " + locationCode);
                        continue;
                    }
                    hierarchyService.applyMainFunctionParent(mf, AssetHierarchyService.SCOPE_LOCATION, loc.get().getId());
                }

                hierarchyService.saveMainFunction(mf);
                result.addSuccess();
            }
        }
        stats.tickFinal();
        finishImport(stats, result);
        return result;
    }

    // ── SubFunction ───────────────────────────────────────────────────────────
    // Columns: code | name | tag | parentSubFunctionCode | mainFunctionCode | systemCode | locationCode
    public ImportResult importSubFunctions(MultipartFile file) throws IOException {
        return importSubFunctions(file, null);
    }

    public ImportResult importSubFunctions(MultipartFile file, ImportProgressListener listener) throws IOException {
        ImportStats stats = new ImportStats("sub-functions", file, listener);
        ImportResult result = new ImportResult();
        MasterDataUniquenessValidator.FileUniqueness fileUniq = new MasterDataUniquenessValidator.FileUniqueness();
        try (Workbook wb = new XSSFWorkbook(file.getInputStream())) {
            Sheet sheet = requireSheetWithinLimit(wb);
            stats.sheetRows = sheet.getLastRowNum();
            businessEventLogger.importStarted(stats.entityType, stats.fileName, stats.fileSize, stats.sheetRows);
            log.info("[IMPORT] ExcelImportService.importSubFunctions file={} sheetRows={} → SubFunctionRepository.save",
                    stats.fileName, stats.sheetRows);

            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (isBlankRow(row, 7)) {
                    stats.blankSkipped++;
                    continue;
                }
                stats.rowsRead++;
                stats.tickProgress();

                String code = cellStr(row, 0);
                String name = cellStr(row, 1);
                String tag = cellStr(row, 2);
                if (isEmpty(code) || isEmpty(name)) {
                    result.addError(i + 1, "Code and name are required.");
                    continue;
                }
                if (!uniquenessValidator.validateSubFunctionForImport(code, tag, i + 1, result, fileUniq)) {
                    continue;
                }

                String parentSfCode = cellStr(row, 3);
                String mfCode = cellStr(row, 4);
                String systemCode = cellStr(row, 5);
                String locationCode = cellStr(row, 6);

                long now = System.currentTimeMillis();
                SubFunction sf = new SubFunction();
                sf.setCode(code.trim());
                sf.setName(name.trim());
                sf.setTag(tag.trim());
                sf.setCreatedAt(now);
                sf.setUpdatedAt(now);

                if (!isEmpty(parentSfCode)) {
                    Optional<SubFunction> parent = subFunctionRepository.findByCodeIgnoreCase(parentSfCode);
                    if (parent.isEmpty()) {
                        result.addError(i + 1, "Parent sub function not found: " + parentSfCode);
                        continue;
                    }
                    hierarchyService.applySubFunctionParent(
                            sf, AssetHierarchyService.SCOPE_SUB_FUNCTION, parent.get().getId());
                } else if (!isEmpty(mfCode)) {
                    Optional<MainFunction> mf = mainFunctionRepository.findByCodeIgnoreCase(mfCode);
                    if (mf.isEmpty()) {
                        result.addError(i + 1, "Main function not found: " + mfCode);
                        continue;
                    }
                    hierarchyService.applySubFunctionParent(sf, AssetHierarchyService.SCOPE_MAIN_FUNCTION, mf.get().getId());
                } else if (!isEmpty(systemCode)) {
                    Optional<PlantSystem> sys = plantSystemRepository.findByCodeIgnoreCase(systemCode);
                    if (sys.isEmpty()) {
                        result.addError(i + 1, "Plant system not found: " + systemCode);
                        continue;
                    }
                    hierarchyService.applySubFunctionParent(sf, AssetHierarchyService.SCOPE_SYSTEM, sys.get().getId());
                } else if (!isEmpty(locationCode)) {
                    Optional<Location> loc = locationRepository.findByCodeIgnoreCase(locationCode);
                    if (loc.isEmpty()) {
                        result.addError(i + 1, "Location not found: " + locationCode);
                        continue;
                    }
                    hierarchyService.applySubFunctionParent(sf, AssetHierarchyService.SCOPE_LOCATION, loc.get().getId());
                }

                hierarchyService.saveSubFunction(sf);
                result.addSuccess();
            }
        }
        stats.tickFinal();
        finishImport(stats, result);
        return result;
    }

    // ── AssetEntry ────────────────────────────────────────────────────────────
    // Columns: assetCode | assetName | nfcTagId | subFunctionCode | className
    public ImportResult importAssetEntries(MultipartFile file) throws IOException {
        return importAssetEntries(file, null);
    }

    public ImportResult importAssetEntries(MultipartFile file, ImportProgressListener listener) throws IOException {
        ImportStats stats = new ImportStats("asset-entries", file, listener);
        ImportResult result = new ImportResult();
        MasterDataUniquenessValidator.FileUniqueness fileUniq = new MasterDataUniquenessValidator.FileUniqueness();
        try (Workbook wb = new XSSFWorkbook(file.getInputStream())) {
            Sheet sheet = requireSheetWithinLimit(wb);
            stats.sheetRows = sheet.getLastRowNum();
            businessEventLogger.importStarted(stats.entityType, stats.fileName, stats.fileSize, stats.sheetRows);
            log.info("[IMPORT] ExcelImportService.importAssetEntries file={} sheetRows={} → AssetEntryRepository.save",
                    stats.fileName, stats.sheetRows);

            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (isBlankRow(row, 6)) {
                    stats.blankSkipped++;
                    continue;
                }
                stats.rowsRead++;
                stats.tickProgress();

                String assetCode = cellStr(row, 0);
                String assetName = cellStr(row, 1);
                if (isEmpty(assetCode)) {
                    result.addError(i + 1, "Asset code is required.");
                    continue;
                }
                if (isEmpty(assetName)) {
                    result.addError(i + 1, "Asset name is required.");
                    continue;
                }
                if (!uniquenessValidator.validateAssetEntryForImport(assetCode, i + 1, result, fileUniq)) {
                    continue;
                }

                String nfcTagId = cellStr(row, 2);
                String sfCode = cellStr(row, 3);
                if (isEmpty(sfCode)) {
                    result.addError(i + 1, "Sub function code is required.");
                    continue;
                }
                Optional<SubFunction> sfOpt = subFunctionRepository.findByCodeIgnoreCase(sfCode);
                if (sfOpt.isEmpty()) {
                    result.addError(i + 1, "Sub function not found: " + sfCode);
                    continue;
                }
                SubFunction subFunction = sfOpt.get();
                Long subFunctionId = subFunction.getId();

                String className = cellStr(row, 4);
                Long classId = null;
                if (!isEmpty(className)) {
                    Optional<AssetClass> ac = assetClassRepository.findByNameIgnoreCase(className);
                    if (ac.isEmpty()) {
                        result.addError(i + 1, "Asset class not found: " + className);
                        continue;
                    }
                    classId = ac.get().getId();
                }
                boolean active = parseActive(cellStr(row, 5));

                long now = System.currentTimeMillis();
                AssetEntry ae = new AssetEntry();
                ae.setAssetCode(assetCode.trim());
                ae.setAssetName(assetName);
                ae.setNfcTagId(isEmpty(nfcTagId) ? null : nfcTagId.trim());
                ae.setSubFunctionId(subFunctionId);
                ae.setClassId(classId);
                ae.setActive(active);
                ae.setCreatedAt(now);
                ae.setUpdatedAt(now);
                assetEntryService.prepareForImport(ae);

                if (!uniquenessValidator.validateAssetNfcForImport(ae.getNfcTagId(), i + 1, result, fileUniq)) {
                    continue;
                }
                if (isEmpty(nfcTagId)) {
                    log.debug("[IMPORT] asset-entries row={} NFC auto from subFunction tag={}", i + 1, subFunction.getTag());
                }

                assetEntryRepository.save(ae);
                result.addSuccess();
            }
        }
        stats.tickFinal();
        finishImport(stats, result);
        return result;
    }

    // ── Users ─────────────────────────────────────────────────────────────────
    public ImportResult importUsers(MultipartFile file) throws IOException {
        return importUsers(file, null);
    }

    public ImportResult importUsers(MultipartFile file, ImportProgressListener listener) throws IOException {
        ImportStats stats = new ImportStats("users", file, listener);
        ImportResult result = new ImportResult();
        try (Workbook wb = new XSSFWorkbook(file.getInputStream())) {
            Sheet sheet = requireSheetWithinLimit(wb);
            stats.sheetRows = sheet.getLastRowNum();
            businessEventLogger.importStarted(stats.entityType, stats.fileName, stats.fileSize, stats.sheetRows);
            log.info("[IMPORT] ExcelImportService.importUsers file={} sheetRows={} → UserRepository.save",
                    stats.fileName, stats.sheetRows);

            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (ExcelUtils.isBlankRow(row, 9)) {
                    stats.blankSkipped++;
                    continue;
                }
                stats.rowsRead++;
                stats.tickProgress();

                String username = cellStr(row, 0);
                String fullName = cellStr(row, 1);
                String nationalCode = cellStr(row, 2);
                String phoneNumber = cellStr(row, 3);
                String nfcTag = cellStr(row, 4);
                String password = cellStr(row, 5);
                String authTypeStr = cellStr(row, 6);
                String activeStr = cellStr(row, 7);
                String roleCodes = cellStr(row, 8);

                if (ExcelUtils.isEmpty(username)) {
                    result.addError(i + 1, "Username is required.");
                    continue;
                }
                UserAuthType authType;
                try {
                    authType = UserService.parseAuthType(authTypeStr);
                } catch (IllegalArgumentException e) {
                    result.addError(i + 1, "Invalid authType: " + authTypeStr);
                    continue;
                }
                if (authType != UserAuthType.ACTIVE_DIRECTORY && ExcelUtils.isEmpty(password)) {
                    result.addError(i + 1, "Password is required for LOCAL and HYBRID users.");
                    continue;
                }
                if (userRepository.existsByUsername(username.trim())) {
                    result.addError(i + 1, "Duplicate username: " + username);
                    continue;
                }
                List<Long> roleIds = resolveRoleIds(roleCodes, i + 1, result);
                if (roleIds == null) continue;

                long now = System.currentTimeMillis();
                User user = new User();
                user.setUsername(username.trim());
                user.setFullName(fullName != null && !fullName.isBlank() ? fullName.trim() : null);
                try {
                    userService.applyContactFields(user, nationalCode, phoneNumber, nfcTag);
                    user.setPasswordHash(userService.resolvePasswordHash(password, authType));
                } catch (IllegalArgumentException e) {
                    result.addError(i + 1, e.getMessage());
                    continue;
                }
                user.setAuthType(authType);
                user.setActive(parseActive(activeStr));
                user.setCreatedAt(now);
                user.setUpdatedAt(now);
                userRepository.save(user);
                assignRoles(user.getId(), roleIds);
                result.addSuccess();
            }
        }
        stats.tickFinal();
        finishImport(stats, result);
        return result;
    }

    public ImportResult importOperationalUnits(MultipartFile file) throws IOException {
        return importOperationalUnits(file, null);
    }

    @Transactional
    public ImportResult importOperationalUnits(MultipartFile file, ImportProgressListener listener) throws IOException {
        ImportStats stats = new ImportStats("operational-units", file, listener);
        ImportResult result = new ImportResult();
        List<UnitImportRow> rows = new ArrayList<>();

        try (Workbook wb = new XSSFWorkbook(file.getInputStream())) {
            Sheet sheet = requireSheetWithinLimit(wb);
            stats.sheetRows = sheet.getLastRowNum();
            businessEventLogger.importStarted(stats.entityType, stats.fileName, stats.fileSize, stats.sheetRows);
            log.info("[IMPORT] ExcelImportService.importOperationalUnits file={} sheetRows={}",
                    stats.fileName, stats.sheetRows);

            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (ExcelUtils.isBlankRow(row, 3)) {
                    stats.blankSkipped++;
                    continue;
                }
                stats.rowsRead++;
                stats.tickProgress();

                String code = cellStr(row, 0);
                String name = cellStr(row, 1);
                String parentCode = cellStr(row, 2);
                if (ExcelUtils.isEmpty(code) || ExcelUtils.isEmpty(name)) {
                    result.addError(i + 1, "Code and name are required.");
                    continue;
                }
                rows.add(new UnitImportRow(i + 1, code.trim(), name.trim(),
                        ExcelUtils.isEmpty(parentCode) ? null : parentCode.trim()));
            }
        }

        if (result.hasErrors()) {
            result.clearSuccessCount();
            stats.tickFinal();
        finishImport(stats, result);
            return result;
        }
        if (rows.isEmpty()) {
            stats.tickFinal();
        finishImport(stats, result);
            return result;
        }

        Set<String> availableCodes = operationalUnitRepository.findAll().stream()
                .map(OperationalUnit::getCode)
                .filter(c -> c != null && !c.isBlank())
                .map(c -> c.toLowerCase(Locale.ROOT))
                .collect(Collectors.toCollection(HashSet::new));
        Set<String> seenInFile = new HashSet<>();

        for (UnitImportRow row : rows) {
            String codeKey = row.code.toLowerCase(Locale.ROOT);
            String parentKey = row.parentCode != null ? row.parentCode.toLowerCase(Locale.ROOT) : null;
            if (availableCodes.contains(codeKey) || seenInFile.contains(codeKey)) {
                result.addError(row.rowNum, "Duplicate unit code: " + row.code);
                continue;
            }
            if (parentKey != null && !availableCodes.contains(parentKey)) {
                result.addError(row.rowNum,
                        "Parent unit not found before this row (check row order): " + row.parentCode);
                continue;
            }
            seenInFile.add(codeKey);
            availableCodes.add(codeKey);
        }

        if (result.hasErrors()) {
            result.clearSuccessCount();
            stats.tickFinal();
        finishImport(stats, result);
            return result;
        }

        Map<String, Long> codeToId = operationalUnitRepository.findAll().stream()
                .filter(u -> u.getCode() != null && !u.getCode().isBlank())
                .collect(Collectors.toMap(
                        u -> u.getCode().toLowerCase(Locale.ROOT),
                        OperationalUnit::getId,
                        (a, b) -> a));

        long now = System.currentTimeMillis();
        for (UnitImportRow row : rows) {
            Long parentId = row.parentCode != null
                    ? codeToId.get(row.parentCode.toLowerCase(Locale.ROOT))
                    : null;
            OperationalUnit unit = new OperationalUnit();
            unit.setCode(row.code);
            unit.setName(row.name);
            unit.setParentId(parentId);
            unit.setCreatedAt(now);
            unit.setUpdatedAt(now);
            OperationalUnit saved = operationalUnitRepository.save(unit);
            codeToId.put(row.code.toLowerCase(Locale.ROOT), saved.getId());
            result.addSuccess();
        }
        stats.tickFinal();
        finishImport(stats, result);
        return result;
    }

    public ImportResult importUnitStaff(MultipartFile file) throws IOException {
        return importUnitStaff(file, null);
    }

    @Transactional
    public ImportResult importUnitStaff(MultipartFile file, ImportProgressListener listener) throws IOException {
        ImportStats stats = new ImportStats("unit-staff", file, listener);
        ImportResult result = new ImportResult();
        List<StaffImportRow> rows = new ArrayList<>();

        try (Workbook wb = new XSSFWorkbook(file.getInputStream())) {
            Sheet sheet = requireSheetWithinLimit(wb);
            stats.sheetRows = sheet.getLastRowNum();
            businessEventLogger.importStarted(stats.entityType, stats.fileName, stats.fileSize, stats.sheetRows);
            log.info("[IMPORT] ExcelImportService.importUnitStaff file={} sheetRows={}",
                    stats.fileName, stats.sheetRows);

            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (ExcelUtils.isBlankRow(row, 3)) {
                    stats.blankSkipped++;
                    continue;
                }
                stats.rowsRead++;
                stats.tickProgress();

                String unitCode = cellStr(row, 0);
                String roleType = cellStr(row, 1);
                String username = cellStr(row, 2);

                if (ExcelUtils.isEmpty(unitCode) || ExcelUtils.isEmpty(roleType) || ExcelUtils.isEmpty(username)) {
                    result.addError(i + 1, "Unit code, role type and username are required.");
                    continue;
                }

                Optional<OperationalUnit> unit = operationalUnitRepository.findByCodeIgnoreCase(unitCode.trim());
                if (unit.isEmpty()) {
                    result.addError(i + 1, "Operational unit not found: " + unitCode);
                    continue;
                }
                Optional<User> user = userRepository.findByUsername(username.trim());
                if (user.isEmpty()) {
                    result.addError(i + 1, "User not found: " + username);
                    continue;
                }
                StaffRole staffRole = parseStaffRole(roleType.trim());
                if (staffRole == null) {
                    result.addError(i + 1, "Invalid role type (SUPERVISOR or OPERATOR): " + roleType);
                    continue;
                }
                rows.add(new StaffImportRow(unit.get().getId(), user.get().getId(), staffRole));
            }
        }

        if (result.hasErrors()) {
            result.clearSuccessCount();
            stats.tickFinal();
        finishImport(stats, result);
            return result;
        }

        for (StaffImportRow row : rows) {
            if (row.role == StaffRole.SUPERVISOR) {
                UnitUserId id = new UnitUserId();
                id.setUnitId(row.unitId);
                id.setUserId(row.userId);
                if (!unitSupervisorRepository.existsById(id)) {
                    UnitSupervisor link = new UnitSupervisor();
                    link.setUnitId(row.unitId);
                    link.setUserId(row.userId);
                    unitSupervisorRepository.save(link);
                    result.addSuccess();
                }
            } else {
                UnitUserId id = new UnitUserId();
                id.setUnitId(row.unitId);
                id.setUserId(row.userId);
                if (!unitOperatorRepository.existsById(id)) {
                    UnitOperator link = new UnitOperator();
                    link.setUnitId(row.unitId);
                    link.setUserId(row.userId);
                    unitOperatorRepository.save(link);
                    result.addSuccess();
                }
            }
        }
        stats.tickFinal();
        finishImport(stats, result);
        return result;
    }

    private Sheet requireSheetWithinLimit(Workbook wb) {
        Sheet sheet = wb.getSheetAt(0);
        ExcelUtils.assertWithinImportRowLimit(
                ExcelUtils.countDataRows(sheet), importStorageProperties.getMaxRows());
        return sheet;
    }

    private void finishImport(ImportStats stats, ImportResult result) {
        businessEventLogger.importCompleted(
                stats.entityType, stats.rowsRead, stats.blankSkipped,
                result.getSuccessCount(), result.getErrorCount());
        log.info("[IMPORT] ExcelImportService.{} finished file={} rowsRead={} blankSkipped={} success={} errors={}",
                stats.entityType, stats.fileName, stats.rowsRead, stats.blankSkipped,
                result.getSuccessCount(), result.getErrorCount());
    }

    private List<Long> resolveRoleIds(String roleCodes, int rowNum, ImportResult result) {
        if (ExcelUtils.isEmpty(roleCodes)) return List.of();
        List<Long> ids = new ArrayList<>();
        for (String part : roleCodes.split("[,،]")) {
            String code = part.trim();
            if (code.isEmpty()) continue;
            Optional<Role> role = roleRepository.findByCode(code);
            if (role.isEmpty()) {
                result.addError(rowNum, "Role code not found: " + code);
                return null;
            }
            ids.add(role.get().getId());
        }
        return ids;
    }

    private void assignRoles(Long userId, List<Long> roleIds) {
        for (Long roleId : roleIds) {
            UserRole ur = new UserRole();
            ur.setUserId(userId);
            ur.setRoleId(roleId);
            userRoleRepository.save(ur);
        }
    }

    private boolean parseActive(String value) {
        if (ExcelUtils.isEmpty(value)) return true;
        String v = value.trim().toLowerCase(Locale.ROOT);
        return switch (v) {
            case "false", "0", "no", "غیرفعال", "خیر" -> false;
            case "true", "1", "yes", "فعال", "بله" -> true;
            default -> true;
        };
    }

    private StaffRole parseStaffRole(String value) {
        String trimmed = value.trim();
        if ("سرپرست".equals(trimmed)) return StaffRole.SUPERVISOR;
        if ("اپراتور".equals(trimmed)) return StaffRole.OPERATOR;
        return switch (trimmed.toUpperCase(Locale.ROOT)) {
            case "SUPERVISOR" -> StaffRole.SUPERVISOR;
            case "OPERATOR" -> StaffRole.OPERATOR;
            default -> null;
        };
    }

    private String cellStr(Row row, int col) {
        return ExcelUtils.cellStr(row, col);
    }

    private boolean isEmpty(String s) {
        return ExcelUtils.isEmpty(s);
    }

    private boolean isBlankRow(Row row, int cols) {
        return ExcelUtils.isBlankRow(row, cols);
    }

    private enum StaffRole { SUPERVISOR, OPERATOR }

    private record UnitImportRow(int rowNum, String code, String name, String parentCode) {}

    private record StaffImportRow(Long unitId, Long userId, StaffRole role) {}

    private static final class ImportStats {
        final String entityType;
        final String fileName;
        final long fileSize;
        final ImportProgressListener listener;
        int sheetRows;
        int rowsRead;
        int blankSkipped;

        ImportStats(String entityType, MultipartFile file, ImportProgressListener listener) {
            this.entityType = entityType;
            this.fileName = file.getOriginalFilename() != null ? file.getOriginalFilename() : "unknown";
            this.fileSize = file.getSize();
            this.listener = listener;
        }

        void tickProgress() {
            if (listener != null && (rowsRead % 25 == 0 || rowsRead == sheetRows)) {
                listener.onProgress(rowsRead, sheetRows);
            }
        }

        void tickFinal() {
            if (listener != null) {
                listener.onProgress(rowsRead, sheetRows);
            }
        }
    }
}
