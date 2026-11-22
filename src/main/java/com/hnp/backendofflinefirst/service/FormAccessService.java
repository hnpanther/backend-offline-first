package com.hnp.backendofflinefirst.service;

import com.hnp.backendofflinefirst.entity.FormDefinition;
import com.hnp.backendofflinefirst.entity.FormSubmission;
import com.hnp.backendofflinefirst.repository.FormDefinitionRepository;
import com.hnp.backendofflinefirst.repository.FormSubmissionRepository;
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
 * Access rules for ad-hoc forms (decision 10).
 *
 * <ul>
 *   <li><b>FILL</b> — form active, subject unit linked to the form, reporter unit is one the
 *       caller operates or supervises (or any unit when plant-wide).</li>
 *   <li><b>VIEW</b> — reporter_unit <em>or</em> subject_unit in the caller's accessible units,
 *       or the caller is the author.</li>
 *   <li><b>Edit draft</b> — author only (supervisors may view, not mutate another person's draft).</li>
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
     * Units the caller may name as subject: accessible units when scoped; every linked unit of
     * active forms when plant-wide (subject still must be linked to the chosen form).
     */
    @Transactional(readOnly = true)
    public Set<Long> subjectUnitCandidates(Long userId) {
        if (!SecurityUtils.isUnitScopedOnly()) {
            return unitScopeService.getAccessibleUnitIds(userId); // still useful for reporter pick
        }
        return unitScopeService.getAccessibleUnitIds(userId);
    }

    @Transactional(readOnly = true)
    public Set<Long> reporterUnitCandidates(Long userId) {
        if (!SecurityUtils.isUnitScopedOnly()) {
            // Plant-wide actors may file under any unit they can see; prefer supervisor scope ∪ operated.
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
        // Cross-unit subject is intentional: subject need not be in reporter's operated units,
        // only linked to the form. Plant-wide callers may pick any reporter unit.
    }

    @Transactional(readOnly = true)
    public boolean canView(Long userId, FormSubmission submission) {
        if (submission == null || userId == null) {
            return false;
        }
        if (Objects.equals(submission.getReportedByUserId(), userId)) {
            return true;
        }
        if (!SecurityUtils.isUnitScopedOnly()) {
            return true;
        }
        Set<Long> accessible = unitScopeService.getAccessibleUnitIds(userId);
        return accessible.contains(submission.getReporterUnitId())
                || accessible.contains(submission.getSubjectUnitId());
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
        if (submission.getStatus() != com.hnp.backendofflinefirst.domain.FormSubmissionStatus.DRAFT) {
            throw new IllegalStateException("Submitted form cannot be edited.");
        }
        if (!Objects.equals(submission.getReportedByUserId(), userId)) {
            throw new AccessDeniedException("Only the author may edit a draft form submission.");
        }
    }

    @Transactional(readOnly = true)
    public Collection<Long> visibleUnitFilter(Long userId) {
        if (!SecurityUtils.isUnitScopedOnly()) {
            // Non-empty sentinel so JPQL IN (:unitIds) is never empty; unrestricted path ignores it.
            return List.of(-1L);
        }
        Set<Long> ids = unitScopeService.getAccessibleUnitIds(userId);
        return ids.isEmpty() ? List.of(-1L) : ids;
    }
}
