package com.hnp.backendofflinefirst.repository;

import com.hnp.backendofflinefirst.entity.FormSchemaVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface FormSchemaVersionRepository extends JpaRepository<FormSchemaVersion, Long> {
    Optional<FormSchemaVersion> findFirstByFormDefinitionIdOrderByIdDesc(Long formDefinitionId);
    Optional<FormSchemaVersion> findByIdAndFormDefinitionId(Long id, Long formDefinitionId);
}
