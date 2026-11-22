package com.hnp.backendofflinefirst.integration;

import com.hnp.backendofflinefirst.domain.ActionSource;
import com.hnp.backendofflinefirst.dto.FormDraftRequest;
import com.hnp.backendofflinefirst.entity.Attachment;
import com.hnp.backendofflinefirst.entity.FormDefinition;
import com.hnp.backendofflinefirst.entity.OperationalUnit;
import com.hnp.backendofflinefirst.repository.AttachmentRepository;
import com.hnp.backendofflinefirst.repository.OperationalUnitRepository;
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

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Attachments for form submissions: XOR owner CHECK and draft-only upload.
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
    @Autowired OperationalUnitRepository unitRepository;
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

        FormDraftRequest draft = new FormDraftRequest();
        draft.setFormDefinitionId(form.getId());
        draft.setReporterUnitId(unit.getId());
        draft.setSubjectUnitId(unit.getId());
        draft.setLocalId("fa-local-1");
        draft.setFormData(Map.of());
        submissionId = formSubmissionService.upsertDraft(1L, draft, ActionSource.MOBILE).getSubmissionId();
    }

    @Test
    void formSubmissionAttachmentHasNullLogSheetId() throws Exception {
        // Minimal valid JPEG header so MIME sniffing accepts it as IMAGE.
        byte[] jpeg = new byte[]{
                (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0x00, 0x10,
                0x4A, 0x46, 0x49, 0x46, 0x00, 0x01, 0x01, 0x00, 0x00, 0x01,
                0x00, 0x01, 0x00, 0x00, (byte) 0xFF, (byte) 0xD9
        };
        String id = UUID.randomUUID().toString();
        Attachment saved = attachmentService.uploadForFormSubmission(
                id, submissionId, "photo", jpeg, 10, 10, null);
        assertThat(saved.getFormSubmissionId()).isEqualTo(submissionId);
        assertThat(saved.getLogSheetId()).isNull();
        assertThat(attachmentRepository.findById(id)).isPresent();
    }

    @Test
    void databaseRejectsAttachmentWithNeitherOwner() {
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO attachments (id, field_key, kind, mime_type, size_bytes, storage_key, uploaded_at)
                VALUES (?, 'x', 'IMAGE', 'image/jpeg', 1, ?, ?)
                """, UUID.randomUUID().toString(), "orphan-" + UUID.randomUUID(), System.currentTimeMillis()))
                .hasMessageContaining("ck_attachments_one_owner");
    }
}
