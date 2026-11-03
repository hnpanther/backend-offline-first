package com.hnp.backendofflinefirst.entity;

import com.hnp.backendofflinefirst.domain.ActionSource;
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

import java.util.Map;

/**
 * An answer to a round-level question that a later change replaced.
 *
 * <p>The sheet-level twin of {@link LogSheetEntryRevision}, and it keeps that type's three
 * properties exactly:
 *
 * <ul>
 *   <li><b>Append-only, holding the OLD value.</b> The current answers always live in
 *       {@code log_sheets.sheet_form_data}, so the full history is this table's rows in id
 *       order followed by the sheet itself.</li>
 *   <li><b>A first fill writes nothing.</b> Nothing was replaced, so there is no earlier answer
 *       to keep — which is what keeps this table proportional to corrections rather than to
 *       rounds.</li>
 *   <li><b>Excluded from the repository audit aspect</b>, for the reason
 *       {@code LogSheetActionLog} is: this <em>is</em> a history trail, and auditing it would
 *       write a second row for every first one.</li>
 * </ul>
 *
 * <p>No {@code entry_source}: nobody scans a tag to answer a round-level question. No
 * {@code attachment_snapshot}: this release does not allow attachment types at sheet level,
 * because {@code attachments.asset_id} is NOT NULL and a round-level photo has no asset.
 */
@Entity
@Table(name = "log_sheet_revisions")
@Data
public class LogSheetRevision {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "log_sheet_id")
    private Long logSheetId;

    /** The replaced answers, in the shape {@code log_sheets.sheet_form_data} had. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "form_data", columnDefinition = "jsonb")
    private Map<String, Object> formData;

    /** Who had given the replaced answers. */
    @Column(name = "recorded_by_user_id")
    private Long recordedByUserId;

    /** When they gave them — the sheet's own {@code sheet_form_updated_at}. */
    @Column(name = "recorded_at")
    private Long recordedAt;

    @Column(name = "superseded_by_user_id")
    private Long supersededByUserId;

    /** Server time the overwrite was accepted. Never a device clock: this is not a measurement. */
    @Column(name = "superseded_at")
    private Long supersededAt;

    /** Which surface performed the overwrite: {@code WEB}, {@code MOBILE} or {@code SERVER}. */
    @Enumerated(EnumType.STRING)
    @Column(name = "superseded_source")
    private ActionSource supersededSource;

    /** The sheet's status at the moment of the overwrite. Context for the reader. */
    @Enumerated(EnumType.STRING)
    @Column(name = "sheet_status")
    private LogSheetStatus sheetStatus;
}
