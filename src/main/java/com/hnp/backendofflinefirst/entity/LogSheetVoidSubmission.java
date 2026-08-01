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
    private Long id;

    private Long logSheetId;
    private Long submittedByUserId;
    private Long completedAt;
    private Long syncedAt;
    private String reason;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<Map<String, Object>> payload;
}
