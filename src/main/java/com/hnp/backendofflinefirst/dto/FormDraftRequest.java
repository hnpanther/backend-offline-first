package com.hnp.backendofflinefirst.dto;

import lombok.Data;

import com.hnp.backendofflinefirst.domain.FieldDefinitionSnapshot;
import java.util.List;
import java.util.Map;

/** Mobile/panel draft upsert for an ad-hoc form submission. */
@Data
public class FormDraftRequest {
    /** Server-issued definition snapshot identity for offline work. */
    private Long schemaVersionId;
    /** Comparison evidence only. Never installed as the authoritative definition. */
    private List<FieldDefinitionSnapshot> legacyFields;
    /** Server id when updating an existing draft; null on first create. */
    private Long submissionId;
    /** Last observed server update. Zero expects a new filing; absent on older clients. */
    private Long expectedUpdatedAt;
    private Long formDefinitionId;
    /** Filing unit («واحد ثبت‌کننده»). Required. Mirrored onto subject_unit_id server-side. */
    private Long reporterUnitId;
    /**
     * Ignored on write — kept so older clients that still send it do not fail JSON binding.
     * Server always sets {@code subject_unit_id = reporter_unit_id}.
     */
    private Long subjectUnitId;
    /** Device-minted id — used to find the draft on retry when submissionId is unknown. */
    private String localId;
    /** Optional idempotency for the draft upsert itself. */
    private String clientActionId;
    private Map<String, Object> formData;
    private Long deviceTimeAt;
}
