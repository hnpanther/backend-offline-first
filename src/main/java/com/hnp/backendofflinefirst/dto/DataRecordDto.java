package com.hnp.backendofflinefirst.dto;

import lombok.Data;

import java.util.Map;

/**
 * Request DTO for incoming DataRecord from the frontend.
 * The frontend's DataRecord has id?: number (IndexedDB auto-increment key),
 * which must NOT map to the server's String UUID id field.
 * We use localId for upsert logic; the server assigns its own UUID as serverId.
 */
@Data
public class DataRecordDto {
    private String localId;
    private String nfcTagId;
    private String assetEntryId;
    private String assetName;
    private String assetTypeId;
    private String recordStatus;
    private String syncStatus;
    private Map<String, Object> formData;
    private String notes;
    private String operatorName;
    private String location;
    private Long syncedAt;
    private String syncError;
    private Long createdAt;
    private Long updatedAt;
}
