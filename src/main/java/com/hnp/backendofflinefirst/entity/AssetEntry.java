package com.hnp.backendofflinefirst.entity;

import jakarta.persistence.*;
import lombok.Data;

@Entity
@Table(name = "asset_entries")
@Data
public class AssetEntry {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "asset_code", nullable = false)
    private String assetCode;

    @Column(name = "nfc_tag_id")
    private String nfcTagId;

    /**
     * Physical NFC chip serial / UID, e.g. {@code 00:aa:34:9f:12:cd}. Optional, but unique
     * when supplied. Unlike {@link #nfcTagId} it is never inherited from the sub-function and
     * never released when the asset goes inactive — it identifies the piece of hardware, not
     * the position it is mounted on.
     */
    @Column(name = "nfc_serial")
    private String nfcSerial;

    @Column(name = "class_id")
    private Long classId;
    @Column(name = "asset_name")
    private String assetName;
    /** Optional secondary (Persian) title; display-only, never used for lookups. */
    @Column(name = "asset_name_fa")
    private String assetNameFa;
    @Column(name = "sub_function_id", nullable = false)
    private Long subFunctionId;
    @Column(name = "description")
    private String description;
    /** When false, asset is excluded from log-sheet template preview and generation. */
    @Column(name = "active")
    private boolean active = true;

    /**
     * Current operational state, e.g. «در سرویس» / «خارج از سرویس».
     *
     * <p>Written by {@link com.hnp.backendofflinefirst.service.AssetStatusService} when a log
     * sheet carrying a {@code status} field is completed, and restored when that completion is
     * undone. Every change is recorded in {@code asset_status_history} — never edit this column
     * directly without writing that history, or the reversal logic loses its anchor.
     */
    @Column(name = "status")
    private String status;
    @Column(name = "created_at")
    private Long createdAt;
    @Column(name = "updated_at")
    private Long updatedAt;
}
