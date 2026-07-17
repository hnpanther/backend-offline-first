package com.hnp.backendofflinefirst.repository;

import com.hnp.backendofflinefirst.entity.DataRecord;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface DataRecordRepository extends JpaRepository<DataRecord, Long> {

    @Query("""
            SELECT r FROM DataRecord r
            WHERE (:status IS NULL OR r.recordStatus = :status)
              AND (:asset IS NULL OR LOWER(r.assetName) LIKE LOWER(CONCAT('%', :asset, '%')))
              AND (LOWER(COALESCE(r.assetName, '')) LIKE LOWER(CONCAT('%', :q, '%'))
                OR LOWER(COALESCE(r.operatorName, '')) LIKE LOWER(CONCAT('%', :q, '%'))
                OR LOWER(COALESCE(r.nfcTagId, '')) LIKE LOWER(CONCAT('%', :q, '%')))
            """)
    Page<DataRecord> searchWithTerm(@Param("q") String q,
                                    @Param("status") String status,
                                    @Param("asset") String asset,
                                    Pageable pageable);

    @Query("""
            SELECT r FROM DataRecord r
            WHERE (:status IS NULL OR r.recordStatus = :status)
              AND (:asset IS NULL OR LOWER(r.assetName) LIKE LOWER(CONCAT('%', :asset, '%')))
            """)
    Page<DataRecord> filter(@Param("status") String status,
                            @Param("asset") String asset,
                            Pageable pageable);
    Optional<DataRecord> findByLocalId(String localId);
    List<DataRecord> findAllByOrderByIdDesc();
    boolean existsByAssetEntryId(Long assetEntryId);

    @Query("""
            SELECT COALESCE(r.recordStatus, 'نامشخص'), COUNT(r)
            FROM DataRecord r
            GROUP BY r.recordStatus
            """)
    List<Object[]> countGroupedByRecordStatus();

    @Query("""
            SELECT r.assetName, COUNT(r)
            FROM DataRecord r
            WHERE r.assetName IS NOT NULL
            GROUP BY r.assetName
            ORDER BY COUNT(r) DESC
            """)
    List<Object[]> countTopAssetsByName(Pageable pageable);
}
