package com.hnp.backendofflinefirst.service;

import com.hnp.backendofflinefirst.dto.ImportResult;
import com.hnp.backendofflinefirst.entity.*;
import com.hnp.backendofflinefirst.repository.*;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ExcelImportService {

    private final LocationRepository locationRepository;
    private final PlantSystemRepository plantSystemRepository;
    private final MainFunctionRepository mainFunctionRepository;
    private final SubFunctionRepository subFunctionRepository;
    private final AssetEntryRepository assetEntryRepository;
    private final AssetClassRepository assetClassRepository;
    private final OperationalUnitRepository operationalUnitRepository;

    // ── Location ──────────────────────────────────────────────────────────────
    // Columns: code | name | parentCode | parentName | unitCode | unitName
    public ImportResult importLocations(MultipartFile file) throws IOException {
        ImportResult result = new ImportResult();
        try (Workbook wb = new XSSFWorkbook(file.getInputStream())) {
            Sheet sheet = wb.getSheetAt(0);
            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (isBlankRow(row)) continue;

                String code = cellStr(row, 0);
                String name = cellStr(row, 1);
                if (isEmpty(code) || isEmpty(name)) {
                    result.addError(i + 1, "کد و نام اجباری هستند");
                    continue;
                }

                String parentCode = cellStr(row, 2);
                String parentName = cellStr(row, 3);
                String parentId = null;
                if (!isEmpty(parentCode) || !isEmpty(parentName)) {
                    Optional<Location> parent = !isEmpty(parentCode)
                            ? locationRepository.findByCode(parentCode)
                            : locationRepository.findByName(parentName);
                    if (parent.isEmpty()) {
                        result.addError(i + 1, "مکان والد یافت نشد: " + coalesce(parentCode, parentName));
                        continue;
                    }
                    parentId = parent.get().getId();
                }

                String unitCode = cellStr(row, 4);
                String unitName = cellStr(row, 5);
                String unitId = null;
                if (!isEmpty(unitCode) || !isEmpty(unitName)) {
                    Optional<OperationalUnit> unit = !isEmpty(unitCode)
                            ? operationalUnitRepository.findByCode(unitCode)
                            : operationalUnitRepository.findByName(unitName);
                    if (unit.isEmpty()) {
                        result.addError(i + 1, "واحد عملیاتی یافت نشد: " + coalesce(unitCode, unitName));
                        continue;
                    }
                    unitId = unit.get().getId();
                }

                long now = System.currentTimeMillis();
                Location loc = new Location();
                loc.setId(UUID.randomUUID().toString());
                loc.setCode(code);
                loc.setName(name);
                loc.setParentId(parentId);
                loc.setUnitId(unitId);
                loc.setCreatedAt(now);
                loc.setUpdatedAt(now);
                locationRepository.save(loc);
                result.addSuccess();
            }
        }
        return result;
    }

    // ── PlantSystem ───────────────────────────────────────────────────────────
    // Columns: code | name | locationCode | locationName
    public ImportResult importPlantSystems(MultipartFile file) throws IOException {
        ImportResult result = new ImportResult();
        try (Workbook wb = new XSSFWorkbook(file.getInputStream())) {
            Sheet sheet = wb.getSheetAt(0);
            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (isBlankRow(row)) continue;

                String code = cellStr(row, 0);
                String name = cellStr(row, 1);
                if (isEmpty(code) || isEmpty(name)) {
                    result.addError(i + 1, "کد و نام اجباری هستند");
                    continue;
                }

                String locationCode = cellStr(row, 2);
                String locationName = cellStr(row, 3);
                String locationId = null;
                if (!isEmpty(locationCode) || !isEmpty(locationName)) {
                    Optional<Location> loc = !isEmpty(locationCode)
                            ? locationRepository.findByCode(locationCode)
                            : locationRepository.findByName(locationName);
                    if (loc.isEmpty()) {
                        result.addError(i + 1, "مکان یافت نشد: " + coalesce(locationCode, locationName));
                        continue;
                    }
                    locationId = loc.get().getId();
                }

                long now = System.currentTimeMillis();
                PlantSystem ps = new PlantSystem();
                ps.setId(UUID.randomUUID().toString());
                ps.setCode(code);
                ps.setName(name);
                ps.setLocationId(locationId);
                ps.setCreatedAt(now);
                ps.setUpdatedAt(now);
                plantSystemRepository.save(ps);
                result.addSuccess();
            }
        }
        return result;
    }

    // ── MainFunction ──────────────────────────────────────────────────────────
    // Columns: code | name | systemCode | systemName | locationCode | locationName
    // Parent priority: system > location (system's locationId auto-fills locationId)
    public ImportResult importMainFunctions(MultipartFile file) throws IOException {
        ImportResult result = new ImportResult();
        try (Workbook wb = new XSSFWorkbook(file.getInputStream())) {
            Sheet sheet = wb.getSheetAt(0);
            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (isBlankRow(row)) continue;

                String code = cellStr(row, 0);
                String name = cellStr(row, 1);
                if (isEmpty(code) || isEmpty(name)) {
                    result.addError(i + 1, "کد و نام اجباری هستند");
                    continue;
                }

                String systemCode = cellStr(row, 2);
                String systemName = cellStr(row, 3);
                String locationCode = cellStr(row, 4);
                String locationName = cellStr(row, 5);

                String systemId = null;
                String locationId = null;

                if (!isEmpty(systemCode) || !isEmpty(systemName)) {
                    Optional<PlantSystem> sys = !isEmpty(systemCode)
                            ? plantSystemRepository.findByCode(systemCode)
                            : plantSystemRepository.findByName(systemName);
                    if (sys.isEmpty()) {
                        result.addError(i + 1, "سیستم واحد یافت نشد: " + coalesce(systemCode, systemName));
                        continue;
                    }
                    systemId = sys.get().getId();
                    locationId = sys.get().getLocationId();
                } else if (!isEmpty(locationCode) || !isEmpty(locationName)) {
                    Optional<Location> loc = !isEmpty(locationCode)
                            ? locationRepository.findByCode(locationCode)
                            : locationRepository.findByName(locationName);
                    if (loc.isEmpty()) {
                        result.addError(i + 1, "مکان یافت نشد: " + coalesce(locationCode, locationName));
                        continue;
                    }
                    locationId = loc.get().getId();
                }

                long now = System.currentTimeMillis();
                MainFunction mf = new MainFunction();
                mf.setId(UUID.randomUUID().toString());
                mf.setCode(code);
                mf.setName(name);
                mf.setSystemId(systemId);
                mf.setLocationId(locationId);
                mf.setCreatedAt(now);
                mf.setUpdatedAt(now);
                mainFunctionRepository.save(mf);
                result.addSuccess();
            }
        }
        return result;
    }

    // ── SubFunction ───────────────────────────────────────────────────────────
    // Columns: code | name | tag | mainFunctionCode | mainFunctionName | systemCode | systemName | locationCode | locationName
    // Parent priority: mainFunction > system > location
    public ImportResult importSubFunctions(MultipartFile file) throws IOException {
        ImportResult result = new ImportResult();
        try (Workbook wb = new XSSFWorkbook(file.getInputStream())) {
            Sheet sheet = wb.getSheetAt(0);
            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (isBlankRow(row)) continue;

                String code = cellStr(row, 0);
                String name = cellStr(row, 1);
                if (isEmpty(code) || isEmpty(name)) {
                    result.addError(i + 1, "کد و نام اجباری هستند");
                    continue;
                }

                String tag = cellStr(row, 2);
                String mfCode = cellStr(row, 3);
                String mfName = cellStr(row, 4);
                String systemCode = cellStr(row, 5);
                String systemName = cellStr(row, 6);
                String locationCode = cellStr(row, 7);
                String locationName = cellStr(row, 8);

                String mainFunctionId = null;
                String systemId = null;
                String locationId = null;

                if (!isEmpty(mfCode) || !isEmpty(mfName)) {
                    Optional<MainFunction> mf = !isEmpty(mfCode)
                            ? mainFunctionRepository.findByCode(mfCode)
                            : mainFunctionRepository.findByName(mfName);
                    if (mf.isEmpty()) {
                        result.addError(i + 1, "تابع اصلی یافت نشد: " + coalesce(mfCode, mfName));
                        continue;
                    }
                    mainFunctionId = mf.get().getId();
                    systemId = mf.get().getSystemId();
                    locationId = mf.get().getLocationId();
                } else if (!isEmpty(systemCode) || !isEmpty(systemName)) {
                    Optional<PlantSystem> sys = !isEmpty(systemCode)
                            ? plantSystemRepository.findByCode(systemCode)
                            : plantSystemRepository.findByName(systemName);
                    if (sys.isEmpty()) {
                        result.addError(i + 1, "سیستم واحد یافت نشد: " + coalesce(systemCode, systemName));
                        continue;
                    }
                    systemId = sys.get().getId();
                    locationId = sys.get().getLocationId();
                } else if (!isEmpty(locationCode) || !isEmpty(locationName)) {
                    Optional<Location> loc = !isEmpty(locationCode)
                            ? locationRepository.findByCode(locationCode)
                            : locationRepository.findByName(locationName);
                    if (loc.isEmpty()) {
                        result.addError(i + 1, "مکان یافت نشد: " + coalesce(locationCode, locationName));
                        continue;
                    }
                    locationId = loc.get().getId();
                }

                long now = System.currentTimeMillis();
                SubFunction sf = new SubFunction();
                sf.setId(UUID.randomUUID().toString());
                sf.setCode(code);
                sf.setName(name);
                sf.setTag(tag);
                sf.setMainFunctionId(mainFunctionId);
                sf.setSystemId(systemId);
                sf.setLocationId(locationId);
                sf.setCreatedAt(now);
                sf.setUpdatedAt(now);
                subFunctionRepository.save(sf);
                result.addSuccess();
            }
        }
        return result;
    }

    // ── AssetEntry ────────────────────────────────────────────────────────────
    // Columns: nfcTagId | assetName | subFunctionCode | subFunctionName | className
    public ImportResult importAssetEntries(MultipartFile file) throws IOException {
        ImportResult result = new ImportResult();
        try (Workbook wb = new XSSFWorkbook(file.getInputStream())) {
            Sheet sheet = wb.getSheetAt(0);
            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (isBlankRow(row)) continue;

                String nfcTagId = cellStr(row, 0);
                String assetName = cellStr(row, 1);
                if (isEmpty(assetName)) {
                    result.addError(i + 1, "نام دارایی اجباری است");
                    continue;
                }

                String sfCode = cellStr(row, 2);
                String sfName = cellStr(row, 3);
                String subFunctionId = null;
                if (!isEmpty(sfCode) || !isEmpty(sfName)) {
                    Optional<SubFunction> sf = !isEmpty(sfCode)
                            ? subFunctionRepository.findByCode(sfCode)
                            : subFunctionRepository.findByName(sfName);
                    if (sf.isEmpty()) {
                        result.addError(i + 1, "تابع فرعی یافت نشد: " + coalesce(sfCode, sfName));
                        continue;
                    }
                    subFunctionId = sf.get().getId();
                }

                String className = cellStr(row, 4);
                String classId = null;
                if (!isEmpty(className)) {
                    Optional<AssetClass> ac = assetClassRepository.findByName(className);
                    if (ac.isEmpty()) {
                        result.addError(i + 1, "کلاس دارایی یافت نشد: " + className);
                        continue;
                    }
                    classId = ac.get().getId();
                }

                long now = System.currentTimeMillis();
                AssetEntry ae = new AssetEntry();
                ae.setId(UUID.randomUUID().toString());
                ae.setNfcTagId(isEmpty(nfcTagId) ? null : nfcTagId);
                ae.setAssetName(assetName);
                ae.setSubFunctionId(subFunctionId);
                ae.setClassId(classId);
                ae.setCreatedAt(now);
                ae.setUpdatedAt(now);
                assetEntryRepository.save(ae);
                result.addSuccess();
            }
        }
        return result;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String cellStr(Row row, int col) {
        if (row == null) return null;
        Cell cell = row.getCell(col, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
        if (cell == null) return null;
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue().trim();
            case NUMERIC -> {
                double v = cell.getNumericCellValue();
                yield v == Math.floor(v) ? String.valueOf((long) v) : String.valueOf(v);
            }
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            default -> null;
        };
    }

    private boolean isEmpty(String s) {
        return s == null || s.isBlank();
    }

    private boolean isBlankRow(Row row) {
        if (row == null) return true;
        for (int c = 0; c < 6; c++) {
            String v = cellStr(row, c);
            if (!isEmpty(v)) return false;
        }
        return true;
    }

    private String coalesce(String a, String b) {
        return !isEmpty(a) ? a : b;
    }
}
