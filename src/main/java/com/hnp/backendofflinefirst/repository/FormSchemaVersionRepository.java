package com.hnp.backendofflinefirst.repository;

import com.hnp.backendofflinefirst.entity.FormSchemaVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;

import java.util.Optional;

public interface FormSchemaVersionRepository extends JpaRepository<FormSchemaVersion, Long> {
    Slice<FormSchemaVersion> findByFormDefinitionIdOrderByIdDesc(
            Long formId, Pageable page);
    Optional<FormSchemaVersion> findFirstByFormDefinitionIdOrderByIdDesc(Long formDefinitionId);
    Optional<FormSchemaVersion> findByIdAndFormDefinitionId(Long id, Long formDefinitionId);
}
