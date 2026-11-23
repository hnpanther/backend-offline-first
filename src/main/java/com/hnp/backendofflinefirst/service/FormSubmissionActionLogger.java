package com.hnp.backendofflinefirst.service;

import com.hnp.backendofflinefirst.domain.ActionSource;
import com.hnp.backendofflinefirst.domain.FormSubmissionActionType;
import com.hnp.backendofflinefirst.entity.FormSubmissionActionLog;
import com.hnp.backendofflinefirst.repository.FormSubmissionActionLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/** Immutable audit trail for form submissions — final submit and review transitions (V33). */
@Service
@RequiredArgsConstructor
public class FormSubmissionActionLogger {

    private final FormSubmissionActionLogRepository repository;

    public FormSubmissionActionLog record(Long submissionId,
                                          FormSubmissionActionType action,
                                          ActionSource source,
                                          Long actorUserId,
                                          String comment) {
        long now = System.currentTimeMillis();
        FormSubmissionActionLog row = new FormSubmissionActionLog();
        row.setFormSubmissionId(submissionId);
        row.setAction(action);
        row.setSource(source);
        row.setActorUserId(actorUserId);
        row.setActionAt(now);
        row.setRecordedAt(now);
        row.setComment(blankToNull(comment));
        return repository.save(row);
    }

    public List<FormSubmissionActionLog> history(Long submissionId) {
        return repository.findByFormSubmissionIdOrderByRecordedAtDesc(submissionId);
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
