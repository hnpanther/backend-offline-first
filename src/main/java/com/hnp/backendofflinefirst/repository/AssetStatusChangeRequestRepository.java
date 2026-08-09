package com.hnp.backendofflinefirst.repository;

import com.hnp.backendofflinefirst.domain.AssetStatusRequestStatus;
import com.hnp.backendofflinefirst.entity.AssetStatusChangeRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface AssetStatusChangeRequestRepository extends JpaRepository<AssetStatusChangeRequest, Long> {

    /**
     * The asset's newest request, which is the <b>only</b> one whose approval may be undone.
     *
     * <p>Ordering by id, not by {@code requestedAt}: two requests raised in the same millisecond
     * are perfectly possible when a sheet completes several assets at once, and a timestamp tie
     * would make "latest" ambiguous exactly where the guard has to be exact. The id sequence is
     * monotonic and never ties.
     */
    Optional<AssetStatusChangeRequest> findFirstByAssetIdOrderByIdDesc(Long assetId);

    /** One asset's requests, newest first — the asset history timeline. */
    List<AssetStatusChangeRequest> findByAssetIdOrderByIdDesc(Long assetId);

    /**
     * Whether this sheet already raised a request for this asset that is still open or in force.
     *
     * <p>Guards against duplicates when a sheet is completed more than once — reopened for a
     * correction, or restored after being voided. A rejected request is deliberately excluded:
     * once a supervisor has said no, a re-completion asking again is a new question.
     */
    @Query("""
            SELECT COUNT(r) > 0 FROM AssetStatusChangeRequest r
            WHERE r.logSheetId = :logSheetId
              AND r.assetId = :assetId
              AND r.status <> com.hnp.backendofflinefirst.domain.AssetStatusRequestStatus.REJECTED
            """)
    boolean existsOpenOrApprovedForSheetAndAsset(@Param("logSheetId") Long logSheetId,
                                                 @Param("assetId") Long assetId);

    /**
     * The approval queue, unit-scoped through the asset's log sheets and locations.
     *
     * <p>{@code unitIds} null means unrestricted (admin). Filters are all optional so one query
     * serves the whole page.
     */
    @Query("""
            SELECT r FROM AssetStatusChangeRequest r
            WHERE (:status IS NULL OR r.status = :status)
              AND (:assetId IS NULL OR r.assetId = :assetId)
              AND (:assetIds IS NULL OR r.assetId IN :assetIds)
            ORDER BY r.id DESC
            """)
    Page<AssetStatusChangeRequest> search(@Param("status") AssetStatusRequestStatus status,
                                          @Param("assetId") Long assetId,
                                          @Param("assetIds") Collection<Long> assetIds,
                                          Pageable pageable);

    long countByStatus(AssetStatusRequestStatus status);
}
