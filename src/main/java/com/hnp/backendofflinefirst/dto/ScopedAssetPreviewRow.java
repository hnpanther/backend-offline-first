package com.hnp.backendofflinefirst.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class ScopedAssetPreviewRow {
    private String assetCode;
    private String assetName;
    private String nfcTagId;
    private String subFunctionCode;
    private String subFunctionTag;
}
