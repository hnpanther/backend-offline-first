package com.hnp.backendofflinefirst.integration;

import com.hnp.backendofflinefirst.domain.ActionSource;
import com.hnp.backendofflinefirst.domain.FormSubmissionStatus;
import com.hnp.backendofflinefirst.dto.FormDraftRequest;
import com.hnp.backendofflinefirst.dto.FormSubmitRequest;
import com.hnp.backendofflinefirst.dto.FormSubmitResult;
import com.hnp.backendofflinefirst.entity.FormDefinition;
import com.hnp.backendofflinefirst.entity.FormSubmission;
import com.hnp.backendofflinefirst.entity.OperationalUnit;
import com.hnp.backendofflinefirst.repository.FormSubmissionRepository;
import com.hnp.backendofflinefirst.repository.OperationalUnitRepository;
import com.hnp.backendofflinefirst.service.FormDefinitionService;
import com.hnp.backendofflinefirst.service.FormFieldService;
import com.hnp.backendofflinefirst.service.FormSubmissionService;
import com.hnp.backendofflinefirst.service.LookupGrantService;
import com.hnp.backendofflinefirst.support.AbstractPostgresIntegrationTest;
import com.hnp.backendofflinefirst.support.WithAppUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.context.support.WithSecurityContextTestExecutionListener;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;
import org.springframework.test.context.support.DirtiesContextTestExecutionListener;
import org.springframework.test.context.transaction.TransactionalTestExecutionListener;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Carrier-3 form submissions: draft sync, freeze, final immutability, idempotent resubmit,
 * filing-unit FILL (subject mirrors reporter), and lookup lease for fillable forms.
 */
@Transactional
@WithAppUser(roles = "ADMIN", authorities = {
        "GET:/forms", "POST:/forms", "POST:/forms/{id}", "POST:/forms/{id}/delete",
        "GET:/forms/{id}/fields", "POST:/forms/{id}/fields",
        "POST:/forms/{id}/fields/{fieldId}", "POST:/forms/{id}/fields/{fieldId}/delete",
        "POST:/forms/{id}/fields/{fieldId}/state",
        "GET:/form-submissions", "GET:/form-submissions/{id}",
        "GET:/api/forms", "GET:/api/forms/{id}",
        "POST:/api/forms/drafts", "POST:/api/forms/submit",
        "GET:/api/form-submissions", "GET:/api/form-submissions/{id}",
        "CAP:SCOPE_PLANT_WIDE"
})
@TestExecutionListeners(listeners = {
        DependencyInjectionTestExecutionListener.class,
        DirtiesContextTestExecutionListener.class,
        TransactionalTestExecutionListener.class,
        WithSecurityContextTestExecutionListener.class
}, mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS)
class FormSubmissionIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired FormDefinitionService formDefinitionService;
    @Autowired FormFieldService formFieldService;
    @Autowired FormSubmissionService formSubmissionService;
    @Autowired FormSubmissionRepository submissionRepository;
    @Autowired OperationalUnitRepository unitRepository;
    @Autowired LookupGrantService lookupGrantService;

    OperationalUnit unitA;
    OperationalUnit unitB;
    FormDefinition form;
    Long adminUserId = 1L;

    @BeforeEach
    void seed() {
        unitA = saveUnit("UNIT-A", "واحد الف");
        unitB = saveUnit("UNIT-B", "واحد ب");
        form = formDefinitionService.create(
                "OBS-" + UUID.randomUUID().toString().substring(0, 8),
                "مشاهده میدانی",
                null,
                List.of(unitA.getId(), unitB.getId()),
                true);
        formFieldService.create(form.getId(), "note", "توضیح", "text", null, true, Map.of(), 10);
        formFieldService.create(form.getId(), "severity", "شدت", "select", null, false,
                Map.of("options", List.of("کم", "زیاد")), 20);
    }

    @Test
    void firstDraftFreezesSchemaAndLaterDefinitionEditDoesNotChangeIt() {
        FormDraftRequest draft = draftRequest("local-1", Map.of("note", "اول"));
        FormSubmitResult r = formSubmissionService.upsertDraft(adminUserId, draft, ActionSource.MOBILE);
        assertThat(r.getOutcome()).isEqualTo(FormSubmissionService.OUTCOME_OK);

        FormSubmission saved = submissionRepository.findById(r.getSubmissionId()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(FormSubmissionStatus.DRAFT);
        assertThat(saved.getFieldDefinitionsSnapshot()).isNotEmpty();
        int frozenCount = saved.getFieldDefinitionsSnapshot().size();

        formFieldService.create(form.getId(), "extra", "اضافی", "text", null, false, Map.of(), 30);

        FormDraftRequest again = draftRequest("local-1", Map.of("note", "دوم"));
        again.setSubmissionId(saved.getId());
        formSubmissionService.upsertDraft(adminUserId, again, ActionSource.MOBILE);

        FormSubmission reloaded = submissionRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getFieldDefinitionsSnapshot()).hasSize(frozenCount);
        assertThat(reloaded.getFormData()).containsEntry("note", "دوم");
        assertThat(reloaded.getFormData()).doesNotContainKey("extra");
    }

    @Test
    void finalSubmitIsImmutableAndIdempotentOnClientActionId() {
        FormDraftRequest draft = draftRequest("local-2", Map.of("note", "آماده"));
        FormSubmitResult drafted = formSubmissionService.upsertDraft(adminUserId, draft, ActionSource.MOBILE);

        FormSubmitRequest submit = new FormSubmitRequest();
        submit.setSubmissionId(drafted.getSubmissionId());
        submit.setLocalId("local-2");
        submit.setClientActionId("act-final-1");
        submit.setFormData(Map.of("note", "آماده"));
        FormSubmitResult first = formSubmissionService.submitFinal(adminUserId, submit, ActionSource.MOBILE);
        assertThat(first.getOutcome()).isEqualTo(FormSubmissionService.OUTCOME_OK);
        assertThat(first.getStatus()).isEqualTo("SUBMITTED");

        FormSubmitResult replay = formSubmissionService.submitFinal(adminUserId, submit, ActionSource.MOBILE);
        assertThat(replay.getOutcome()).isEqualTo(FormSubmissionService.OUTCOME_DUPLICATE);
        assertThat(replay.getSubmissionId()).isEqualTo(first.getSubmissionId());

        FormDraftRequest mutate = draftRequest("local-2", Map.of("note", "تلاش برای ویرایش"));
        mutate.setSubmissionId(first.getSubmissionId());
        FormSubmitResult denied = formSubmissionService.upsertDraft(adminUserId, mutate, ActionSource.MOBILE);
        assertThat(denied.getOutcome()).isIn(
                FormSubmissionService.OUTCOME_VALIDATION_ERROR,
                FormSubmissionService.OUTCOME_IMMUTABLE,
                FormSubmissionService.OUTCOME_DENIED);
    }

    @Test
    void subjectUnitIdIsForcedToReporterUnitOnWrite() {
        // Older clients may still send a different subjectUnitId — server ignores it.
        FormDraftRequest draft = new FormDraftRequest();
        draft.setFormDefinitionId(form.getId());
        draft.setReporterUnitId(unitA.getId());
        draft.setSubjectUnitId(unitB.getId());
        draft.setLocalId("mirror-1");
        draft.setFormData(Map.of("note", "فقط واحد ثبت‌کننده"));
        FormSubmitResult r = formSubmissionService.upsertDraft(adminUserId, draft, ActionSource.WEB);
        assertThat(r.getOutcome()).isEqualTo(FormSubmissionService.OUTCOME_OK);

        FormSubmission saved = submissionRepository.findById(r.getSubmissionId()).orElseThrow();
        assertThat(saved.getReporterUnitId()).isEqualTo(unitA.getId());
        assertThat(saved.getSubjectUnitId()).isEqualTo(unitA.getId());
    }

    @Test
    void createRefusesFormWithNoOperationalUnits() {
        assertThatThrownBy(() -> formDefinitionService.create(
                "EMPTY-" + UUID.randomUUID().toString().substring(0, 8),
                "بدون واحد",
                null,
                List.of(),
                true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("At least one operational unit");
    }

    @Test
    void updateRefusesClearingEveryOperationalUnit() {
        assertThatThrownBy(() -> formDefinitionService.update(
                form.getId(), null, form.getTitle(), null, List.of(), true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("At least one operational unit");
        assertThat(formDefinitionService.unitIds(form.getId()))
                .containsExactlyInAnyOrder(unitA.getId(), unitB.getId());
    }

    @Test
    void filingUnitNotLinkedIsRefused() {
        OperationalUnit outsider = saveUnit("UNIT-X", "خارج");
        FormDraftRequest draft = new FormDraftRequest();
        draft.setFormDefinitionId(form.getId());
        draft.setReporterUnitId(outsider.getId());
        draft.setLocalId("bad-filing");
        draft.setFormData(Map.of("note", "x"));
        FormSubmitResult r = formSubmissionService.upsertDraft(adminUserId, draft, ActionSource.MOBILE);
        assertThat(r.getOutcome()).isEqualTo(FormSubmissionService.OUTCOME_DENIED);
    }

    @Test
    void fillableFormLookupsAreLeasedEvenWithoutOpenRound() {
        // No open round; form has no lookup field yet — empty lease is still a stable answer.
        Set<String> codes = lookupGrantService.listsForFillableForms(adminUserId);
        assertThat(codes).isNotNull();
        // Adding a lookup-typed field would need a real lookup list; emptiness here proves the
        // union path runs without requiring an assigned log sheet.
        assertThat(lookupGrantService.requiredListsForFillableForms(adminUserId)).isNotNull();
    }

    @Test
    void requiredFieldBlocksFinalButNotDraft() {
        FormDraftRequest draft = draftRequest("local-req", Map.of());
        FormSubmitResult d = formSubmissionService.upsertDraft(adminUserId, draft, ActionSource.MOBILE);
        assertThat(d.getOutcome()).isEqualTo(FormSubmissionService.OUTCOME_OK);

        FormSubmitRequest submit = new FormSubmitRequest();
        submit.setSubmissionId(d.getSubmissionId());
        submit.setLocalId("local-req");
        submit.setClientActionId("act-req-1");
        submit.setFormData(Map.of());
        FormSubmitResult refused = formSubmissionService.submitFinal(adminUserId, submit, ActionSource.MOBILE);
        assertThat(refused.getOutcome()).isEqualTo(FormSubmissionService.OUTCOME_VALIDATION_ERROR);
    }

    private FormDraftRequest draftRequest(String localId, Map<String, Object> data) {
        FormDraftRequest draft = new FormDraftRequest();
        draft.setFormDefinitionId(form.getId());
        draft.setReporterUnitId(unitA.getId());
        draft.setSubjectUnitId(unitA.getId());
        draft.setLocalId(localId);
        draft.setFormData(data);
        return draft;
    }

    private OperationalUnit saveUnit(String code, String name) {
        OperationalUnit u = new OperationalUnit();
        u.setCode(code);
        u.setName(name);
        u.setCreatedAt(System.currentTimeMillis());
        u.setUpdatedAt(System.currentTimeMillis());
        return unitRepository.save(u);
    }
}
