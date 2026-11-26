package com.hnp.backendofflinefirst.repository;

import com.hnp.backendofflinefirst.entity.FormSchemaVersion;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface FormSchemaVersionRepository extends JpaRepository<FormSchemaVersion, Long> {
    @Query(value = """
            SELECT DISTINCT ON (form_definition_id) * FROM form_schema_versions
            WHERE form_definition_id IN (:ids) ORDER BY form_definition_id, id DESC
            """, nativeQuery = true)
    List<FormSchemaVersion> latestForForms(Set<Long> ids);

    Slice<FormSchemaVersion> findByFormDefinitionIdOrderByIdDesc(
            Long formId, Pageable page);
    Optional<FormSchemaVersion> findFirstByFormDefinitionIdOrderByIdDesc(Long formDefinitionId);
    Optional<FormSchemaVersion> findByIdAndFormDefinitionId(Long id, Long formDefinitionId);
}
