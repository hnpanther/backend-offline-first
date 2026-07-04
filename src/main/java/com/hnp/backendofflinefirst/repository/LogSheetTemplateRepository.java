package com.hnp.backendofflinefirst.repository;

import com.hnp.backendofflinefirst.domain.GenerationMode;
import com.hnp.backendofflinefirst.entity.LogSheetTemplate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface LogSheetTemplateRepository extends JpaRepository<LogSheetTemplate, Long> {

    @Query("SELECT t FROM LogSheetTemplate t WHERE LOWER(t.name) LIKE LOWER(CONCAT('%', :q, '%'))")
    Page<LogSheetTemplate> search(@Param("q") String q, Pageable pageable);
    List<LogSheetTemplate> findAllByOrderByIdDesc();
    List<LogSheetTemplate> findByUpdatedAtGreaterThanEqual(Long since);

    /** Active scheduled templates whose next run is due at or before {@code now}. */
    List<LogSheetTemplate> findByGenerationModeAndScheduleActiveTrueAndNextRunAtLessThanEqual(
            GenerationMode generationMode, Long now);
}
