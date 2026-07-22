package com.hnp.backendofflinefirst.dto;

import lombok.Data;

import java.util.Map;

@Data
/** Mobile sync payload. Only {@link #assetId}, {@link #formData}, {@link #createdAt}, and {@link #updatedAt}
 *  are applied on submit; other fields are server-authoritative snapshots for offline display. */
public class LogSheetEntryDto {
    private Long assetId;
    private String assetName;
    private String subFunctionCode;
    private String subFunctionTag;
    private String nfcTagId;
    private Long classId;
    private Map<String, Object> formData;
    private Long createdAt;
    private Long updatedAt;
}
