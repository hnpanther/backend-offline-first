package com.hnp.backendofflinefirst.entity;

import com.hnp.backendofflinefirst.domain.ActionSource;
import com.hnp.backendofflinefirst.domain.FieldDefinitionSnapshot;
import com.hnp.backendofflinefirst.domain.FormSubmissionStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.List;
import java.util.Map;

/**
 * One ad-hoc form instance: draft (mutable) or a finished status.
 *
 * <p>{@link #fieldDefinitionsSnapshot} freezes on the <b>first draft save</b>, so later edits to
 * the definition never re-describe an in-flight or finished filing. After {@code SUBMITTED},
 * answers are immutable until a subject-unit supervisor reopens to {@code DRAFT} (V31); the prior
 * map is kept in {@code form_submission_revisions}.
 */
@Data
@Entity
@Table(name = "form_submissions")
public class FormSubmission {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "history_position", insertable = false, updatable = false)
    private Long historyPosition;

    @Column(name = "form_definition_id", nullable = false)
    private Long formDefinitionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private FormSubmissionStatus status;

    /** Reporter's operational unit at filing (accountability). */
    @Column(name = "reporter_unit_id", nullable = false)
    private Long reporterUnitId;

    /** Unit where the event was observed (routing / subject). */
    @Column(name = "subject_unit_id", nullable = false)
    private Long subjectUnitId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "form_data", columnDefinition = "jsonb")
    private Map<String, Object> formData;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "field_definitions_snapshot", columnDefinition = "jsonb")
    private List<FieldDefinitionSnapshot> fieldDefinitionsSnapshot;

    @Column(name = "reported_by_user_id")
    private Long reportedByUserId;

    @Column(name = "reported_by_name")
    private String reportedByName;

    @Column(name = "last_saved_by_user_id")
    private Long lastSavedByUserId;

    @Column(name = "last_saved_by_name")
    private String lastSavedByName;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 20)
    private ActionSource source;

    /** Device-minted correlation id for offline creates. */
    @Column(name = "local_id")
    private String localId;

    /** Final-submit idempotency key (unique when set). */
    @Column(name = "client_action_id")
    private String clientActionId;

    /** Last draft-upsert idempotency key (optional; drafts also merge by local_id). */
    @Column(name = "draft_client_action_id")
    private String draftClientActionId;

    @Column(name = "created_at", nullable = false)
    private Long createdAt;

    @Column(name = "updated_at", nullable = false)
    private Long updatedAt;

    @Column(name = "submitted_at")
    private Long submittedAt;

    @Column(name = "approved_at")
    private Long approvedAt;

    @Column(name = "approved_by_user_id")
    private Long approvedByUserId;

    @Column(name = "synced_at")
    private Long syncedAt;

    /** Device clock at the action (offline); may differ from {@link #syncedAt}. */
    @Column(name = "device_time_at")
    private Long deviceTimeAt;
}
