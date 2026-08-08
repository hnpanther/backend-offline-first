package com.hnp.backendofflinefirst.repository;

import com.hnp.backendofflinefirst.domain.AssetStatusChangeType;
import com.hnp.backendofflinefirst.entity.AssetStatusHistory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface AssetStatusHistoryRepository extends JpaRepository<AssetStatusHistory, Long> {

    /**
     * The changes this sheet made that are still in effect — exactly what a reversal must undo.
     *
     * <p>One query for the whole sheet, backed by the partial index on
     * {@code (log_sheet_id) WHERE reverted_at IS NULL AND change_type = 'APPLIED'}. The
     * alternative — asking per asset — would be 50 round trips on a typical round.
     */
    @Query("""
            SELECT h FROM AssetStatusHistory h
            WHERE h.logSheetId = :logSheetId
              AND h.changeType = com.hnp.backendofflinefirst.domain.AssetStatusChangeType.APPLIED
              AND h.revertedAt IS NULL
            """)
    List<AssetStatusHistory> findActiveAppliedForSheet(@Param("logSheetId") Long logSheetId);

    /** An asset's full history, newest first — what the asset detail page shows. */
    Page<AssetStatusHistory> findByAssetIdOrderByChangedAtDescIdDesc(Long assetId, Pageable pageable);

    List<AssetStatusHistory> findByAssetIdAndChangeTypeOrderByChangedAtDesc(
            Long assetId, AssetStatusChangeType changeType);
}
