package com.hnp.backendofflinefirst.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class LogSheetSubmitResult {
    private String localId;
    private String serverId;
    private String error;
}
