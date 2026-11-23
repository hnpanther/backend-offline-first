package com.hnp.backendofflinefirst.integration;

import com.hnp.backendofflinefirst.domain.ActionSource;
import com.hnp.backendofflinefirst.domain.FormSubmissionActionType;
import com.hnp.backendofflinefirst.domain.FormSubmissionStatus;
import com.hnp.backendofflinefirst.dto.FormDraftRequest;
import com.hnp.backendofflinefirst.dto.FormSubmitRequest;
import com.hnp.backendofflinefirst.dto.FormSubmitResult;
import com.hnp.backendofflinefirst.entity.FormDefinition;
import com.hnp.backendofflinefirst.entity.FormSubmission;
import com.hnp.backendofflinefirst.entity.FormSubmissionRevision;
import com.hnp.backendofflinefirst.entity.OperationalUnit;
import com.hnp.backendofflinefirst.entity.UnitOperator;
import com.hnp.backendofflinefirst.entity.UnitSupervisor;
import com.hnp.backendofflinefirst.entity.User;
import com.hnp.backendofflinefirst.repository.FormSubmissionRepository;
import com.hnp.backendofflinefirst.repository.OperationalUnitRepository;
import com.hnp.backendofflinefirst.repository.UnitOperatorRepository;
import com.hnp.backendofflinefirst.repository.UnitSupervisorRepository;
import com.hnp.backendofflinefirst.repository.UserRepository;
import com.hnp.backendofflinefirst.security.AppUserDetails;
import com.hnp.backendofflinefirst.security.Capabilities;
import com.hnp.backendofflinefirst.service.FormAccessService;
import com.hnp.backendofflinefirst.service.FormDefinitionService;
import com.hnp.backendofflinefirst.service.FormFieldService;
import com.hnp.backendofflinefirst.service.FormSubmissionActionLogger;
import com.hnp.backendofflinefirst.service.FormSubmissionRevisionService;
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
 * V31 reopen-with-revision history, and subject-unit VIEW hierarchy (parent supervisor sees
 * child-unit filings; operators stay direct-unit only).
 */
@Transactional
@WithAppUser(roles = "ADMIN", authorities = {
        "GET:/forms", "POST:/forms", "POST:/forms/{id}",
        "GET:/forms/{id}/fields", "POST:/forms/{id}/fields",
        "GET:/form-submissions", "GET:/form-submissions/{id}",
        "POST:/form-submissions/draft", "POST:/form-submissions/submit",
        "POST:/form-submissions/{id}/approve", "POST:/form-submissions/{id}/unapprove",
        "POST:/form-submissions/{id}/void", "POST:/form-submissions/{id}/unvoid",
        "POST:/form-submissions/{id}/reopen",
        "GET:/api/forms", "POST:/api/forms/drafts", "POST:/api/forms/submit",
        Capabilities.SCOPE_PLANT_WIDE, Capabilities.SUPERVISE_ANY_UNIT
})
@TestExecutionListeners(listeners = {
        DependencyInjectionTestExecutionListener.class,
        DirtiesContextTestExecutionListener.class,
        TransactionalTestExecutionListener.class,
        WithSecurityContextTestExecutionListener.class
}, mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS)
class FormReopenAndHierarchyIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired FormDefinitionService formDefinitionService;
    @Autowired FormFieldService formFieldService;
    @Autowired FormSubmissionService formSubmissionService;
    @Autowired FormAccessService formAccessService;
    @Autowired FormSubmissionRevisionService revisionService;
    @Autowired FormSubmissionActionLogger actionLogger;
    @Autowired FormSubmissionRepository submissionRepository;
    @Autowired OperationalUnitRepository unitRepository;
    @Autowired UserRepository userRepository;
    @Autowired UnitSupervisorRepository unitSupervisorRepository;
    @Autowired UnitOperatorRepository unitOperatorRepository;

    OperationalUnit parent;
    OperationalUnit child;
    FormDefinition form;
    User parentSupervisor;
    User childOperator;
    Long adminUserId = 1L;

    @BeforeEach
    void seed() {
        parent = saveUnit("FRH-P-" + System.nanoTime(), "والد", null);
        child = saveUnit("FRH-C-" + System.nanoTime(), "فرزند", parent.getId());
        form = formDefinitionService.create(
                "FRH-" + UUID.randomUUID().toString().substring(0, 8),
                "مشاهده دود آزمایشی",
                null,
                List.of(parent.getId(), child.getId()),
                true);
        formFieldService.create(form.getId(), "note", "توضیح", "text", null, true, Map.of(), 10);

        parentSupervisor = saveUser("frh-sup-" + System.nanoTime());
        childOperator = saveUser("frh-op-" + System.nanoTime());
        linkSupervisor(parentSupervisor.getId(), parent.getId());
        linkOperator(childOperator.getId(), child.getId());
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void reopenPreservesPriorSubmissionInRevisionAndAllowsSupervisorEdit() {
        Long submissionId = submitAs(adminUserId, child.getId(), child.getId(), "first-submit");

        FormSubmission reopened = formSubmissionService.reopen(
                submissionId, adminUserId, ActionSource.WEB, "fix reading");
        assertThat(reopened.getStatus()).isEqualTo(FormSubmissionStatus.DRAFT);
        assertThat(reopened.getSubmittedAt()).isNull();
        assertThat(reopened.getClientActionId()).isNull();
        assertThat(reopened.getFormData()).containsEntry("note", "first-submit");

        List<FormSubmissionRevision> revisions = revisionService.findForSubmission(submissionId);
        assertThat(revisions).hasSize(1);
        assertThat(revisions.getFirst().getFormData()).containsEntry("note", "first-submit");
        assertThat(revisions.getFirst().getSubmissionStatus()).isEqualTo(FormSubmissionStatus.SUBMITTED);

        assertThat(actionLogger.history(submissionId).stream().map(h -> h.getAction()))
                .contains(FormSubmissionActionType.REOPEN, FormSubmissionActionType.SUBMIT);

        authenticateScoped(parentSupervisor, Set.of(
                "POST:/form-submissions/draft", "POST:/form-submissions/submit",
                "GET:/form-submissions/{id}", "POST:/form-submissions/{id}/reopen"));

        FormDraftRequest draft = new FormDraftRequest();
        draft.setFormDefinitionId(form.getId());
        draft.setSubmissionId(submissionId);
        draft.setReporterUnitId(child.getId());
        draft.setSubjectUnitId(child.getId());
        draft.setFormData(Map.of("note", "corrected-by-supervisor"));
        FormSubmitResult saved = formSubmissionService.upsertDraft(
                parentSupervisor.getId(), draft, ActionSource.WEB);
        assertThat(saved.getOutcome()).isEqualTo(FormSubmissionService.OUTCOME_OK);

        FormSubmission afterEdit = submissionRepository.findById(submissionId).orElseThrow();
        assertThat(afterEdit.getFormData()).containsEntry("note", "corrected-by-supervisor");
        assertThat(revisionService.findForSubmission(submissionId)).hasSizeGreaterThanOrEqualTo(1);
        assertThat(revisionService.findForSubmission(submissionId).getFirst().getFormData())
                .containsEntry("note", "first-submit");

        FormSubmitRequest resubmit = new FormSubmitRequest();
        resubmit.setSubmissionId(submissionId);
        resubmit.setClientActionId("frh-resubmit-" + UUID.randomUUID());
        resubmit.setFormData(Map.of("note", "corrected-by-supervisor"));
        FormSubmitResult finalAgain = formSubmissionService.submitFinal(
                parentSupervisor.getId(), resubmit, ActionSource.WEB);
        assertThat(finalAgain.getOutcome()).isEqualTo(FormSubmissionService.OUTCOME_OK);
        assertThat(actionLogger.history(submissionId).stream()
                .filter(h -> h.getAction() == FormSubmissionActionType.SUBMIT))
                .hasSize(2);
    }

    @Test
    void reopenRefusesApprovedAndNonSubjectSupervisor() {
        Long submissionId = submitAs(adminUserId, child.getId(), child.getId(), "locked");
        formSubmissionService.approve(submissionId, adminUserId, ActionSource.WEB, null);

        assertThatThrownBy(() -> formSubmissionService.reopen(
                submissionId, adminUserId, ActionSource.WEB, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Only submitted");

        formSubmissionService.unapprove(submissionId, adminUserId, ActionSource.WEB, null);

        User stranger = saveUser("frh-stranger-" + System.nanoTime());
        OperationalUnit other = saveUnit("FRH-O-" + System.nanoTime(), "دیگر", null);
        linkSupervisor(stranger.getId(), other.getId());
        authenticateScoped(stranger, Set.of(
                "POST:/form-submissions/{id}/reopen", "GET:/form-submissions/{id}"));
        assertThatThrownBy(() -> formSubmissionService.reopen(
                submissionId, stranger.getId(), ActionSource.WEB, null))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void parentSupervisorSeesChildSubjectFilings_operatorOfParentDoesNot() {
        Long submissionId = submitAs(adminUserId, child.getId(), child.getId(), "child-row");

        authenticateScoped(parentSupervisor, Set.of("GET:/form-submissions", "GET:/form-submissions/{id}"));
        var pageParent = formSubmissionService.pageVisible(
                parentSupervisor.getId(), form.getId(), null, PageRequest.of(0, 50));
        assertThat(pageParent.getContent().stream().map(FormSubmission::getId))
                .contains(submissionId);
        assertThat(formAccessService.canView(parentSupervisor.getId(),
                submissionRepository.findById(submissionId).orElseThrow())).isTrue();

        User parentOperator = saveUser("frh-parent-op-" + System.nanoTime());
        linkOperator(parentOperator.getId(), parent.getId());
        authenticateScoped(parentOperator, Set.of("GET:/form-submissions", "GET:/form-submissions/{id}"));
        var pageOp = formSubmissionService.pageVisible(
                parentOperator.getId(), form.getId(), null, PageRequest.of(0, 50));
        assertThat(pageOp.getContent().stream().map(FormSubmission::getId))
                .doesNotContain(submissionId);
        assertThat(formAccessService.canView(parentOperator.getId(),
                submissionRepository.findById(submissionId).orElseThrow())).isFalse();
    }

    private Long submitAs(Long userId, Long reporterUnitId, Long subjectUnitId, String note) {
        FormDraftRequest draft = new FormDraftRequest();
        draft.setFormDefinitionId(form.getId());
        draft.setReporterUnitId(reporterUnitId);
        draft.setSubjectUnitId(subjectUnitId);
        draft.setLocalId("frh-" + UUID.randomUUID());
        draft.setFormData(Map.of("note", note));
        FormSubmitResult drafted = formSubmissionService.upsertDraft(userId, draft, ActionSource.WEB);
        assertThat(drafted.getOutcome()).isEqualTo(FormSubmissionService.OUTCOME_OK);

        FormSubmitRequest submit = new FormSubmitRequest();
        submit.setSubmissionId(drafted.getSubmissionId());
        submit.setClientActionId("frh-act-" + UUID.randomUUID());
        submit.setFormData(Map.of("note", note));
        FormSubmitResult result = formSubmissionService.submitFinal(userId, submit, ActionSource.WEB);
        assertThat(result.getOutcome()).isEqualTo(FormSubmissionService.OUTCOME_OK);
        return result.getSubmissionId();
    }

    private void authenticateScoped(User user, Set<String> authorities) {
        AppUserDetails principal = new AppUserDetails(user, Set.of("SUPERVISOR"), authorities);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null,
                        authorities.stream().map(SimpleGrantedAuthority::new).collect(Collectors.toList())));
    }

    private OperationalUnit saveUnit(String code, String name, Long parentId) {
        OperationalUnit u = new OperationalUnit();
        u.setCode(code);
        u.setName(name);
        u.setParentId(parentId);
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

    private void linkOperator(Long userId, Long unitId) {
        UnitOperator link = new UnitOperator();
        link.setUserId(userId);
        link.setUnitId(unitId);
        unitOperatorRepository.save(link);
    }
}
