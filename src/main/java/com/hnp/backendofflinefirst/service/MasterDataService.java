package com.hnp.backendofflinefirst.service;

import com.hnp.backendofflinefirst.dto.MasterDataResponse;
import com.hnp.backendofflinefirst.entity.*;
import com.hnp.backendofflinefirst.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class MasterDataService {

    private final LocationRepository locationRepository;
    private final PlantSystemRepository plantSystemRepository;
    private final MainFunctionRepository mainFunctionRepository;
    private final SubFunctionRepository subFunctionRepository;
    private final AssetClassRepository assetClassRepository;
    private final FieldDefinitionRepository fieldDefinitionRepository;
    private final AssetEntryRepository assetEntryRepository;
    private final LogSheetTemplateRepository logSheetTemplateRepository;
    private final OperationalUnitRepository operationalUnitRepository;

    public MasterDataResponse getMasterData(Long since) {
        List<Location> locations;
        List<PlantSystem> plantSystems;
        List<MainFunction> mainFunctions;
        List<SubFunction> subFunctions;
        List<AssetClass> assetClasses;
        List<FieldDefinition> fieldDefinitions;
        List<AssetEntry> assetEntries;
        List<LogSheetTemplate> logSheetTemplates;
        List<OperationalUnit> operationalUnits;

        if (since != null) {
            locations = locationRepository.findByUpdatedAtGreaterThanEqual(since);
            plantSystems = plantSystemRepository.findByUpdatedAtGreaterThanEqual(since);
            mainFunctions = mainFunctionRepository.findByUpdatedAtGreaterThanEqual(since);
            subFunctions = subFunctionRepository.findByUpdatedAtGreaterThanEqual(since);
            assetClasses = assetClassRepository.findByUpdatedAtGreaterThanEqual(since);
            fieldDefinitions = fieldDefinitionRepository.findByUpdatedAtGreaterThanEqual(since);
            assetEntries = assetEntryRepository.findByUpdatedAtGreaterThanEqual(since);
            logSheetTemplates = logSheetTemplateRepository.findByUpdatedAtGreaterThanEqual(since);
            operationalUnits = operationalUnitRepository.findByUpdatedAtGreaterThanEqual(since);
        } else {
            locations = locationRepository.findAll();
            plantSystems = plantSystemRepository.findAll();
            mainFunctions = mainFunctionRepository.findAll();
            subFunctions = subFunctionRepository.findAll();
            assetClasses = assetClassRepository.findAll();
            fieldDefinitions = fieldDefinitionRepository.findAll();
            assetEntries = assetEntryRepository.findAll();
            logSheetTemplates = logSheetTemplateRepository.findAll();
            operationalUnits = operationalUnitRepository.findAll();
        }

        return MasterDataResponse.builder()
                .serverTime(System.currentTimeMillis())
                .locations(locations)
                .plantSystems(plantSystems)
                .mainFunctions(mainFunctions)
                .subFunctions(subFunctions)
                .assetClasses(assetClasses)
                .fieldDefinitions(fieldDefinitions)
                .assetEntries(assetEntries)
                .logSheetTemplates(logSheetTemplates)
                .operationalUnits(operationalUnits)
                .build();
    }
}
