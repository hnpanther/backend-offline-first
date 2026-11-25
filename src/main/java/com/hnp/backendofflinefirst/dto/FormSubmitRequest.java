package com.hnp.backendofflinefirst.dto;

import lombok.Data;

import com.hnp.backendofflinefirst.domain.FieldDefinitionSnapshot;
import java.util.List;
import java.util.Map;

/**
 * Final submit for an ad-hoc form. {@code clientActionId} is required and unique —
 * a replay returns {@code DUPLICATE} without mutating the immutable record.
 */
@Data
public class FormSubmitRequest {
    /** Server-issued definition snapshot identity for offline work. */
    private Long schemaVersionId;
    /** Comparison evidence only. Never installed as the authoritative definition. */
    private List<FieldDefinitionSnapshot> legacyFields;
    private boolean requireUploadedMedia;
    private Long submissionId;
    private Long formDefinitionId;
    /** Filing unit («واحد ثبت‌کننده»). Required when creating; mirrored onto subject_unit_id. */
    private Long reporterUnitId;
    /**
     * Ignored on write — kept for older clients. Server always sets
     * {@code subject_unit_id = reporter_unit_id}.
     */
    private Long subjectUnitId;
    private String localId;
    /** Required. Unique across submissions. */
    private String clientActionId;
    private Map<String, Object> formData;
    private Long deviceTimeAt;
}
