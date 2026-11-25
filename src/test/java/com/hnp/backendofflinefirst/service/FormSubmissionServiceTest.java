package com.hnp.backendofflinefirst.service;

import com.hnp.backendofflinefirst.domain.ActionSource;
import com.hnp.backendofflinefirst.domain.FormSubmissionStatus;
import com.hnp.backendofflinefirst.dto.FormSubmitRequest;
import com.hnp.backendofflinefirst.entity.FormDefinition;
import com.hnp.backendofflinefirst.entity.FormSubmission;
import com.hnp.backendofflinefirst.repository.FormSubmissionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FormSubmissionServiceTest {
    @Mock FormSubmissionRepository repository;
    @Mock FormDefinitionService definitions;
    @Mock FormFieldService fields;
    @Mock FormAccessService access;
    @InjectMocks FormSubmissionService service;

    private FormSubmission draft;
    private FormDefinition definition;
    private FormSubmitRequest request;

    @BeforeEach
    void existingDraft() {
        draft = new FormSubmission();
        draft.setId(12L);
        draft.setFormDefinitionId(10L);
        draft.setReporterUnitId(1L);
        draft.setSubjectUnitId(1L);
        draft.setStatus(FormSubmissionStatus.DRAFT);
        draft.setFormData(Map.of("note", "original"));
        definition = new FormDefinition();
        definition.setId(10L);
        request = new FormSubmitRequest();
        request.setSubmissionId(12L);
        request.setClientActionId("final-1");
        request.setFormData(Map.of("note", "edited"));
        when(access.requireVisible(7L, 12L)).thenReturn(draft);
        when(definitions.require(10L)).thenReturn(definition);
    }

    @Test
    void omittedUnitStillChecksAvailabilityAndScopeOfStoredUnit() {
        doThrow(new AccessDeniedException("Form is not available for filling."))
                .when(access).assertCanFill(7L, definition, 1L);

        var result = service.submitFinal(7L, request, ActionSource.MOBILE);

        assertThat(result.getOutcome()).isEqualTo(FormSubmissionService.OUTCOME_DENIED);
        verify(access).assertCanFill(7L, definition, 1L);
        verify(repository, never()).save(any());
        assertThat(draft.getFormData()).containsEntry("note", "original");
    }

    @Test
    void rejectedAnswersDoNotDirtyTheManagedFilingUnit() {
        request.setReporterUnitId(2L);
        when(fields.fullValidationMessage(eq(draft), eq(request.getFormData()), anyLong()))
                .thenReturn("Form submission validation failed: required field.");

        var result = service.submitFinal(7L, request, ActionSource.MOBILE);

        assertThat(result.getOutcome()).isEqualTo(FormSubmissionService.OUTCOME_VALIDATION_ERROR);
        verify(access).assertCanFill(7L, definition, 2L);
        assertThat(draft.getReporterUnitId()).isEqualTo(1L);
        assertThat(draft.getSubjectUnitId()).isEqualTo(1L);
        assertThat(draft.getStatus()).isEqualTo(FormSubmissionStatus.DRAFT);
        assertThat(draft.getFormData()).containsEntry("note", "original");
        verify(repository, never()).save(any());
    }
}
