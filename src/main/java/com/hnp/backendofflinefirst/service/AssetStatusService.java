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
 * The low-level asset-status primitives: reading a sheet's status values, and writing a status
 * onto an asset with a journal entry.
 *
 * <h2>What this no longer does</h2>
 * It used to copy a completed sheet's reading straight onto the asset and undo that when the
 * sheet was voided. That decision now belongs to {@link AssetStatusRequestService}: a reading
 * taken in the field is a claim, and a supervisor approves it. Nothing here decides whether a
 * change is allowed — it only performs one and records it.
 *
 * <h2>The one rule that stayed</h2>
 * A blank reading is ignored. Blank means "the operator did not record a state", which is not
 * the same as "the asset has no state"; raising a request to blank a status because a field was
 * skipped would be worse than useless.
 *
 * <h2>Performance</h2>
 * {@link #readingsFromSheet} is per sheet, not per asset: one query for the entries and one
 * snapshot resolution per class, so a 50-asset sheet costs a constant handful of statements.
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
     * The status each asset on this sheet was recorded as, keyed by asset id.
     *
     * <p>Only assets whose class declares a {@code status} field and whose reading is non-blank
     * appear. Blank means "the operator did not record a state", which is not the same as "the
     * asset has no state" — leaving it out is the only honest reading.
     *
     * <p>The field is resolved from the sheet's own frozen snapshot, so what counts is the form
     * the operator actually filled in, not whatever the class looks like today.
     */
    @Transactional(readOnly = true)
    public List<SheetStatusReading> readingsFromSheet(LogSheet sheet) {
        if (sheet == null || sheet.getId() == null) {
            return List.of();
        }
        List<LogSheetEntry> entries = logSheetEntryRepository.findByLogSheetId(sheet.getId());
        if (entries.isEmpty()) {
            return List.of();
        }
        Map<Long, String> statusKeyByClass = statusKeyByClass(sheet, entries);
        if (statusKeyByClass.isEmpty()) {
            return List.of();
        }

        List<SheetStatusReading> readings = new ArrayList<>();
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
                continue;
            }
            // The device time of the operator's last edit to this entry — when the reading was
            // actually taken. Falls back to creation, then to nothing at all for legacy rows.
            Long recordedAt = entry.getUpdatedAt() != null ? entry.getUpdatedAt() : entry.getCreatedAt();
            readings.add(new SheetStatusReading(entry.getAssetId(), entry.getId(), key, value, recordedAt));
        }
        return readings;
    }

    /**
     * One asset's status reading on a sheet.
     *
     * @param recordedAt device time the operator recorded it, or null for a row old enough to
     *                   have no timestamp; it is what an approval stamps the history with
     */
    public record SheetStatusReading(Long assetId, Long entryId, String fieldKey, String value,
                                     Long recordedAt) {}

    /**
     * Writes a status onto an asset and journals it. The single place the column changes.
     *
     * <p>Deliberately dumb: it decides nothing about whether the change is allowed — that is the
     * request workflow's job — it only makes the change and leaves a record of who, when and
     * under which request. Returns false when the value is already what was asked for, so a
     * no-op never adds a history row saying nothing happened.
     *
     * @param changeType {@code APPLIED} when a request was approved, {@code REVERTED} when an
     *                   approval was undone; the two read very differently on the timeline
     * @param changedAt  when the change should be dated. An approval passes the time the
     *                   reading was <em>taken</em>, not now, so the asset timeline lines up
     *                   with the round that produced it; an undo passes the actual undo time,
     *                   because that is an administrative act rather than an observation.
     *                   Null falls back to now.
     */
    @Transactional
    public boolean writeStatus(AssetEntry asset,
                               String newStatus,
                               AssetStatusChangeType changeType,
                               AssetStatusSource source,
                               Long requestId,
                               Long logSheetId,
                               Long logSheetEntryId,
                               String fieldKey,
                               Long actorUserId,
                               Long changedAt) {
        if (asset == null || asset.getId() == null) {
            return false;
        }
        String desired = normalise(newStatus);
        if (Objects.equals(asset.getStatus(), desired)) {
            return false;
        }

        long now = System.currentTimeMillis();
        AssetStatusHistory row = new AssetStatusHistory();
        row.setAssetId(asset.getId());
        row.setOldStatus(asset.getStatus());
        row.setNewStatus(desired);
        row.setChangeType(changeType);
        row.setSource(source);
        row.setRequestId(requestId);
        row.setLogSheetId(logSheetId);
        row.setLogSheetEntryId(logSheetEntryId);
        row.setFieldKey(fieldKey);
        row.setActorUserId(actorUserId);
        row.setChangedAt(changedAt != null ? changedAt : now);
        historyRepository.save(row);

        asset.setStatus(desired);
        // The row's own modification time stays real: only the history entry is back-dated to
        // the observation, because that is the thing being described.
        asset.setUpdatedAt(now);
        assetEntryRepository.save(asset);
        log.info("Asset {} status {} -> '{}' ({} via request {})", asset.getId(),
                row.getOldStatus(), desired, changeType, requestId);
        return true;
    }

    /**
     * Records a status set by hand on the asset form, rather than by a log sheet.
     *
     * <p><b>Mutates the entity and writes the history row; persisting the asset is the
     * caller's job</b> — it is called mid-edit from {@code AssetEntryService.update}, which
     * saves once at the end rather than twice.
     *
     * <p>Returns true when the column actually moved. Callers pass the value already normalised
     * by the form; blank clears the status, which is a legitimate edit here — unlike a log
     * sheet, where blank means "the operator did not record a state" and is ignored. Someone
     * editing the asset directly and emptying the field is saying "no known state", and the
     * only honest reading of that is to store it.
     *
     * <p>The row is written with {@link AssetStatusSource#MANUAL} and no sheet reference, so the
     * history shows at a glance which changes came from the field and which from a desk.
     *
     * <p><b>This deliberately does not touch {@code revertedAt} on anything.</b> A manual edit
     * does not "undo" a sheet — it moves the value on. The reversal guard in
     * {@link #revertForSheet} then does the right thing by itself: the asset no longer holds
     * what the sheet set, so voiding that sheet later declines to roll back over this newer
     * value and says so in the log.
     */
    @Transactional
    public boolean applyManualChange(AssetEntry asset, String rawStatus, Long actorUserId) {
        if (asset == null || asset.getId() == null) {
            return false;
        }
        String desired = normalise(rawStatus);
        if (Objects.equals(asset.getStatus(), desired)) {
            return false;
        }

        long now = System.currentTimeMillis();
        AssetStatusHistory row = new AssetStatusHistory();
        row.setAssetId(asset.getId());
        row.setOldStatus(asset.getStatus());
        row.setNewStatus(desired);
        row.setChangeType(AssetStatusChangeType.APPLIED);
        row.setSource(AssetStatusSource.MANUAL);
        row.setActorUserId(actorUserId);
        row.setChangedAt(now);
        historyRepository.save(row);

        asset.setStatus(desired);
        asset.setUpdatedAt(now);
        log.info("Asset {} status set manually to '{}' by user {}", asset.getId(), desired, actorUserId);
        return true;
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
    /** Public face of {@link #normalise} for callers outside this class. */
    public static String normaliseStatus(Object raw) {
        return normalise(raw);
    }

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
