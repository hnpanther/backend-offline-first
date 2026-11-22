package com.hnp.backendofflinefirst.service;

import com.hnp.backendofflinefirst.domain.FormSubmissionStatus;
import com.hnp.backendofflinefirst.entity.FormDefinition;
import com.hnp.backendofflinefirst.entity.FormSubmission;
import com.hnp.backendofflinefirst.repository.FormDefinitionRepository;
import com.hnp.backendofflinefirst.repository.FormSubmissionRepository;
import com.hnp.backendofflinefirst.security.Capabilities;
import com.hnp.backendofflinefirst.security.SecurityUtils;
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
 *   <li><b>FILL</b> — form active, subject unit linked to the form, reporter unit is one the
 *       caller operates or supervises (or any unit when plant-wide). Cross-unit subject OK.</li>
 *   <li><b>VIEW (list)</b> — {@code subject_unit_id} in the caller's accessible units, same axis
 *       as log-sheet {@code operational_unit_id}. Reporter unit alone does <em>not</em> open a
 *       row. Plant-wide sees all.</li>
 *   <li><b>VIEW (object)</b> — list rule, <em>or</em> the caller is the author (mirrors log-sheet
 *       assignee own-work so an offline delivery is not orphaned after a unit move). Own-work
 *       is deliberately absent from list SQL.</li>
 *   <li><b>Edit draft</b> — author only, and only while {@code DRAFT}.</li>
 *   <li><b>Approve / void</b> — supervisor of the <em>subject</em> unit (or
 *       {@link Capabilities#SUPERVISE_ANY_UNIT}), never reporter-unit alone.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class FormAccessService {

    private final FormDefinitionRepository formRepository;
    private final FormDefinitionService formDefinitionService;
    private final FormSubmissionRepository submissionRepository;
    private final OperationalUnitScopeService unitScopeService;

    @Transactional(readOnly = true)
    public List<FormDefinition> fillableForms(Long userId) {
        Set<Long> subjectCandidates = subjectUnitCandidates(userId);
        if (subjectCandidates.isEmpty() && SecurityUtils.isUnitScopedOnly()) {
            return List.of();
        }
        if (!SecurityUtils.isUnitScopedOnly()) {
            return formRepository.findByActiveTrueOrderByTitleAsc().stream()
                    .filter(f -> !formDefinitionService.unitIds(f.getId()).isEmpty())
                    .toList();
        }
        return formDefinitionService.findActiveFillableForSubjectUnits(subjectCandidates);
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

    @Transactional(readOnly = true)
    public void assertCanFill(Long userId, FormDefinition form, Long reporterUnitId, Long subjectUnitId) {
        if (form == null || !form.isActive()) {
            throw new AccessDeniedException("Form is not available for filling.");
        }
        if (subjectUnitId == null || reporterUnitId == null) {
            throw new IllegalArgumentException("Reporter unit and subject unit are required.");
        }
        if (!formDefinitionService.subjectUnitAllowed(form.getId(), subjectUnitId)) {
            throw new AccessDeniedException("Subject unit is not linked to this form.");
        }
        if (SecurityUtils.isUnitScopedOnly()) {
            Set<Long> reporters = reporterUnitCandidates(userId);
            if (!reporters.contains(reporterUnitId)) {
                throw new AccessDeniedException("Reporter unit is outside the caller's scope.");
            }
        }
    }

    /**
     * Object-level visibility: plant-wide, author own-work, or {@code subject_unit_id} in scope.
     * Reporter unit is not enough — a supervisor of A must not open a filing whose subject is B.
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

    @Transactional(readOnly = true)
    public void assertCanEditDraft(Long userId, FormSubmission submission) {
        if (submission.getStatus() != FormSubmissionStatus.DRAFT) {
            throw new IllegalStateException("Submitted form cannot be edited.");
        }
        if (!Objects.equals(submission.getReportedByUserId(), userId)) {
            throw new AccessDeniedException("Only the author may edit a draft form submission.");
        }
    }

    /**
     * Review gate: supervisor of the <em>subject</em> unit (ownership), or plant-wide supervise.
     */
    @Transactional(readOnly = true)
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
