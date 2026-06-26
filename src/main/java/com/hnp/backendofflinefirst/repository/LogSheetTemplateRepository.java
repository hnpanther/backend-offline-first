package com.hnp.backendofflinefirst.repository;

import com.hnp.backendofflinefirst.entity.LogSheetTemplate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LogSheetTemplateRepository extends JpaRepository<LogSheetTemplate, String> {
    List<LogSheetTemplate> findByUpdatedAtGreaterThanEqual(Long since);
}
