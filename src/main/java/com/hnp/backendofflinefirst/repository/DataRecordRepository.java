package com.hnp.backendofflinefirst.repository;

import com.hnp.backendofflinefirst.entity.DataRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface DataRecordRepository extends JpaRepository<DataRecord, Long> {
    Optional<DataRecord> findByLocalId(String localId);
}
