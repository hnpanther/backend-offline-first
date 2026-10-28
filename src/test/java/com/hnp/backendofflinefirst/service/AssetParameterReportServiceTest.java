package com.hnp.backendofflinefirst.service;

import com.hnp.backendofflinefirst.domain.FieldValidationSeverity;
import com.hnp.backendofflinefirst.domain.FieldValidationSupport;
import com.hnp.backendofflinefirst.domain.LogSheetStatus;
import com.hnp.backendofflinefirst.entity.AssetEntry;
import com.hnp.backendofflinefirst.entity.FieldDefinition;
import com.hnp.backendofflinefirst.repository.AssetEntryRepository;
import com.hnp.backendofflinefirst.repository.FieldDefinitionRepository;
import com.hnp.backendofflinefirst.repository.LogSheetEntryRepository;
import com.hnp.backendofflinefirst.util.DateUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AssetParameterReportServiceTest {

    @Mock LogSheetEntryRepository logSheetEntryRepository;
    @Mock AssetEntryRepository assetEntryRepository;
    @Mock AssetAccessService assetAccessService;
    @Mock FieldDefinitionRepository fieldDefinitionRepository;
    @Mock DateUtils dateUtils;
    @Mock LogSheetFieldDefinitionsService fieldDefinitionsService;

    @InjectMocks AssetParameterReportService service;

    @Test
    void buildChartSeries_extractsNumericPoints() {
        AssetEntry asset = new AssetEntry();
        asset.setId(5L);
        asset.setClassId(2L);
        when(assetAccessService.findReportable(5L)).thenReturn(Optional.of(asset));

        FieldDefinition temp = new FieldDefinition();
        temp.setKey("temp");
        temp.setLabel("دما");
        temp.setDataType("number");
        temp.setUnit("°C");
        when(fieldDefinitionsService.resolveForReporting(asset)).thenReturn(List.of(temp));

        Map<String, Object> formData = new LinkedHashMap<>();
        formData.put("temp", 42.5);
        Object[] row = {1L, 10L, 1_700_000_000_000L, "Round A", "Ali", formData};
        when(logSheetEntryRepository.findSubmittedReadingRowsByAssetIdAsc(
                eq(5L), eq(LogSheetStatus.COMPLETED_STATUSES), isNull(), isNull()))
                .thenReturn(List.<Object[]>of(row));

        var series = service.buildChartSeries(5L, "temp", null, null);
        assertThat(series).isPresent();
        assertThat(series.get().points()).hasSize(1);
        assertThat(series.get().points().get(0).value()).isEqualTo(42.5);
        assertThat(series.get().fieldLabel()).isEqualTo("دما");
    }

    @Test
    void buildValueHistoryPage_flattensFormDataRows() {
        AssetEntry asset = new AssetEntry();
        asset.setId(5L);
        asset.setClassId(2L);
        when(assetAccessService.findReportable(5L)).thenReturn(Optional.of(asset));

        FieldDefinition temp = new FieldDefinition();
        temp.setKey("temp");
        temp.setLabel("دما");
        temp.setDataType("number");
        when(fieldDefinitionsService.resolveForReporting(asset)).thenReturn(List.of(temp));
        when(dateUtils.format(any())).thenReturn("۱۴۰۴/۰۱/۰۱ ۱۰:۰۰");

        Map<String, Object> formData = Map.of("temp", 40);
        Object[] row = {1L, 10L, 100L, "Round A", "Ali", formData};
        when(logSheetEntryRepository.findSubmittedReadingRowsByAssetId(
                eq(5L), eq(LogSheetStatus.COMPLETED_STATUSES), isNull(), isNull(), any()))
                .thenReturn(new PageImpl<>(List.<Object[]>of(row), PageRequest.of(0, 25), 1));

        var page = service.buildValueHistoryPage(5L, null, null, null, PageRequest.of(0, 25));
        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getContent().get(0).fieldLabel()).isEqualTo("دما");
        assertThat(page.getContent().get(0).displayValue()).isEqualTo("40");
        assertThat(page.getContent().get(0).severity()).isEqualTo(FieldValidationSeverity.OK);
    }

    @Test
    void buildChartSeries_rejectsNonNumericField() {
        AssetEntry asset = new AssetEntry();
        asset.setId(5L);
        asset.setClassId(2L);
        when(assetAccessService.findReportable(5L)).thenReturn(Optional.of(asset));

        FieldDefinition note = new FieldDefinition();
        note.setKey("note");
        note.setLabel("یادداشت");
        note.setDataType("text");
        when(fieldDefinitionsService.resolveForReporting(asset)).thenReturn(List.of(note));

        assertThat(service.buildChartSeries(5L, "note", null, null)).isEmpty();
    }

    /**
     * A parameter that has been switched off still owns its readings.
     *
     * <p>Without its definition the history prints the bare key, no unit and {@code OK} for a
     * value that breached — which is the invisibility the whole disable feature exists to
     * remove, reappearing in the one place built to show history. The service is handed
     * disabled definitions deliberately (resolveForReporting), and must not filter them out
     * again on the way through.
     */
    @Test
    void aSwitchedOffParameterStillNamesAndJudgesItsReadings() {
        AssetEntry asset = new AssetEntry();
        asset.setId(5L);
        asset.setClassId(2L);
        when(assetAccessService.findReportable(5L)).thenReturn(Optional.of(asset));

        FieldDefinition retired = new FieldDefinition();
        retired.setKey("temp");
        retired.setLabel("دما");
        retired.setDataType("number");
        retired.setUnit("°C");
        retired.setDeleted(true);
        Map<String, Object> validation = new LinkedHashMap<>();
        validation.put(FieldValidationSupport.KEY_DANGER, Map.of("max", 50));
        retired.setValidation(validation);
        when(fieldDefinitionsService.resolveForReporting(asset)).thenReturn(List.of(retired));
        when(dateUtils.format(any())).thenReturn("۱۴۰۴/۰۱/۰۱");

        Object[] row = {1L, 10L, 100L, "Round A", "Ali", Map.of("temp", 80)};
        when(logSheetEntryRepository.findSubmittedReadingRowsByAssetId(
                eq(5L), eq(LogSheetStatus.COMPLETED_STATUSES), isNull(), isNull(), any()))
                .thenReturn(new PageImpl<>(List.<Object[]>of(row), PageRequest.of(0, 25), 1));

        var only = service.buildValueHistoryPage(5L, null, null, null, PageRequest.of(0, 25))
                .getContent().get(0);

        assertThat(only.fieldLabel()).isEqualTo("دما");
        assertThat(only.unit()).isEqualTo("°C");
        assertThat(only.severity()).isEqualTo(FieldValidationSeverity.DANGER);
        assertThat(only.disabled())
                .as("the page needs to say WHY the series stops, or it reads as missing data")
                .isTrue();
    }

    /** And the chart still draws it — a retired parameter's history is why you open this page. */
    @Test
    void aSwitchedOffParameterStillCharts() {
        AssetEntry asset = new AssetEntry();
        asset.setId(5L);
        asset.setClassId(2L);
        when(assetAccessService.findReportable(5L)).thenReturn(Optional.of(asset));

        FieldDefinition retired = new FieldDefinition();
        retired.setKey("temp");
        retired.setLabel("دما");
        retired.setDataType("number");
        retired.setDeleted(true);
        when(fieldDefinitionsService.resolveForReporting(asset)).thenReturn(List.of(retired));

        Object[] row = {1L, 10L, 1_700_000_000_000L, "Round A", "Ali", Map.of("temp", 42.5)};
        when(logSheetEntryRepository.findSubmittedReadingRowsByAssetIdAsc(
                eq(5L), eq(LogSheetStatus.COMPLETED_STATUSES), isNull(), isNull()))
                .thenReturn(List.<Object[]>of(row));

        assertThat(service.buildChartSeries(5L, "temp", null, null)).isPresent();
    }
}
