package com.hnp.backendofflinefirst.service;

import com.hnp.backendofflinefirst.dto.LogSheetBundleDto;
import com.hnp.backendofflinefirst.dto.LogSheetContextDto;
import com.hnp.backendofflinefirst.dto.LogSheetEntryDto;
import com.hnp.backendofflinefirst.entity.AssetClass;
import com.hnp.backendofflinefirst.entity.AssetEntry;
import com.hnp.backendofflinefirst.entity.FieldDefinition;
import com.hnp.backendofflinefirst.entity.Location;
import com.hnp.backendofflinefirst.entity.LogSheet;
import com.hnp.backendofflinefirst.entity.LogSheetEntry;
import com.hnp.backendofflinefirst.entity.LogSheetTemplate;
import com.hnp.backendofflinefirst.entity.MainFunction;
import com.hnp.backendofflinefirst.entity.PlantSystem;
import com.hnp.backendofflinefirst.entity.SubFunction;
import com.hnp.backendofflinefirst.mapper.LogSheetEntryMapper;
import com.hnp.backendofflinefirst.repository.AssetClassRepository;
import com.hnp.backendofflinefirst.repository.AssetEntryRepository;
import com.hnp.backendofflinefirst.repository.LocationRepository;
import com.hnp.backendofflinefirst.repository.LogSheetEntryRepository;
import com.hnp.backendofflinefirst.repository.LogSheetTemplateRepository;
import com.hnp.backendofflinefirst.repository.MainFunctionRepository;
import com.hnp.backendofflinefirst.repository.PlantSystemRepository;
import com.hnp.backendofflinefirst.repository.SubFunctionRepository;
import com.hnp.backendofflinefirst.util.ReferenceLabelService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Builds self-contained mobile payloads for individual log sheets, including
 * entries and the minimal hierarchy / field-definition slice needed offline.
 */
@Service
@RequiredArgsConstructor
public class LogSheetBundleService {

    private final LogSheetAccessService logSheetAccessService;
    private final LogSheetEntryRepository logSheetEntryRepository;
    private final LogSheetTemplateRepository templateRepository;
    private final AssetHierarchyService hierarchyService;
    private final SubFunctionRepository subFunctionRepository;
    private final MainFunctionRepository mainFunctionRepository;
    private final PlantSystemRepository plantSystemRepository;
    private final LocationRepository locationRepository;
    private final AssetEntryRepository assetEntryRepository;
    private final AssetClassRepository assetClassRepository;
    private final LogSheetFieldDefinitionsService fieldDefinitionsService;
    private final ReferenceLabelService referenceLabelService;

    public LogSheetBundleDto buildFullBundle(Long logSheetId) {
        LogSheet sheet = logSheetAccessService.requireVisibleLogSheet(logSheetId);
        return buildFullBundle(sheet);
    }

    public LogSheetBundleDto buildFullBundle(LogSheet sheet) {
        List<LogSheetEntry> rawEntries = logSheetEntryRepository.findByLogSheetId(sheet.getId());
        List<LogSheetEntryDto> entries = rawEntries.stream()
                .map(LogSheetEntryMapper::toDto)
                .toList();
        LogSheetContextDto context = buildContext(sheet, rawEntries);
        return LogSheetBundleDto.builder()
                .sheet(sheet)
                .entries(entries)
                .context(context)
                .build();
    }

    public LogSheetBundleDto buildMetadataOnly(LogSheet sheet) {
        return LogSheetBundleDto.builder()
                .sheet(sheet)
                .entries(List.of())
                .context(null)
                .build();
    }

    private LogSheetContextDto buildContext(LogSheet sheet, List<LogSheetEntry> rawEntries) {
        LogSheetTemplate template = sheet.getTemplateId() != null
                ? templateRepository.findById(sheet.getTemplateId()).orElse(null)
                : null;

        Set<Long> assetIds = rawEntries.stream()
                .map(LogSheetEntry::getAssetId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        List<AssetEntry> assetEntries = assetIds.isEmpty()
                ? List.of()
                : sortedById(assetEntryRepository.findAllById(assetIds), AssetEntry::getId);

        // Prefer SFs of assets actually on the sheet (matches generation/class filter).
        // Fall back to full template scope only for empty sheets so hierarchy labels still work.
        Set<Long> subFunctionIds = resolveSubFunctionIds(template, assetEntries);
        List<SubFunction> subFunctions = subFunctionIds.isEmpty()
                ? List.of()
                : sortedById(subFunctionRepository.findAllById(subFunctionIds), SubFunction::getId);

        Set<Long> mainFunctionIds = new HashSet<>();
        Set<Long> systemIds = new HashSet<>();
        Set<Long> locationIds = new HashSet<>();
        collectHierarchyIds(subFunctions, mainFunctionIds, systemIds, locationIds);
        if (template != null) {
            addScopeAnchorIds(template, mainFunctionIds, systemIds, locationIds);
        }
        expandLocationAncestors(locationIds);

        List<MainFunction> mainFunctions = mainFunctionIds.isEmpty()
                ? List.of()
                : sortedById(mainFunctionRepository.findAllById(mainFunctionIds), MainFunction::getId);
        List<PlantSystem> plantSystems = systemIds.isEmpty()
                ? List.of()
                : sortedById(plantSystemRepository.findAllById(systemIds), PlantSystem::getId);
        List<Location> locations = locationIds.isEmpty()
                ? List.of()
                : sortedById(locationRepository.findAllById(locationIds), Location::getId);

        Set<Long> classIds = rawEntries.stream()
                .map(LogSheetEntry::getClassId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (template != null && template.getClassId() != null) {
            classIds.add(template.getClassId());
        }

        List<AssetClass> assetClasses = classIds.isEmpty()
                ? List.of()
                : sortedById(assetClassRepository.findAllById(classIds), AssetClass::getId);
        List<FieldDefinition> fieldDefinitions = classIds.isEmpty()
                ? List.of()
                : fieldDefinitionsService.resolveForBundle(sheet, classIds);

        String scopeDisplayLabel = template != null
                ? referenceLabelService.templateScopeDisplayLabel(
                        template.getScopeType(), template.getScopeId(), template.getClassId())
                : sheet.getScopeSummary();

        return LogSheetContextDto.builder()
                .locations(locations)
                .plantSystems(plantSystems)
                .mainFunctions(mainFunctions)
                .subFunctions(subFunctions)
                .assetEntries(assetEntries)
                .assetClasses(assetClasses)
                .fieldDefinitions(fieldDefinitions)
                .scopeDisplayLabel(scopeDisplayLabel)
                .build();
    }

    private Set<Long> resolveSubFunctionIds(LogSheetTemplate template, List<AssetEntry> assetEntries) {
        Set<Long> fromAssets = assetEntries.stream()
                .map(AssetEntry::getSubFunctionId)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(HashSet::new));
        if (!fromAssets.isEmpty()) {
            return fromAssets;
        }
        if (template == null
                || template.getScopeType() == null
                || template.getScopeId() == null) {
            return Set.of();
        }
        return hierarchyService.subFunctionIdsInScope(template.getScopeType(), template.getScopeId());
    }

    private void collectHierarchyIds(
            List<SubFunction> subFunctions,
            Set<Long> mainFunctionIds,
            Set<Long> systemIds,
            Set<Long> locationIds) {
        for (SubFunction subFunction : subFunctions) {
            if (subFunction.getMainFunctionId() != null) {
                mainFunctionIds.add(subFunction.getMainFunctionId());
            }
            if (subFunction.getSystemId() != null) {
                systemIds.add(subFunction.getSystemId());
            }
            if (subFunction.getLocationId() != null) {
                locationIds.add(subFunction.getLocationId());
            }
        }
    }

    private void addScopeAnchorIds(
            LogSheetTemplate template,
            Set<Long> mainFunctionIds,
            Set<Long> systemIds,
            Set<Long> locationIds) {
        String scopeType = template.getScopeType();
        Long scopeId = template.getScopeId();
        if (scopeType == null || scopeId == null) {
            return;
        }
        switch (scopeType) {
            case AssetHierarchyService.SCOPE_LOCATION -> locationIds.add(scopeId);
            case AssetHierarchyService.SCOPE_SYSTEM -> {
                systemIds.addAll(hierarchyService.descendantSystemIds(scopeId));
                plantSystemRepository.findById(scopeId)
                        .map(PlantSystem::getLocationId)
                        .ifPresent(locationIds::add);
            }
            case AssetHierarchyService.SCOPE_MAIN_FUNCTION -> {
                mainFunctionIds.addAll(hierarchyService.descendantMainFunctionIds(scopeId));
                mainFunctionRepository.findById(scopeId).ifPresent(mainFunction -> {
                    if (mainFunction.getSystemId() != null) {
                        systemIds.add(mainFunction.getSystemId());
                    }
                    if (mainFunction.getLocationId() != null) {
                        locationIds.add(mainFunction.getLocationId());
                    }
                });
            }
            case AssetHierarchyService.SCOPE_SUB_FUNCTION ->
                    subFunctionRepository.findById(scopeId).ifPresent(sf -> {
                        if (sf.getMainFunctionId() != null) {
                            mainFunctionIds.add(sf.getMainFunctionId());
                        }
                        if (sf.getSystemId() != null) {
                            systemIds.add(sf.getSystemId());
                        }
                        if (sf.getLocationId() != null) {
                            locationIds.add(sf.getLocationId());
                        }
                    });
            default -> { /* ignore unknown scope types */ }
        }
    }

    private void expandLocationAncestors(Set<Long> locationIds) {
        Set<Long> pending = new HashSet<>(locationIds);
        while (!pending.isEmpty()) {
            Long id = pending.iterator().next();
            pending.remove(id);
            locationRepository.findById(id).ifPresent(location -> {
                Long parentId = location.getParentId();
                if (parentId != null && locationIds.add(parentId)) {
                    pending.add(parentId);
                }
            });
        }
    }

    private <T> List<T> sortedById(Iterable<T> items, Function<T, Long> idExtractor) {
        return java.util.stream.StreamSupport.stream(items.spliterator(), false)
                .sorted(java.util.Comparator.comparing(
                        item -> idExtractor.apply(item) != null ? idExtractor.apply(item) : 0L))
                .toList();
    }
}
