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

    // --- Image annotation switch ---------------------------------------------
    // The one setting here whose default is ON, which is what makes the fallback behaviour
    // below worth pinning: every unreadable row must leave the feature enabled rather than
    // quietly disabling it on every tablet.

    private static AppSetting annotationRow(String value) {
        AppSetting setting = new AppSetting();
        setting.setSettingKey(AppSettingsService.KEY_IMAGE_ANNOTATION_ENABLED);
        setting.setValue(value);
        return setting;
    }

    private void storedAnnotationValue(String value) {
        when(appSettingRepository.findById(AppSettingsService.KEY_IMAGE_ANNOTATION_ENABLED))
                .thenReturn(Optional.of(annotationRow(value)));
    }

    @Test
    void imageAnnotationIsOnUntilSomebodyTurnsItOff() {
        when(appSettingRepository.findById(AppSettingsService.KEY_IMAGE_ANNOTATION_ENABLED))
                .thenReturn(Optional.empty());

        assertThat(appSettingsService.isImageAnnotationEnabled())
                .isEqualTo(AppSettingsService.DEFAULT_IMAGE_ANNOTATION_ENABLED)
                .isTrue();
    }

    @Test
    void imageAnnotationReadsAStoredDecision() {
        storedAnnotationValue("false");
        assertThat(appSettingsService.isImageAnnotationEnabled()).isFalse();
    }

    @Test
    void imageAnnotationAcceptsTheOtherSpellingsOfTheSameAnswer() {
        // Rows written by hand or by an older tool. "1"/"0" in particular is what a DBA reaches
        // for when flipping this straight in the database.
        storedAnnotationValue("0");
        assertThat(appSettingsService.isImageAnnotationEnabled()).isFalse();
    }

    @Test
    void imageAnnotationIgnoresAnUnreadableRowRatherThanReadingItAsOff() {
        // Boolean.parseBoolean would answer false to every one of these and silently disable a
        // feature nobody asked to disable.
        storedAnnotationValue("maybe");
        assertThat(appSettingsService.isImageAnnotationEnabled()).isTrue();

        storedAnnotationValue("");
        assertThat(appSettingsService.isImageAnnotationEnabled()).isTrue();
    }

    // --- NFC scan policy -----------------------------------------------------

    @Test
    void nfcStrictSerialMatchIsOnUntilAnAdministratorRelaxesIt() {
        when(appSettingRepository.findById(AppSettingsService.KEY_NFC_STRICT_SERIAL_MATCH))
                .thenReturn(Optional.empty());

        assertThat(appSettingsService.isNfcStrictSerialMatch())
                .isEqualTo(AppSettingsService.DEFAULT_NFC_STRICT_SERIAL_MATCH)
                .isTrue();
    }

    @Test
    void nfcStrictSerialMatchReadsAStoredDecision() {
        AppSetting setting = new AppSetting();
        setting.setSettingKey(AppSettingsService.KEY_NFC_STRICT_SERIAL_MATCH);
        setting.setValue("false");
        when(appSettingRepository.findById(AppSettingsService.KEY_NFC_STRICT_SERIAL_MATCH))
                .thenReturn(Optional.of(setting));

        assertThat(appSettingsService.isNfcStrictSerialMatch()).isFalse();
    }

    @Test
    void anUnreadableScanPolicyRowStaysStrict() {
        // The failure that matters: a garbled row must not quietly downgrade the one rule that
        // makes a scan mean "I stood in front of this equipment".
        AppSetting setting = new AppSetting();
        setting.setSettingKey(AppSettingsService.KEY_NFC_STRICT_SERIAL_MATCH);
        setting.setValue("¯\\_(ツ)_/¯");
        when(appSettingRepository.findById(AppSettingsService.KEY_NFC_STRICT_SERIAL_MATCH))
                .thenReturn(Optional.of(setting));

        assertThat(appSettingsService.isNfcStrictSerialMatch()).isTrue();
    }

    @Test
    void saveNfcStrictSerialMatchPersists() {
        when(appSettingRepository.findById(AppSettingsService.KEY_NFC_STRICT_SERIAL_MATCH))
                .thenReturn(Optional.empty());

        appSettingsService.saveNfcStrictSerialMatch(false);

        ArgumentCaptor<AppSetting> captor = ArgumentCaptor.forClass(AppSetting.class);
        verify(appSettingRepository).save(captor.capture());
        assertThat(captor.getValue().getSettingKey())
                .isEqualTo(AppSettingsService.KEY_NFC_STRICT_SERIAL_MATCH);
        assertThat(captor.getValue().getValue()).isEqualTo("false");
    }

    @Test
    void theTwoSwitchesUseDifferentKeys() {
        // They are saved from the same form; one key for both would make either switch move the
        // other, which is the kind of thing nobody notices until a scan rule changes by itself.
        assertThat(AppSettingsService.KEY_NFC_STRICT_SERIAL_MATCH)
                .isNotEqualTo(AppSettingsService.KEY_IMAGE_ANNOTATION_ENABLED);
    }

    @Test
    void saveImageAnnotationEnabledPersistsBothAnswers() {
        when(appSettingRepository.findById(AppSettingsService.KEY_IMAGE_ANNOTATION_ENABLED))
                .thenReturn(Optional.empty());

        appSettingsService.saveImageAnnotationEnabled(false);

        ArgumentCaptor<AppSetting> captor = ArgumentCaptor.forClass(AppSetting.class);
        verify(appSettingRepository).save(captor.capture());
        assertThat(captor.getValue().getSettingKey())
                .isEqualTo(AppSettingsService.KEY_IMAGE_ANNOTATION_ENABLED);
        assertThat(captor.getValue().getValue()).isEqualTo("false");
    }
}
