package com.hnp.backendofflinefirst.entity;

import com.hnp.backendofflinefirst.domain.LogSheetEntrySource;
import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.Map;

@Entity
@Table(name = "log_sheet_entries")
@Data
public class LogSheetEntry {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "log_sheet_id")
    private Long logSheetId;
    @Column(name = "asset_id")
    private Long assetId;
    @Column(name = "asset_name")
    private String assetName;
    @Column(name = "sub_function_code")
    private String subFunctionCode;
    @Column(name = "sub_function_tag")
    private String subFunctionTag;
    @Column(name = "nfc_tag_id")
    private String nfcTagId;
    /** Snapshot of the asset's physical NFC chip serial at generation time; may be null. */
    @Column(name = "nfc_serial")
    private String nfcSerial;
    @Column(name = "class_id")
    private Long classId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "form_data", columnDefinition = "jsonb")
    private Map<String, Object> formData;

    /** Device time when entry form data was first saved (epoch millis). */
    @Column(name = "created_at")
    private Long createdAt;

    /** Device time of the latest entry edit (epoch millis). */
    @Column(name = "updated_at")
    private Long updatedAt;

    /** How this entry's current form data was captured; null until first submitted. */
    @Enumerated(EnumType.STRING)
    @Column(name = "entry_source")
    private LogSheetEntrySource entrySource;

    /** Who last submitted this entry's form data (last-writer-wins); null until first submitted. */
    @Column(name = "filled_by_user_id")
    private Long filledByUserId;
}
