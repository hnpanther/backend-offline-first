package com.hnp.backendofflinefirst.dto;

import lombok.Data;

import java.util.List;

/** Body of {@code POST /api/log-sheets/progress}. */
@Data
public class LogSheetProgressRequest {
    private List<LogSheetProgressItem> logSheets;
}
