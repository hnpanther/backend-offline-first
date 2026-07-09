package com.hnp.backendofflinefirst.dto;

import com.hnp.backendofflinefirst.entity.AssetClass;
import com.hnp.backendofflinefirst.entity.AssetEntry;
import com.hnp.backendofflinefirst.entity.FieldDefinition;
import com.hnp.backendofflinefirst.entity.Location;
import com.hnp.backendofflinefirst.entity.MainFunction;
import com.hnp.backendofflinefirst.entity.PlantSystem;
import com.hnp.backendofflinefirst.entity.SubFunction;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Reference data scoped to a single log sheet: hierarchy slice, assets, and form
 * field definitions required to fill the sheet offline.
 */
@Data
@Builder
public class LogSheetContextDto {
    private List<Location> locations;
    private List<PlantSystem> plantSystems;
    private List<MainFunction> mainFunctions;
    private List<SubFunction> subFunctions;
    private List<AssetEntry> assetEntries;
    private List<AssetClass> assetClasses;
    private List<FieldDefinition> fieldDefinitions;
    private String scopeDisplayLabel;
}
