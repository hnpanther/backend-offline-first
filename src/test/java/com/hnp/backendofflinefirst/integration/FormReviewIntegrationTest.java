package com.hnp.backendofflinefirst.integration;

import com.hnp.backendofflinefirst.domain.ActionSource;
import com.hnp.backendofflinefirst.domain.FormSubmissionStatus;
import com.hnp.backendofflinefirst.dto.FormDraftRequest;
import com.hnp.backendofflinefirst.dto.FormSubmitRequest;
import com.hnp.backendofflinefirst.dto.FormSubmitResult;
import com.hnp.backendofflinefirst.entity.FormDefinition;
import com.hnp.backendofflinefirst.entity.FormSubmission;
import com.hnp.backendofflinefirst.entity.OperationalUnit;
import com.hnp.backendofflinefirst.entity.UnitSupervisor;
import com.hnp.backendofflinefirst.entity.User;
import com.hnp.backendofflinefirst.repository.FormSubmissionRepository;
import com.hnp.backendofflinefirst.repository.OperationalUnitRepository;
import com.hnp.backendofflinefirst.repository.UnitSupervisorRepository;
import com.hnp.backendofflinefirst.repository.UserRepository;
import com.hnp.backendofflinefirst.security.AppUserDetails;
import com.hnp.backendofflinefirst.security.Capabilities;
import com.hnp.backendofflinefirst.service.FormAccessService;
import com.hnp.backendofflinefirst.service.FormDefinitionService;
import com.hnp.backendofflinefirst.service.FormFieldService;
import com.hnp.backendofflinefirst.service.FormSubmissionService;
import com.hnp.backendofflinefirst.support.AbstractPostgresIntegrationTest;
import com.hnp.backendofflinefirst.support.WithAppUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
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
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V30 review (approve/void) and subject-unit VIEW scope for ad-hoc form submissions.
 * Filing unit (= reporter, mirrored onto subject) is the sole unit axis on write.
 */
@Transactional
@WithAppUser(roles = "ADMIN", authorities = {
        "GET:/forms", "POST:/forms", "POST:/forms/{id}",
        "GET:/forms/{id}/fields", "POST:/forms/{id}/fields",
        "GET:/form-submissions", "GET:/form-submissions/{id}",
        "POST:/form-submissions/draft", "POST:/form-submissions/submit",
        "POST:/form-submissions/{id}/approve", "POST:/form-submissions/{id}/unapprove",
        "POST:/form-submissions/{id}/void", "POST:/form-submissions/{id}/unvoid",
        "GET:/api/forms", "POST:/api/forms/drafts", "POST:/api/forms/submit",
        Capabilities.SCOPE_PLANT_WIDE, Capabilities.SUPERVISE_ANY_UNIT
})
@TestExecutionListeners(listeners = {
        DependencyInjectionTestExecutionListener.class,
        DirtiesContextTestExecutionListener.class,
        TransactionalTestExecutionListener.class,
        WithSecurityContextTestExecutionListener.class
}, mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS)
class FormReviewIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired FormDefinitionService formDefinitionService;
    @Autowired FormFieldService formFieldService;
    @Autowired FormSubmissionService formSubmissionService;
    @Autowired FormAccessService formAccessService;
    @Autowired FormSubmissionRepository submissionRepository;
    @Autowired OperationalUnitRepository unitRepository;
    @Autowired UserRepository userRepository;
    @Autowired UnitSupervisorRepository unitSupervisorRepository;

    OperationalUnit unitA;
    OperationalUnit unitB;
    FormDefinition form;
    User supervisorA;
    User supervisorB;
    Long adminUserId = 1L;

    @BeforeEach
    void seed() {
        unitA = saveUnit("FR-A-" + System.nanoTime(), "واحد الف");
        unitB = saveUnit("FR-B-" + System.nanoTime(), "واحد ب");
        form = formDefinitionService.create(
                "FR-" + UUID.randomUUID().toString().substring(0, 8),
                "ثبت تخلف آزمایشی",
                null,
                List.of(unitA.getId(), unitB.getId()),
                true);
        formFieldService.create(form.getId(), "note", "توضیح", "text", null, true, Map.of(), 10);

        supervisorA = saveUser("fr-sup-a-" + System.nanoTime());
        supervisorB = saveUser("fr-sup-b-" + System.nanoTime());
        linkSupervisor(supervisorA.getId(), unitA.getId());
        linkSupervisor(supervisorB.getId(), unitB.getId());
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void approveAndVoidHappyPath() {
        Long submissionId = submitForUnit(adminUserId, unitA.getId(), "happy");

        FormSubmission approved = formSubmissionService.approve(
                submissionId, adminUserId, ActionSource.WEB, "ok");
        assertThat(approved.getStatus()).isEqualTo(FormSubmissionStatus.APPROVED);
        assertThat(approved.getApprovedAt()).isNotNull();
        assertThat(approved.getApprovedByUserId()).isEqualTo(adminUserId);
        assertThat(approved.getFormData()).containsEntry("note", "happy");

        assertThatThrownBy(() -> formSubmissionService.voidSubmitted(
                submissionId, adminUserId, ActionSource.WEB, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Only submitted");

        formSubmissionService.unapprove(submissionId, adminUserId, ActionSource.WEB, null);
        FormSubmission voided = formSubmissionService.voidSubmitted(
                submissionId, adminUserId, ActionSource.WEB, "wrong");
        assertThat(voided.getStatus()).isEqualTo(FormSubmissionStatus.VOIDED);
        assertThat(voided.getFormData()).containsEntry("note", "happy");

        FormSubmission restored = formSubmissionService.restoreVoided(
                submissionId, adminUserId, ActionSource.WEB, null);
        assertThat(restored.getStatus()).isEqualTo(FormSubmissionStatus.SUBMITTED);
    }

    @Test
    void filingUnitSupervisorMayApprove_siblingUnitSupervisorMayNot() {
        Long submissionId = submitForUnit(adminUserId, unitB.getId(), "filed-b");

        authenticateScoped(supervisorA, Set.of(
                "POST:/form-submissions/{id}/approve",
                "GET:/form-submissions/{id}"));
        assertThatThrownBy(() -> formSubmissionService.approve(
                submissionId, supervisorA.getId(), ActionSource.WEB, null))
                .isInstanceOf(AccessDeniedException.class);

        authenticateScoped(supervisorB, Set.of(
                "POST:/form-submissions/{id}/approve",
                "GET:/form-submissions/{id}"));
        FormSubmission approved = formSubmissionService.approve(
                submissionId, supervisorB.getId(), ActionSource.WEB, "seen");
        assertThat(approved.getStatus()).isEqualTo(FormSubmissionStatus.APPROVED);
    }

    @Test
    void listIsFilingUnitScoped() {
        Long submissionId = submitForUnit(adminUserId, unitB.getId(), "scope");

        authenticateScoped(supervisorA, Set.of("GET:/form-submissions"));
        var pageA = formSubmissionService.pageVisible(
                supervisorA.getId(), form.getId(), null, PageRequest.of(0, 50));
        assertThat(pageA.getContent().stream().map(FormSubmission::getId))
                .doesNotContain(submissionId);
        assertThat(formAccessService.canView(supervisorA.getId(),
                submissionRepository.findById(submissionId).orElseThrow())).isFalse();

        authenticateScoped(supervisorB, Set.of("GET:/form-submissions"));
        var pageB = formSubmissionService.pageVisible(
                supervisorB.getId(), form.getId(), null, PageRequest.of(0, 50));
        assertThat(pageB.getContent().stream().map(FormSubmission::getId))
                .contains(submissionId);
        assertThat(formAccessService.canView(supervisorB.getId(),
                submissionRepository.findById(submissionId).orElseThrow())).isTrue();
    }

    @Test
    void authorSeesOwnFilingOnObjectView() {
        authenticateScoped(supervisorA, Set.of(
                "POST:/api/forms/drafts", "POST:/api/forms/submit", "GET:/form-submissions/{id}"));
        FormDraftRequest draft = new FormDraftRequest();
        draft.setFormDefinitionId(form.getId());
        draft.setReporterUnitId(unitA.getId());
        draft.setLocalId("own-" + UUID.randomUUID());
        draft.setFormData(Map.of("note", "filed in A"));
        FormSubmitResult drafted = formSubmissionService.upsertDraft(
                supervisorA.getId(), draft, ActionSource.WEB);
        assertThat(drafted.getOutcome()).isEqualTo(FormSubmissionService.OUTCOME_OK);

        FormSubmission saved = submissionRepository.findById(drafted.getSubmissionId()).orElseThrow();
        assertThat(saved.getSubjectUnitId()).isEqualTo(unitA.getId());

        FormSubmitRequest submit = new FormSubmitRequest();
        submit.setSubmissionId(drafted.getSubmissionId());
        submit.setClientActionId("own-act-" + UUID.randomUUID());
        submit.setFormData(Map.of("note", "filed in A"));
        FormSubmitResult submitted = formSubmissionService.submitFinal(
                supervisorA.getId(), submit, ActionSource.WEB);
        assertThat(submitted.getOutcome()).isEqualTo(FormSubmissionService.OUTCOME_OK);

        assertThat(formAccessService.canView(supervisorA.getId(),
                submissionRepository.findById(submitted.getSubmissionId()).orElseThrow())).isTrue();
        var pageA = formSubmissionService.pageVisible(
                supervisorA.getId(), form.getId(), null, PageRequest.of(0, 50));
        assertThat(pageA.getContent().stream().map(FormSubmission::getId))
                .contains(submitted.getSubmissionId());
    }

    private Long submitForUnit(Long userId, Long filingUnitId, String note) {
        FormDraftRequest draft = new FormDraftRequest();
        draft.setFormDefinitionId(form.getId());
        draft.setReporterUnitId(filingUnitId);
        draft.setLocalId("fr-" + UUID.randomUUID());
        draft.setFormData(Map.of("note", note));
        FormSubmitResult drafted = formSubmissionService.upsertDraft(userId, draft, ActionSource.WEB);
        assertThat(drafted.getOutcome()).isEqualTo(FormSubmissionService.OUTCOME_OK);

        FormSubmitRequest submit = new FormSubmitRequest();
        submit.setSubmissionId(drafted.getSubmissionId());
        submit.setClientActionId("fr-act-" + UUID.randomUUID());
        submit.setFormData(Map.of("note", note));
        FormSubmitResult result = formSubmissionService.submitFinal(userId, submit, ActionSource.WEB);
        assertThat(result.getOutcome()).isEqualTo(FormSubmissionService.OUTCOME_OK);
        return result.getSubmissionId();
    }

    private void authenticateScoped(User user, Set<String> authorities) {
        // No SCOPE_PLANT_WIDE → unit-scoped.
        AppUserDetails principal = new AppUserDetails(user, Set.of("SUPERVISOR"), authorities);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null,
                        authorities.stream().map(SimpleGrantedAuthority::new).collect(Collectors.toList())));
    }

    private OperationalUnit saveUnit(String code, String name) {
        OperationalUnit u = new OperationalUnit();
        u.setCode(code);
        u.setName(name);
        u.setCreatedAt(System.currentTimeMillis());
        u.setUpdatedAt(System.currentTimeMillis());
        return unitRepository.save(u);
    }

    private User saveUser(String username) {
        User u = new User();
        u.setUsername(username);
        u.setPersonnelCode("PC-" + UUID.randomUUID());
        u.setPasswordHash("x");
        u.setActive(true);
        return userRepository.save(u);
    }

    private void linkSupervisor(Long userId, Long unitId) {
        UnitSupervisor link = new UnitSupervisor();
        link.setUserId(userId);
        link.setUnitId(unitId);
        unitSupervisorRepository.save(link);
    }
}
