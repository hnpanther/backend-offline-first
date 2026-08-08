package com.hnp.backendofflinefirst.service;

import com.hnp.backendofflinefirst.domain.AssetActivationChangeType;
import com.hnp.backendofflinefirst.entity.AssetActivationHistory;
import com.hnp.backendofflinefirst.repository.AssetActivationHistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Journals who switched an asset on or off, and when.
 *
 * <h2>Why this is not part of {@link AssetStatusService}</h2>
 * The two look similar and are not. {@code status} is a <em>reading about the equipment</em>
 * that a log sheet sets and a reversal can take back; {@code active} is a <em>registry
 * decision</em> — whether this record takes part in log-sheet generation — that no sheet ever
 * touches. Keeping them apart means the reversal logic can never see an activation row, so
 * undoing a log sheet cannot switch an asset off no matter how a future query is written.
 * They meet only in the merged history view, which is display, not behaviour.
 *
 * <h2>Recording, not deciding</h2>
 * Nothing here validates or blocks. The activation rules (one active asset per sub-function,
 * NFC tag release) live in {@link AssetEntryService} and run first; this only writes down what
 * was decided. A failure to journal must never cost the caller their edit, so callers invoke
 * this after the asset is saved.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssetActivationHistoryService {

    private final AssetActivationHistoryRepository repository;

    /**
     * The baseline row for a newly registered asset.
     *
     * <p>Without it the timeline would start at the first toggle, leaving "was it active when it
     * was created?" unanswerable for an asset nobody has ever switched — which is most of them.
     */
    public void recordCreated(Long assetId, boolean active, Long actorUserId) {
        if (assetId == null) {
            return;
        }
        save(assetId, null, active, AssetActivationChangeType.CREATED, actorUserId);
    }

    /**
     * Records a change, or does nothing when the flag did not actually move.
     *
     * <p>An asset edit that renames the asset must not add "activated" to its history — that
     * would fill the timeline with events that never happened and bury the real ones.
     */
    public void recordIfChanged(Long assetId, boolean wasActive, boolean isActive, Long actorUserId) {
        if (assetId == null || wasActive == isActive) {
            return;
        }
        save(assetId, wasActive, isActive,
                isActive ? AssetActivationChangeType.ACTIVATED : AssetActivationChangeType.DEACTIVATED,
                actorUserId);
    }

    /** One asset's activation history, newest first. */
    public List<AssetActivationHistory> forAsset(Long assetId) {
        if (assetId == null) {
            return List.of();
        }
        return repository.findByAssetIdOrderByChangedAtDescIdDesc(assetId);
    }

    private void save(Long assetId, Boolean wasActive, boolean isActive,
                      AssetActivationChangeType changeType, Long actorUserId) {
        AssetActivationHistory row = new AssetActivationHistory();
        row.setAssetId(assetId);
        row.setWasActive(wasActive);
        row.setActive(isActive);
        row.setChangeType(changeType);
        row.setActorUserId(actorUserId);
        row.setChangedAt(System.currentTimeMillis());
        repository.save(row);
        log.info("Asset {} activation {} by user {}", assetId, changeType, actorUserId);
    }
}
