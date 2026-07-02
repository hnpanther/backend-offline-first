package com.hnp.backendofflinefirst.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.Map;

@Entity
@Table(name = "data_records")
@Data
public class DataRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id; // server-generated incremental id, returned to client as serverId

    @Column(unique = true)
    private String localId;

    private String nfcTagId;
    private Long assetEntryId;
    private String assetName;
    private Long assetTypeId;
    private String recordStatus;
    private String syncStatus;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> formData;

    private String notes;
    private String operatorName;
    private String location;
    private Long syncedAt;
    private String syncError;
    private Long createdAt;
    private Long updatedAt;
}
