package com.hnp.backendofflinefirst.repository;

import com.hnp.backendofflinefirst.entity.LogSheet;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface LogSheetRepository extends JpaRepository<LogSheet, String> {
    Optional<LogSheet> findByLocalId(String localId);
    List<LogSheet> findByOperationalUnitIdIn(Collection<String> unitIds);
}
