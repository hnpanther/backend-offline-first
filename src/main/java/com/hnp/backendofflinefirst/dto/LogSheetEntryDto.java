package com.hnp.backendofflinefirst.dto;

import lombok.Data;

import java.util.Map;

@Data
public class LogSheetEntryDto {
    private Long assetId;
    private String assetName;
    private String subFunctionCode;
    private String subFunctionTag;
    private String nfcTagId;
    private Long classId;
    private Map<String, Object> formData;
}
