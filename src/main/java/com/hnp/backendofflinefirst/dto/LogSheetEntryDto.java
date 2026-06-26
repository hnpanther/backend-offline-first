package com.hnp.backendofflinefirst.dto;

import lombok.Data;

import java.util.Map;

@Data
public class LogSheetEntryDto {
    private String assetId;
    private String assetName;
    private String subFunctionCode;
    private String subFunctionTag;
    private String classId;
    private Map<String, Object> formData;
}
