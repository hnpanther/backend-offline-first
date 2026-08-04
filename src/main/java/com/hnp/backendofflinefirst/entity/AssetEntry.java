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
    @Column(name = "created_at")
    private Long createdAt;
    @Column(name = "updated_at")
    private Long updatedAt;
}
