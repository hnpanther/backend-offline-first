package com.hnp.backendofflinefirst.dto;

import com.hnp.backendofflinefirst.entity.*;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class MasterDataResponse {
    private Long serverTime;
    private List<Location> locations;
    private List<PlantSystem> plantSystems;
    private List<MainFunction> mainFunctions;
    private List<SubFunction> subFunctions;
    private List<AssetClass> assetClasses;
    private List<FieldDefinition> fieldDefinitions;
    private List<AssetEntry> assetEntries;
    private List<LogSheetTemplate> logSheetTemplates;
    private List<OperationalUnit> operationalUnits;
}
