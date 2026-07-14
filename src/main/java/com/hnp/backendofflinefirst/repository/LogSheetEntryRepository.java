package com.hnp.backendofflinefirst.repository;

import com.hnp.backendofflinefirst.domain.LogSheetStatus;
import com.hnp.backendofflinefirst.entity.LogSheetEntry;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface LogSheetEntryRepository extends JpaRepository<LogSheetEntry, Long> {
    List<LogSheetEntry> findByLogSheetId(Long logSheetId);

    @Query(value = """
            SELECT e.id, s.id, COALESCE(s.completedAt, s.submittedAt), s.templateName, s.operatorName, e.formData
            FROM LogSheetEntry e, LogSheet s
            WHERE e.logSheetId = s.id
              AND e.assetId = :assetId
              AND s.status = :status
              AND (:from IS NULL OR COALESCE(s.completedAt, s.submittedAt) >= :from)
              AND (:to IS NULL OR COALESCE(s.completedAt, s.submittedAt) <= :to)
            ORDER BY COALESCE(s.completedAt, s.submittedAt) DESC
            """,
            countQuery = """
            SELECT COUNT(e) FROM LogSheetEntry e, LogSheet s
            WHERE e.logSheetId = s.id
              AND e.assetId = :assetId
              AND s.status = :status
              AND (:from IS NULL OR COALESCE(s.completedAt, s.submittedAt) >= :from)
              AND (:to IS NULL OR COALESCE(s.completedAt, s.submittedAt) <= :to)
            """)
    Page<Object[]> findSubmittedReadingRowsByAssetId(@Param("assetId") Long assetId,
                                                       @Param("status") LogSheetStatus status,
                                                       @Param("from") Long from,
                                                       @Param("to") Long to,
                                                       Pageable pageable);

    @Query("""
            SELECT e.id, s.id, COALESCE(s.completedAt, s.submittedAt), s.templateName, s.operatorName, e.formData
            FROM LogSheetEntry e, LogSheet s
            WHERE e.logSheetId = s.id
              AND e.assetId = :assetId
              AND s.status = :status
              AND (:from IS NULL OR COALESCE(s.completedAt, s.submittedAt) >= :from)
              AND (:to IS NULL OR COALESCE(s.completedAt, s.submittedAt) <= :to)
            ORDER BY COALESCE(s.completedAt, s.submittedAt) ASC
            """)
    List<Object[]> findSubmittedReadingRowsByAssetIdAsc(@Param("assetId") Long assetId,
                                                         @Param("status") LogSheetStatus status,
                                                         @Param("from") Long from,
                                                         @Param("to") Long to);
}
