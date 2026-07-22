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
import com.hnp.backendofflinefirst.repository.LogSheetEntryRepository;
import com.hnp.backendofflinefirst.repository.LogSheetRepository;
import com.hnp.backendofflinefirst.repository.LogSheetTemplateRepository;
import com.hnp.backendofflinefirst.repository.SubFunctionRepository;
import com.hnp.backendofflinefirst.domain.FieldDefinitionSnapshot;
import com.hnp.backendofflinefirst.dto.ScopedAssetPreviewRow;
import com.hnp.backendofflinefirst.logging.BusinessEventLogger;
import com.hnp.backendofflinefirst.util.AssetNfcSupport;
import com.hnp.backendofflinefirst.util.ReferenceLabelService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZoneId;
import java.util.ArrayList;
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
    private final AssetHierarchyService hierarchyService;
    private final LogSheetActionLogger actionLogger;
    private final BusinessEventLogger businessEventLogger;
    private final ReferenceLabelService referenceLabelService;
    private final LogSheetFieldDefinitionsService fieldDefinitionsService;

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
     * missed run), the sheet is still pre-populated with scoped assets and marked
     * {@code EXPIRED} so the missed run stays on record.
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
        if (template.getClassId() != null) {
            List<FieldDefinitionSnapshot> snapshot = fieldDefinitionsService.captureSnapshot(template.getClassId());
            sheet.setFieldDefinitionsSnapshot(snapshot);
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
     * Scheduler entry point. Advances the template cursor from {@code nextRunAt} through due
     * occurrence boundaries.
     * <p>
     * {@code maxBackfill} controls catch-up after an outage (per template):
     * <ul>
     *   <li>{@code <= 0}: skip the entire backlog without creating sheets; park {@code nextRunAt}
     *       on the first future boundary. If only a single live occurrence is due (no backlog
     *       behind it), that one sheet is still generated.</li>
     *   <li>{@code > 0}: create up to that many missed occurrences (oldest first); any remainder
     *       is skipped and the cursor jumps to the next future boundary.</li>
     * </ul>
     */
    @Transactional
    public void runScheduled(LogSheetTemplate template, long now, int maxBackfill) {
        RecurrenceUnit unit = template.getRecurrenceUnit();
        int every = template.getRecurrenceEvery() != null ? template.getRecurrenceEvery() : 1;
        long boundary = template.getNextRunAt() != null ? template.getNextRunAt() : now;

        if (maxBackfill <= 0) {
            boundary = resumeFromNowWithoutBackfill(template, unit, every, boundary, now);
        } else {
            int count = 0;
            while (boundary <= now && count < maxBackfill) {
                generateAt(template, GenerationMode.SCHEDULED, null, boundary, now);
                boundary = unit.advance(boundary, every, ZONE);
                count++;
            }
            if (boundary <= now) {
                // Outage longer than the cap: skip the remaining backlog to the next future boundary.
                while (boundary <= now) {
                    boundary = unit.advance(boundary, every, ZONE);
                }
                log.warn("Template {} exceeded back-fill cap ({}); skipped remaining missed runs",
                        template.getId(), maxBackfill);
            }
        }

        template.setLastRunAt(now);
        template.setNextRunAt(boundary);
        templateRepository.save(template);
    }

    /**
     * {@code maxBackfill <= 0}: do not materialize historical missed runs.
     * <ul>
     *   <li>If several occurrences are already due, skip all of them and leave the cursor on the
     *       first future boundary (resume from now onward).</li>
     *   <li>If exactly one occurrence is due (the live tick), generate that sheet.</li>
     * </ul>
     */
    private long resumeFromNowWithoutBackfill(LogSheetTemplate template, RecurrenceUnit unit,
                                              int every, long boundary, long now) {
        boolean skippedBacklog = false;
        // While the *next* boundary is also already due, the current one is pure backlog.
        while (unit.advance(boundary, every, ZONE) <= now) {
            boundary = unit.advance(boundary, every, ZONE);
            skippedBacklog = true;
        }
        if (skippedBacklog) {
            // Also drop the last past-due boundary; park on the first future slot.
            if (boundary <= now) {
                boundary = unit.advance(boundary, every, ZONE);
            }
            log.info("Template {} resumed schedule without back-fill; nextRunAt={}",
                    template.getId(), boundary);
            return boundary;
        }
        if (boundary <= now) {
            // Single live due occurrence — generate it even when back-fill is disabled.
            generateAt(template, GenerationMode.SCHEDULED, null, boundary, now);
            return unit.advance(boundary, every, ZONE);
        }
        return boundary;
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

    private List<AssetEntry> resolveScopedAssets(LogSheetTemplate template) {
        if (template.getScopeType() == null || template.getScopeId() == null || template.getClassId() == null) {
            return List.of();
        }
        return hierarchyService.findAssetsInScope(
                template.getScopeType(), template.getScopeId(), template.getClassId());
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
