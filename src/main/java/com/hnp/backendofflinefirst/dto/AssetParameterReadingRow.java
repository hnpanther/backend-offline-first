package com.hnp.backendofflinefirst.dto;

import java.util.Map;

/** One submitted log-sheet reading row for an asset (joined with sheet metadata). */
public record AssetParameterReadingRow(
        Long entryId,
        Long logSheetId,
        Long recordedAt,
        String templateName,
        String operatorName,
        Map<String, Object> formData
) {}
