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

    @Column(unique = true)
    private String assetCode;

    @Column(unique = true)
    private String nfcTagId;

    private Long classId;
    private String assetName;
    private Long subFunctionId;
    private String description;
    private Long createdAt;
    private Long updatedAt;
}
