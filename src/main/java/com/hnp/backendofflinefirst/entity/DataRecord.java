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
    @Column(name = "id")
    private Long id; // server-generated incremental id, returned to client as serverId

    @Column(name = "local_id", unique = true)
    private String localId;

    @Column(name = "nfc_tag_id")
    private String nfcTagId;
    @Column(name = "asset_entry_id")
    private Long assetEntryId;
    @Column(name = "asset_name")
    private String assetName;
    @Column(name = "asset_type_id")
    private Long assetTypeId;
    @Column(name = "record_status")
    private String recordStatus;
    @Column(name = "sync_status")
    private String syncStatus;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "form_data", columnDefinition = "jsonb")
    private Map<String, Object> formData;

    @Column(name = "notes")
    private String notes;
    @Column(name = "operator_name")
    private String operatorName;
    @Column(name = "location")
    private String location;
    @Column(name = "synced_at")
    private Long syncedAt;
    @Column(name = "sync_error")
    private String syncError;
    @Column(name = "created_at")
    private Long createdAt;
    @Column(name = "updated_at")
    private Long updatedAt;
}
