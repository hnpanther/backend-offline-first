package com.hnp.backendofflinefirst.dto;

import lombok.Data;

import java.util.Map;

/** Mobile/panel draft upsert for an ad-hoc form submission. */
@Data
public class FormDraftRequest {
    /** Server id when updating an existing draft; null on first create. */
    private Long submissionId;
    private Long formDefinitionId;
    private Long reporterUnitId;
    private Long subjectUnitId;
    /** Device-minted id — used to find the draft on retry when submissionId is unknown. */
    private String localId;
    /** Optional idempotency for the draft upsert itself. */
    private String clientActionId;
    private Map<String, Object> formData;
    private Long deviceTimeAt;
}
