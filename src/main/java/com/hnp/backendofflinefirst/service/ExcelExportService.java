package com.hnp.backendofflinefirst.service;

import com.hnp.backendofflinefirst.entity.*;
import com.hnp.backendofflinefirst.repository.*;
import com.hnp.backendofflinefirst.util.DateUtils;
import com.hnp.backendofflinefirst.ui.FaMessages;
import com.hnp.backendofflinefirst.util.ExcelUtils;
import com.hnp.backendofflinefirst.util.ReferenceLabelService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ExcelExportService {

    private final AppSettingsService appSettingsService;
    private final DateUtils dateUtils;
    private final ReferenceLabelService labels;
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final UserRoleRepository userRoleRepository;
    private final OperationalUnitRepository operationalUnitRepository;
    private final LocationRepository locationRepository;
    private final PlantSystemRepository plantSystemRepository;
    private final MainFunctionRepository mainFunctionRepository;
    private final SubFunctionRepository subFunctionRepository;
    private final AssetClassRepository assetClassRepository;
    private final AssetEntryRepository assetEntryRepository;
    private final FieldDefinitionRepository fieldDefinitionRepository;
    private final LogSheetTemplateService logSheetTemplateService;
    private final DataRecordRepository dataRecordRepository;
    private final LogSheetAccessService logSheetAccessService;
    private final AssetReportService assetReportService;

    public void exportUsers(HttpServletResponse response) throws IOException {
        Map<Long, String> roleCodesByUser = roleCodesByUserId();
        List<String[]> rows = userRepository.findAll(exportPage()).getContent().stream()
                .map(u -> new String[]{
                        str(u.getId()),
                        u.getUsername(),
                        u.getFullName(),
                        u.getNationalCode(),
                        u.getPhoneNumber(),
                        u.getNfcTagId(),
                        u.getAuthType() != null ? u.getAuthType().name() : UserAuthType.LOCAL.name(),
                        u.isActive() ? "true" : "false",
                        roleCodesByUser.getOrDefault(u.getId(), ""),
                        dateUtils.format(u.getCreatedAt()),
                        dateUtils.format(u.getUpdatedAt())
                })
                .toList();
        write(response, "users-export.xlsx", "users",
                new String[]{"id", "username", "fullName", "nationalCode", "phoneNumber", "nfcTag",
                        "authType", "active", "roleCodes", "createdAt", "updatedAt"}, rows);
    }

    public void exportRoles(HttpServletResponse response) throws IOException {
        List<String[]> rows = roleRepository.findAll(exportPage()).getContent().stream()
                .map(r -> new String[]{
                        str(r.getId()),
                        r.getCode(),
                        r.getName(),
                        r.getDescription(),
                        r.isSystemRole() ? "true" : "false",
                        dateUtils.format(r.getCreatedAt()),
                        dateUtils.format(r.getUpdatedAt())
                })
                .toList();
        write(response, "roles-export.xlsx", "roles",
                new String[]{"id", "code", "name", "description", "systemRole", "createdAt", "updatedAt"}, rows);
    }

    public void exportOperationalUnits(HttpServletResponse response) throws IOException {
        List<OperationalUnit> units = operationalUnitRepository.findAll(exportPage()).getContent();
        Set<Long> parentIds = units.stream().map(OperationalUnit::getParentId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, String> codeById = loadUnitCodes(parentIds);
        for (OperationalUnit u : units) {
            if (u.getCode() != null) {
                codeById.putIfAbsent(u.getId(), u.getCode());
            }
        }
        List<String[]> rows = units.stream()
                .map(u -> new String[]{
                        str(u.getId()),
                        u.getCode(),
                        u.getName(),
                        u.getParentId() != null ? codeById.getOrDefault(u.getParentId(), str(u.getParentId())) : "",
                        dateUtils.format(u.getCreatedAt()),
                        dateUtils.format(u.getUpdatedAt())
                })
                .toList();
        write(response, "operational-units-export.xlsx", "operational-units",
                new String[]{"id", "code", "name", "parentCode", "createdAt", "updatedAt"}, rows);
    }

    public void exportLocations(HttpServletResponse response) throws IOException {
        List<Location> locations = locationRepository.findAll(exportPage()).getContent();
        Map<Long, String> locationCodes = loadLocationCodes(locations.stream().map(Location::getParentId).collect(Collectors.toSet()));
        for (Location l : locations) {
            if (l.getCode() != null) locationCodes.putIfAbsent(l.getId(), l.getCode());
        }
        Map<Long, String> unitCodes = loadUnitCodes(locations.stream().map(Location::getUnitId).collect(Collectors.toSet()));
        List<String[]> rows = locations.stream()
                .map(l -> new String[]{
                        str(l.getId()),
                        l.getCode(),
                        l.getName(),
                        l.getParentId() != null ? locationCodes.getOrDefault(l.getParentId(), "") : "",
                        l.getUnitId() != null ? unitCodes.getOrDefault(l.getUnitId(), "") : "",
                        dateUtils.format(l.getCreatedAt())
                })
                .toList();
        write(response, "locations-export.xlsx", "locations",
                new String[]{"id", "code", "name", "parentCode", "unitCode", "createdAt"}, rows);
    }

    public void exportPlantSystems(HttpServletResponse response) throws IOException {
        List<PlantSystem> systems = plantSystemRepository.findAll(exportPage()).getContent();
        Map<Long, String> systemCodes = loadPlantSystemCodes(systems.stream().map(PlantSystem::getParentId).collect(Collectors.toSet()));
        for (PlantSystem ps : systems) {
            if (ps.getCode() != null) systemCodes.putIfAbsent(ps.getId(), ps.getCode());
        }
        Map<Long, String> locationCodes = loadLocationCodes(systems.stream().map(PlantSystem::getLocationId).collect(Collectors.toSet()));
        List<String[]> rows = systems.stream()
                .map(ps -> new String[]{
                        str(ps.getId()),
                        ps.getCode(),
                        ps.getName(),
                        ps.getParentId() != null ? systemCodes.getOrDefault(ps.getParentId(), "") : "",
                        ps.getLocationId() != null ? locationCodes.getOrDefault(ps.getLocationId(), "") : "",
                        dateUtils.format(ps.getCreatedAt())
                })
                .toList();
        write(response, "plant-systems-export.xlsx", "plant-systems",
                new String[]{"id", "code", "name", "parentSystemCode", "locationCode", "createdAt"}, rows);
    }

    public void exportMainFunctions(HttpServletResponse response) throws IOException {
        List<MainFunction> items = mainFunctionRepository.findAll(exportPage()).getContent();
        Map<Long, String> mfCodes = loadMainFunctionCodes(items.stream().map(MainFunction::getParentId).collect(Collectors.toSet()));
        for (MainFunction mf : items) {
            if (mf.getCode() != null) mfCodes.putIfAbsent(mf.getId(), mf.getCode());
        }
        Map<Long, String> systemCodes = loadPlantSystemCodes(items.stream().map(MainFunction::getSystemId).collect(Collectors.toSet()));
        Map<Long, String> locationCodes = loadLocationCodes(items.stream().map(MainFunction::getLocationId).collect(Collectors.toSet()));
        List<String[]> rows = items.stream()
                .map(mf -> new String[]{
                        str(mf.getId()),
                        mf.getCode(),
                        mf.getName(),
                        mf.getParentId() != null ? mfCodes.getOrDefault(mf.getParentId(), "") : "",
                        mf.getSystemId() != null ? systemCodes.getOrDefault(mf.getSystemId(), "") : "",
                        mf.getLocationId() != null ? locationCodes.getOrDefault(mf.getLocationId(), "") : "",
                        dateUtils.format(mf.getCreatedAt())
                })
                .toList();
        write(response, "main-functions-export.xlsx", "main-functions",
                new String[]{"id", "code", "name", "parentMainFunctionCode", "systemCode", "locationCode", "createdAt"}, rows);
    }

    public void exportSubFunctions(HttpServletResponse response) throws IOException {
        List<SubFunction> items = subFunctionRepository.findAll(exportPage()).getContent();
        Map<Long, String> sfCodes = loadSubFunctionCodes(items.stream().map(SubFunction::getParentId).collect(Collectors.toSet()));
        for (SubFunction sf : items) {
            if (sf.getCode() != null) sfCodes.putIfAbsent(sf.getId(), sf.getCode());
        }
        Map<Long, String> mfCodes = loadMainFunctionCodes(items.stream().map(SubFunction::getMainFunctionId).collect(Collectors.toSet()));
        Map<Long, String> systemCodes = loadPlantSystemCodes(items.stream().map(SubFunction::getSystemId).collect(Collectors.toSet()));
        Map<Long, String> locationCodes = loadLocationCodes(items.stream().map(SubFunction::getLocationId).collect(Collectors.toSet()));
        List<String[]> rows = items.stream()
                .map(sf -> new String[]{
                        str(sf.getId()),
                        sf.getCode(),
                        sf.getName(),
                        sf.getTag(),
                        sf.getParentId() != null ? sfCodes.getOrDefault(sf.getParentId(), "") : "",
                        sf.getMainFunctionId() != null ? mfCodes.getOrDefault(sf.getMainFunctionId(), "") : "",
                        sf.getSystemId() != null ? systemCodes.getOrDefault(sf.getSystemId(), "") : "",
                        sf.getLocationId() != null ? locationCodes.getOrDefault(sf.getLocationId(), "") : "",
                        dateUtils.format(sf.getCreatedAt())
                })
                .toList();
        write(response, "sub-functions-export.xlsx", "sub-functions",
                new String[]{"id", "code", "name", "tag", "parentSubFunctionCode", "mainFunctionCode", "systemCode", "locationCode", "createdAt"}, rows);
    }

    public void exportAssetClasses(HttpServletResponse response) throws IOException {
        List<String[]> rows = assetClassRepository.findAll(exportPage()).getContent().stream()
                .map(ac -> new String[]{
                        str(ac.getId()),
                        ac.getName(),
                        dateUtils.format(ac.getCreatedAt()),
                        dateUtils.format(ac.getUpdatedAt())
                })
                .toList();
        write(response, "asset-classes-export.xlsx", "asset-classes",
                new String[]{"id", "name", "createdAt", "updatedAt"}, rows);
    }

    public void exportAssetEntries(HttpServletResponse response) throws IOException {
        List<AssetEntry> entries = assetEntryRepository.findAll(exportPage()).getContent();
        Map<Long, String> sfCodes = loadSubFunctionCodes(entries.stream().map(AssetEntry::getSubFunctionId).collect(Collectors.toSet()));
        Set<Long> classIds = entries.stream().map(AssetEntry::getClassId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, String> classNames = classIds.isEmpty() ? Map.of()
                : assetClassRepository.findAllById(classIds).stream()
                        .collect(Collectors.toMap(AssetClass::getId, AssetClass::getName, (a, b) -> a));
        List<String[]> rows = entries.stream()
                .map(ae -> new String[]{
                        str(ae.getId()),
                        ae.getAssetCode(),
                        ae.getNfcTagId(),
                        ae.getAssetName(),
                        ae.getSubFunctionId() != null ? sfCodes.getOrDefault(ae.getSubFunctionId(), "") : "",
                        ae.getClassId() != null ? classNames.getOrDefault(ae.getClassId(), "") : "",
                        ae.isActive() ? "true" : "false",
                        dateUtils.format(ae.getCreatedAt())
                })
                .toList();
        write(response, "asset-entries-export.xlsx", "asset-entries",
                new String[]{"id", "assetCode", "nfcTagId", "assetName", "subFunctionCode", "className", "active", "createdAt"}, rows);
    }

    public void exportAssetInventoryReport(HttpServletResponse response) throws IOException {
        int max = appSettingsService.getExcelExportMaxRows();
        List<String[]> rows = assetReportService.buildAssetInventoryForExport(max).stream()
                .map(row -> new String[]{
                        row.getAssetCode(),
                        row.getAssetName(),
                        row.getNfcTagId(),
                        row.getLocationCode(),
                        row.getSystemCode(),
                        row.getMainFunctionCode(),
                        row.getSubFunctionCode(),
                        row.getClassName()
                })
                .toList();
        write(response, "asset-inventory-report.xlsx", "asset-inventory",
                new String[]{"assetCode", "assetName", "nfcTagId", "locationCode", "systemCode",
                        "mainFunctionCode", "subFunctionCode", "className"}, rows);
    }

    public void exportFieldDefinitions(Long classId, HttpServletResponse response) throws IOException {
        String className = assetClassRepository.findById(classId).map(AssetClass::getName).orElse(String.valueOf(classId));
        List<String[]> rows = fieldDefinitionRepository.findByClassIdOrderByIdDesc(classId).stream()
                .map(fd -> new String[]{
                        str(fd.getId()),
                        str(fd.getOrder()),
                        fd.getKey(),
                        fd.getLabel(),
                        fd.getDataType(),
                        fd.getUnit(),
                        fd.isRequired() ? "true" : "false"
                })
                .toList();
        write(response, "fields-" + className + "-export.xlsx", "fields",
                new String[]{"id", "order", "key", "label", "dataType", "unit", "required"}, rows);
    }

    public void exportLogSheetTemplates(HttpServletResponse response) throws IOException {
        List<String[]> rows = logSheetTemplateService.findVisible(null, exportPage()).getContent().stream()
                .map(t -> new String[]{
                        str(t.getId()),
                        t.getName(),
                        t.getScopeType(),
                        labels.scopeLabel(t.getScopeType(), t.getScopeId()),
                        labels.assetClassLabel(t.getClassId()),
                        labels.operationalUnitLabel(t.getOperationalUnitId()),
                        bool(t.getActive()),
                        t.getGenerationMode() != null ? t.getGenerationMode().name() : "",
                        bool(t.getScheduleActive()),
                        str(t.getRecurrenceEvery()),
                        t.getRecurrenceUnit() != null ? t.getRecurrenceUnit().name() : "",
                        str(t.getCompletionWindowMinutes()),
                        dateUtils.format(t.getNextRunAt())
                })
                .toList();
        write(response, "log-sheet-templates-export.xlsx", "templates",
                new String[]{"id", "name", "scopeType", "scopeLabel", "assetClass", "operationalUnit", "active", "generationMode",
                        "scheduleActive", "recurrenceEvery", "recurrenceUnit", "completionWindowMinutes", "nextRunAt"}, rows);
    }

    public void exportLogSheets(String statusFilter, HttpServletResponse response) throws IOException {
        List<String[]> rows = logSheetAccessService.findVisibleLogSheets(statusFilter, null, exportPage()).getContent().stream()
                .map(ls -> new String[]{
                        str(ls.getId()),
                        ls.getTemplateName(),
                        ls.getScopeSummary(),
                        labels.operationalUnitLabel(ls.getOperationalUnitId()),
                        ls.getStatus() != null ? ls.getStatus().name() : "",
                        labels.userDisplayName(ls.getAssigneeUserId()),
                        dateUtils.format(ls.getDueAt()),
                        dateUtils.format(ls.getCreatedAt())
                })
                .toList();
        write(response, "log-sheets-export.xlsx", "log-sheets",
                new String[]{"id", "templateName", "scopeSummary", "operationalUnit", "status", "assignee", "dueAt", "createdAt"}, rows);
    }

    public void exportRecords(HttpServletResponse response) throws IOException {
        List<String[]> rows = dataRecordRepository.findAll(exportPage()).getContent().stream()
                .map(r -> new String[]{
                        str(r.getId()),
                        r.getLocalId(),
                        r.getNfcTagId(),
                        r.getAssetName(),
                        r.getRecordStatus(),
                        r.getSyncStatus(),
                        r.getOperatorName(),
                        r.getLocation(),
                        dateUtils.format(r.getCreatedAt())
                })
                .toList();
        write(response, "records-export.xlsx", "records",
                new String[]{"id", "localId", "nfcTagId", "assetName", "recordStatus", "syncStatus", "operatorName", "location", "createdAt"}, rows);
    }

    public void exportMyInbox(Long userId, HttpServletResponse response) throws IOException {
        List<String[]> rows = new ArrayList<>();
        logSheetAccessService.findAssignedTo(userId).forEach(ls ->
                rows.add(inboxRow("assigned", ls)));
        logSheetAccessService.findAvailablePool(userId).forEach(ls ->
                rows.add(inboxRow("available", ls)));
        write(response, "my-inbox-export.xlsx", "inbox",
                new String[]{"listType", "id", "templateName", "scopeSummary", "status", "dueAt", "createdAt"}, rows);
    }

    private String[] inboxRow(String listType, LogSheet ls) {
        return new String[]{
                listType,
                str(ls.getId()),
                ls.getTemplateName(),
                ls.getScopeSummary(),
                ls.getStatus() != null ? ls.getStatus().name() : "",
                dateUtils.format(ls.getDueAt()),
                dateUtils.format(ls.getCreatedAt())
        };
    }

    private Map<Long, String> roleCodesByUserId() {
        Map<Long, String> roleCodeById = roleRepository.findAll().stream()
                .collect(Collectors.toMap(Role::getId, Role::getCode));
        Map<Long, List<String>> codes = new HashMap<>();
        for (UserRole ur : userRoleRepository.findAll()) {
            String code = roleCodeById.get(ur.getRoleId());
            if (code != null) {
                codes.computeIfAbsent(ur.getUserId(), k -> new ArrayList<>()).add(code);
            }
        }
        return codes.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> String.join(",", e.getValue())));
    }

    private Pageable exportPage() {
        int max = appSettingsService.getExcelExportMaxRows();
        // +1 so ExcelUtils can detect truncation without loading the full table
        return PageRequest.of(0, Math.max(1, max) + 1, Sort.by(Sort.Direction.DESC, "id"));
    }

    private Map<Long, String> loadLocationCodes(Set<Long> ids) {
        if (ids == null || ids.isEmpty()) return new HashMap<>();
        return locationRepository.findAllById(ids).stream()
                .filter(l -> l.getCode() != null)
                .collect(Collectors.toMap(Location::getId, Location::getCode, (a, b) -> a, HashMap::new));
    }

    private Map<Long, String> loadUnitCodes(Set<Long> ids) {
        if (ids == null || ids.isEmpty()) return new HashMap<>();
        return operationalUnitRepository.findAllById(ids).stream()
                .filter(u -> u.getCode() != null)
                .collect(Collectors.toMap(OperationalUnit::getId, OperationalUnit::getCode, (a, b) -> a, HashMap::new));
    }

    private Map<Long, String> loadPlantSystemCodes(Set<Long> ids) {
        if (ids == null || ids.isEmpty()) return new HashMap<>();
        return plantSystemRepository.findAllById(ids).stream()
                .filter(ps -> ps.getCode() != null)
                .collect(Collectors.toMap(PlantSystem::getId, PlantSystem::getCode, (a, b) -> a, HashMap::new));
    }

    private Map<Long, String> loadMainFunctionCodes(Set<Long> ids) {
        if (ids == null || ids.isEmpty()) return new HashMap<>();
        return mainFunctionRepository.findAllById(ids).stream()
                .filter(mf -> mf.getCode() != null)
                .collect(Collectors.toMap(MainFunction::getId, MainFunction::getCode, (a, b) -> a, HashMap::new));
    }

    private Map<Long, String> loadSubFunctionCodes(Set<Long> ids) {
        if (ids == null || ids.isEmpty()) return new HashMap<>();
        return subFunctionRepository.findAllById(ids).stream()
                .filter(sf -> sf.getCode() != null)
                .collect(Collectors.toMap(SubFunction::getId, SubFunction::getCode, (a, b) -> a, HashMap::new));
    }

    private void write(HttpServletResponse response, String filename, String sheetName,
                       String[] headers, List<String[]> rows) throws IOException {
        int max = appSettingsService.getExcelExportMaxRows();
        ExcelUtils.writeWorkbook(response, filename, sheetName, headers, rows, max,
                FaMessages.exportTruncated(max));
    }

    private static String str(Object o) {
        return o != null ? String.valueOf(o) : "";
    }

    private static String bool(Boolean b) {
        return Boolean.TRUE.equals(b) ? "true" : "false";
    }
}
