package com.hnp.backendofflinefirst.service;

import com.hnp.backendofflinefirst.dto.LogSheetBundleDto;
import com.hnp.backendofflinefirst.entity.AssetClass;
import com.hnp.backendofflinefirst.entity.AssetEntry;
import com.hnp.backendofflinefirst.entity.FieldDefinition;
import com.hnp.backendofflinefirst.entity.Location;
import com.hnp.backendofflinefirst.entity.LogSheet;
import com.hnp.backendofflinefirst.entity.LogSheetEntry;
import com.hnp.backendofflinefirst.entity.LogSheetTemplate;
import com.hnp.backendofflinefirst.entity.MainFunction;
import com.hnp.backendofflinefirst.entity.PlantSystem;
import com.hnp.backendofflinefirst.entity.SubFunction;
import com.hnp.backendofflinefirst.repository.AssetClassRepository;
import com.hnp.backendofflinefirst.repository.AssetEntryRepository;
import com.hnp.backendofflinefirst.repository.FieldDefinitionRepository;
import com.hnp.backendofflinefirst.repository.LocationRepository;
import com.hnp.backendofflinefirst.repository.LogSheetEntryRepository;
import com.hnp.backendofflinefirst.repository.LogSheetTemplateRepository;
import com.hnp.backendofflinefirst.repository.MainFunctionRepository;
import com.hnp.backendofflinefirst.repository.PlantSystemRepository;
import com.hnp.backendofflinefirst.repository.SubFunctionRepository;
import com.hnp.backendofflinefirst.util.ReferenceLabelService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LogSheetBundleServiceTest {

    @Mock LogSheetAccessService logSheetAccessService;
    @Mock LogSheetEntryRepository logSheetEntryRepository;
    @Mock LogSheetTemplateRepository templateRepository;
    @Mock AssetHierarchyService hierarchyService;
    @Mock SubFunctionRepository subFunctionRepository;
    @Mock MainFunctionRepository mainFunctionRepository;
    @Mock PlantSystemRepository plantSystemRepository;
    @Mock LocationRepository locationRepository;
    @Mock AssetEntryRepository assetEntryRepository;
    @Mock AssetClassRepository assetClassRepository;
    @Mock FieldDefinitionRepository fieldDefinitionRepository;
    @Mock ReferenceLabelService referenceLabelService;

    @InjectMocks LogSheetBundleService service;

    @Test
    void buildFullBundleIncludesEntriesContextAndFieldDefinitions() {
        LogSheet sheet = new LogSheet();
        sheet.setId(1L);
        sheet.setTemplateId(5L);
        sheet.setScopeSummary("location:10");

        LogSheetTemplate template = new LogSheetTemplate();
        template.setId(5L);
        template.setScopeType("location");
        template.setScopeId(10L);
        template.setClassId(7L);

        LogSheetEntry entry = new LogSheetEntry();
        entry.setLogSheetId(1L);
        entry.setAssetId(42L);
        entry.setAssetName("Pump A");
        entry.setClassId(7L);
        entry.setNfcTagId("NFC-1");
        entry.setSubFunctionCode("SF-01");
        entry.setFormData(Map.of("temp", 25));
        entry.setCreatedAt(1_700_000_000_000L);
        entry.setUpdatedAt(1_700_000_100_000L);

        SubFunction subFunction = new SubFunction();
        subFunction.setId(100L);
        subFunction.setCode("SF-01");
        subFunction.setLocationId(10L);

        Location location = new Location();
        location.setId(10L);
        location.setCode("LOC-1");
        location.setName("Hall A");

        AssetEntry asset = new AssetEntry();
        asset.setId(42L);
        asset.setAssetName("Pump A");
        asset.setClassId(7L);
        asset.setSubFunctionId(100L);

        AssetClass assetClass = new AssetClass();
        assetClass.setId(7L);
        assetClass.setName("Pump");

        FieldDefinition activeField = new FieldDefinition();
        activeField.setId(1L);
        activeField.setClassId(7L);
        activeField.setKey("temp");
        activeField.setLabel("Temperature");
        activeField.setDeleted(false);

        FieldDefinition deletedField = new FieldDefinition();
        deletedField.setId(2L);
        deletedField.setClassId(7L);
        deletedField.setKey("old");
        deletedField.setDeleted(true);

        when(logSheetAccessService.requireVisibleLogSheet(1L)).thenReturn(sheet);
        when(logSheetEntryRepository.findByLogSheetId(1L)).thenReturn(List.of(entry));
        when(templateRepository.findById(5L)).thenReturn(Optional.of(template));
        when(subFunctionRepository.findAllById(Set.of(100L))).thenReturn(List.of(subFunction));
        when(locationRepository.findAllById(Set.of(10L))).thenReturn(List.of(location));
        when(locationRepository.findById(10L)).thenReturn(Optional.of(location));
        when(assetEntryRepository.findAllById(Set.of(42L))).thenReturn(List.of(asset));
        when(assetClassRepository.findAllById(Set.of(7L))).thenReturn(List.of(assetClass));
        when(fieldDefinitionRepository.findByClassIdIn(Set.of(7L)))
                .thenReturn(List.of(activeField, deletedField));
        when(referenceLabelService.templateScopeDisplayLabel("location", 10L, 7L))
                .thenReturn("Hall A · کلاس: Pump");

        LogSheetBundleDto bundle = service.buildFullBundle(1L);

        assertThat(bundle.getSheet()).isSameAs(sheet);
        assertThat(bundle.getEntries()).hasSize(1);
        assertThat(bundle.getEntries().get(0).getAssetId()).isEqualTo(42L);
        assertThat(bundle.getEntries().get(0).getFormData()).containsEntry("temp", 25);
        assertThat(bundle.getEntries().get(0).getCreatedAt()).isEqualTo(1_700_000_000_000L);
        assertThat(bundle.getEntries().get(0).getUpdatedAt()).isEqualTo(1_700_000_100_000L);
        assertThat(bundle.getContext()).isNotNull();
        assertThat(bundle.getContext().getLocations()).containsExactly(location);
        assertThat(bundle.getContext().getSubFunctions()).containsExactly(subFunction);
        assertThat(bundle.getContext().getAssetEntries()).containsExactly(asset);
        assertThat(bundle.getContext().getAssetClasses()).containsExactly(assetClass);
        assertThat(bundle.getContext().getFieldDefinitions()).containsExactly(activeField);
        assertThat(bundle.getContext().getScopeDisplayLabel()).isEqualTo("Hall A · کلاس: Pump");
        // Sheet already has assets → do not dump every SF under the template location.
        verify(hierarchyService, org.mockito.Mockito.never()).subFunctionIdsInScope(any(), any());
    }

    @Test
    void buildMetadataOnlyLeavesEntriesAndContextEmpty() {
        LogSheet sheet = new LogSheet();
        sheet.setId(9L);

        LogSheetBundleDto bundle = service.buildMetadataOnly(sheet);

        assertThat(bundle.getSheet()).isSameAs(sheet);
        assertThat(bundle.getEntries()).isEmpty();
        assertThat(bundle.getContext()).isNull();
    }

    @Test
    void buildFullBundleBySheetSkipsVisibilityLookup() {
        LogSheet sheet = new LogSheet();
        sheet.setId(2L);
        when(logSheetEntryRepository.findByLogSheetId(2L)).thenReturn(List.of());

        LogSheetBundleDto bundle = service.buildFullBundle(sheet);

        assertThat(bundle.getSheet()).isSameAs(sheet);
        assertThat(bundle.getEntries()).isEmpty();
        assertThat(bundle.getContext()).isNotNull();
        verify(logSheetAccessService, org.mockito.Mockito.never()).requireVisibleLogSheet(any());
    }

    @Test
    void buildFullBundlePropagatesAccessDenied() {
        when(logSheetAccessService.requireVisibleLogSheet(99L))
                .thenThrow(new AccessDeniedException("denied"));

        assertThatThrownBy(() -> service.buildFullBundle(99L))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void buildFullBundleForSystemScopeIncludesPlantSystemAndLocation() {
        LogSheet sheet = sheetWithTemplate(3L, 8L);
        LogSheetTemplate template = template("system", 20L, 7L);

        PlantSystem system = new PlantSystem();
        system.setId(20L);
        system.setCode("SYS-1");
        system.setLocationId(10L);

        Location location = new Location();
        location.setId(10L);
        location.setCode("LOC-1");

        SubFunction subFunction = new SubFunction();
        subFunction.setId(100L);
        subFunction.setSystemId(20L);
        subFunction.setLocationId(10L);

        stubTemplateAndScope(sheet, template, Set.of(100L), List.of(subFunction));
        when(plantSystemRepository.findAllById(Set.of(20L))).thenReturn(List.of(system));
        when(locationRepository.findAllById(Set.of(10L))).thenReturn(List.of(location));
        when(locationRepository.findById(10L)).thenReturn(Optional.of(location));
        stubEmptyAssetsAndFields();

        LogSheetBundleDto bundle = service.buildFullBundle(sheet);

        assertThat(bundle.getContext().getPlantSystems()).containsExactly(system);
        assertThat(bundle.getContext().getLocations()).containsExactly(location);
    }

    @Test
    void buildFullBundleForMainFunctionScopeIncludesMainFunctionChain() {
        LogSheet sheet = sheetWithTemplate(4L, 9L);
        LogSheetTemplate template = template("mainFunction", 30L, 7L);

        MainFunction mainFunction = new MainFunction();
        mainFunction.setId(30L);
        mainFunction.setCode("MF-1");
        mainFunction.setSystemId(20L);
        mainFunction.setLocationId(10L);

        PlantSystem system = new PlantSystem();
        system.setId(20L);
        system.setLocationId(10L);

        Location location = new Location();
        location.setId(10L);

        SubFunction subFunction = new SubFunction();
        subFunction.setId(100L);
        subFunction.setMainFunctionId(30L);
        subFunction.setSystemId(20L);
        subFunction.setLocationId(10L);

        when(logSheetEntryRepository.findByLogSheetId(9L)).thenReturn(List.of());
        when(templateRepository.findById(4L)).thenReturn(Optional.of(template));
        when(hierarchyService.subFunctionIdsInScope("mainFunction", 30L)).thenReturn(Set.of(100L));
        when(subFunctionRepository.findAllById(Set.of(100L))).thenReturn(List.of(subFunction));
        when(mainFunctionRepository.findAllById(Set.of(30L))).thenReturn(List.of(mainFunction));
        when(mainFunctionRepository.findById(30L)).thenReturn(Optional.of(mainFunction));
        when(plantSystemRepository.findAllById(Set.of(20L))).thenReturn(List.of(system));
        when(locationRepository.findAllById(Set.of(10L))).thenReturn(List.of(location));
        when(locationRepository.findById(10L)).thenReturn(Optional.of(location));
        when(referenceLabelService.templateScopeDisplayLabel("mainFunction", 30L, 7L))
                .thenReturn("MF label");
        stubEmptyAssetsAndFields();

        LogSheetBundleDto bundle = service.buildFullBundle(sheet);

        assertThat(bundle.getContext().getMainFunctions()).containsExactly(mainFunction);
        assertThat(bundle.getContext().getPlantSystems()).containsExactly(system);
    }

    @Test
    void buildFullBundleExpandsLocationParentChain() {
        LogSheet sheet = sheetWithTemplate(5L, 11L);
        LogSheetTemplate template = template("location", 11L, 7L);

        Location parent = new Location();
        parent.setId(10L);
        parent.setCode("PARENT");

        Location child = new Location();
        child.setId(11L);
        child.setCode("CHILD");
        child.setParentId(10L);

        SubFunction subFunction = new SubFunction();
        subFunction.setId(100L);
        subFunction.setLocationId(11L);

        stubTemplateAndScope(sheet, template, Set.of(100L), List.of(subFunction));
        when(locationRepository.findAllById(Set.of(10L, 11L))).thenReturn(List.of(parent, child));
        when(locationRepository.findById(11L)).thenReturn(Optional.of(child));
        when(locationRepository.findById(10L)).thenReturn(Optional.of(parent));
        stubEmptyAssetsAndFields();

        LogSheetBundleDto bundle = service.buildFullBundle(sheet);

        assertThat(bundle.getContext().getLocations()).containsExactly(parent, child);
    }

    @Test
    void buildFullBundleWithoutTemplateUsesScopeSummaryLabel() {
        LogSheet sheet = new LogSheet();
        sheet.setId(12L);
        sheet.setScopeSummary("legacy-scope");

        when(logSheetEntryRepository.findByLogSheetId(12L)).thenReturn(List.of());

        LogSheetBundleDto bundle = service.buildFullBundle(sheet);

        assertThat(bundle.getContext().getScopeDisplayLabel()).isEqualTo("legacy-scope");
        assertThat(bundle.getContext().getLocations()).isEmpty();
        verify(templateRepository, org.mockito.Mockito.never()).findById(any());
    }

    private LogSheet sheetWithTemplate(Long templateId, Long sheetId) {
        LogSheet sheet = new LogSheet();
        sheet.setId(sheetId);
        sheet.setTemplateId(templateId);
        return sheet;
    }

    private LogSheetTemplate template(String scopeType, Long scopeId, Long classId) {
        LogSheetTemplate template = new LogSheetTemplate();
        template.setId(scopeType.equals("system") ? 3L : scopeType.equals("mainFunction") ? 4L : 5L);
        template.setScopeType(scopeType);
        template.setScopeId(scopeId);
        template.setClassId(classId);
        return template;
    }

    private void stubTemplateAndScope(
            LogSheet sheet,
            LogSheetTemplate template,
            Set<Long> subFunctionIds,
            List<SubFunction> subFunctions) {
        when(logSheetEntryRepository.findByLogSheetId(sheet.getId())).thenReturn(List.of());
        when(templateRepository.findById(sheet.getTemplateId())).thenReturn(Optional.of(template));
        when(hierarchyService.subFunctionIdsInScope(template.getScopeType(), template.getScopeId()))
                .thenReturn(subFunctionIds);
        when(subFunctionRepository.findAllById(subFunctionIds)).thenReturn(subFunctions);
        PlantSystem scopeSystem = new PlantSystem();
        scopeSystem.setId(20L);
        scopeSystem.setLocationId(10L);
        lenient().when(plantSystemRepository.findById(20L)).thenReturn(Optional.of(scopeSystem));
        lenient().when(referenceLabelService.templateScopeDisplayLabel(
                eq(template.getScopeType()), eq(template.getScopeId()), eq(template.getClassId())))
                .thenReturn("scope");
    }

    private void stubEmptyAssetsAndFields() {
        when(assetClassRepository.findAllById(Set.of(7L))).thenReturn(List.of());
        when(fieldDefinitionRepository.findByClassIdIn(Set.of(7L))).thenReturn(List.of());
    }
}
