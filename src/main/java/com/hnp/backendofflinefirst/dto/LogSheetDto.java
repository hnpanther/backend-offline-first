package com.hnp.backendofflinefirst.dto;

import lombok.Data;

import java.util.List;

@Data
public class LogSheetDto {
    private Long id;
    private String localId;
    private Long templateId;
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
    private Long operationalUnitId;
    private Long serverId;

    // Offline completion: device-recorded time the operator finished the sheet,
    // and an idempotency key so a replayed sync is not applied twice.
    private Long completedAt;
    private String clientActionId;
}
