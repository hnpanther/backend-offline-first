package com.hnp.backendofflinefirst.repository;

import com.hnp.backendofflinefirst.domain.GenerationMode;
import com.hnp.backendofflinefirst.entity.LogSheetTemplate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface LogSheetTemplateRepository extends JpaRepository<LogSheetTemplate, Long> {

    @Query("SELECT t FROM LogSheetTemplate t WHERE LOWER(t.name) LIKE LOWER(CONCAT('%', :q, '%'))")
    Page<LogSheetTemplate> search(@Param("q") String q, Pageable pageable);

    @Query("""
            SELECT t FROM LogSheetTemplate t
            WHERE t.operationalUnitId IN :unitIds
              AND LOWER(t.name) LIKE LOWER(CONCAT('%', :q, '%'))
            """)
    Page<LogSheetTemplate> searchInUnits(@Param("q") String q,
                                         @Param("unitIds") Collection<Long> unitIds,
                                         Pageable pageable);

    Page<LogSheetTemplate> findByOperationalUnitIdIn(Collection<Long> unitIds, Pageable pageable);

    List<LogSheetTemplate> findAllByOrderByIdDesc();

    List<LogSheetTemplate> findByOperationalUnitIdInOrderByIdDesc(Collection<Long> unitIds);

    boolean existsByOperationalUnitId(Long operationalUnitId);
    Optional<LogSheetTemplate> findByNameIgnoreCase(String name);
    List<LogSheetTemplate> findByUpdatedAtGreaterThanEqual(Long since);

    /** Active scheduled templates whose next run is due at or before {@code now}. */
    List<LogSheetTemplate> findByGenerationModeAndScheduleActiveTrueAndNextRunAtLessThanEqual(
            GenerationMode generationMode, Long now);
}
