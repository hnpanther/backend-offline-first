package com.hnp.backendofflinefirst.service;

import com.hnp.backendofflinefirst.domain.FieldDefinitionSnapshot;
import com.hnp.backendofflinefirst.entity.FormSchemaVersion;
import com.hnp.backendofflinefirst.repository.FormSchemaVersionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.PageRequest;

import java.util.List;
import java.util.Optional;

/** Versions are server-owned, immutable snapshots; legacy clients can provide fields for an exact server-side match. */
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

    /** Mobile recovery compares the whole old definition, including labels, units and validation. */
    @Transactional(readOnly = true)
    public Optional<List<FieldDefinitionSnapshot>> matchPublished(
            Long formId, List<FieldDefinitionSnapshot> evidence) {
        var page = PageRequest.of(0, 16);
        while (true) {
            var versions = repository.findByFormDefinitionIdOrderByIdDesc(formId, page);
            for (var version : versions) {
                if (version.getFields().equals(evidence)) return Optional.of(version.getFields());
            }
            if (!versions.hasNext()) return Optional.empty();
            page = page.next();
        }
    }

    @Transactional(readOnly = true)
    public Optional<List<FieldDefinitionSnapshot>> findPublished(Long formId, Long versionId) {
        return repository.findByIdAndFormDefinitionId(versionId, formId)
                .map(FormSchemaVersion::getFields);
    }
}
