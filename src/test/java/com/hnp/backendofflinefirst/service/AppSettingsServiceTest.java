package com.hnp.backendofflinefirst.service;

import com.hnp.backendofflinefirst.entity.AppSetting;
import com.hnp.backendofflinefirst.repository.AppSettingRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AppSettingsServiceTest {

    @Mock AppSettingRepository appSettingRepository;
    @InjectMocks AppSettingsService appSettingsService;

    @Test
    void getExcelExportMaxRowsFallsBackToDefault() {
        when(appSettingRepository.findById(AppSettingsService.KEY_EXCEL_EXPORT_MAX_ROWS))
                .thenReturn(Optional.empty());
        assertThat(appSettingsService.getExcelExportMaxRows())
                .isEqualTo(AppSettingsService.DEFAULT_EXCEL_EXPORT_MAX_ROWS);
    }

    @Test
    void getExcelExportMaxRowsReadsStoredValue() {
        AppSetting setting = new AppSetting();
        setting.setSettingKey(AppSettingsService.KEY_EXCEL_EXPORT_MAX_ROWS);
        setting.setValue("250");
        when(appSettingRepository.findById(AppSettingsService.KEY_EXCEL_EXPORT_MAX_ROWS))
                .thenReturn(Optional.of(setting));
        assertThat(appSettingsService.getExcelExportMaxRows()).isEqualTo(250);
    }

    @Test
    void saveExcelExportMaxRowsRejectsOutOfRange() {
        assertThatThrownBy(() -> appSettingsService.saveExcelExportMaxRows(50))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> appSettingsService.saveExcelExportMaxRows(2_000_000))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void saveExcelExportMaxRowsPersists() {
        when(appSettingRepository.findById(AppSettingsService.KEY_EXCEL_EXPORT_MAX_ROWS))
                .thenReturn(Optional.empty());
        appSettingsService.saveExcelExportMaxRows(500);
        ArgumentCaptor<AppSetting> captor = ArgumentCaptor.forClass(AppSetting.class);
        verify(appSettingRepository).save(captor.capture());
        assertThat(captor.getValue().getSettingKey()).isEqualTo(AppSettingsService.KEY_EXCEL_EXPORT_MAX_ROWS);
        assertThat(captor.getValue().getValue()).isEqualTo("500");
    }
}
