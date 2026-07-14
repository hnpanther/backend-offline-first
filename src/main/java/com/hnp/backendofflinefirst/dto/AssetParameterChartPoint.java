package com.hnp.backendofflinefirst.dto;

/** Time-series point for chart rendering. */
public record AssetParameterChartPoint(
        long timestamp,
        Double value,
        Long logSheetId
) {}
