package com.hnp.backendofflinefirst.service;

import com.hnp.backendofflinefirst.dto.AssetInventoryRow;
import com.hnp.backendofflinefirst.entity.AssetEntry;
import com.hnp.backendofflinefirst.repository.*;
import com.hnp.backendofflinefirst.util.DateUtils;
import com.hnp.backendofflinefirst.util.ReferenceLabelService;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExcelExportServiceTest {

    @Mock AppSettingsService appSettingsService;
    @Mock DateUtils dateUtils;
    @Mock ReferenceLabelService labels;
    @Mock UserRepository userRepository;
    @Mock RoleRepository roleRepository;
    @Mock UserRoleRepository userRoleRepository;
    @Mock OperationalUnitRepository operationalUnitRepository;
    @Mock LocationRepository locationRepository;
    @Mock PlantSystemRepository plantSystemRepository;
    @Mock MainFunctionRepository mainFunctionRepository;
    @Mock SubFunctionRepository subFunctionRepository;
    @Mock AssetClassRepository assetClassRepository;
    @Mock AssetEntryRepository assetEntryRepository;
    @Mock FieldDefinitionRepository fieldDefinitionRepository;
    @Mock LogSheetTemplateService logSheetTemplateService;
    @Mock DataRecordRepository dataRecordRepository;
    @Mock LogSheetAccessService logSheetAccessService;
    @Mock AssetReportService assetReportService;

    @InjectMocks ExcelExportService excelExportService;

    @Test
    void exportAssetEntriesFetchesOnlyMaxPlusOneFromDatabase() throws IOException {
        when(appSettingsService.getExcelExportMaxRows()).thenReturn(100);
        List<AssetEntry> page = IntStream.rangeClosed(1, 101)
                .mapToObj(i -> {
                    AssetEntry ae = new AssetEntry();
                    ae.setId((long) i);
                    ae.setAssetCode("A-" + i);
                    return ae;
                })
                .toList();
        when(assetEntryRepository.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(page));

        HttpServletResponse response = mockResponse();
        excelExportService.exportAssetEntries(response);

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(assetEntryRepository).findAll(pageableCaptor.capture());
        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(101);
        assertThat(pageableCaptor.getValue().getPageNumber()).isEqualTo(0);
    }

    @Test
    void exportAssetInventoryUsesLimitedReportFetch() throws IOException {
        when(appSettingsService.getExcelExportMaxRows()).thenReturn(50);
        when(assetReportService.buildAssetInventoryForExport(50)).thenReturn(List.of(
                new AssetInventoryRow(1L, "A1", "N1", "nfc", "L", "S", "M", "SF", "C")
        ));

        excelExportService.exportAssetInventoryReport(mockResponse());
        verify(assetReportService).buildAssetInventoryForExport(50);
    }

    @Test
    void exportLogSheetsUsesPagedVisibleQuery() throws IOException {
        when(appSettingsService.getExcelExportMaxRows()).thenReturn(200);
        when(logSheetAccessService.findVisibleLogSheets(isNull(), isNull(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        excelExportService.exportLogSheets(null, mockResponse());

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(logSheetAccessService).findVisibleLogSheets(isNull(), isNull(), pageableCaptor.capture());
        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(201);
    }

    private static HttpServletResponse mockResponse() throws IOException {
        HttpServletResponse response = mock(HttpServletResponse.class);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        when(response.getOutputStream()).thenReturn(new ServletOutputStream() {
            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setWriteListener(WriteListener writeListener) {
            }

            @Override
            public void write(int b) {
                bos.write(b);
            }
        });
        return response;
    }
}
