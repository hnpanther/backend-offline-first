package com.hnp.backendofflinefirst.repository;

import com.hnp.backendofflinefirst.entity.NfcFaultReport;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;

public interface NfcFaultReportRepository extends JpaRepository<NfcFaultReport, Long> {

    /**
     * Unresolved fault reports for the caller's units.
     *
     * <p>Scoped in SQL. This used to be {@code findAll()} filtered in Java, which loaded every
     * report ever filed into memory to display a handful — fine with a few rows, linear in the
     * whole table forever after.
     */
    @Query("""
            SELECT r FROM NfcFaultReport r
            WHERE r.status = com.hnp.backendofflinefirst.domain.NfcFaultReportStatus.OPEN
              AND r.assetId IS NOT NULL
              AND (:unitIds IS NULL OR r.operationalUnitId IN :unitIds)
            """)
    java.util.List<NfcFaultReport> findOpenForUnits(
            @org.springframework.data.repository.query.Param("unitIds") java.util.Collection<Long> unitIds);
    List<NfcFaultReport> findByLogSheetIdOrderByCreatedAtDesc(Long logSheetId);
    List<NfcFaultReport> findByOperationalUnitIdInOrderByCreatedAtDesc(Collection<Long> unitIds);
    List<NfcFaultReport> findAllByOrderByCreatedAtDesc();
    boolean existsByClientActionId(String clientActionId);
}
