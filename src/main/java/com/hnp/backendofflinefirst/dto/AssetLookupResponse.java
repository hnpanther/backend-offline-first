package com.hnp.backendofflinefirst.dto;

import com.hnp.backendofflinefirst.entity.AssetClass;
import com.hnp.backendofflinefirst.entity.AssetEntry;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class AssetLookupResponse {
    private AssetEntry entry;
    private AssetClass assetClass;
}
