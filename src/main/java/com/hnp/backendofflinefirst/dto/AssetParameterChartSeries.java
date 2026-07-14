package com.hnp.backendofflinefirst.dto;

import java.util.List;

/** Chart payload for a numeric field (includes validation bands). */
public record AssetParameterChartSeries(
        String fieldKey,
        String fieldLabel,
        String unit,
        Double warningMin,
        Double warningMax,
        Double dangerMin,
        Double dangerMax,
        List<AssetParameterChartPoint> points
) {}
