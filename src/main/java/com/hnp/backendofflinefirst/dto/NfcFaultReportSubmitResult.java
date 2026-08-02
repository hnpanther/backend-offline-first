package com.hnp.backendofflinefirst.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

/** Per-item mobile batch submit outcome, mirroring {@link LogSheetSubmitResult}'s shape. */
@Data
@AllArgsConstructor
public class NfcFaultReportSubmitResult {
    private String localId;
    private Long serverId;
    private String error;
    private String outcome;
}
