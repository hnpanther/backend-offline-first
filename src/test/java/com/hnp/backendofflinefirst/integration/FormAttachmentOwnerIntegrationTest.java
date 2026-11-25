package com.hnp.backendofflinefirst.integration;

import com.hnp.backendofflinefirst.domain.ActionSource;
import com.hnp.backendofflinefirst.domain.FormSubmissionStatus;
import com.hnp.backendofflinefirst.dto.FormDraftRequest;
import com.hnp.backendofflinefirst.entity.Attachment;
import com.hnp.backendofflinefirst.entity.FormDefinition;
import com.hnp.backendofflinefirst.entity.FormSubmission;
import com.hnp.backendofflinefirst.entity.OperationalUnit;
import com.hnp.backendofflinefirst.entity.User;
import com.hnp.backendofflinefirst.repository.AttachmentRepository;
import com.hnp.backendofflinefirst.repository.FormSubmissionRepository;
import com.hnp.backendofflinefirst.repository.OperationalUnitRepository;
import com.hnp.backendofflinefirst.repository.UserRepository;
import com.hnp.backendofflinefirst.service.AttachmentService;
import com.hnp.backendofflinefirst.service.FormDefinitionService;
import com.hnp.backendofflinefirst.service.FormFieldService;
import com.hnp.backendofflinefirst.service.FormSubmissionService;
import com.hnp.backendofflinefirst.support.AbstractPostgresIntegrationTest;
import com.hnp.backendofflinefirst.support.WithAppUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Attachments for form submissions: XOR owner CHECK, draft upload, and the late sync of
 * media a submit already named. Log-sheet upload is a different method and is not exercised here.
 */
@Transactional
@WithAppUser(roles = "ADMIN", authorities = {
        "POST:/forms", "POST:/forms/{id}/fields",
        "POST:/api/forms/drafts", "POST:/api/attachments",
        "CAP:SCOPE_PLANT_WIDE"
})
class FormAttachmentOwnerIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired FormDefinitionService formDefinitionService;
    @Autowired FormFieldService formFieldService;
    @Autowired FormSubmissionService formSubmissionService;
    @Autowired AttachmentService attachmentService;
    @Autowired AttachmentRepository attachmentRepository;
    @Autowired FormSubmissionRepository submissionRepository;
    @Autowired OperationalUnitRepository unitRepository;
    @Autowired UserRepository userRepository;
    @Autowired JdbcTemplate jdbc;

    OperationalUnit unit;
    FormDefinition form;
    Long submissionId;

    @BeforeEach
    void seed() throws Exception {
        unit = new OperationalUnit();
        unit.setCode("FA-" + UUID.randomUUID().toString().substring(0, 8));
        unit.setName("Form Attach Unit");
        unit.setCreatedAt(System.currentTimeMillis());
        unit.setUpdatedAt(System.currentTimeMillis());
        unit = unitRepository.save(unit);

        form = formDefinitionService.create(
                "FA-" + UUID.randomUUID().toString().substring(0, 8),
                "فرم با عکس", null, List.of(unit.getId()), true);
        formFieldService.create(form.getId(), "photo", "عکس", "image", null, false, Map.of(), 10);
        formFieldService.create(form.getId(), "sign", "امضا", "signature", null, false, Map.of(), 20);

        FormDraftRequest draft = new FormDraftRequest();
        draft.setFormDefinitionId(form.getId());
        draft.setReporterUnitId(unit.getId());
        draft.setSubjectUnitId(unit.getId());
        draft.setLegacyFields(formFieldService.captureSnapshot(form.getId()));
        draft.setLocalId("fa-local-1");
        draft.setFormData(Map.of());
        submissionId = formSubmissionService.upsertDraft(1L, draft, ActionSource.MOBILE).getSubmissionId();
    }

    @Test
    void formSubmissionAttachmentHasNullLogSheetId() throws Exception {
        String id = UUID.randomUUID().toString();
        Attachment saved = attachmentService.uploadForFormSubmission(
                id, submissionId, "photo", JPEG, 10, 10, null);
        assertThat(saved.getFormSubmissionId()).isEqualTo(submissionId);
        assertThat(saved.getLogSheetId()).isNull();
        assertThat(attachmentRepository.findById(id)).isPresent();
    }

    @Test
    void strictFinalCanReplaceAMissingPhotoWithDeliveredBytes() throws Exception {
        var request = new com.hnp.backendofflinefirst.dto.FormSubmitRequest();
        request.setSubmissionId(submissionId);
        request.setClientActionId("verified-file");
        request.setRequireUploadedMedia(true);
        request.setFormData(Map.of("photo", attachmentValue(UUID.randomUUID().toString())));
        assertThat(formSubmissionService.submitFinal(1L, request, ActionSource.MOBILE).getOutcome())
                .isEqualTo(FormSubmissionService.OUTCOME_VALIDATION_ERROR);
        assertThat(submissionRepository.findById(submissionId).orElseThrow().getStatus())
                .isEqualTo(FormSubmissionStatus.DRAFT);
        String replacement = UUID.randomUUID().toString();
        attachmentService.uploadForFormSubmission(replacement, submissionId, "photo", JPEG, 10, 10, null);
        request.setFormData(Map.of("photo", attachmentValue(replacement)));
        assertThat(formSubmissionService.submitFinal(1L, request, ActionSource.MOBILE).getOutcome())
                .isEqualTo(FormSubmissionService.OUTCOME_OK);
        assertThat(submissionRepository.findById(submissionId).orElseThrow().getFormData())
                .containsEntry("photo", attachmentValue(replacement));
    }

    @Test
    void submittedFormAcceptsAuthorUploadOfMediaTheSubmitAlreadyNamed() throws Exception {
        String photoId = UUID.randomUUID().toString();
        freeze(FormSubmissionStatus.SUBMITTED, Map.of("photo", attachmentValue(photoId)), 1L);

        Attachment saved = attachmentService.uploadForFormSubmission(
                photoId, submissionId, "photo", JPEG, 10, 10, null);

        assertThat(saved.getId()).isEqualTo(photoId);
        assertThat(saved.getFormSubmissionId()).isEqualTo(submissionId);
        assertThat(saved.getLogSheetId()).isNull();
        assertThat(submissionRepository.findById(submissionId).orElseThrow().getStatus())
                .isEqualTo(FormSubmissionStatus.SUBMITTED);
    }

    @Test
    void approvedFormAcceptsAuthorUploadOfSignatureTheSubmitAlreadyNamed() throws Exception {
        String signId = UUID.randomUUID().toString();
        Map<String, Object> signature = new LinkedHashMap<>();
        signature.put("type", "signature");
        signature.put("ids", List.of(signId));
        signature.put("signedByUserId", 1);
        freeze(FormSubmissionStatus.APPROVED, Map.of("sign", signature), 1L);

        Attachment saved = attachmentService.uploadForFormSubmission(
                signId, submissionId, "sign", PNG, null, null, null);

        assertThat(saved.getId()).isEqualTo(signId);
        assertThat(saved.getFieldKey()).isEqualTo("sign");
        assertThat(saved.getFormSubmissionId()).isEqualTo(submissionId);
        assertThat(submissionRepository.findById(submissionId).orElseThrow().getStatus())
                .isEqualTo(FormSubmissionStatus.APPROVED);
    }

    @Test
    void submittedFormRejectsAnAttachmentTheSubmitDidNotName() {
        String named = UUID.randomUUID().toString();
        String stranger = UUID.randomUUID().toString();
        freeze(FormSubmissionStatus.SUBMITTED, Map.of("photo", attachmentValue(named)), 1L);

        assertThatThrownBy(() -> attachmentService.uploadForFormSubmission(
                stranger, submissionId, "photo", JPEG, 10, 10, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Submitted form cannot be edited.");
        assertThat(attachmentRepository.findById(stranger)).isEmpty();
    }

    @Test
    void submittedFormRejectsANamedIdFiledAgainstAnotherField() {
        String photoId = UUID.randomUUID().toString();
        freeze(FormSubmissionStatus.SUBMITTED, Map.of("photo", attachmentValue(photoId)), 1L);

        assertThatThrownBy(() -> attachmentService.uploadForFormSubmission(
                photoId, submissionId, "sign", JPEG, 10, 10, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Submitted form cannot be edited.");
        assertThat(attachmentRepository.findById(photoId)).isEmpty();
    }

    @Test
    void submittedFormRejectsNamedMediaFromSomeoneOtherThanTheAuthor() {
        User author = new User();
        author.setUsername("fa-other-" + UUID.randomUUID().toString().substring(0, 8));
        author.setPersonnelCode("PC-" + UUID.randomUUID());
        author.setPasswordHash("x");
        author.setActive(true);
        author = userRepository.save(author);

        String photoId = UUID.randomUUID().toString();
        freeze(FormSubmissionStatus.SUBMITTED, Map.of("photo", attachmentValue(photoId)), author.getId());

        assertThatThrownBy(() -> attachmentService.uploadForFormSubmission(
                photoId, submissionId, "photo", JPEG, 10, 10, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Submitted form cannot be edited.");
        assertThat(attachmentRepository.findById(photoId)).isEmpty();
    }

    @Test
    void voidedFormRejectsEvenMediaTheSubmitNamed() {
        String photoId = UUID.randomUUID().toString();
        freeze(FormSubmissionStatus.VOIDED, Map.of("photo", attachmentValue(photoId)), 1L);

        assertThatThrownBy(() -> attachmentService.uploadForFormSubmission(
                photoId, submissionId, "photo", JPEG, 10, 10, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Submitted form cannot be edited.");
        assertThat(attachmentRepository.findById(photoId)).isEmpty();
    }

    @Test
    void scopedRemovalOnlyDeletesTheNamedFormAndField() throws Exception {
        String id = UUID.randomUUID().toString();
        attachmentService.uploadForFormSubmission(id, submissionId, "photo", JPEG, 10, 10, null);
        attachmentService.deleteFormFile(submissionId + 1, "photo", id);
        attachmentService.deleteFormFile(submissionId, "sign", id);
        assertThat(attachmentRepository.existsById(id)).isTrue();
        attachmentService.deleteFormFile(submissionId, "photo", id);
        attachmentService.deleteFormFile(submissionId, "photo", id);
        assertThat(attachmentRepository.existsById(id)).isFalse();
    }

    private void freeze(FormSubmissionStatus status, Map<String, Object> formData, Long authorId) {
        FormSubmission submission = submissionRepository.findById(submissionId).orElseThrow();
        submission.setStatus(status);
        submission.setFormData(formData);
        submission.setReportedByUserId(authorId);
        submissionRepository.saveAndFlush(submission);
    }

    private static Map<String, Object> attachmentValue(String id) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("type", "attachment");
        value.put("ids", List.of(id));
        return value;
    }

    /** Minimal JPEG magic so sniffing accepts it as IMAGE. */
    private static final byte[] JPEG = new byte[]{
            (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0x00, 0x10,
            0x4A, 0x46, 0x49, 0x46, 0x00, 0x01, 0x01, 0x00, 0x00, 0x01,
            0x00, 0x01, 0x00, 0x00, (byte) 0xFF, (byte) 0xD9
    };

    /** Minimal PNG magic. A signature drawing is stored as an image. */
    private static final byte[] PNG = new byte[]{
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x00
    };

    @Test
    void databaseRejectsAttachmentWithNeitherOwner() {
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO attachments (id, field_key, kind, mime_type, size_bytes, storage_key, uploaded_at)
                VALUES (?, 'x', 'IMAGE', 'image/jpeg', 1, ?, ?)
                """, UUID.randomUUID().toString(), "orphan-" + UUID.randomUUID(), System.currentTimeMillis()))
                .hasMessageContaining("ck_attachments_one_owner");
    }
}
