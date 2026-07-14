package com.hnp.backendofflinefirst.service;

import com.hnp.backendofflinefirst.dto.ImportResult;
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

    // ── Location ──────────────────────────────────────────────────────────────
    // Columns: code | name | parentCode | unitCode
    public ImportResult importLocations(MultipartFile file) throws IOException {
        ImportStats stats = new ImportStats("locations", file);
        ImportResult result = new ImportResult();
        try (Workbook wb = new XSSFWorkbook(file.getInputStream())) {
            Sheet sheet = wb.getSheetAt(0);
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

                String code = cellStr(row, 0);
                String name = cellStr(row, 1);
                if (isEmpty(code) || isEmpty(name)) {
                    result.addError(i + 1, "Code and name are required.");
                    continue;
                }

                String parentCode = cellStr(row, 2);
                Long parentId = null;
                if (!isEmpty(parentCode)) {
                    Optional<Location> parent = locationRepository.findByCode(parentCode);
                    if (parent.isEmpty()) {
                        result.addError(i + 1, "Parent location not found: " + parentCode);
                        continue;
                    }
                    parentId = parent.get().getId();
                }

                String unitCode = cellStr(row, 3);
                Long unitId = null;
                if (!isEmpty(unitCode)) {
                    Optional<OperationalUnit> unit = operationalUnitRepository.findByCode(unitCode);
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
        finishImport(stats, result);
        return result;
    }

    // ── PlantSystem ───────────────────────────────────────────────────────────
    // Columns: code | name | locationCode
    public ImportResult importPlantSystems(MultipartFile file) throws IOException {
        ImportStats stats = new ImportStats("plant-systems", file);
        ImportResult result = new ImportResult();
        try (Workbook wb = new XSSFWorkbook(file.getInputStream())) {
            Sheet sheet = wb.getSheetAt(0);
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

                String code = cellStr(row, 0);
                String name = cellStr(row, 1);
                if (isEmpty(code) || isEmpty(name)) {
                    result.addError(i + 1, "Code and name are required.");
                    continue;
                }

                String parentSystemCode = cellStr(row, 2);
                Long parentId = null;
                if (!isEmpty(parentSystemCode)) {
                    Optional<PlantSystem> parent = plantSystemRepository.findByCode(parentSystemCode);
                    if (parent.isEmpty()) {
                        result.addError(i + 1, "Parent system not found: " + parentSystemCode);
                        continue;
                    }
                    parentId = parent.get().getId();
                }

                String locationCode = cellStr(row, 3);
                Long locationId = null;
                if (!isEmpty(locationCode)) {
                    Optional<Location> loc = locationRepository.findByCode(locationCode);
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
        finishImport(stats, result);
        return result;
    }

    // ── MainFunction ──────────────────────────────────────────────────────────
    // Columns: code | name | parentMainFunctionCode | systemCode | locationCode
    public ImportResult importMainFunctions(MultipartFile file) throws IOException {
        ImportStats stats = new ImportStats("main-functions", file);
        ImportResult result = new ImportResult();
        try (Workbook wb = new XSSFWorkbook(file.getInputStream())) {
            Sheet sheet = wb.getSheetAt(0);
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

                String code = cellStr(row, 0);
                String name = cellStr(row, 1);
                if (isEmpty(code) || isEmpty(name)) {
                    result.addError(i + 1, "Code and name are required.");
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
                    Optional<MainFunction> parent = mainFunctionRepository.findByCode(parentMainFunctionCode);
                    if (parent.isEmpty()) {
                        result.addError(i + 1, "Parent main function not found: " + parentMainFunctionCode);
                        continue;
                    }
                    hierarchyService.applyMainFunctionParent(
                            mf, AssetHierarchyService.SCOPE_MAIN_FUNCTION, parent.get().getId());
                } else if (!isEmpty(systemCode)) {
                    Optional<PlantSystem> sys = plantSystemRepository.findByCode(systemCode);
                    if (sys.isEmpty()) {
                        result.addError(i + 1, "Plant system not found: " + systemCode);
                        continue;
                    }
                    hierarchyService.applyMainFunctionParent(mf, AssetHierarchyService.SCOPE_SYSTEM, sys.get().getId());
                } else if (!isEmpty(locationCode)) {
                    Optional<Location> loc = locationRepository.findByCode(locationCode);
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
        finishImport(stats, result);
        return result;
    }

    // ── SubFunction ───────────────────────────────────────────────────────────
    // Columns: code | name | tag | parentSubFunctionCode | mainFunctionCode | systemCode | locationCode
    public ImportResult importSubFunctions(MultipartFile file) throws IOException {
        ImportStats stats = new ImportStats("sub-functions", file);
        ImportResult result = new ImportResult();
        try (Workbook wb = new XSSFWorkbook(file.getInputStream())) {
            Sheet sheet = wb.getSheetAt(0);
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

                String code = cellStr(row, 0);
                String name = cellStr(row, 1);
                if (isEmpty(code) || isEmpty(name)) {
                    result.addError(i + 1, "Code and name are required.");
                    continue;
                }

                String tag = cellStr(row, 2);
                String parentSfCode = cellStr(row, 3);
                String mfCode = cellStr(row, 4);
                String systemCode = cellStr(row, 5);
                String locationCode = cellStr(row, 6);

                long now = System.currentTimeMillis();
                SubFunction sf = new SubFunction();
                sf.setCode(code);
                sf.setName(name);
                sf.setTag(tag);
                sf.setCreatedAt(now);
                sf.setUpdatedAt(now);

                if (!isEmpty(parentSfCode)) {
                    Optional<SubFunction> parent = subFunctionRepository.findByCode(parentSfCode);
                    if (parent.isEmpty()) {
                        result.addError(i + 1, "Parent sub function not found: " + parentSfCode);
                        continue;
                    }
                    hierarchyService.applySubFunctionParent(
                            sf, AssetHierarchyService.SCOPE_SUB_FUNCTION, parent.get().getId());
                } else if (!isEmpty(mfCode)) {
                    Optional<MainFunction> mf = mainFunctionRepository.findByCode(mfCode);
                    if (mf.isEmpty()) {
                        result.addError(i + 1, "Main function not found: " + mfCode);
                        continue;
                    }
                    hierarchyService.applySubFunctionParent(sf, AssetHierarchyService.SCOPE_MAIN_FUNCTION, mf.get().getId());
                } else if (!isEmpty(systemCode)) {
                    Optional<PlantSystem> sys = plantSystemRepository.findByCode(systemCode);
                    if (sys.isEmpty()) {
                        result.addError(i + 1, "Plant system not found: " + systemCode);
                        continue;
                    }
                    hierarchyService.applySubFunctionParent(sf, AssetHierarchyService.SCOPE_SYSTEM, sys.get().getId());
                } else if (!isEmpty(locationCode)) {
                    Optional<Location> loc = locationRepository.findByCode(locationCode);
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
        finishImport(stats, result);
        return result;
    }

    // ── AssetEntry ────────────────────────────────────────────────────────────
    // Columns: assetCode | assetName | nfcTagId | subFunctionCode | className
    public ImportResult importAssetEntries(MultipartFile file) throws IOException {
        ImportStats stats = new ImportStats("asset-entries", file);
        ImportResult result = new ImportResult();
        try (Workbook wb = new XSSFWorkbook(file.getInputStream())) {
            Sheet sheet = wb.getSheetAt(0);
            stats.sheetRows = sheet.getLastRowNum();
            businessEventLogger.importStarted(stats.entityType, stats.fileName, stats.fileSize, stats.sheetRows);
            log.info("[IMPORT] ExcelImportService.importAssetEntries file={} sheetRows={} → AssetEntryRepository.save",
                    stats.fileName, stats.sheetRows);

            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (isBlankRow(row, 5)) {
                    stats.blankSkipped++;
                    continue;
                }
                stats.rowsRead++;

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
                if (!assetEntryService.isAssetCodeAvailable(assetCode)) {
                    result.addError(i + 1, "Duplicate asset code: " + assetCode);
                    continue;
                }

                String nfcTagId = cellStr(row, 2);
                String sfCode = cellStr(row, 3);
                Long subFunctionId = null;
                SubFunction subFunction = null;
                if (!isEmpty(sfCode)) {
                    Optional<SubFunction> sf = subFunctionRepository.findByCode(sfCode);
                    if (sf.isEmpty()) {
                        result.addError(i + 1, "Sub function not found: " + sfCode);
                        continue;
                    }
                    subFunctionId = sf.get().getId();
                    subFunction = sf.get();
                }

                String className = cellStr(row, 4);
                Long classId = null;
                if (!isEmpty(className)) {
                    Optional<AssetClass> ac = assetClassRepository.findByName(className);
                    if (ac.isEmpty()) {
                        result.addError(i + 1, "Asset class not found: " + className);
                        continue;
                    }
                    classId = ac.get().getId();
                }

                long now = System.currentTimeMillis();
                AssetEntry ae = new AssetEntry();
                ae.setAssetCode(assetCode.trim());
                ae.setAssetName(assetName);
                ae.setNfcTagId(isEmpty(nfcTagId) ? null : nfcTagId.trim());
                ae.setSubFunctionId(subFunctionId);
                ae.setClassId(classId);
                ae.setCreatedAt(now);
                ae.setUpdatedAt(now);
                assetEntryService.prepareForImport(ae);

                if (ae.getNfcTagId() != null && !assetEntryService.isNfcAvailable(ae.getNfcTagId())) {
                    result.addError(i + 1, "Duplicate NFC tag: " + ae.getNfcTagId());
                    continue;
                }
                if (ae.getNfcTagId() == null && subFunction != null) {
                    log.debug("[IMPORT] asset-entries row={} NFC auto from subFunction tag={}", i + 1, subFunction.getTag());
                }

                assetEntryRepository.save(ae);
                result.addSuccess();
            }
        }
        finishImport(stats, result);
        return result;
    }

    // ── Users ─────────────────────────────────────────────────────────────────
    public ImportResult importUsers(MultipartFile file) throws IOException {
        ImportStats stats = new ImportStats("users", file);
        ImportResult result = new ImportResult();
        try (Workbook wb = new XSSFWorkbook(file.getInputStream())) {
            Sheet sheet = wb.getSheetAt(0);
            stats.sheetRows = sheet.getLastRowNum();
            businessEventLogger.importStarted(stats.entityType, stats.fileName, stats.fileSize, stats.sheetRows);
            log.info("[IMPORT] ExcelImportService.importUsers file={} sheetRows={} → UserRepository.save",
                    stats.fileName, stats.sheetRows);

            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (ExcelUtils.isBlankRow(row, 6)) {
                    stats.blankSkipped++;
                    continue;
                }
                stats.rowsRead++;

                String username = cellStr(row, 0);
                String fullName = cellStr(row, 1);
                String password = cellStr(row, 2);
                String authTypeStr = cellStr(row, 3);
                String activeStr = cellStr(row, 4);
                String roleCodes = cellStr(row, 5);

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
                user.setFullName(fullName != null ? fullName.trim() : null);
                try {
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
        finishImport(stats, result);
        return result;
    }

    @Transactional
    public ImportResult importOperationalUnits(MultipartFile file) throws IOException {
        ImportStats stats = new ImportStats("operational-units", file);
        ImportResult result = new ImportResult();
        List<UnitImportRow> rows = new ArrayList<>();

        try (Workbook wb = new XSSFWorkbook(file.getInputStream())) {
            Sheet sheet = wb.getSheetAt(0);
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
            finishImport(stats, result);
            return result;
        }
        if (rows.isEmpty()) {
            finishImport(stats, result);
            return result;
        }

        Set<String> availableCodes = operationalUnitRepository.findAll().stream()
                .map(OperationalUnit::getCode)
                .filter(c -> c != null && !c.isBlank())
                .collect(Collectors.toCollection(HashSet::new));
        Set<String> seenInFile = new HashSet<>();

        for (UnitImportRow row : rows) {
            if (availableCodes.contains(row.code) || seenInFile.contains(row.code)) {
                result.addError(row.rowNum, "Duplicate unit code: " + row.code);
                continue;
            }
            if (row.parentCode != null && !availableCodes.contains(row.parentCode)) {
                result.addError(row.rowNum,
                        "Parent unit not found before this row (check row order): " + row.parentCode);
                continue;
            }
            seenInFile.add(row.code);
            availableCodes.add(row.code);
        }

        if (result.hasErrors()) {
            result.clearSuccessCount();
            finishImport(stats, result);
            return result;
        }

        Map<String, Long> codeToId = operationalUnitRepository.findAll().stream()
                .filter(u -> u.getCode() != null && !u.getCode().isBlank())
                .collect(Collectors.toMap(OperationalUnit::getCode, OperationalUnit::getId, (a, b) -> a));

        long now = System.currentTimeMillis();
        for (UnitImportRow row : rows) {
            Long parentId = row.parentCode != null ? codeToId.get(row.parentCode) : null;
            OperationalUnit unit = new OperationalUnit();
            unit.setCode(row.code);
            unit.setName(row.name);
            unit.setParentId(parentId);
            unit.setCreatedAt(now);
            unit.setUpdatedAt(now);
            OperationalUnit saved = operationalUnitRepository.save(unit);
            codeToId.put(row.code, saved.getId());
            result.addSuccess();
        }
        finishImport(stats, result);
        return result;
    }

    @Transactional
    public ImportResult importUnitStaff(MultipartFile file) throws IOException {
        ImportStats stats = new ImportStats("unit-staff", file);
        ImportResult result = new ImportResult();
        List<StaffImportRow> rows = new ArrayList<>();

        try (Workbook wb = new XSSFWorkbook(file.getInputStream())) {
            Sheet sheet = wb.getSheetAt(0);
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

                String unitCode = cellStr(row, 0);
                String roleType = cellStr(row, 1);
                String username = cellStr(row, 2);

                if (ExcelUtils.isEmpty(unitCode) || ExcelUtils.isEmpty(roleType) || ExcelUtils.isEmpty(username)) {
                    result.addError(i + 1, "Unit code, role type and username are required.");
                    continue;
                }

                Optional<OperationalUnit> unit = operationalUnitRepository.findByCode(unitCode.trim());
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
        finishImport(stats, result);
        return result;
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
        int sheetRows;
        int rowsRead;
        int blankSkipped;

        ImportStats(String entityType, MultipartFile file) {
            this.entityType = entityType;
            this.fileName = file.getOriginalFilename() != null ? file.getOriginalFilename() : "unknown";
            this.fileSize = file.getSize();
        }
    }
}
