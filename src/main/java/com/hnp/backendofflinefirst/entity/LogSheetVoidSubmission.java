package com.hnp.backendofflinefirst.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.List;
import java.util.Map;

/**
 * A late/offline operator submission that arrived after the sheet's state had already
 * moved on without it — completed by someone else (e.g. a supervisor takeover), no
 * longer assigned to this operator, or cancelled by a supervisor while offline. Kept
 * for the audit record but flagged void — it never overwrites the sheet's authoritative
 * state.
 */
@Entity
@Table(name = "log_sheet_void_submissions")
@Data
public class LogSheetVoidSubmission {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "log_sheet_id")
    private Long logSheetId;
    @Column(name = "submitted_by_user_id")
    private Long submittedByUserId;
    @Column(name = "completed_at")
    private Long completedAt;
    @Column(name = "synced_at")
    private Long syncedAt;
    @Column(name = "reason")
    private String reason;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", columnDefinition = "jsonb")
    private List<Map<String, Object>> payload;

    /**
     * The round's <b>own</b> answers, exactly as the refused submission carried them — null when
     * it carried none (a round that asks nothing sends nothing).
     *
     * <p>Not part of {@link #payload} on purpose: that column is one element per asset, and every
     * reader iterates it as assets. Before V14 these answers arrived in the same request and were
     * discarded on the void path, so a takeover of a round with questions lost them without trace
     * (roadmap §34).
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "sheet_form_data", columnDefinition = "jsonb")
    private Map<String, Object> sheetFormData;
}
