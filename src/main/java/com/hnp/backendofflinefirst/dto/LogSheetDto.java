package com.hnp.backendofflinefirst.dto;

import lombok.Data;

import java.util.List;
import java.util.Map;

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

    /**
     * Answers to the round's <strong>own</strong> questions — asked once per sheet rather than
     * once per asset (V9).
     *
     * <p>Additive, and {@code null} means "this client did not carry them" rather than "clear
     * them": an older build knows nothing about the round-level form, and its submissions must
     * leave whatever the web panel stored exactly where it is.
     *
     * <p>Downward the device needs nothing new — {@code LogSheetBundleDto.sheet} serialises the
     * whole {@code LogSheet}, so the questions and the current answers already travel with it.
     * This is the only field the contract grew.
     */
    private Map<String, Object> sheetFormData;

    // Offline completion: device-recorded time the operator finished the sheet,
    // and an idempotency key so a replayed sync is not applied twice.
    private Long completedAt;
    private String clientActionId;
}
