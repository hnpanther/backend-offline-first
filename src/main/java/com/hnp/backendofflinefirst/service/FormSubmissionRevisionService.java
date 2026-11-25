package com.hnp.backendofflinefirst.service;

import com.hnp.backendofflinefirst.domain.ActionSource;
import com.hnp.backendofflinefirst.domain.FormDataValidationSupport;
import com.hnp.backendofflinefirst.entity.Attachment;
import com.hnp.backendofflinefirst.entity.FormSubmission;
import com.hnp.backendofflinefirst.entity.FormSubmissionRevision;
import com.hnp.backendofflinefirst.repository.AttachmentRepository;
import com.hnp.backendofflinefirst.repository.FormSubmissionRevisionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Keeps the answers a form-submission edit replaced — twin of {@link LogSheetRevisionService}.
 *
 * <p>Call <em>before</em> mutating {@code form_data}. A first fill writes nothing (nothing was
 * replaced). Reopen snapshots the submitted state even when the next edit has not happened yet,
 * so the prior submission remains recoverable.
 */
@Service
@RequiredArgsConstructor
public class FormSubmissionRevisionService {

    private final FormSubmissionRevisionRepository repository;
    private final AttachmentRepository attachmentRepository;

    /**
     * Records the answers {@code submission} is about to lose, if it had any.
     *
     * @return {@code true} when a revision row was written
     */
    @Transactional
    public boolean recordSupersededAnswers(FormSubmission submission, Long actorUserId,
                                           ActionSource source, long supersededAt) {
        if (submission == null || submission.getId() == null) {
            return false;
        }
        Map<String, Object> previous = submission.getFormData();
        if (!FormDataValidationSupport.hasMeaningfulFormData(previous)) {
            return false;
        }

        FormSubmissionRevision revision = new FormSubmissionRevision();
        revision.setFormSubmissionId(submission.getId());
        revision.setFormData(new LinkedHashMap<>(previous));
        revision.setRecordedByUserId(submission.getLastSavedByUserId() != null
                ? submission.getLastSavedByUserId() : submission.getReportedByUserId());
        revision.setRecordedAt(submission.getSubmittedAt() != null
                ? submission.getSubmittedAt()
                : submission.getUpdatedAt());
        revision.setSupersededByUserId(actorUserId);
        revision.setSupersededAt(supersededAt);
        revision.setSupersededSource(source);
        revision.setSubmissionStatus(submission.getStatus());
        revision.setAttachmentSnapshot(snapshotAttachments(previous));
        repository.save(revision);
        return true;
    }

    private Map<String, Map<String, Object>> snapshotAttachments(Map<String, Object> formData) {
        Map<String, List<String>> byField = AttachmentReferences.extract(formData);
        if (byField.isEmpty()) {
            return null;
        }
        List<String> ids = byField.values().stream().flatMap(List::stream).distinct().toList();
        if (ids.isEmpty()) {
            return null;
        }
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        for (Attachment a : attachmentRepository.findAllById(ids)) {
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("kind", a.getKind() == null ? null : a.getKind().name());
            meta.put("mimeType", a.getMimeType());
            meta.put("sizeBytes", a.getSizeBytes());
            meta.put("durationMs", a.getDurationMs());
            meta.put("width", a.getWidth());
            meta.put("height", a.getHeight());
            meta.put("uploadedAt", a.getUploadedAt());
            meta.put("createdByUserId", a.getCreatedByUserId());
            out.put(a.getId(), meta);
        }
        return out.isEmpty() ? null : out;
    }

    @Transactional(readOnly = true)
    public List<FormSubmissionRevision> findForSubmission(Long formSubmissionId) {
        if (formSubmissionId == null) {
            return List.of();
        }
        return repository.findByFormSubmissionIdOrderByIdAsc(formSubmissionId);
    }
}
