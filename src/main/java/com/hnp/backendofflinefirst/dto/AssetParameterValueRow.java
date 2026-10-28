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
        String operatorName,
        /**
         * Whether the parameter this reading belongs to has since been switched off.
         *
         * <p>The reading is still real and is still shown with its label, its unit and the
         * severity it was judged at. The flag exists so the page can say why no new round
         * offers it — without it a reader seeing a parameter that stops partway through the
         * history concludes the data is missing rather than that somebody turned it off.
         */
        boolean disabled
) {}
