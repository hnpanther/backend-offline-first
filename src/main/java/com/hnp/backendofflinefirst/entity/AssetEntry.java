package com.hnp.backendofflinefirst.entity;

import jakarta.persistence.*;
import lombok.Data;

@Entity
@Table(name = "asset_entries")
@Data
public class AssetEntry {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String assetCode;

    private String nfcTagId;

    private Long classId;
    private String assetName;
    @Column(nullable = false)
    private Long subFunctionId;
    private String description;
    private Long createdAt;
    private Long updatedAt;
}
