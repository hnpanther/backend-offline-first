package com.hnp.backendofflinefirst.repository;

import com.hnp.backendofflinefirst.entity.LogSheet;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface LogSheetRepository extends JpaRepository<LogSheet, String> {
    Optional<LogSheet> findByLocalId(String localId);
}
