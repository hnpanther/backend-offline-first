package com.hnp.backendofflinefirst.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class ScopedAssetPreviewRow {
    /**
     * The asset's id, so the preview can also be the form that freezes the list
     * (roadmap.md §21). The other fields are what a person reads; this is what is posted.
     */
    private Long assetId;
    private String assetCode;
    private String assetName;
    private String nfcTagId;
    private String subFunctionCode;
    private String subFunctionTag;
}
