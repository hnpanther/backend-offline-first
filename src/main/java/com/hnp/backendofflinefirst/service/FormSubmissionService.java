package com.hnp.backendofflinefirst.service;

import com.hnp.backendofflinefirst.domain.ActionSource;
import com.hnp.backendofflinefirst.domain.AttachmentKind;
import com.hnp.backendofflinefirst.domain.FieldDefinitionSnapshot;
import com.hnp.backendofflinefirst.domain.FormDataValidationSupport;
import com.hnp.backendofflinefirst.domain.FormSubmissionActionType;
import com.hnp.backendofflinefirst.domain.FormSubmissionStatus;
import com.hnp.backendofflinefirst.domain.SignatureValues;
import com.hnp.backendofflinefirst.dto.FormDraftRequest;
import com.hnp.backendofflinefirst.dto.FormSubmitRequest;
import com.hnp.backendofflinefirst.dto.FormSubmitResult;
import com.hnp.backendofflinefirst.entity.FieldDefinition;
import com.hnp.backendofflinefirst.entity.FormDefinition;
import com.hnp.backendofflinefirst.entity.FormSubmission;
import com.hnp.backendofflinefirst.entity.User;
import com.hnp.backendofflinefirst.repository.AttachmentRepository;
import com.hnp.backendofflinefirst.repository.FormSubmissionRepository;
import com.hnp.backendofflinefirst.repository.UserRepository;
import com.hnp.backendofflinefirst.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Draft upsert, final submit, and review transitions for ad-hoc form submissions.
 *
 * <p>Freeze-on-first-draft. After {@code SUBMITTED}, answers are immutable until a subject-unit
 * supervisor <em>reopens</em> to {@code DRAFT} (V31); the prior map is kept in
 * {@code form_submission_revisions}. Approve / void are status transitions only. List visibility
 * is subject-unit scoped via {@link OperationalUnitScopeService#getAccessibleUnitIds}.
 */
@Service
@RequiredArgsConstructor
public class FormSubmissionService {

    public static final String OUTCOME_OK = "OK";
    public static final String OUTCOME_DUPLICATE = "DUPLICATE";
    public static final String OUTCOME_VALIDATION_ERROR = "VALIDATION_ERROR";
    public static final String OUTCOME_DENIED = "DENIED";
    public static final String OUTCOME_NOT_FOUND = "NOT_FOUND";
    public static final String OUTCOME_IMMUTABLE = "IMMUTABLE";

    public static final String OUTCOME_CONFLICT = "CONFLICT";
    private static final int FINAL_ACTION_LOCK = 0x4143544e;
    private static final int LOCAL_DELIVERY_LOCK = 0x464f524d;
    private static final int MAX_COMMENT_LENGTH = 2000;

    private final FormSubmissionRepository submissionRepository;
    private final FormDefinitionService formDefinitionService;
    private final FormFieldService formFieldService;
    private final AttachmentRepository attachmentRepository;
    private final FormSchemaService formSchemaService;
    private final FormAccessService formAccessService;
    private final FormSubmissionActionLogger actionLogger;
    private final FormSubmissionRevisionService revisionService;
    private final LookupAnswerService lookupAnswerService;
    private final UserRepository userRepository;

    @Value("${app.sync.batch-max-items:500}")
    private int batchMaxItems;

    @Transactional(readOnly = true)
    public FormSubmission require(Long id) {
        return submissionRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Form submission not found: " + id));
    }

    @Transactional(readOnly = true)
    public Page<FormSubmission> pageVisible(Long userId, Long formId, FormSubmissionStatus status,
                                            Pageable pageable) {
        boolean unrestricted = !SecurityUtils.isUnitScopedOnly();
        return submissionRepository.findVisibleFiltered(
                unrestricted,
                formAccessService.visibleUnitFilter(userId),
                formId,
                status,
                pageable);
    }

    @Transactional
    public FormSubmitResult upsertDraft(Long userId, FormDraftRequest request, ActionSource source) {
        if (request == null) {
            return FormSubmitResult.fail(null, null, OUTCOME_VALIDATION_ERROR, "Draft request is required.");
        }
        try {
            FormSubmission saved = upsertDraftInternal(userId, request, source);
            return accepted(saved, OUTCOME_OK);
        } catch (StaleSubmission ex) {
            return FormSubmitResult.fail(ex.submissionId, request.getLocalId(), OUTCOME_CONFLICT, ex.getMessage());
        } catch (IllegalArgumentException | IllegalStateException ex) {
            return FormSubmitResult.fail(null, request.getLocalId(), OUTCOME_VALIDATION_ERROR, ex.getMessage());
        } catch (AccessDeniedException ex) {
            return FormSubmitResult.fail(null, request.getLocalId(), OUTCOME_DENIED, ex.getMessage());
        }
    }

    @Transactional
    public FormSubmitResult submitFinal(Long userId, FormSubmitRequest request, ActionSource source) {
        if (request == null) {
            return FormSubmitResult.fail(null, null, OUTCOME_VALIDATION_ERROR, "Submit request is required.");
        }
        String clientActionId = blankToNull(request.getClientActionId());
        if (clientActionId == null) {
            return FormSubmitResult.fail(null, request.getLocalId(), OUTCOME_VALIDATION_ERROR,
                    "clientActionId is required for final submit.");
        }
        try {
            submissionRepository.lockDeliveryKey(FINAL_ACTION_LOCK, clientActionId);
            var receipt = actionLogger.receipt(clientActionId);
            if (receipt.isPresent()) {
                var prior = receipt.get();
                if (userId == null || !userId.equals(prior.getActorUserId()) ||
                        (request.getSubmissionId() != null && !request.getSubmissionId().equals(prior.getFormSubmissionId()))) {
                    return refusedReceipt(request);
                }
                var target = formAccessService.requireVisibleForUpdate(userId, prior.getFormSubmissionId());
                if (!matchesReceiptTarget(request, target) ||
                        (request.getFormDefinitionId() != null && !request.getFormDefinitionId().equals(target.getFormDefinitionId()))) {
                    return refusedReceipt(request);
                }
                if (!clientActionId.equals(target.getClientActionId())) throw new StaleSubmission(target.getId());
                return accepted(target, OUTCOME_DUPLICATE);
            }
            FormSubmission saved = submitFinalInternal(userId, request, source, clientActionId);
            return accepted(saved, OUTCOME_OK);
        } catch (StaleSubmission ex) {
            return FormSubmitResult.fail(ex.submissionId, request.getLocalId(), OUTCOME_CONFLICT, ex.getMessage());
        } catch (IllegalArgumentException | IllegalStateException ex) {
            String code = ex.getMessage() != null && ex.getMessage().startsWith("Form submission validation")
                    ? OUTCOME_VALIDATION_ERROR
                    : (ex.getMessage() != null && ex.getMessage().contains("immutable")
                    ? OUTCOME_IMMUTABLE
                    : OUTCOME_VALIDATION_ERROR);
            return FormSubmitResult.fail(request.getSubmissionId(), request.getLocalId(), code, ex.getMessage());
        } catch (AccessDeniedException ex) {
            return FormSubmitResult.fail(request.getSubmissionId(), request.getLocalId(), OUTCOME_DENIED,
                    ex.getMessage());
        }
    }

    @Transactional
    public FormSubmission approve(Long id, Long actor, ActionSource source, String comment) {
        return review(id, actor, source, FormSubmissionActionType.APPROVE, comment, null);
    }

    @Transactional
    public FormSubmission unapprove(Long id, Long actor, ActionSource source, String comment) {
        return review(id, actor, source, FormSubmissionActionType.UNAPPROVE, comment, null);
    }

    @Transactional
    public FormSubmission voidSubmitted(Long id, Long actor, ActionSource source, String comment) {
        return review(id, actor, source, FormSubmissionActionType.VOID, comment, null);
    }

    @Transactional
    public FormSubmission restoreVoided(Long id, Long actor, ActionSource source, String comment) {
        return review(id, actor, source, FormSubmissionActionType.UNVOID, comment, null);
    }

    @Transactional
    public FormSubmission reopen(Long id, Long actor, ActionSource source, String comment) {
        return review(id, actor, source, FormSubmissionActionType.REOPEN, comment, null);
    }

    @Transactional
    public FormSubmission review(Long id, Long actor, ActionSource source, FormSubmissionActionType action,
                                 String comment, Long expectedUpdatedAt) {
        String reason = normalizeComment(comment);
        FormSubmission submission = formAccessService.requireVisibleForUpdate(actor, id);
        formAccessService.assertCanReview(actor, submission);
        checkBase(submission, expectedUpdatedAt);
        long now = nextUpdate(submission);
        switch (action) {
            case APPROVE -> {
                requireStatus(submission, FormSubmissionStatus.SUBMITTED, "Only submitted form submissions can be approved.");
                submission.setApprovedAt(now);
                submission.setApprovedByUserId(actor);
                submission.setStatus(FormSubmissionStatus.APPROVED);
            }
            case UNAPPROVE -> {
                requireStatus(submission, FormSubmissionStatus.APPROVED, "Only approved form submissions can have their approval withdrawn.");
                submission.setApprovedAt(null);
                submission.setApprovedByUserId(null);
                submission.setStatus(FormSubmissionStatus.SUBMITTED);
            }
            case VOID -> {
                requireStatus(submission, FormSubmissionStatus.SUBMITTED, "Only submitted form submissions can be voided.");
                submission.setStatus(FormSubmissionStatus.VOIDED);
            }
            case UNVOID -> {
                requireStatus(submission, FormSubmissionStatus.VOIDED, "Only voided form submissions can be restored.");
                submission.setStatus(FormSubmissionStatus.SUBMITTED);
            }
            case REOPEN -> {
                requireStatus(submission, FormSubmissionStatus.SUBMITTED, "Only submitted form submissions can be reopened.");
                revisionService.recordSupersededAnswers(submission, actor, source, now);
                submission.setSubmittedAt(null);
                submission.setClientActionId(null);
                submission.setDraftClientActionId(null);
                submission.setApprovedAt(null);
                submission.setApprovedByUserId(null);
                submission.setStatus(FormSubmissionStatus.DRAFT);
            }
            default -> throw new IllegalArgumentException("Unsupported review action.");
        }
        submission.setUpdatedAt(now);
        FormSubmission saved = submissionRepository.save(submission);
        actionLogger.record(id, action, source, actor, reason);
        return saved;
    }

    private static void requireStatus(FormSubmission submission, FormSubmissionStatus required, String message) {
        if (submission.getStatus() != required) throw new IllegalStateException(message);
    }

    private FormSubmission upsertDraftInternal(Long userId, FormDraftRequest request, ActionSource source) {
        FormDefinition form = formDefinitionService.require(request.getFormDefinitionId());
        Long reporterUnitId = request.getReporterUnitId();
        // subject_unit_id mirrors reporter for V30 VIEW / review (no separate subject picker).
        Long subjectUnitId = reporterUnitId;
        formAccessService.assertCanFill(userId, form, reporterUnitId);

        FormSubmission submission = resolveTarget(userId, request.getSubmissionId(), request.getLocalId(), request.getExpectedUpdatedAt());
        if (submission != null) {
            if (submission.getStatus() != FormSubmissionStatus.DRAFT) checkBase(submission, request.getExpectedUpdatedAt());
            formAccessService.assertCanEditDraft(userId, submission);
            if (!Objects.equals(submission.getFormDefinitionId(), form.getId())) {
                throw new IllegalArgumentException("Draft belongs to a different form definition.");
            }
            if (request.getExpectedUpdatedAt() != null && request.getClientActionId() != null
                    && request.getClientActionId().equals(submission.getDraftClientActionId())
                    && Objects.equals(userId, submission.getLastSavedByUserId())
                    && Objects.equals(request.getDeviceTimeAt(), submission.getDeviceTimeAt())) {
                return submission;
            }
            checkBase(submission, request.getExpectedUpdatedAt());
        } else {
            submission = newDraft(userId, form, reporterUnitId, subjectUnitId, request.getLocalId(), source);
        }

        resolveSchema(submission, request.getSchemaVersionId(), request.getLegacyFields(), source);

        Map<String, Object> incoming = request.getFormData() == null ? Map.of() : request.getFormData();
        String partial = formFieldService.partialValidationMessage(submission, incoming,
                source == ActionSource.MOBILE && request.getDeviceTimeAt() != null
                        ? request.getDeviceTimeAt() : System.currentTimeMillis());
        if (partial != null) {
            throw new IllegalArgumentException(partial);
        }
        long now = nextUpdate(submission);
        applyAnswers(submission, incoming, userId, source, now);
        submission.setReporterUnitId(reporterUnitId);
        submission.setSubjectUnitId(subjectUnitId);
        submission.setDraftClientActionId(blankToNull(request.getClientActionId()));
        submission.setDeviceTimeAt(request.getDeviceTimeAt());
        submission.setUpdatedAt(now);
        submission.setSyncedAt(now);
        return submissionRepository.save(submission);
    }

    private static boolean matchesReceiptTarget(FormSubmitRequest request, FormSubmission target) {
        String localId = blankToNull(request.getLocalId());
        if (request.getSubmissionId() == null) return Objects.equals(localId, target.getLocalId());
        return localId == null || localId.equals(target.getLocalId()) ||
                (target.getLocalId() == null && localId.equals("server-form:" + target.getId()));
    }

    private static FormSubmitResult refusedReceipt(FormSubmitRequest request) {
        return FormSubmitResult.fail(null, request.getLocalId(), OUTCOME_DENIED, "Form is not available for filling.");
    }

    private FormSubmission submitFinalInternal(Long userId, FormSubmitRequest request, ActionSource source,
                                               String clientActionId) {
        FormSubmission submission = resolveTarget(userId, request.getSubmissionId(), request.getLocalId(), request.getExpectedUpdatedAt());
        if (submission != null) {
            if (source == ActionSource.MOBILE && request.getExpectedUpdatedAt() == null && actionLogger.wasReopened(submission.getId())) {
                throw new StaleSubmission(submission.getId());
            }
            checkBase(submission, request.getExpectedUpdatedAt());
            formAccessService.assertCanEditDraft(userId, submission);
        }

        FormDefinition form;
        if (submission == null) {
            if (request.getFormDefinitionId() == null) {
                throw new IllegalArgumentException("formDefinitionId is required to create on submit.");
            }
            form = formDefinitionService.require(request.getFormDefinitionId());
            Long reporterUnitId = request.getReporterUnitId();
            formAccessService.assertCanFill(userId, form, reporterUnitId);
            submission = newDraft(userId, form, reporterUnitId, reporterUnitId,
                    request.getLocalId(), source);
        } else {
            form = formDefinitionService.require(submission.getFormDefinitionId());
        }

        Long filingUnitId = request.getReporterUnitId() != null
                ? request.getReporterUnitId() : submission.getReporterUnitId();
        // Existing drafts must pass the same availability/scope gate as new filings.
        formAccessService.assertCanFill(userId, form, filingUnitId);

        if (submission.getStatus() != null && submission.getStatus().isFinal()) {
            throw new IllegalStateException("Submitted form is immutable.");
        }

        resolveSchema(submission, request.getSchemaVersionId(), request.getLegacyFields(), source);

        Map<String, Object> incoming = request.getFormData() == null
                ? (submission.getFormData() == null ? Map.of() : submission.getFormData())
                : request.getFormData();
        String full = formFieldService.fullValidationMessage(submission, incoming,
                source == ActionSource.MOBILE && request.getDeviceTimeAt() != null
                        ? request.getDeviceTimeAt() : System.currentTimeMillis());
        if (full != null) {
            throw new IllegalArgumentException(full);
        }
        if (request.isRequireUploadedMedia()) assertMediaDelivered(submission, incoming);

        long now = nextUpdate(submission);
        applyAnswers(submission, incoming, userId, source, now);
        // Do not dirty a managed entity until validation and answer stamping have succeeded.
        submission.setReporterUnitId(filingUnitId);
        submission.setSubjectUnitId(filingUnitId);
        submission.setClientActionId(clientActionId);
        submission.setStatus(FormSubmissionStatus.SUBMITTED);
        submission.setSubmittedAt(now);
        submission.setUpdatedAt(now);
        submission.setSyncedAt(now);
        submission.setDeviceTimeAt(request.getDeviceTimeAt() != null
                ? request.getDeviceTimeAt()
                : submission.getDeviceTimeAt());
        FormSubmission saved = submissionRepository.save(submission);
        actionLogger.record(saved.getId(), FormSubmissionActionType.SUBMIT, source, userId, null, clientActionId);
        return saved;
    }

    /**
     * Stamps lookup + signature like log-sheet write paths, and records a revision when the map
     * actually changes (a first fill writes none).
     */
    private void applyAnswers(FormSubmission submission, Map<String, Object> incoming,
                              Long actorUserId, ActionSource source, long now) {
        List<FieldDefinition> defs = formFieldService.resolveForSubmission(submission);
        Map<String, Object> previous = submission.getFormData();
        Map<String, Object> storable = formFieldService.storable(submission, new HashMap<>(incoming));
        Map<String, Object> stamped = SignatureValues.stamp(
                lookupAnswerService.stamp(storable, previous, defs),
                previous, defs, actorUserId, now);
        if (!Objects.equals(previous, stamped)
                && FormDataValidationSupport.hasMeaningfulFormData(previous)) {
            revisionService.recordSupersededAnswers(submission, actorUserId, source, now);
        }
        if (!Objects.equals(actorUserId, submission.getLastSavedByUserId())) {
            String name = userRepository.findById(actorUserId).map(FormSubmissionService::displayName)
                    .orElseThrow(() -> new IllegalArgumentException("User not found: " + actorUserId));
            submission.setLastSavedByUserId(actorUserId);
            submission.setLastSavedByName(name);
        }
        submission.setFormData(stamped);
    }

    private FormSubmission resolveTarget(Long userId, Long submissionId, String localId, Long expected) {
        if (submissionId == null && blankToNull(localId) != null) {
            submissionRepository.lockDeliveryKey(LOCAL_DELIVERY_LOCK, userId + "/" + localId.trim());
            submissionId = submissionRepository.findByReportedByUserIdAndLocalId(userId, localId.trim())
                    .map(FormSubmission::getId).orElse(null);
        }
        if (submissionId == null && expected != null && expected != 0) throw new StaleSubmission(null);
        return submissionId == null ? null : formAccessService.requireVisibleForUpdate(userId, submissionId);
    }

    private static FormSubmitResult accepted(FormSubmission submission, String outcome) {
        var result = FormSubmitResult.ok(submission.getId(), submission.getLocalId(), outcome, submission.getStatus().name());
        result.setUpdatedAt(submission.getUpdatedAt());
        return result;
    }

    private static long nextUpdate(FormSubmission submission) {
        long previous = submission.getUpdatedAt() == null ? 0L : submission.getUpdatedAt();
        return Math.max(System.currentTimeMillis(), previous + 1);
    }

    private static void checkBase(FormSubmission submission, Long expected) {
        if (expected != null && !Objects.equals(expected, submission.getUpdatedAt())) {
            throw new StaleSubmission(submission.getId());
        }
    }

    private static final class StaleSubmission extends IllegalStateException {
        private final Long submissionId;

        StaleSubmission(Long submissionId) {
            super("The form has changed since it was opened. Review the latest server answers.");
            this.submissionId = submissionId;
        }
    }

    private void resolveSchema(FormSubmission submission, Long versionId,
                               List<FieldDefinitionSnapshot> evidence, ActionSource source) {
        var frozen = submission.getFieldDefinitionsSnapshot();
        var formId = submission.getFormDefinitionId();
        if (versionId != null) {
            var published = formSchemaService.findPublished(formId, versionId)
                    .orElseThrow(() -> new IllegalArgumentException("Form schema version is not available."));
            if (frozen != null && !frozen.equals(published)) {
                throw new IllegalArgumentException("Form schema version does not match the saved draft.");
            }
            if (frozen == null) submission.setFieldDefinitionsSnapshot(published);
            return;
        }
        if (frozen != null) {
            if (evidence != null && !frozen.equals(evidence)) throw legacySchemaMismatch();
            return;
        }
        if (source == ActionSource.MOBILE && evidence == null) throw legacySchemaMismatch();
        var current = formFieldService.captureSnapshot(formId);
        if (evidence == null || current.equals(evidence)) {
            submission.setFieldDefinitionsSnapshot(current);
        } else {
            submission.setFieldDefinitionsSnapshot(formSchemaService.matchPublished(formId, evidence)
                    .orElseThrow(FormSubmissionService::legacySchemaMismatch));
        }
    }

    private static IllegalArgumentException legacySchemaMismatch() {
        return new IllegalArgumentException("Original offline form schema could not be verified.");
    }

    private void assertMediaDelivered(FormSubmission submission, Map<String, Object> answers) {
        var required = new HashMap<String, Map<String, AttachmentKind>>();
        for (var field : submission.getFieldDefinitionsSnapshot()) {
            var kind = AttachmentKind.forFieldDataType(field.getDataType());
            if (kind == null) continue;
            for (String id : AttachmentReferences.idsOf(answers.get(field.getKey()))) {
                required.computeIfAbsent(field.getKey(), key -> new HashMap<>()).put(id, kind);
            }
        }
        if (required.isEmpty()) return;
        if (submission.getId() != null) {
            for (var file : attachmentRepository.findByFormSubmissionIdOrderByUploadedAtAsc(submission.getId())) {
                var ids = required.get(file.getFieldKey());
                if (ids != null) ids.remove(file.getId(), file.getKind());
            }
        }
        if (required.values().stream().anyMatch(ids -> !ids.isEmpty())) {
            throw new IllegalArgumentException("Referenced form attachments have not been delivered.");
        }
    }

    private FormSubmission newDraft(Long userId, FormDefinition form, Long reporterUnitId,
                                    Long subjectUnitId, String localId, ActionSource source) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));
        long now = System.currentTimeMillis();
        FormSubmission submission = new FormSubmission();
        submission.setFormDefinitionId(form.getId());
        submission.setStatus(FormSubmissionStatus.DRAFT);
        submission.setReporterUnitId(reporterUnitId);
        submission.setSubjectUnitId(subjectUnitId);
        submission.setReportedByUserId(userId);
        submission.setReportedByName(displayName(user));
        submission.setSource(source);
        submission.setLocalId(blankToNull(localId));
        submission.setFormData(Map.of());
        submission.setCreatedAt(now);
        submission.setUpdatedAt(now);
        submission.setSyncedAt(now);
        return submission;
    }

    private static String displayName(User user) {
        if (user.getFullName() != null && !user.getFullName().isBlank()) {
            return user.getFullName().trim();
        }
        return user.getUsername();
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String normalizeComment(String comment) {
        if (comment == null || comment.isBlank()) {
            return null;
        }
        String trimmed = comment.trim();
        if (trimmed.length() > MAX_COMMENT_LENGTH) {
            throw new IllegalArgumentException("Action comment is too long.");
        }
        return trimmed;
    }
}
