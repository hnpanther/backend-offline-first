package com.hnp.backendofflinefirst.repository;

import com.hnp.backendofflinefirst.entity.NfcFaultReport;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface NfcFaultReportRepository extends JpaRepository<NfcFaultReport, Long> {
    List<NfcFaultReport> findByLogSheetIdOrderByCreatedAtDesc(Long logSheetId);
    List<NfcFaultReport> findByOperationalUnitIdInOrderByCreatedAtDesc(Collection<Long> unitIds);
    List<NfcFaultReport> findAllByOrderByCreatedAtDesc();
    boolean existsByClientActionId(String clientActionId);
}
