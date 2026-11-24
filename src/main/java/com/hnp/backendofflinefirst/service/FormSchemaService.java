package com.hnp.backendofflinefirst.service;

import com.hnp.backendofflinefirst.domain.FieldDefinitionSnapshot;
import com.hnp.backendofflinefirst.entity.FormSchemaVersion;
import com.hnp.backendofflinefirst.repository.FormSchemaVersionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

/** Versions are server-owned, immutable snapshots; clients send only their identity. */
@Service
@RequiredArgsConstructor
public class FormSchemaService {
    private final FormSchemaVersionRepository repository;

    @Transactional
    public Long publish(Long formId, List<FieldDefinitionSnapshot> fields) {
        var latest = repository.findFirstByFormDefinitionIdOrderByIdDesc(formId);
        if (latest.isPresent() && latest.get().getFields().equals(fields)) {
            return latest.get().getId();
        }
        // Concurrent catalog reads may publish equivalent versions. Both remain valid;
        // correctness does not depend on a singleton latest version or a client hash.
        FormSchemaVersion version = new FormSchemaVersion();
        version.setFormDefinitionId(formId);
        version.setFields(fields);
        version.setCreatedAt(System.currentTimeMillis());
        return repository.save(version).getId();
    }

    @Transactional(readOnly = true)
    public List<FieldDefinitionSnapshot> require(Long formId, Long versionId) {
        return repository.findByIdAndFormDefinitionId(versionId, formId)
                .orElseThrow(() -> new IllegalArgumentException("Form schema version is not available."))
                .getFields();
    }
}
