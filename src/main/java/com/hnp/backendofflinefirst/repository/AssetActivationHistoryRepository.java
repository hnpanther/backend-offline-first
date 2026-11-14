package com.hnp.backendofflinefirst.repository;

import com.hnp.backendofflinefirst.entity.AssetActivationHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AssetActivationHistoryRepository extends JpaRepository<AssetActivationHistory, Long> {

    /**
     * One asset's activation history, newest first — the only way this table is ever read.
     *
     * <p>Unpaged on purpose. A row exists only when someone switches an asset on or off, which
     * is a rare registry action measured in a handful per asset over its life, not per shift.
     * The merged history view needs the whole series anyway to interleave it with status
     * changes, and paging the two tables independently would make the merge incorrect.
     */
    List<AssetActivationHistory> findByAssetIdOrderByChangedAtDescIdDesc(Long assetId);

    /**
     * The asset delete guard. Every asset has its {@code CREATED} row from the moment it exists, so
     * only a toggle after that — a decision somebody made — makes the asset history.
     */
    boolean existsByAssetIdAndChangeTypeNot(Long assetId,
                                            com.hnp.backendofflinefirst.domain.AssetActivationChangeType changeType);
}
