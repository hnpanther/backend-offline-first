package com.hnp.backendofflinefirst.repository;

import com.hnp.backendofflinefirst.domain.GenerationMode;
import com.hnp.backendofflinefirst.entity.LogSheetTemplate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LogSheetTemplateRepository extends JpaRepository<LogSheetTemplate, Long> {
    List<LogSheetTemplate> findByUpdatedAtGreaterThanEqual(Long since);

    /** Active scheduled templates whose next run is due at or before {@code now}. */
    List<LogSheetTemplate> findByGenerationModeAndScheduleActiveTrueAndNextRunAtLessThanEqual(
            GenerationMode generationMode, Long now);
}
