package com.hnp.backendofflinefirst.service;

import com.hnp.backendofflinefirst.domain.FieldDefinitionSnapshot;
import com.hnp.backendofflinefirst.domain.FormDataValidationSupport;
import com.hnp.backendofflinefirst.domain.FormDataValidationSupport.ValidationIssue;
import com.hnp.backendofflinefirst.entity.FieldDefinition;
import com.hnp.backendofflinefirst.entity.FormDefinition;
import com.hnp.backendofflinefirst.entity.FormField;
import com.hnp.backendofflinefirst.entity.FormSubmission;
import com.hnp.backendofflinefirst.repository.FormFieldRepository;
import com.hnp.backendofflinefirst.repository.FormSubmissionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Field editor for ad-hoc forms. Reuses {@code FieldValidationSupport} /
 * {@link FormDataValidationSupport} — never a second DSL. The web controller builds
 * {@code validation} the same way asset-class and sheet-field editors do.
 */
@Service
@RequiredArgsConstructor
public class FormFieldService {

    private final FormFieldRepository fieldRepository;
    private final FormDefinitionService formDefinitionService;
    private final FormSubmissionRepository submissionRepository;
    private final MasterDataUniquenessValidator uniquenessValidator;

    @Transactional(readOnly = true)
    public List<FormField> listForForm(Long formId) {
        formDefinitionService.require(formId);
        return fieldRepository.findByFormDefinitionIdOrderByOrderAscIdAsc(formId);
    }

    @Transactional(readOnly = true)
    public List<FieldDefinition> activeDefinitions(Long formId) {
        return fieldRepository.findByFormDefinitionIdAndDeletedFalseOrderByOrderAscIdAsc(formId)
                .stream()
                .map(FormField::toFieldDefinition)
                .toList();
    }

    /** Snapshot used on first draft: live non-deleted fields, ordered for display. */
    @Transactional(readOnly = true)
    public List<FieldDefinitionSnapshot> captureSnapshot(Long formId) {
        List<FieldDefinitionSnapshot> out = new ArrayList<>();
        for (FormField field : fieldRepository.findByFormDefinitionIdAndDeletedFalseOrderByOrderAscIdAsc(formId)) {
            out.add(FieldDefinitionSnapshot.from(field.toFieldDefinition()));
        }
        out.sort(FieldDefinitionSnapshot.displayOrder());
        return List.copyOf(out);
    }

    @Transactional(readOnly = true)
    public List<FieldDefinition> resolveForSubmission(FormSubmission submission) {
        if (submission.getFieldDefinitionsSnapshot() != null) {
            return FieldDefinitionSnapshot.toFieldDefinitions(submission.getFieldDefinitionsSnapshot());
        }
        return activeDefinitions(submission.getFormDefinitionId());
    }

    @Transactional
    public FormField create(Long formId, String key, String label, String dataType, String unit,
                            boolean required, Map<String, Object> validation, Integer order) {
        FormDefinition form = formDefinitionService.require(formId);
        uniquenessValidator.validateFormField(null, formId, key);
        long now = System.currentTimeMillis();
        FormField field = new FormField();
        field.setFormDefinitionId(form.getId());
        field.setKey(key.trim());
        field.setLabel(blankToNull(label));
        field.setDataType(dataType);
        field.setUnit(blankToNull(unit));
        field.setRequired(required);
        field.setValidation(validation);
        field.setOrder(order != null ? order : nextOrder(formId));
        field.setVersion(1);
        field.setDeleted(false);
        field.setCreatedAt(now);
        field.setUpdatedAt(now);
        return fieldRepository.save(field);
    }

    @Transactional
    public FormField update(Long formId, Long fieldId, String key, String label, String dataType,
                            String unit, boolean required, Map<String, Object> validation, Integer order) {
        FormField field = require(formId, fieldId);
        String trimmedKey = key == null ? field.getKey() : key.trim();
        uniquenessValidator.validateFormField(fieldId, formId, trimmedKey);
        if (!trimmedKey.equalsIgnoreCase(field.getKey())
                && submissionRepository.existsByFormDefinitionId(formId)) {
            throw new IllegalStateException(
                    "Form field key cannot change once a submission exists.");
        }
        field.setKey(trimmedKey);
        field.setLabel(blankToNull(label));
        field.setDataType(dataType);
        field.setUnit(blankToNull(unit));
        field.setRequired(required);
        field.setValidation(validation);
        if (order != null) {
            field.setOrder(order);
        }
        field.setVersion(field.getVersion() == null ? 1 : field.getVersion() + 1);
        field.setUpdatedAt(System.currentTimeMillis());
        return fieldRepository.save(field);
    }

    /** Soft-disable. Hard delete is refused once any submission exists (key stays an identity). */
    @Transactional
    public void setDeleted(Long formId, Long fieldId, boolean deleted) {
        FormField field = require(formId, fieldId);
        field.setDeleted(deleted);
        field.setUpdatedAt(System.currentTimeMillis());
        fieldRepository.save(field);
    }

    @Transactional
    public void hardDelete(Long formId, Long fieldId) {
        FormField field = require(formId, fieldId);
        if (submissionRepository.existsByFormDefinitionId(formId)) {
            throw new IllegalStateException(
                    "Form field cannot be deleted once a submission exists; disable it instead.");
        }
        fieldRepository.delete(field);
    }

    @Transactional(readOnly = true)
    public String partialValidationMessage(FormSubmission submission, Map<String, Object> formData) {
        String schemaError = unknownAnswerMessage(submission, formData);
        if (schemaError != null) return schemaError;
        return format(FormDataValidationSupport.validatePartialEntry(
                formData, resolveForSubmission(submission)));
    }

    @Transactional(readOnly = true)
    public String fullValidationMessage(FormSubmission submission, Map<String, Object> formData) {
        String schemaError = unknownAnswerMessage(submission, formData);
        if (schemaError != null) return schemaError;
        return format(FormDataValidationSupport.validate(
                formData, resolveForSubmission(submission)));
    }

    private String unknownAnswerMessage(FormSubmission submission, Map<String, Object> formData) {
        if (formData == null) return null;
        var keys = resolveForSubmission(submission).stream().map(FieldDefinition::getKey)
                .collect(java.util.stream.Collectors.toSet());
        return keys.containsAll(formData.keySet()) ? null
                : "Form schema contains unknown answer fields. Refresh the form definition.";
    }

    private static String format(List<ValidationIssue> issues) {
        if (issues == null || issues.isEmpty()) {
            return null;
        }
        StringBuilder message = new StringBuilder("Form submission validation failed: ");
        for (int i = 0; i < issues.size(); i++) {
            if (i > 0) {
                message.append("; ");
            }
            ValidationIssue issue = issues.get(i);
            message.append("field '").append(issue.fieldKey()).append("': ").append(issue.message());
        }
        return message.toString();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> storable(FormSubmission submission, Map<String, Object> submitted) {
        List<FieldDefinition> defs = resolveForSubmission(submission);
        return FormDataValidationSupport.answeredOnly(
                FormDataValidationSupport.retainKnownKeys(submitted, defs));
    }

    private FormField require(Long formId, Long fieldId) {
        formDefinitionService.require(formId);
        return fieldRepository.findByIdAndFormDefinitionId(fieldId, formId)
                .orElseThrow(() -> new IllegalArgumentException("Form field not found: " + fieldId));
    }

    private int nextOrder(Long formId) {
        return fieldRepository.findByFormDefinitionIdOrderByOrderAscIdAsc(formId).stream()
                .map(FormField::getOrder)
                .filter(Objects::nonNull)
                .max(Comparator.naturalOrder())
                .orElse(0) + 10;
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
