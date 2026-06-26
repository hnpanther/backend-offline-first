package com.hnp.backendofflinefirst.entity;

import jakarta.persistence.*;
import lombok.Data;

@Entity
@Table(name = "asset_entries")
@Data
public class AssetEntry {
    @Id
    private String id;

    @Column(unique = true)
    private String nfcTagId;

    private String classId;
    private String assetName;
    private String subFunctionId;
    private String location;
    private Long createdAt;
    private Long updatedAt;
}
