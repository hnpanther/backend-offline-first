package com.hnp.backendofflinefirst.dto;

import com.hnp.backendofflinefirst.domain.FieldValidationSeverity;

/** Flattened parameter value for the history table. */
public record AssetParameterValueRow(
        Long recordedAt,
        String recordedAtLabel,
        String fieldKey,
        String fieldLabel,
        String displayValue,
        String unit,
        FieldValidationSeverity severity,
        Long logSheetId,
        String templateName,
        String operatorName
) {}
