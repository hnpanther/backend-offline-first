package com.hnp.backendofflinefirst.dto;

import lombok.Data;

import java.util.Map;

/**
 * Final submit for an ad-hoc form. {@code clientActionId} is required and unique —
 * a replay returns {@code DUPLICATE} without mutating the immutable record.
 */
@Data
public class FormSubmitRequest {
    private Long submissionId;
    private Long formDefinitionId;
    private Long reporterUnitId;
    private Long subjectUnitId;
    private String localId;
    /** Required. Unique across submissions. */
    private String clientActionId;
    private Map<String, Object> formData;
    private Long deviceTimeAt;
}
