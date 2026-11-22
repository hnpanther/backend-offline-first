package com.hnp.backendofflinefirst.service;

import com.hnp.backendofflinefirst.entity.FormDefinition;
import com.hnp.backendofflinefirst.entity.FormDefinitionUnit;
import com.hnp.backendofflinefirst.repository.FormDefinitionRepository;
import com.hnp.backendofflinefirst.repository.FormDefinitionUnitRepository;
import com.hnp.backendofflinefirst.repository.FormSubmissionRepository;
import com.hnp.backendofflinefirst.repository.OperationalUnitRepository;
import com.hnp.backendofflinefirst.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * ADMIN authorship of ad-hoc form definitions (carrier 3). Unit links decide which
 * filing units ({@code reporter_unit_id}, mirrored onto {@code subject_unit_id}) may
 * FILL the form — not who may author the schema.
 */
@Service
@RequiredArgsConstructor
public class FormDefinitionService {

    private final FormDefinitionRepository formRepository;
    private final FormDefinitionUnitRepository unitLinkRepository;
    private final FormSubmissionRepository submissionRepository;
    private final OperationalUnitRepository unitRepository;

    @Transactional(readOnly = true)
    public FormDefinition require(Long id) {
        return formRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Form definition not found: " + id));
    }

    @Transactional(readOnly = true)
    public List<FormDefinition> findByIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return formRepository.findAllById(ids);
    }

    @Transactional(readOnly = true)
    public Page<FormDefinition> page(String q, Pageable pageable) {
        return formRepository.search(q == null ? "" : q.trim(), pageable);
    }

    @Transactional(readOnly = true)
    public Set<Long> unitIds(Long formId) {
        return unitLinkRepository.findUnitIdsByFormDefinitionId(formId);
    }

    @Transactional
    public FormDefinition create(String code, String title, String description,
                                 Collection<Long> subjectUnitIds, boolean active) {
        String normalised = normaliseCode(code);
        if (formRepository.existsByCodeIgnoreCase(normalised)) {
            throw new IllegalArgumentException("Duplicate form code: " + normalised);
        }
        String cleanTitle = requireTitle(title);
        long now = System.currentTimeMillis();
        FormDefinition form = new FormDefinition();
        form.setCode(normalised);
        form.setTitle(cleanTitle);
        form.setDescription(blankToNull(description));
        form.setActive(active);
        form.setCreatedAt(now);
        form.setUpdatedAt(now);
        form.setCreatedByUserId(SecurityUtils.currentUserId());
        form = formRepository.save(form);
        replaceUnits(form.getId(), subjectUnitIds);
        return form;
    }

    @Transactional
    public FormDefinition update(Long id, String code, String title, String description,
                                 Collection<Long> subjectUnitIds, Boolean active) {
        FormDefinition form = require(id);
        if (code != null && !code.isBlank()) {
            String normalised = normaliseCode(code);
            if (formRepository.existsByCodeIgnoreCaseAndIdNot(normalised, id)) {
                throw new IllegalArgumentException("Duplicate form code: " + normalised);
            }
            // Code is fixed once any submission exists — same identity rule as field keys.
            if (!normalised.equalsIgnoreCase(form.getCode())
                    && submissionRepository.existsByFormDefinitionId(id)) {
                throw new IllegalStateException(
                        "Form code cannot change once a submission exists.");
            }
            form.setCode(normalised);
        }
        form.setTitle(requireTitle(title));
        form.setDescription(blankToNull(description));
        if (active != null) {
            form.setActive(active);
        }
        form.setUpdatedAt(System.currentTimeMillis());
        form = formRepository.save(form);
        // Always rewrite the allow-list: an empty/missing picker must refuse, not silently keep
        // the previous links (remote-multi-select posts nothing when cleared).
        replaceUnits(id, subjectUnitIds);
        return form;
    }

    @Transactional
    public void delete(Long id) {
        FormDefinition form = require(id);
        if (submissionRepository.existsByFormDefinitionId(id)) {
            throw new IllegalStateException(
                    "Form definition cannot be deleted while submissions exist; deactivate it instead.");
        }
        unitLinkRepository.deleteByFormDefinitionId(id);
        formRepository.delete(form);
    }

    @Transactional(readOnly = true)
    public List<FormDefinition> findActiveFillableForSubjectUnits(Collection<Long> subjectUnitCandidates) {
        if (subjectUnitCandidates == null || subjectUnitCandidates.isEmpty()) {
            return List.of();
        }
        return formRepository.findActiveLinkedToAnyUnit(subjectUnitCandidates);
    }

    @Transactional(readOnly = true)
    public boolean subjectUnitAllowed(Long formId, Long subjectUnitId) {
        return unitLinkRepository.existsByFormDefinitionIdAndOperationalUnitId(formId, subjectUnitId);
    }

    private void replaceUnits(Long formId, Collection<Long> subjectUnitIds) {
        Set<Long> distinct = new LinkedHashSet<>();
        if (subjectUnitIds != null) {
            for (Long unitId : subjectUnitIds) {
                if (unitId != null) {
                    distinct.add(unitId);
                }
            }
        }
        if (distinct.isEmpty()) {
            throw new IllegalArgumentException("At least one operational unit is required.");
        }
        unitLinkRepository.deleteByFormDefinitionId(formId);
        for (Long unitId : distinct) {
            if (!unitRepository.existsById(unitId)) {
                throw new IllegalArgumentException("Operational unit not found: " + unitId);
            }
            FormDefinitionUnit link = new FormDefinitionUnit();
            link.setFormDefinitionId(formId);
            link.setOperationalUnitId(unitId);
            unitLinkRepository.save(link);
        }
    }

    private static String normaliseCode(String code) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("Form code is required.");
        }
        String trimmed = code.trim();
        if (trimmed.length() > 100) {
            throw new IllegalArgumentException("Form code is too long.");
        }
        return trimmed.toUpperCase(Locale.ROOT);
    }

    private static String requireTitle(String title) {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("Form title is required.");
        }
        return title.trim();
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
