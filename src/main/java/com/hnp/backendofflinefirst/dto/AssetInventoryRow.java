package com.hnp.backendofflinefirst.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class AssetInventoryRow {
    private Long id;
    private String assetCode;
    private String assetName;
    private String nfcTagId;
    private String locationCode;
    private String systemCode;
    private String mainFunctionCode;
    private String subFunctionCode;
    private String className;
}
