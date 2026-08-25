package com.hnp.backendofflinefirst.entity;

import com.hnp.backendofflinefirst.domain.ActionSource;
import com.hnp.backendofflinefirst.domain.LogSheetEntrySource;
import com.hnp.backendofflinefirst.domain.LogSheetStatus;
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
 * A reading that a later change replaced.
 *
 * <p><b>Append-only, and it holds the OLD value.</b> The current value always lives in
 * {@link LogSheetEntry#getFormData()}, so an entry's full history is this table's rows in id
 * order followed by the entry itself. Storing the new value here as well would duplicate the
 * live row on every correction and leave two places that could disagree about what is current.
 *
 * <p><b>Filling an empty entry writes nothing.</b> A row exists only where something was
 * genuinely overwritten, which is what keeps this table proportional to corrections rather than
 * to readings — a normal round produces none at all.
 *
 * <p>Excluded from the repository audit aspect ({@code AuditEntitySupport.EXCLUDED_TYPES}) for
 * the same reason {@code LogSheetActionLog} is: this <em>is</em> a history trail, and auditing
 * it would write a second row for every first one, in the one table whose readability depends
 * on holding only deliberate changes.
 */
@Entity
@Table(name = "log_sheet_entry_revisions")
@Data
public class LogSheetEntryRevision {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "log_sheet_entry_id")
    private Long logSheetEntryId;

    @Column(name = "log_sheet_id")
    private Long logSheetId;

    @Column(name = "asset_id")
    private Long assetId;

    /** The replaced answers, in the same shape {@code log_sheet_entries.form_data} had. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "form_data", columnDefinition = "jsonb")
    private Map<String, Object> formData;

    /**
     * Severity of the replaced value, carried rather than recomputed.
     *
     * <p>It was evaluated against the bands frozen on the sheet at the time. Re-deriving it
     * later would judge a historical reading by today's limits, which is exactly what
     * {@code field_definitions_snapshot} exists to prevent.
     */
    @Column(name = "max_severity", length = 10)
    private String maxSeverity;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "breached_fields", columnDefinition = "jsonb")
    private List<String> breachedFields;

    /** How the replaced value was captured. */
    @Enumerated(EnumType.STRING)
    @Column(name = "entry_source")
    private LogSheetEntrySource entrySource;

    /** Who had recorded the replaced value. */
    @Column(name = "recorded_by_user_id")
    private Long recordedByUserId;

    /** Device time of the replaced value — the entry's own {@code updated_at}. */
    @Column(name = "recorded_at")
    private Long recordedAt;

    @Column(name = "superseded_by_user_id")
    private Long supersededByUserId;

    /** Server time the overwrite was accepted. Never a device clock — this is not a measurement. */
    @Column(name = "superseded_at")
    private Long supersededAt;

    /** Which surface performed the overwrite: {@code WEB}, {@code MOBILE} or {@code SERVER}. */
    @Enumerated(EnumType.STRING)
    @Column(name = "superseded_source")
    private ActionSource supersededSource;

    /** The sheet's status at the moment of the overwrite. */
    @Enumerated(EnumType.STRING)
    @Column(name = "sheet_status")
    private LogSheetStatus sheetStatus;
}
