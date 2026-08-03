package com.hnp.backendofflinefirst.entity;

import jakarta.persistence.*;
import lombok.Data;

/**
 * One hand-picked asset of an EXPLICIT {@link LogSheetTemplate}. Rows are frozen: they
 * change only when a user edits the template, never as a side effect of the plant
 * hierarchy changing. Generation skips inactive assets but leaves the row in place, so
 * reactivating the asset brings it back on the next run.
 */
@Entity
@Table(name = "log_sheet_template_assets")
@IdClass(LogSheetTemplateAssetId.class)
@Data
public class LogSheetTemplateAsset {
    @Id
    @Column(name = "template_id")
    private Long templateId;

    @Id
    @Column(name = "asset_id")
    private Long assetId;
}
