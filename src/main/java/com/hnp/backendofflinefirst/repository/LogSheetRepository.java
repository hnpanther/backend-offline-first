package com.hnp.backendofflinefirst.repository;

import com.hnp.backendofflinefirst.domain.LogSheetStatus;
import com.hnp.backendofflinefirst.entity.LogSheet;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface LogSheetRepository extends JpaRepository<LogSheet, Long> {

    @Query("""
            SELECT s FROM LogSheet s
            WHERE (:unitIds IS NULL OR s.operationalUnitId IN :unitIds)
              AND (:status IS NULL OR s.status = :status)
              AND (LOWER(COALESCE(s.templateName, '')) LIKE LOWER(CONCAT('%', :q, '%'))
                OR LOWER(COALESCE(s.operatorName, '')) LIKE LOWER(CONCAT('%', :q, '%')))
            """)
    Page<LogSheet> searchVisibleWithTerm(@Param("unitIds") Collection<Long> unitIds,
                                         @Param("status") LogSheetStatus status,
                                         @Param("q") String q,
                                         Pageable pageable);

    @Query("""
            SELECT s FROM LogSheet s
            WHERE (:unitIds IS NULL OR s.operationalUnitId IN :unitIds)
              AND (:status IS NULL OR s.status = :status)
            """)
    Page<LogSheet> searchVisible(@Param("unitIds") Collection<Long> unitIds,
                                 @Param("status") LogSheetStatus status,
                                 Pageable pageable);
    Optional<LogSheet> findByLocalId(String localId);
    List<LogSheet> findByOperationalUnitIdIn(Collection<Long> unitIds);
    List<LogSheet> findByOperationalUnitIdInAndStatus(Collection<Long> unitIds, LogSheetStatus status);
    List<LogSheet> findByAssigneeUserId(Long assigneeUserId);
    List<LogSheet> findByStatusInAndDueAtLessThanEqual(Collection<LogSheetStatus> statuses, Long threshold);
    List<LogSheet> findAllByOrderByIdDesc();
    List<LogSheet> findByOperationalUnitIdInOrderByIdDesc(Collection<Long> unitIds);

    boolean existsByOperationalUnitId(Long operationalUnitId);

    @Query("""
            SELECT s.status, COUNT(s)
            FROM LogSheet s
            WHERE (:unitIds IS NULL OR s.operationalUnitId IN :unitIds)
            GROUP BY s.status
            """)
    List<Object[]> countGroupedByStatus(@Param("unitIds") Collection<Long> unitIds);

    @Query("""
            SELECT s.templateName, COUNT(s)
            FROM LogSheet s
            WHERE (:unitIds IS NULL OR s.operationalUnitId IN :unitIds)
              AND s.templateName IS NOT NULL
            GROUP BY s.templateName
            ORDER BY COUNT(s) DESC
            """)
    List<Object[]> countGroupedByTemplateName(@Param("unitIds") Collection<Long> unitIds);

    @Query("""
            SELECT COUNT(s)
            FROM LogSheet s
            WHERE (:unitIds IS NULL OR s.operationalUnitId IN :unitIds)
            """)
    long countVisible(@Param("unitIds") Collection<Long> unitIds);
}
