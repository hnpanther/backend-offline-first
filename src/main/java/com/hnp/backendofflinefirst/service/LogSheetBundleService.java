package com.hnp.backendofflinefirst.service;

import com.hnp.backendofflinefirst.dto.AttachmentDto;
import com.hnp.backendofflinefirst.dto.LogSheetBundleDto;
import com.hnp.backendofflinefirst.dto.LogSheetContextDto;
import com.hnp.backendofflinefirst.dto.LogSheetEntryDto;
import com.hnp.backendofflinefirst.dto.NfcFaultReportDto;
import com.hnp.backendofflinefirst.entity.AssetClass;
import com.hnp.backendofflinefirst.entity.AssetEntry;
import com.hnp.backendofflinefirst.entity.FieldDefinition;
import com.hnp.backendofflinefirst.entity.Location;
import com.hnp.backendofflinefirst.entity.LogSheet;
import com.hnp.backendofflinefirst.entity.LogSheetEntry;
import com.hnp.backendofflinefirst.entity.User;
import com.hnp.backendofflinefirst.entity.LogSheetTemplate;
import com.hnp.backendofflinefirst.entity.MainFunction;
import com.hnp.backendofflinefirst.entity.PlantSystem;
import com.hnp.backendofflinefirst.entity.SubFunction;
import com.hnp.backendofflinefirst.mapper.LogSheetEntryMapper;
import com.hnp.backendofflinefirst.mapper.NfcFaultReportMapper;
import com.hnp.backendofflinefirst.repository.AssetClassRepository;
import com.hnp.backendofflinefirst.repository.AssetEntryRepository;
import com.hnp.backendofflinefirst.repository.LocationRepository;
import com.hnp.backendofflinefirst.repository.LogSheetEntryRepository;
import com.hnp.backendofflinefirst.repository.LogSheetTemplateRepository;
import com.hnp.backendofflinefirst.repository.MainFunctionRepository;
import com.hnp.backendofflinefirst.repository.NfcFaultReportRepository;
import com.hnp.backendofflinefirst.repository.UserRepository;
import com.hnp.backendofflinefirst.repository.PlantSystemRepository;
import com.hnp.backendofflinefirst.repository.SubFunctionRepository;
import com.hnp.backendofflinefirst.util.ReferenceLabelService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
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
    private final NfcFaultReportRepository nfcFaultReportRepository;
    private final AttachmentService attachmentService;
    private final UserRepository userRepository;

    public LogSheetBundleDto buildFullBundle(Long logSheetId) {
        LogSheet sheet = logSheetAccessService.requireVisibleLogSheet(logSheetId);
        return buildFullBundle(sheet);
    }

    public LogSheetBundleDto buildFullBundle(LogSheet sheet) {
        List<LogSheetEntry> rawEntries = logSheetEntryRepository.findByLogSheetId(sheet.getId());
        Map<Long, String> fillerNames = resolveFillerNames(rawEntries);
        List<LogSheetEntryDto> entries = rawEntries.stream()
                .map(entry -> {
                    LogSheetEntryDto dto = LogSheetEntryMapper.toDto(entry);
                    dto.setFilledByName(entry.getFilledByUserId() == null
                            ? null : fillerNames.get(entry.getFilledByUserId()));
                    return dto;
                })
                .toList();
        LogSheetContextDto context = buildContext(sheet, rawEntries);
        List<NfcFaultReportDto> nfcFaultReports = nfcFaultReportRepository
                .findByLogSheetIdOrderByCreatedAtDesc(sheet.getId()).stream()
                .map(NfcFaultReportMapper::toDto)
                .toList();
        List<AttachmentDto> attachments = attachmentService.findForLogSheet(sheet.getId()).stream()
                .map(AttachmentDto::from)
                .toList();

        return LogSheetBundleDto.builder()
                .sheet(sheet)
                .entries(entries)
                .context(context)
                .nfcFaultReports(nfcFaultReports)
                .attachments(attachments)
                .build();
    }

    /**
     * Display names for whoever filled these entries, in one query.
     *
     * <p>One lookup for the whole sheet rather than one per entry: a round carries up to a few
     * dozen assets and the bundle is fetched on every sync, so a per-row lookup would be a few
     * dozen queries per sheet per tablet.
     *
     * <p>Falls back to the username when the account has no full name, and omits an account
     * that no longer exists — the device then shows the row as filled by nobody, which is the
     * truth once the user is gone.
     */
    private Map<Long, String> resolveFillerNames(List<LogSheetEntry> entries) {
        Set<Long> ids = entries.stream()
                .map(LogSheetEntry::getFilledByUserId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> names = new HashMap<>();
        for (User user : userRepository.findAllById(ids)) {
            String label = user.getFullName() != null && !user.getFullName().isBlank()
                    ? user.getFullName()
                    : user.getUsername();
            names.put(user.getId(), label);
        }
        return names;
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
        // Reused below rather than re-read: the walk has already loaded every one of these.
        Map<Long, Location> locationsById = expandLocationAncestors(locationIds);

        List<MainFunction> mainFunctions = mainFunctionIds.isEmpty()
                ? List.of()
                : sortedById(mainFunctionRepository.findAllById(mainFunctionIds), MainFunction::getId);
        List<PlantSystem> plantSystems = systemIds.isEmpty()
                ? List.of()
                : sortedById(plantSystemRepository.findAllById(systemIds), PlantSystem::getId);
        // Every id in `locationIds` was loaded by the ancestor walk, so this is a lookup rather
        // than a query. An id missing from the map would mean a dangling parent reference; it is
        // skipped rather than re-fetched, because a second query here would put back the N+1 the
        // walk exists to avoid and would return nothing anyway.
        List<Location> locations = locationIds.isEmpty()
                ? List.of()
                : sortedById(locationIds.stream()
                        .map(locationsById::get)
                        .filter(Objects::nonNull)
                        .toList(), Location::getId);

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
                ? referenceLabelService.templateAssetSourceLabel(template.getAssetSelectionMode(),
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

    /**
     * Walks a location set up to its roots, and returns every row it loaded on the way.
     *
     * <h2>One query per depth level, not one per location</h2>
     *
     * <p>This used to call {@code findById} for each id in turn and then throw the rows away, so
     * the caller re-read the identical set with {@code findAllById} immediately afterwards. The
     * query count grew with the number of distinct locations multiplied by the depth of the
     * tree — on a sheet whose assets are spread across a plant, and on every bundle fetch, from
     * every tablet syncing at shift change.
     *
     * <p>Now each level is one {@code findAllById} and the rows are handed back, which removes
     * the caller's second read as well. Depth is what remains, and a location tree is a handful
     * of levels deep.
     *
     * <p>{@code locationIds.add(parentId)} is still what terminates the walk: a parent already in
     * the set is not queued again, so a cycle — which the hierarchy forbids but a bad import
     * could still produce — cannot spin here.
     *
     * @param locationIds mutated in place to include every ancestor
     * @return every location row loaded, keyed by id, so the caller need not re-read them
     */
    private Map<Long, Location> expandLocationAncestors(Set<Long> locationIds) {
        Map<Long, Location> loaded = new HashMap<>();
        Set<Long> pending = new HashSet<>(locationIds);
        while (!pending.isEmpty()) {
            Set<Long> nextLevel = new HashSet<>();
            for (Location location : locationRepository.findAllById(pending)) {
                loaded.put(location.getId(), location);
                Long parentId = location.getParentId();
                if (parentId != null && locationIds.add(parentId)) {
                    nextLevel.add(parentId);
                }
            }
            pending = nextLevel;
        }
        return loaded;
    }

    private <T> List<T> sortedById(Iterable<T> items, Function<T, Long> idExtractor) {
        return java.util.stream.StreamSupport.stream(items.spliterator(), false)
                .sorted(java.util.Comparator.comparing(
                        item -> idExtractor.apply(item) != null ? idExtractor.apply(item) : 0L))
                .toList();
    }
}
