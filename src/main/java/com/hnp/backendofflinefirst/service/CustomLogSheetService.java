package com.hnp.backendofflinefirst.service;

import com.hnp.backendofflinefirst.domain.ActionSource;
import com.hnp.backendofflinefirst.domain.AssetScanMode;
import com.hnp.backendofflinefirst.domain.FieldDefinitionSnapshot;
import com.hnp.backendofflinefirst.domain.GenerationMode;
import com.hnp.backendofflinefirst.domain.LogSheetSizeLimits;
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
import com.hnp.backendofflinefirst.util.DateUtils;
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
    private final LogSheetSizeLimits sizeLimits;
    private final OperationalUnitScopeService scopeService;
    private final LogSheetFieldDefinitionsService fieldDefinitionsService;
    private final LogSheetActionLogger actionLogger;
    private final BusinessEventLogger businessEventLogger;
    private final LogSheetCategoryService categoryService;

    /**
     * Whether this actor may still edit <b>this</b> round's own questions — for the buttons.
     *
     * <p>Four conditions, each refusing for its own reason; {@link #assertCanEditOwnQuestions}
     * is the same four as a refusal, and the two are deliberately adjacent so neither can grow
     * a condition the other lacks.
     *
     * <ol>
     *   <li><b>A custom sheet only.</b> A template-raised round inherits its questions and
     *       freezes them at generation; a second editor pointing at the same round would be two
     *       owners of one list.</li>
     *   <li><b>Not finished.</b> A terminal round has been delivered — re-wording what it asked
     *       is re-describing work already done. This also covers a round with no answers at all
     *       (voided, cancelled): it is still over.</li>
     *   <li><b>Not yet frozen.</b> A custom sheet resolves its questions live until the first
     *       answer arrives, and freezes them at that moment. After the freeze an edit here
     *       could not reach the round anyway — so it is refused honestly instead of accepted
     *       and silently ignored.</li>
     *   <li><b>A unit the actor may raise custom rounds for</b>, checked exactly as
     *       {@link #createCustom} checks it. The permission reaches every supervisor; the unit
     *       is what narrows it to their own plant.</li>
     * </ol>
     */
    public boolean canEditOwnQuestions(LogSheet sheet, Long actorUserId) {
        return sheet != null
                && sheet.getTemplateId() == null
                && (sheet.getStatus() == null || !sheet.getStatus().isTerminal())
                && sheet.getSheetFieldDefinitionsSnapshot() == null
                && isWithinCustomScope(sheet.getOperationalUnitId(), actorUserId);
    }

    /**
     * The same four conditions, as a refusal, on every write.
     *
     * <p>English, like every other refusal in this layer; {@code ErrorTranslator} carries the
     * Persian. A service that spoke Persian directly would put the only copy of a user-facing
     * sentence somewhere the UI layer cannot reword it.
     */
    public void assertCanEditOwnQuestions(LogSheet sheet, Long actorUserId) {
        if (sheet == null) {
            throw new IllegalArgumentException("Log sheet is required.");
        }
        if (sheet.getTemplateId() != null) {
            throw new IllegalStateException(
                    "A template-raised log sheet gets its questions from its template.");
        }
        if (sheet.getStatus() != null && sheet.getStatus().isTerminal()) {
            throw new IllegalStateException(
                    "This log sheet is finished; its questions can no longer be changed.");
        }
        if (sheet.getSheetFieldDefinitionsSnapshot() != null) {
            throw new IllegalStateException(
                    "This log sheet's questions are already answered and the list is final.");
        }
        if (!isWithinCustomScope(sheet.getOperationalUnitId(), actorUserId)) {
            throw new AccessDeniedException(
                    "You may only edit questions on custom log sheets for units you supervise.");
        }
    }

    /**
     * The unit half of the custom-sheet rule, in one place.
     *
     * <p>An unrestricted administrator passes everything; a unit-scoped supervisor passes their
     * <b>whole branch</b>, which is what {@code getSupervisorScopeUnitIds} returns and what
     * {@link #createCustom} has always accepted.
     */
    private boolean isWithinCustomScope(Long unitId, Long actorUserId) {
        if (!SecurityUtils.isUnitScopedOnly()) {
            return true;
        }
        return unitId != null && scopeService.getSupervisorScopeUnitIds(actorUserId).contains(unitId);
    }

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
        return createCustom(unitId, name, dueAt, assetIds, false, actorUserId, now);
    }

    /**
     * @param withoutAssets the supervisor is deliberately raising a round with <b>no equipment
     *                      in it</b> — a questionnaire, a handover, an area check — and will put
     *                      its questions on it next. The flag exists for the same reason
     *                      {@link com.hnp.backendofflinefirst.domain.AssetSelectionMode#NONE}
     *                      does: a year later nothing can tell "I meant no assets" from "the
     *                      picker was empty and nobody noticed", and only one of those is worth
     *                      accepting.
     */
    @Transactional
    public LogSheet createCustom(Long unitId, String name, Long dueAt,
                                 List<Long> assetIds, boolean withoutAssets,
                                 Long actorUserId, long now) {
        return createCustom(unitId, name, dueAt, assetIds, withoutAssets, null, actorUserId, now);
    }

    /**
     * @param scanMode how the operator must identify each asset — null means {@link
     *                 AssetScanMode#NFC}, which is what every round was before V11. A round with
     *                 <b>no</b> assets ignores it entirely: there is nothing to scan, and the
     *                 service stores {@link AssetScanMode#NONE} for it rather than recording a
     *                 requirement that can never be met.
     */
    @Transactional
    public LogSheet createCustom(Long unitId, String name, Long dueAt,
                                 List<Long> assetIds, boolean withoutAssets,
                                 AssetScanMode scanMode,
                                 Long actorUserId, long now) {
        return createCustom(unitId, name, dueAt, assetIds, withoutAssets, scanMode, null, actorUserId, now);
    }

    /**
     * @param categoryId the round's category (V19, roadmap §28), or null. A custom sheet has no
     *                   template to inherit one from, so the form chooses it, and only an
     *                   <b>active</b> category may be chosen — the same rule as a new template.
     */
    @Transactional
    public LogSheet createCustom(Long unitId, String name, Long dueAt,
                                 List<Long> assetIds, boolean withoutAssets,
                                 AssetScanMode scanMode, Long categoryId,
                                 Long actorUserId, long now) {
        String trimmedName = name != null ? name.trim() : "";
        if (trimmedName.isEmpty()) {
            throw new IllegalArgumentException("Log sheet name is required.");
        }
        if (unitId == null) {
            throw new IllegalArgumentException("Operational unit is required for a custom log sheet.");
        }
        Set<Long> distinctAssetIds = assetIds == null ? Set.of()
                : assetIds.stream().filter(id -> id != null).collect(Collectors.toCollection(LinkedHashSet::new));
        if (distinctAssetIds.isEmpty() && !withoutAssets) {
            throw new IllegalArgumentException("Select at least one asset for the custom log sheet.");
        }
        if (!distinctAssetIds.isEmpty() && withoutAssets) {
            // Both would be a form that contradicts itself, and the quiet resolutions — keeping
            // the assets, or dropping them — are each wrong half the time.
            throw new IllegalArgumentException(
                    "A custom log sheet is either without assets or has them, not both.");
        }
        DateUtils.requireFutureWithinYears(dueAt, now, "Custom log sheet due date");

        // A unit-scoped supervisor may only create sheets for units they supervise. Shared with
        // the question editor through one helper, so granting a unit cannot mean one thing when
        // the round is raised and another when its questions are written.
        if (!isWithinCustomScope(unitId, actorUserId)) {
            throw new AccessDeniedException("You may only create custom log sheets for units you supervise.");
        }

        // Re-validate the selection server-side: every asset must be active and in unit scope.
        // Skipped entirely for a round with no equipment in it — there is nothing to validate,
        // and the query would be an IN () with an empty list.
        List<AssetEntry> assets = distinctAssetIds.isEmpty()
                ? List.of()
                : assetEntryRepository.findVisibleActiveByIdInAndUnitIds(Set.of(unitId), distinctAssetIds);
        if (assets.size() != distinctAssetIds.size()) {
            throw new IllegalArgumentException("Some selected assets are not available in this operational unit.");
        }
        // Same ceiling as a template, refused for the same reason: a supervisor is at the
        // picker right now and can drop assets, which is cheaper than discovering the sheet is
        // unworkable when an operator opens it in the field.
        sizeLimits.requireWithinMax(assets.size());
        // Before anything is written: a retired or unknown category is refused with a sentence.
        Long assignableCategoryId = categoryService.requireAssignable(categoryId, null);

        LogSheet sheet = new LogSheet();
        sheet.setTemplateId(null);
        sheet.setTemplateName(trimmedName);
        sheet.setScopeSummary(null);
        sheet.setOperationalUnitId(unitId);
        sheet.setOrigin(GenerationMode.MANUAL);
        sheet.setStatus(LogSheetStatus.PENDING);
        // A questions-only round has nothing to scan, so NONE is the only honest value — storing
        // «NFC» there would have the tablet offer a scan button for a round with no asset behind
        // any tag, and the operator would be left pressing it.
        sheet.setScanMode(distinctAssetIds.isEmpty()
                ? AssetScanMode.NONE
                : AssetScanMode.orDefault(scanMode));
        sheet.setCreatedAt(now);
        sheet.setUpdatedAt(now);
        sheet.setDueAt(dueAt);
        categoryService.freezeOnto(sheet, assignableCategoryId);

        Set<Long> classIds = assets.stream()
                .map(AssetEntry::getClassId)
                .filter(id -> id != null)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        List<FieldDefinitionSnapshot> snapshot = fieldDefinitionsService.captureSnapshot(classIds);
        if (!snapshot.isEmpty()) {
            sheet.setFieldDefinitionsSnapshot(snapshot);
        }
        // The per-asset bands, frozen beside the definitions. A hand-picked sheet is exactly
        // where re-banded assets are most likely to be gathered deliberately.
        sheet.setFieldOverridesSnapshot(fieldDefinitionsService.captureOverridesSnapshot(assets));
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
            // Copied verbatim: the chip serial belongs to the asset, never to the sub-function.
            entry.setNfcSerial(asset.getNfcSerial());
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
