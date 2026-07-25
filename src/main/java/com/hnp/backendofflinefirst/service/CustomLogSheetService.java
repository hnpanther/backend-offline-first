package com.hnp.backendofflinefirst.service;

import com.hnp.backendofflinefirst.domain.ActionSource;
import com.hnp.backendofflinefirst.domain.FieldDefinitionSnapshot;
import com.hnp.backendofflinefirst.domain.GenerationMode;
import com.hnp.backendofflinefirst.domain.LogSheetActionType;
import com.hnp.backendofflinefirst.domain.LogSheetStatus;
import com.hnp.backendofflinefirst.entity.AssetEntry;
import com.hnp.backendofflinefirst.entity.LogSheet;
import com.hnp.backendofflinefirst.entity.LogSheetEntry;
import com.hnp.backendofflinefirst.entity.SubFunction;
import com.hnp.backendofflinefirst.logging.BusinessEventLogger;
import com.hnp.backendofflinefirst.repository.AssetEntryRepository;
import com.hnp.backendofflinefirst.repository.LogSheetEntryRepository;
import com.hnp.backendofflinefirst.repository.LogSheetRepository;
import com.hnp.backendofflinefirst.repository.SubFunctionRepository;
import com.hnp.backendofflinefirst.security.SecurityUtils;
import com.hnp.backendofflinefirst.util.AssetNfcSupport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Creates <b>custom</b> (template-less) log sheets: a supervisor hand-picks a set of
 * assets — potentially spanning several asset classes — that belong to one of their
 * operational units, and the server materializes a {@link LogSheetStatus#PENDING}
 * sheet with one entry per selected asset.
 * <p>
 * Unlike {@link LogSheetGenerationService}, there is no template and no hierarchy scope
 * walk: the asset set is explicit. The field-definition snapshot is captured across
 * <em>all</em> selected classes so the mobile bundle and web fill form resolve every
 * asset's schema.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CustomLogSheetService {

    private final LogSheetRepository logSheetRepository;
    private final LogSheetEntryRepository logSheetEntryRepository;
    private final SubFunctionRepository subFunctionRepository;
    private final AssetEntryRepository assetEntryRepository;
    private final OperationalUnitScopeService scopeService;
    private final LogSheetFieldDefinitionsService fieldDefinitionsService;
    private final LogSheetActionLogger actionLogger;
    private final BusinessEventLogger businessEventLogger;

    /**
     * @param unitId      operational unit that owns the sheet (asset scope + visibility)
     * @param name        human-readable sheet name (stored as {@code templateName})
     * @param dueAt       optional completion deadline (epoch millis); must be in the future when set
     * @param assetIds    hand-picked assets; deduplicated, must all be active and within {@code unitId}
     * @param actorUserId supervisor creating the sheet
     * @param now         current server time
     */
    @Transactional
    public LogSheet createCustom(Long unitId, String name, Long dueAt,
                                 List<Long> assetIds, Long actorUserId, long now) {
        String trimmedName = name != null ? name.trim() : "";
        if (trimmedName.isEmpty()) {
            throw new IllegalArgumentException("Log sheet name is required.");
        }
        if (unitId == null) {
            throw new IllegalArgumentException("Operational unit is required for a custom log sheet.");
        }
        Set<Long> distinctAssetIds = assetIds == null ? Set.of()
                : assetIds.stream().filter(id -> id != null).collect(Collectors.toCollection(LinkedHashSet::new));
        if (distinctAssetIds.isEmpty()) {
            throw new IllegalArgumentException("Select at least one asset for the custom log sheet.");
        }
        if (dueAt != null && dueAt <= now) {
            throw new IllegalArgumentException("Custom log sheet due date must be in the future.");
        }

        // A unit-scoped supervisor may only create sheets for units they supervise.
        if (SecurityUtils.isUnitScopedOnly()
                && !scopeService.getSupervisorScopeUnitIds(actorUserId).contains(unitId)) {
            throw new AccessDeniedException("You may only create custom log sheets for units you supervise.");
        }

        // Re-validate the selection server-side: every asset must be active and in unit scope.
        List<AssetEntry> assets = assetEntryRepository
                .findVisibleActiveByIdInAndUnitIds(Set.of(unitId), distinctAssetIds);
        if (assets.size() != distinctAssetIds.size()) {
            throw new IllegalArgumentException("Some selected assets are not available in this operational unit.");
        }

        LogSheet sheet = new LogSheet();
        sheet.setTemplateId(null);
        sheet.setTemplateName(trimmedName);
        sheet.setScopeSummary(null);
        sheet.setOperationalUnitId(unitId);
        sheet.setOrigin(GenerationMode.MANUAL);
        sheet.setStatus(LogSheetStatus.PENDING);
        sheet.setCreatedAt(now);
        sheet.setUpdatedAt(now);
        sheet.setDueAt(dueAt);

        Set<Long> classIds = assets.stream()
                .map(AssetEntry::getClassId)
                .filter(id -> id != null)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        List<FieldDefinitionSnapshot> snapshot = fieldDefinitionsService.captureSnapshot(classIds);
        if (!snapshot.isEmpty()) {
            sheet.setFieldDefinitionsSnapshot(snapshot);
        }
        logSheetRepository.save(sheet);

        prepopulateEntries(sheet.getId(), assets);

        actionLogger.record(sheet.getId(), LogSheetActionType.GENERATE, ActionSource.WEB,
                actorUserId, null, null, now, null);
        log.info("Created custom log sheet {} (unit={}, assets={}, classes={}, actor={})",
                sheet.getId(), unitId, assets.size(), classIds.size(), actorUserId);
        businessEventLogger.logSheetGenerated(sheet.getId(), null, trimmedName, GenerationMode.MANUAL.name());
        return sheet;
    }

    /** Creates one empty entry per hand-picked asset (mirrors template generation). */
    private void prepopulateEntries(Long logSheetId, List<AssetEntry> assets) {
        Set<Long> subFunctionIds = assets.stream()
                .map(AssetEntry::getSubFunctionId)
                .filter(id -> id != null)
                .collect(Collectors.toSet());
        Map<Long, SubFunction> subFunctionsById = subFunctionIds.isEmpty()
                ? Map.of()
                : subFunctionRepository.findAllById(subFunctionIds).stream()
                        .collect(Collectors.toMap(SubFunction::getId, sf -> sf));

        List<LogSheetEntry> entries = new ArrayList<>(assets.size());
        for (AssetEntry asset : assets) {
            SubFunction sf = asset.getSubFunctionId() != null
                    ? subFunctionsById.get(asset.getSubFunctionId()) : null;
            LogSheetEntry entry = new LogSheetEntry();
            entry.setLogSheetId(logSheetId);
            entry.setAssetId(asset.getId());
            entry.setAssetName(asset.getAssetName());
            entry.setClassId(asset.getClassId());
            entry.setNfcTagId(AssetNfcSupport.effectiveNfcTag(asset, sf));
            if (sf != null) {
                entry.setSubFunctionCode(sf.getCode());
                entry.setSubFunctionTag(sf.getTag());
            }
            entry.setFormData(new HashMap<>());
            entries.add(entry);
        }
        logSheetEntryRepository.saveAll(entries);
    }
}
