package com.hnp.backendofflinefirst.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class LogSheetSubmitResult {
    private String localId;
    private Long serverId;
    private String error;
    /** Outcome for the client: SUBMITTED | SUPERSEDED | EXPIRED | DUPLICATE. */
    private String outcome;

    public LogSheetSubmitResult(String localId, Long serverId, String error) {
        this(localId, serverId, error, error == null ? "SUBMITTED" : "ERROR");
    }
}
