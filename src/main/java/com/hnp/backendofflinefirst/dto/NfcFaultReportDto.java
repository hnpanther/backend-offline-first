package com.hnp.backendofflinefirst.dto;

import lombok.Data;

/**
 * Mobile sync payload for one NFC fault report. Inbound (batch submit): only
 * {@code logSheetId}, {@code assetId}, {@code reason}, {@code createdAt},
 * {@code clientActionId}, and {@code localId} are read — {@code reportedByUserId}
 * / {@code reportedByName} / {@code source} / {@code status} / {@code syncedAt}
 * are always server-authoritative, matching {@link LogSheetEntryDto}'s contract.
 */
@Data
public class NfcFaultReportDto {
    private Long id;
    private Long logSheetId;
    private Long assetId;
    private String reason;
    private String reportedByName;
    private String source;
    private String status;
    private Long createdAt;
    private Long syncedAt;
    private String clientActionId;
    private String localId;
}
