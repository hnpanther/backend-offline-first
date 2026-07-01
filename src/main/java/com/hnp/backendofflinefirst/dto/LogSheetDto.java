package com.hnp.backendofflinefirst.dto;

import lombok.Data;

import java.util.List;

@Data
public class LogSheetDto {
    private String id;
    private String localId;
    private String templateId;
    private String templateName;
    private String scopeSummary;
    private String operatorName;
    private String status;
    private String syncStatus;
    private List<LogSheetEntryDto> entries;
    private Long submittedAt;
    private Long createdAt;
    private Long updatedAt;
    private Long syncedAt;
    private String syncError;
    private String operationalUnitId;
    private String serverId;
}
