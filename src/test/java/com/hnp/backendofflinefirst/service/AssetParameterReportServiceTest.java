package com.hnp.backendofflinefirst.service;

import com.hnp.backendofflinefirst.domain.FieldValidationSeverity;
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
    @Mock FieldDefinitionRepository fieldDefinitionRepository;
    @Mock DateUtils dateUtils;

    @InjectMocks AssetParameterReportService service;

    @Test
    void buildChartSeries_extractsNumericPoints() {
        AssetEntry asset = new AssetEntry();
        asset.setId(5L);
        asset.setClassId(2L);
        when(assetEntryRepository.findById(5L)).thenReturn(Optional.of(asset));

        FieldDefinition temp = new FieldDefinition();
        temp.setKey("temp");
        temp.setLabel("دما");
        temp.setDataType("number");
        temp.setUnit("°C");
        when(fieldDefinitionRepository.findByClassId(2L)).thenReturn(List.of(temp));

        Map<String, Object> formData = new LinkedHashMap<>();
        formData.put("temp", 42.5);
        Object[] row = {1L, 10L, 1_700_000_000_000L, "Round A", "Ali", formData};
        when(logSheetEntryRepository.findSubmittedReadingRowsByAssetIdAsc(
                eq(5L), eq(LogSheetStatus.SUBMITTED), isNull(), isNull()))
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
        when(assetEntryRepository.findById(5L)).thenReturn(Optional.of(asset));

        FieldDefinition temp = new FieldDefinition();
        temp.setKey("temp");
        temp.setLabel("دما");
        temp.setDataType("number");
        when(fieldDefinitionRepository.findByClassId(2L)).thenReturn(List.of(temp));
        when(dateUtils.format(any())).thenReturn("۱۴۰۴/۰۱/۰۱ ۱۰:۰۰");

        Map<String, Object> formData = Map.of("temp", 40);
        Object[] row = {1L, 10L, 100L, "Round A", "Ali", formData};
        when(logSheetEntryRepository.findSubmittedReadingRowsByAssetId(
                eq(5L), eq(LogSheetStatus.SUBMITTED), isNull(), isNull(), any()))
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
        when(assetEntryRepository.findById(5L)).thenReturn(Optional.of(asset));

        FieldDefinition note = new FieldDefinition();
        note.setKey("note");
        note.setLabel("یادداشت");
        note.setDataType("text");
        when(fieldDefinitionRepository.findByClassId(2L)).thenReturn(List.of(note));

        assertThat(service.buildChartSeries(5L, "note", null, null)).isEmpty();
    }
}
