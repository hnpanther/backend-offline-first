package com.hnp.backendofflinefirst.service;

import com.hnp.backendofflinefirst.domain.FormSubmissionStatus;
import com.hnp.backendofflinefirst.entity.FormDefinition;
import com.hnp.backendofflinefirst.entity.FormSubmission;
import com.hnp.backendofflinefirst.repository.FormDefinitionRepository;
import com.hnp.backendofflinefirst.repository.FormSubmissionRepository;
import com.hnp.backendofflinefirst.security.Capabilities;
import com.hnp.backendofflinefirst.security.SecurityUtils;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Access rules for ad-hoc forms (decision 10 + unit-scoped VIEW).
 *
 * <ul>
 *   <li><b>FILL</b> — form active; filing unit ({@code reporter_unit_id}) is one the caller
 *       operates or supervises (or any when plant-wide) <em>and</em> is linked on
 *       {@code form_definition_units}. Server always sets {@code subject_unit_id = reporter_unit_id}
 *       (observation location, if needed, is an optional lookup field on the form — not a second
 *       unit column).</li>
 *   <li><b>VIEW (list)</b> — {@code subject_unit_id} in the caller's accessible units, same axis
 *       as log-sheet {@code operational_unit_id}. Because subject mirrors reporter on write, this
 *       is the filing unit. Plant-wide sees all.</li>
 *   <li><b>VIEW (object)</b> — list rule, <em>or</em> the caller is the author (mirrors log-sheet
 *       assignee own-work so an offline delivery is not orphaned after a unit move). Own-work
 *       is deliberately absent from list SQL.</li>
 *   <li><b>Edit draft</b> — author, <em>or</em> subject-unit supervisor (so a reopen can be
 *       corrected on the web). Only while {@code DRAFT}.</li>
 *   <li><b>Approve / void / reopen</b> — supervisor of the <em>subject</em> unit (or
 *       {@link Capabilities#SUPERVISE_ANY_UNIT}). With subject = reporter that is the filing
 *       unit's supervisor. Reopen returns {@code SUBMITTED → DRAFT}; prior answers are kept in
 *       {@code form_submission_revisions}.</li>
 * </ul>
 *
 * <p><b>Hierarchy.</b> {@link OperationalUnitScopeService#getAccessibleUnitIds} already expands
 * supervisor membership downward and keeps operators unit-scoped — VIEW list/object and FILL
 * reporter candidates reuse that set. Parent-unit supervisors therefore see subordinate units'
 * submissions; operators of a parent do not see children.
 */
@Service
@RequiredArgsConstructor
public class FormAccessService {

    private final EntityManager entityManager;
    private final FormDefinitionRepository formRepository;
    private final FormDefinitionService formDefinitionService;
    private final FormSubmissionRepository submissionRepository;
    private final OperationalUnitScopeService unitScopeService;

    @Transactional(readOnly = true)
    public List<FormDefinition> fillableForms(Long userId) {
        if (!SecurityUtils.isUnitScopedOnly()) return formRepository.findActiveWithUnitLinks();
        return formDefinitionService.findActiveFillableForSubjectUnits(subjectUnitCandidates(userId));
    }

    /**
     * Active forms for sidebar navigation. Navigation only — never a security boundary.
     * Filtered to forms that have at least one subject-unit link intersecting the caller's
     * accessible units when scoped; plant-wide sees every active form with links.
     */
    @Transactional(readOnly = true)
    public List<FormDefinition> navForms(Long userId) {
        if (userId == null) {
            return List.of();
        }
        if (!SecurityUtils.isUnitScopedOnly()) {
            return formRepository.findByActiveTrueOrderByTitleAsc();
        }
        Set<Long> units = unitScopeService.getAccessibleUnitIds(userId);
        if (units.isEmpty()) {
            return List.of();
        }
        return formDefinitionService.findActiveFillableForSubjectUnits(units);
    }

    @Transactional(readOnly = true)
    public Set<Long> subjectUnitCandidates(Long userId) {
        return unitScopeService.getAccessibleUnitIds(userId);
    }

    @Transactional(readOnly = true)
    public Set<Long> reporterUnitCandidates(Long userId) {
        if (!SecurityUtils.isUnitScopedOnly()) {
            Set<Long> all = new LinkedHashSet<>(unitScopeService.getAccessibleUnitIds(userId));
            all.addAll(unitScopeService.getSupervisorScopeUnitIds(userId));
            return all;
        }
        Set<Long> out = new LinkedHashSet<>(unitScopeService.getAccessibleUnitIds(userId));
        out.addAll(unitScopeService.getSupervisorScopeUnitIds(userId));
        return out;
    }

    /**
     * FILL gate on the filing unit only. Caller must pass the unit that will be stored as
     * {@code reporter_unit_id} (and mirrored onto {@code subject_unit_id}).
     */
    @Transactional(readOnly = true, noRollbackFor = {IllegalArgumentException.class, IllegalStateException.class, AccessDeniedException.class})
    public void assertCanFill(Long userId, FormDefinition form, Long reporterUnitId) {
        if (form == null || !form.isActive()) {
            throw new AccessDeniedException("Form is not available for filling.");
        }
        if (reporterUnitId == null) {
            throw new IllegalArgumentException("Reporter unit is required.");
        }
        // form_definition_units = which filing units may use this form (subject mirrors reporter).
        if (!formDefinitionService.subjectUnitAllowed(form.getId(), reporterUnitId)) {
            throw new AccessDeniedException("Filing unit is not linked to this form.");
        }
        if (SecurityUtils.isUnitScopedOnly()) {
            Set<Long> reporters = reporterUnitCandidates(userId);
            if (!reporters.contains(reporterUnitId)) {
                throw new AccessDeniedException("Reporter unit is outside the caller's scope.");
            }
        }
    }

    /**
     * Object-level visibility: plant-wide, author own-work, or {@code subject_unit_id} in scope
     * (equals filing unit on every write path).
     */
    @Transactional(readOnly = true)
    public boolean canView(Long userId, FormSubmission submission) {
        if (submission == null || userId == null) {
            return false;
        }
        if (!SecurityUtils.isUnitScopedOnly()) {
            return true;
        }
        if (isOwnWork(userId, submission)) {
            return true;
        }
        return unitScopeService.canAccessUnit(userId, submission.getSubjectUnitId());
    }

    @Transactional(readOnly = true)
    public FormSubmission requireVisible(Long userId, Long submissionId) {
        FormSubmission submission = submissionRepository.findById(submissionId)
                .orElseThrow(() -> new IllegalArgumentException("Form submission not found: " + submissionId));
        if (!canView(userId, submission)) {
            throw new AccessDeniedException("Form submission is not visible.");
        }
        return submission;
    }

    /** The panel may already have loaded the row into its request-scoped persistence context. */
    public FormSubmission requireVisibleForUpdate(Long userId, Long submissionId) {
        FormSubmission submission = submissionRepository.findById(submissionId)
                .orElseThrow(() -> new IllegalArgumentException("Form submission not found: " + submissionId));
        if (entityManager.getLockMode(submission) != LockModeType.PESSIMISTIC_WRITE) {
            // A second service call in this transaction must see the first call's pending writes.
            entityManager.flush();
            entityManager.refresh(submission, LockModeType.PESSIMISTIC_WRITE);
        }
        if (!canView(userId, submission)) {
            throw new AccessDeniedException("Form submission is not visible.");
        }
        return submission;
    }

    /**
     * Answer edits and attachment deletes. A late upload of media the submit already named
     * is decided in {@code AttachmentService#uploadForFormSubmission} — widening this method
     * would make a submitted form editable, and it must not be copied onto the log-sheet
     * upload path.
     */
    @Transactional(readOnly = true, noRollbackFor = {IllegalArgumentException.class, IllegalStateException.class, AccessDeniedException.class})
    public void assertCanEditDraft(Long userId, FormSubmission submission) {
        if (submission.getStatus() != FormSubmissionStatus.DRAFT) {
            throw new IllegalStateException("Submitted form cannot be edited.");
        }
        if (Objects.equals(submission.getReportedByUserId(), userId)) {
            return;
        }
        // Subject supervisor (or plant-wide supervise) — needed after reopen, and mirrors
        // log-sheet web completion by a unit supervisor.
        assertCanReview(userId, submission);
    }

    /**
     * Review gate: supervisor of the <em>subject</em> unit (ownership), or plant-wide supervise.
     */
    @Transactional(readOnly = true, noRollbackFor = {IllegalArgumentException.class, IllegalStateException.class, AccessDeniedException.class})
    public void assertCanReview(Long userId, FormSubmission submission) {
        if (SecurityUtils.hasCapability(Capabilities.SUPERVISE_ANY_UNIT)) {
            return;
        }
        if (!unitScopeService.isSupervisorOf(userId, submission.getSubjectUnitId())) {
            throw new AccessDeniedException("You are not the supervisor of this unit.");
        }
    }

    @Transactional(readOnly = true)
    public Collection<Long> visibleUnitFilter(Long userId) {
        if (!SecurityUtils.isUnitScopedOnly()) {
            return List.of(-1L);
        }
        Set<Long> ids = unitScopeService.getAccessibleUnitIds(userId);
        return ids.isEmpty() ? List.of(-1L) : ids;
    }

    private static boolean isOwnWork(Long userId, FormSubmission submission) {
        return Objects.equals(submission.getReportedByUserId(), userId);
    }
}
