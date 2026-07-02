package com.hnp.backendofflinefirst.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class RecordSubmitResult {
    private String localId;
    private Long serverId;
    private String error;
}
