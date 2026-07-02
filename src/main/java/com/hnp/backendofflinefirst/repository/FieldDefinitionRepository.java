package com.hnp.backendofflinefirst.repository;

import com.hnp.backendofflinefirst.entity.FieldDefinition;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface FieldDefinitionRepository extends JpaRepository<FieldDefinition, Long> {
    List<FieldDefinition> findByUpdatedAtGreaterThanEqual(Long since);
    List<FieldDefinition> findByClassId(Long classId);
}
