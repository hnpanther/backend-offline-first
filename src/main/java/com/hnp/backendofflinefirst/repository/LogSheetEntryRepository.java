package com.hnp.backendofflinefirst.repository;

import com.hnp.backendofflinefirst.entity.LogSheetEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LogSheetEntryRepository extends JpaRepository<LogSheetEntry, String> {
    List<LogSheetEntry> findByLogSheetId(String logSheetId);
}
