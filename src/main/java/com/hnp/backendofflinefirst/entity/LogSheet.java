package com.hnp.backendofflinefirst.entity;

import com.hnp.backendofflinefirst.domain.AssignmentType;
import com.hnp.backendofflinefirst.domain.FieldDefinitionSnapshot;
import com.hnp.backendofflinefirst.domain.GenerationMode;
import com.hnp.backendofflinefirst.domain.LogSheetStatus;
import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.List;

/**
 * A unit of inspection work generated (manually or on schedule) from a template.
 * Progresses through a server-authoritative lifecycle ({@link LogSheetStatus}).
 * Milestone timestamps are stored per action; {@code completedAt} is device-
 * authoritative (recorded offline on mobile, synced later), while {@code syncedAt}
 * is the server receive time.
 */
@Entity
@Table(name = "log_sheets")
@Data
public class LogSheet {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id; // server-generated incremental id, returned to client as serverId

    @Column(name = "template_id")
    private Long templateId;
    @Column(name = "template_name")
    private String templateName;
    @Column(name = "scope_summary")
    private String scopeSummary;
    @Column(name = "operational_unit_id")
    private Long operationalUnitId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status")
    private LogSheetStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "origin")
    private GenerationMode origin;

    @Column(name = "assignee_user_id")
    private Long assigneeUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "assignment_type")
    private AssignmentType assignmentType;

    @Column(name = "assigned_by_user_id")
    private Long assignedByUserId;
    @Column(name = "completed_by_user_id")
    private Long completedByUserId;
    @Column(name = "operator_name")
    private String operatorName;

    /**
     * Optional free-text notes for the whole sheet (web fill/complete only; not used by mobile PWA).
     */
    @Column(name = "notes", length = 4000)
    private String notes;

    // lifecycle timestamps (epoch millis)
    @Column(name = "due_at")
    private Long dueAt;
    @Column(name = "assigned_at")
    private Long assignedAt;
    @Column(name = "claimed_at")
    private Long claimedAt;
    @Column(name = "started_at")
    private Long startedAt;
    @Column(name = "completed_at")
    private Long completedAt; // device-authoritative
    @Column(name = "expired_at")
    private Long expiredAt;
    @Column(name = "cancelled_at")
    private Long cancelledAt;
    @Column(name = "submitted_at")
    private Long submittedAt;
    @Column(name = "synced_at")
    private Long syncedAt;    // server receive time

    /**
     * Last time partial values were stored on this sheet without a submission.
     *
     * <p>Two writers, and {@link #draftSource} says which: the panel's «ذخیره پیش‌نویس»
     * ({@code WEB}) and a tablet's progress push ({@code MOBILE}). It used to have one, which is
     * why a round being walked in the field was invisible to the server until the operator hit
     * final submit.
     */
    @Column(name = "draft_saved_at")
    private Long draftSavedAt;

    /** Who saved those partial values. Null on rows written before the column existed. */
    @Column(name = "draft_saved_by_user_id")
    private Long draftSavedByUserId;

    /**
     * Which surface produced the current {@link #draftSavedAt} — {@code WEB} or {@code MOBILE}.
     *
     * <p>A plain String rather than an enum of its own: it names a surface, and {@code WEB} /
     * {@code MOBILE} are already {@link com.hnp.backendofflinefirst.domain.ActionSource} values,
     * so a third spelling of the same idea would be one more thing to keep in step.
     */
    @Column(name = "draft_source")
    private String draftSource;

    @Column(name = "sync_status")
    private String syncStatus;
    @Column(name = "created_at")
    private Long createdAt;
    @Column(name = "updated_at")
    private Long updatedAt;

    /** Field-definition schema frozen at sheet generation time. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "field_definitions_snapshot", columnDefinition = "jsonb")
    private List<FieldDefinitionSnapshot> fieldDefinitionsSnapshot;
}
