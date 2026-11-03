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
import java.util.Map;

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
     * When a supervisor accepted this completed round.
     *
     * <p>Set together with {@code status = APPROVED} and cleared when the approval is withdrawn,
     * so the column and the status can never disagree. Approval is a review laid on top of
     * completion — the readings do not change and the round stays as complete as it was — which
     * is why every condition about completed work asks
     * {@link com.hnp.backendofflinefirst.domain.LogSheetStatus#COMPLETED_STATUSES} rather than
     * naming a single status.
     *
     * <p>The approver may be the same person who completed the round; that is a deliberate
     * decision by the plant, not an oversight.
     */
    @Column(name = "approved_at")
    private Long approvedAt;

    @Column(name = "approved_by_user_id")
    private Long approvedByUserId;

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

    /**
     * Per-asset band overrides frozen at generation, beside the definitions above and for the
     * same reason: re-tuning a band next month must not silently re-judge a reading taken today.
     *
     * <p>Keyed by asset id as a string (JSON object keys are strings), then by field key:
     * <pre>{"4711": {"Ampere": {"warning": {"min": 18, "max": 42}}}}</pre>
     *
     * <p><strong>A delta, not a copy.</strong> It holds only what differs from the definition
     * {@code fieldDefinitionsSnapshot} already carries once. Inlining whole definitions per
     * asset would put 135 KB on a 47-entry sheet instead of ~11 KB — on every bundle fetch,
     * uncompressed. The measurements are in {@code docs/roadmap.md} §20.
     *
     * <p>Null means the sheet was generated before V5 and resolves exactly as it did then.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "field_overrides_snapshot", columnDefinition = "jsonb")
    private Map<String, Map<String, Object>> fieldOverridesSnapshot;

    /**
     * The round's <strong>own</strong> questions, frozen when it was raised (V9).
     *
     * <p>«آیا همه لوله‌ها بازدید شد؟» is asked once per sheet, not once per pump. Beside
     * {@link #fieldDefinitionsSnapshot} and frozen for exactly the same reason: editing the
     * template's questions next month must not re-describe a round already walked.
     *
     * <p>Null on every sheet raised before V9, and on any sheet whose owner defines no
     * questions — which is the overwhelming majority.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "sheet_field_definitions_snapshot", columnDefinition = "jsonb")
    private List<FieldDefinitionSnapshot> sheetFieldDefinitionsSnapshot;

    /**
     * The answers to those questions, keyed by field key.
     *
     * <p>Stored through the same funnel as an entry's {@code form_data}: unknown keys dropped
     * against the frozen snapshot, then unanswered keys removed, so a round nobody answered
     * holds {@code null} rather than a map of blanks.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "sheet_form_data", columnDefinition = "jsonb")
    private Map<String, Object> sheetFormData;

    /**
     * Who answered the round-level questions, and when, and from where.
     *
     * <p><strong>Not {@link #draftSavedByUserId}.</strong> That one moves on every per-asset
     * dialog save and on every tablet progress push, so reusing it would credit a round-level
     * answer to whoever last touched any asset on the round — the attribution error V4's
     * revision table exists to repair, in a new place.
     *
     * <p>Re-stamped only when the answers actually change, the same rule as
     * {@code LogSheetEntry.filledByUserId} (AGENTS.md #20): a save that changes nothing must not
     * move authorship, and a value whose authorship moves must leave a history row.
     */
    @Column(name = "sheet_form_filled_by_user_id")
    private Long sheetFormFilledByUserId;

    @Column(name = "sheet_form_updated_at")
    private Long sheetFormUpdatedAt;

    /** {@code WEB} or {@code MOBILE} — which surface gave the current answers. */
    @Column(name = "sheet_form_source")
    private String sheetFormSource;
}
