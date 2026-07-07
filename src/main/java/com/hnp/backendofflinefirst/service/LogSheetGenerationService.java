package com.hnp.backendofflinefirst.service;

import com.hnp.backendofflinefirst.domain.ActionSource;
import com.hnp.backendofflinefirst.domain.GenerationMode;
import com.hnp.backendofflinefirst.domain.LogSheetActionType;
import com.hnp.backendofflinefirst.domain.LogSheetStatus;
import com.hnp.backendofflinefirst.domain.RecurrenceUnit;
import com.hnp.backendofflinefirst.entity.AssetEntry;
import com.hnp.backendofflinefirst.entity.LogSheet;
import com.hnp.backendofflinefirst.entity.LogSheetEntry;
import com.hnp.backendofflinefirst.entity.LogSheetTemplate;
import com.hnp.backendofflinefirst.entity.SubFunction;
import com.hnp.backendofflinefirst.repository.AssetEntryRepository;
import com.hnp.backendofflinefirst.repository.LogSheetEntryRepository;
import com.hnp.backendofflinefirst.repository.LogSheetRepository;
import com.hnp.backendofflinefirst.repository.LogSheetTemplateRepository;
import com.hnp.backendofflinefirst.repository.SubFunctionRepository;
import com.hnp.backendofflinefirst.dto.ScopedAssetPreviewRow;
import com.hnp.backendofflinefirst.logging.BusinessEventLogger;
import com.hnp.backendofflinefirst.util.AssetNfcSupport;
import com.hnp.backendofflinefirst.util.ReferenceLabelService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Creates log sheets from templates — either manually (on demand) or by the
 * scheduler. Each generated sheet starts as {@link LogSheetStatus#PENDING},
 * carries a {@code dueAt} derived from the template's completion window, and is
 * pre-populated with one entry per asset resolved from the template scope.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LogSheetGenerationService {

    private final LogSheetRepository logSheetRepository;
    private final LogSheetEntryRepository logSheetEntryRepository;
    private final LogSheetTemplateRepository templateRepository;
    private final SubFunctionRepository subFunctionRepository;
    private final AssetEntryRepository assetEntryRepository;
    private final AssetHierarchyService hierarchyService;
    private final LogSheetActionLogger actionLogger;
    private final BusinessEventLogger businessEventLogger;
    private final ReferenceLabelService referenceLabelService;

    private static final ZoneId ZONE = ZoneId.of("Asia/Tehran");

    /** Manual/on-demand generation: the sheet's occurrence time is "now". */
    @Transactional
    public LogSheet generateFromTemplate(LogSheetTemplate template, GenerationMode origin,
                                         Long actorUserId, long now) {
        return generateAt(template, origin, actorUserId, now, now);
    }

    /**
     * Generates a single log sheet for a given occurrence time.
     * <p>If the occurrence's completion window has already elapsed (a back-filled
     * missed run), the sheet is created empty and immediately {@code EXPIRED} so it
     * is on record as "generated but not completed / nobody picked it up".
     *
     * @param occurrenceAt the scheduled time this sheet represents (createdAt/dueAt anchor)
     * @param now          current server time
     */
    @Transactional
    public LogSheet generateAt(LogSheetTemplate template, GenerationMode origin,
                               Long actorUserId, long occurrenceAt, long now) {
        if (Boolean.FALSE.equals(template.getActive())) {
            throw new IllegalStateException("Template is inactive: " + template.getName());
        }
        Long dueAt = computeDueAt(template, occurrenceAt);
        boolean alreadyOverdue = dueAt != null && dueAt <= now;

        LogSheet sheet = new LogSheet();
        sheet.setTemplateId(template.getId());
        sheet.setTemplateName(template.getName());
        sheet.setScopeSummary(buildScopeSummary(template));
        sheet.setOperationalUnitId(template.getOperationalUnitId());
        sheet.setOrigin(origin);
        sheet.setCreatedAt(occurrenceAt);
        sheet.setUpdatedAt(now);
        sheet.setDueAt(dueAt);
        sheet.setStatus(alreadyOverdue ? LogSheetStatus.EXPIRED : LogSheetStatus.PENDING);
        if (alreadyOverdue) {
            sheet.setExpiredAt(now);
        }
        logSheetRepository.save(sheet);

        prepopulateEntries(sheet.getId(), template);

        ActionSource source = origin == GenerationMode.SCHEDULED ? ActionSource.SERVER : ActionSource.WEB;
        actionLogger.record(sheet.getId(), LogSheetActionType.GENERATE, source,
                actorUserId, null, null, occurrenceAt, null);
        if (alreadyOverdue) {
            actionLogger.record(sheet.getId(), LogSheetActionType.EXPIRE, ActionSource.SERVER,
                    null, null, null, now, null);
        }
        log.info("Generated log sheet {} from template {} (origin={}, occurrenceAt={}, dueAt={}, overdue={})",
                sheet.getId(), template.getId(), origin, occurrenceAt, dueAt, alreadyOverdue);
        businessEventLogger.logSheetGenerated(sheet.getId(), template.getId(), template.getName(),
                origin != null ? origin.name() : null);
        return sheet;
    }

    /**
     * Scheduler entry point. Back-fills every missed occurrence boundary from
     * {@code nextRunAt} up to now (each recorded, empty, and expired), then leaves
     * the current live occurrence pending. A safety cap bounds long outages.
     */
    @Transactional
    public void runScheduled(LogSheetTemplate template, long now, int maxBackfill) {
        RecurrenceUnit unit = template.getRecurrenceUnit();
        int every = template.getRecurrenceEvery() != null ? template.getRecurrenceEvery() : 1;
        long boundary = template.getNextRunAt() != null ? template.getNextRunAt() : now;

        int count = 0;
        while (boundary <= now && count < maxBackfill) {
            generateAt(template, GenerationMode.SCHEDULED, null, boundary, now);
            boundary = unit.advance(boundary, every, ZONE);
            count++;
        }
        if (count >= maxBackfill && boundary <= now) {
            // Outage longer than the cap: skip the remaining backlog to the next future boundary.
            while (boundary <= now) {
                boundary = unit.advance(boundary, every, ZONE);
            }
            log.warn("Template {} exceeded back-fill cap ({}); skipped remaining missed runs", template.getId(), maxBackfill);
        }
        template.setLastRunAt(now);
        template.setNextRunAt(boundary);
        templateRepository.save(template);
    }

    private Long computeDueAt(LogSheetTemplate template, long from) {
        Integer window = template.getCompletionWindowMinutes();
        if (window == null || window <= 0) return null;
        return from + window * 60_000L;
    }

    /** Creates one empty entry per asset that belongs to the template's scope. */
    private void prepopulateEntries(Long logSheetId, LogSheetTemplate template) {
        List<AssetEntry> assets = resolveScopedAssets(template);
        if (assets.isEmpty()) return;

        Set<Long> subFunctionIds = assets.stream()
                .map(AssetEntry::getSubFunctionId)
                .filter(id -> id != null)
                .collect(Collectors.toSet());
        Map<Long, SubFunction> subFunctionsById = subFunctionIds.isEmpty()
                ? Map.of()
                : subFunctionRepository.findAllById(subFunctionIds).stream()
                        .collect(Collectors.toMap(SubFunction::getId, sf -> sf));

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
            logSheetEntryRepository.save(entry);
        }
    }

    private List<AssetEntry> resolveScopedAssets(LogSheetTemplate template) {
        if (template.getScopeType() == null || template.getScopeId() == null || template.getClassId() == null) {
            return List.of();
        }
        Set<Long> subFunctionIds = hierarchyService.subFunctionIdsInScope(
                template.getScopeType(), template.getScopeId());
        if (subFunctionIds.isEmpty()) {
            return List.of();
        }
        Long classId = template.getClassId();
        return assetEntryRepository.findAll().stream()
                .filter(a -> classId.equals(a.getClassId()))
                .filter(a -> a.getSubFunctionId() != null && subFunctionIds.contains(a.getSubFunctionId()))
                .toList();
    }

    /** Lists assets that would be included when generating a sheet from this template (preview only). */
    public List<ScopedAssetPreviewRow> listAssetsInScope(LogSheetTemplate template) {
        List<AssetEntry> assets = resolveScopedAssets(template);
        if (assets.isEmpty()) return List.of();

        Set<Long> subFunctionIds = assets.stream()
                .map(AssetEntry::getSubFunctionId)
                .filter(id -> id != null)
                .collect(Collectors.toSet());
        Map<Long, SubFunction> subFunctionsById = subFunctionIds.isEmpty()
                ? Map.of()
                : subFunctionRepository.findAllById(subFunctionIds).stream()
                        .collect(Collectors.toMap(SubFunction::getId, sf -> sf));

        return assets.stream()
                .map(a -> {
                    SubFunction sf = a.getSubFunctionId() != null
                            ? subFunctionsById.get(a.getSubFunctionId()) : null;
                    return new ScopedAssetPreviewRow(
                            a.getAssetCode(),
                            a.getAssetName(),
                            AssetNfcSupport.effectiveNfcTag(a, sf),
                            sf != null ? sf.getCode() : null,
                            sf != null ? sf.getTag() : null
                    );
                })
                .toList();
    }

    private String buildScopeSummary(LogSheetTemplate template) {
        if (template.getScopeType() == null || template.getScopeId() == null) return null;
        return template.getScopeType() + ":" + template.getScopeId();
    }

    /** Human-readable scope for display (hierarchy + asset class). */
    public String buildScopeDisplaySummary(LogSheetTemplate template) {
        return referenceLabelService.templateScopeDisplayLabel(
                template.getScopeType(), template.getScopeId(), template.getClassId());
    }
}
