package com.hnp.backendofflinefirst.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class FormSubmitResult {
    private Long submissionId;
    private String localId;
    private String outcome;
    private String status;
    private Long updatedAt;
    private String message;

    public static FormSubmitResult ok(Long id, String localId, String outcome, String status) {
        return FormSubmitResult.builder()
                .submissionId(id)
                .localId(localId)
                .outcome(outcome)
                .status(status)
                .build();
    }

    public static FormSubmitResult fail(Long id, String localId, String outcome, String message) {
        return FormSubmitResult.builder()
                .submissionId(id)
                .localId(localId)
                .outcome(outcome)
                .message(message)
                .build();
    }
}
