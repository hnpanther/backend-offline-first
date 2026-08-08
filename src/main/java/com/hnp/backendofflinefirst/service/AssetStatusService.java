package com.hnp.backendofflinefirst.service;

import com.hnp.backendofflinefirst.domain.AssetStatusChangeType;
import com.hnp.backendofflinefirst.domain.AssetStatusSource;
import com.hnp.backendofflinefirst.entity.AssetEntry;
import com.hnp.backendofflinefirst.entity.AssetStatusHistory;
import com.hnp.backendofflinefirst.entity.FieldDefinition;
import com.hnp.backendofflinefirst.entity.LogSheet;
import com.hnp.backendofflinefirst.entity.LogSheetEntry;
import com.hnp.backendofflinefirst.repository.AssetEntryRepository;
import com.hnp.backendofflinefirst.repository.AssetStatusHistoryRepository;
import com.hnp.backendofflinefirst.repository.LogSheetEntryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Copies a log sheet's {@code status} reading onto the asset itself, and puts it back when that
 * completion is undone.
 *
 * <h2>What this is for</h2>
 * "What state is this pump in right now" should be answerable from the asset, not by finding its
 * most recent log sheet and reading a field out of a JSON column. When a sheet is completed, any
 * class field keyed {@code status} (case-insensitively — {@code Status}, {@code STATUS} all
 * count) has its value copied to {@link AssetEntry#getStatus()}.
 *
 * <h2>Why every change is journalled</h2>
 * A completion can be undone: a supervisor voids the sheet, or reopens it for correction. The
 * asset must then go back to what it was. Re-deriving "what it was" from earlier history would
 * be slow and — the moment anything else touched the column in between — wrong. So each change
 * records its own {@code oldStatus}, and a reversal restores that exact value.
 *
 * <h2>The rule that protects live data</h2>
 * A reversal only restores when the asset still holds the value this sheet set. If something
 * else has changed it since — a later sheet, a future manual edit — the reversal is <b>skipped
 * and logged</b> rather than clobbering the newer value. Undoing an old completion must never
 * roll back a newer truth.
 *
 * <h2>Performance</h2>
 * Everything is per sheet, not per asset: one query for the entries, one for the assets, one for
 * the active history, and batch saves. A 50-asset sheet costs a constant handful of statements
 * rather than 50 round trips.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssetStatusService {

    /** The field key that drives an asset's status, matched case-insensitively. */
    public static final String STATUS_FIELD_KEY = "status";

    private final AssetEntryRepository assetEntryRepository;
    private final AssetStatusHistoryRepository historyRepository;
    private final LogSheetEntryRepository logSheetEntryRepository;
    private final LogSheetFieldDefinitionsService fieldDefinitionsService;

    /** True when this class field is the one that drives asset status. */
    public static boolean isStatusField(FieldDefinition field) {
        return field != null && isStatusKey(field.getKey());
    }

    public static boolean isStatusKey(String key) {
        return key != null && STATUS_FIELD_KEY.equalsIgnoreCase(key.trim());
    }

    /**
     * Applies the status readings of a completed sheet to its assets.
     *
     * <p>Called after the entries have been persisted, from every completion path (mobile batch,
     * web complete, and the deadline auto-submit). Safe to call for a sheet with no status field
     * at all — it simply does nothing.
     */
    @Transactional
    public int applyFromCompletedSheet(LogSheet sheet, Long actorUserId) {
        if (sheet == null || sheet.getId() == null) {
            return 0;
        }
        List<LogSheetEntry> entries = logSheetEntryRepository.findByLogSheetId(sheet.getId());
        if (entries.isEmpty()) {
            return 0;
        }

        // Resolved from the sheet's own frozen snapshot, so the field that counts is the one the
        // operator actually filled in — not whatever the class looks like today.
        Map<Long, String> statusKeyByClass = statusKeyByClass(sheet, entries);
        if (statusKeyByClass.isEmpty()) {
            return 0;
        }

        Map<Long, String> desiredByAsset = new LinkedHashMap<>();
        Map<Long, LogSheetEntry> entryByAsset = new LinkedHashMap<>();
        for (LogSheetEntry entry : entries) {
            if (entry.getAssetId() == null || entry.getFormData() == null) {
                continue;
            }
            String key = statusKeyByClass.get(entry.getClassId());
            if (key == null) {
                continue;
            }
            String value = normalise(entry.getFormData().get(key));
            if (value == null) {
                // Blank means "the operator did not record a state", which is not the same as
                // "the asset has no state". Leaving the asset alone is the only honest reading.
                continue;
            }
            desiredByAsset.put(entry.getAssetId(), value);
            entryByAsset.put(entry.getAssetId(), entry);
        }
        if (desiredByAsset.isEmpty()) {
            return 0;
        }

        long now = System.currentTimeMillis();
        List<AssetEntry> assets = assetEntryRepository.findAllById(desiredByAsset.keySet());
        List<AssetEntry> changedAssets = new ArrayList<>();
        List<AssetStatusHistory> journal = new ArrayList<>();

        for (AssetEntry asset : assets) {
            String desired = desiredByAsset.get(asset.getId());
            if (Objects.equals(asset.getStatus(), desired)) {
                // Already there. Writing an identical value would add a history row saying
                // nothing happened, which makes the history harder to read, not richer.
                continue;
            }
            AssetStatusHistory row = new AssetStatusHistory();
            row.setAssetId(asset.getId());
            row.setOldStatus(asset.getStatus());
            row.setNewStatus(desired);
            row.setChangeType(AssetStatusChangeType.APPLIED);
            row.setSource(AssetStatusSource.LOG_SHEET);
            row.setLogSheetId(sheet.getId());
            row.setLogSheetEntryId(entryByAsset.get(asset.getId()) != null
                    ? entryByAsset.get(asset.getId()).getId() : null);
            row.setFieldKey(statusKeyByClass.get(asset.getClassId()));
            row.setActorUserId(actorUserId);
            row.setChangedAt(now);
            journal.add(row);

            asset.setStatus(desired);
            asset.setUpdatedAt(now);
            changedAssets.add(asset);
        }

        if (changedAssets.isEmpty()) {
            return 0;
        }
        assetEntryRepository.saveAll(changedAssets);
        historyRepository.saveAll(journal);
        log.info("Asset status applied from log sheet {}: {} asset(s) updated", sheet.getId(),
                changedAssets.size());
        return changedAssets.size();
    }

    /**
     * Puts back what this sheet changed, for a void or a reopen.
     *
     * <p>Only reverses changes still in effect. An asset whose status has moved on since — a
     * later sheet completed, someone edited it — is left alone and logged: rolling an old
     * completion back over a newer truth would be worse than leaving the stale value.
     */
    @Transactional
    public int revertForSheet(Long logSheetId, Long actorUserId) {
        if (logSheetId == null) {
            return 0;
        }
        List<AssetStatusHistory> active = historyRepository.findActiveAppliedForSheet(logSheetId);
        if (active.isEmpty()) {
            return 0;
        }

        long now = System.currentTimeMillis();
        Set<Long> assetIds = active.stream().map(AssetStatusHistory::getAssetId)
                .collect(Collectors.toSet());
        Map<Long, AssetEntry> assetsById = assetEntryRepository.findAllById(assetIds).stream()
                .collect(Collectors.toMap(AssetEntry::getId, a -> a, (a, b) -> a));

        List<AssetEntry> changedAssets = new ArrayList<>();
        List<AssetStatusHistory> toSave = new ArrayList<>();

        for (AssetStatusHistory applied : active) {
            AssetEntry asset = assetsById.get(applied.getAssetId());
            if (asset == null) {
                // The asset is gone; nothing to restore, but the row must stop being "active"
                // or every future reversal would keep reconsidering it.
                applied.setRevertedAt(now);
                toSave.add(applied);
                continue;
            }
            if (!Objects.equals(asset.getStatus(), applied.getNewStatus())) {
                log.info("Asset {} status changed since log sheet {} set it ('{}' now, expected '{}')"
                                + " — leaving it alone rather than rolling back a newer value",
                        asset.getId(), logSheetId, asset.getStatus(), applied.getNewStatus());
                applied.setRevertedAt(now);
                toSave.add(applied);
                continue;
            }

            AssetStatusHistory reversal = new AssetStatusHistory();
            reversal.setAssetId(asset.getId());
            reversal.setOldStatus(asset.getStatus());
            reversal.setNewStatus(applied.getOldStatus());
            reversal.setChangeType(AssetStatusChangeType.REVERTED);
            reversal.setSource(AssetStatusSource.LOG_SHEET);
            reversal.setLogSheetId(logSheetId);
            reversal.setLogSheetEntryId(applied.getLogSheetEntryId());
            reversal.setFieldKey(applied.getFieldKey());
            reversal.setActorUserId(actorUserId);
            reversal.setChangedAt(now);
            toSave.add(reversal);

            applied.setRevertedAt(now);
            toSave.add(applied);

            asset.setStatus(applied.getOldStatus());
            asset.setUpdatedAt(now);
            changedAssets.add(asset);
        }

        if (!changedAssets.isEmpty()) {
            assetEntryRepository.saveAll(changedAssets);
        }
        historyRepository.saveAll(toSave);
        log.info("Asset status reverted for log sheet {}: {} asset(s) restored", logSheetId,
                changedAssets.size());
        return changedAssets.size();
    }

    /**
     * The status field key for each class on this sheet, or an empty map when none has one.
     *
     * <p>Keyed by class rather than resolved once, because a sheet can legitimately carry assets
     * of several classes and only some of them may declare a status field.
     */
    private Map<Long, String> statusKeyByClass(LogSheet sheet, List<LogSheetEntry> entries) {
        Map<Long, String> out = new LinkedHashMap<>();
        Set<Long> classIds = entries.stream()
                .map(LogSheetEntry::getClassId)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));

        for (Long classId : classIds) {
            for (FieldDefinition def : fieldDefinitionsService.resolveForClass(sheet, classId)) {
                if (isStatusField(def)) {
                    // The declared key, not the lower-cased one: the form data is keyed exactly
                    // as the class declared it, so that is what must be looked up.
                    out.put(classId, def.getKey());
                    break;
                }
            }
        }
        return out;
    }

    /**
     * A status value fit to store, or null when the reading is empty.
     *
     * <p>Trimmed and length-capped to the column: a status arriving longer than the column
     * allows is a data problem, but failing the whole completion over it would cost the
     * operator their round. Truncating loudly is the lesser harm.
     *
     * <p>A {@code multiselect} status field arrives as a collection — that is a real shape in
     * this data, not a hypothetical. Its members are joined with ", " so the asset reads
     * {@code "on, IDLE"}; {@code String.valueOf} on the list would store the Java rendering,
     * brackets and all.
     */
    private static String normalise(Object raw) {
        if (raw == null) {
            return null;
        }
        String text = raw instanceof Collection<?> c
                ? c.stream().filter(Objects::nonNull).map(o -> String.valueOf(o).trim())
                        .filter(o -> !o.isEmpty()).collect(Collectors.joining(", "))
                : String.valueOf(raw).trim();
        if (text.isEmpty()) {
            return null;
        }
        if (text.length() > 30) {
            log.warn("Asset status value '{}' exceeds the 30-character column and was truncated", text);
            return text.substring(0, 30);
        }
        return text;
    }

    /** Lower-cased key set, for callers that need to spot a status field in a raw form map. */
    public static boolean formDataHasStatus(Map<String, Object> formData) {
        if (formData == null) {
            return false;
        }
        return formData.keySet().stream()
                .filter(Objects::nonNull)
                .anyMatch(k -> STATUS_FIELD_KEY.equals(k.trim().toLowerCase(Locale.ROOT)));
    }
}
