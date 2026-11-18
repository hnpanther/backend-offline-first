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

    // --- How long a round stays on the tablet (roadmap §41) ------------------------------------
    // The tablet's local purge reads these. The cases that matter are the ones where the stored
    // row is wrong: a value nobody can read must never be the thing that tells every tablet to
    // delete readings that have not reached the server yet.

    private void stored(String key, String value) {
        AppSetting setting = new AppSetting();
        setting.setSettingKey(key);
        setting.setValue(value);
        when(appSettingRepository.findById(key)).thenReturn(Optional.of(setting));
    }

    private void absent(String key) {
        when(appSettingRepository.findById(key)).thenReturn(Optional.empty());
    }

    @Test
    void deviceRetentionFallsBackToTheDecidedDefaultsWithNoRows() {
        absent(AppSettingsService.KEY_DEVICE_SYNCED_RETENTION_HOURS);
        absent(AppSettingsService.KEY_DEVICE_UNSYNCED_PURGE_ENABLED);
        absent(AppSettingsService.KEY_DEVICE_UNSYNCED_RETENTION_HOURS);

        AppSettingsService.DeviceRetention retention = appSettingsService.getDeviceRetention();

        assertThat(retention.syncedRetentionHours()).isEqualTo(24);
        assertThat(retention.unsyncedPurgeEnabled()).isFalse();
        assertThat(retention.unsyncedRetentionHours()).isEqualTo(168);
    }

    @Test
    void deviceRetentionReadsStoredValues() {
        stored(AppSettingsService.KEY_DEVICE_SYNCED_RETENTION_HOURS, "48");
        stored(AppSettingsService.KEY_DEVICE_UNSYNCED_PURGE_ENABLED, "true");
        stored(AppSettingsService.KEY_DEVICE_UNSYNCED_RETENTION_HOURS, "720");

        AppSettingsService.DeviceRetention retention = appSettingsService.getDeviceRetention();

        assertThat(retention.syncedRetentionHours()).isEqualTo(48);
        assertThat(retention.unsyncedPurgeEnabled()).isTrue();
        assertThat(retention.unsyncedRetentionHours()).isEqualTo(720);
    }

    @Test
    void anUnreadablePurgeSwitchKeepsUndeliveredRoundsRatherThanPurgingThem() {
        // Boolean.parseBoolean would answer false here too, but by accident; this pins it on
        // purpose, together with the spellings a DBA might use by hand.
        stored(AppSettingsService.KEY_DEVICE_SYNCED_RETENTION_HOURS, "24");
        stored(AppSettingsService.KEY_DEVICE_UNSYNCED_RETENTION_HOURS, "168");

        stored(AppSettingsService.KEY_DEVICE_UNSYNCED_PURGE_ENABLED, "perhaps");
        assertThat(appSettingsService.getDeviceRetention().unsyncedPurgeEnabled()).isFalse();

        stored(AppSettingsService.KEY_DEVICE_UNSYNCED_PURGE_ENABLED, "");
        assertThat(appSettingsService.getDeviceRetention().unsyncedPurgeEnabled()).isFalse();

        stored(AppSettingsService.KEY_DEVICE_UNSYNCED_PURGE_ENABLED, "1");
        assertThat(appSettingsService.getDeviceRetention().unsyncedPurgeEnabled()).isTrue();
    }

    @Test
    void anUnreadableOrNonPositiveDurationFallsBackToTheDefaultNotToZero() {
        // Zero hours would read on a tablet as "purge now" — exactly what the design forbids.
        stored(AppSettingsService.KEY_DEVICE_UNSYNCED_PURGE_ENABLED, "false");

        stored(AppSettingsService.KEY_DEVICE_SYNCED_RETENTION_HOURS, "0");
        stored(AppSettingsService.KEY_DEVICE_UNSYNCED_RETENTION_HOURS, "-5");
        AppSettingsService.DeviceRetention retention = appSettingsService.getDeviceRetention();
        assertThat(retention.syncedRetentionHours()).isEqualTo(24);
        assertThat(retention.unsyncedRetentionHours()).isEqualTo(168);

        stored(AppSettingsService.KEY_DEVICE_SYNCED_RETENTION_HOURS, "a day");
        stored(AppSettingsService.KEY_DEVICE_UNSYNCED_RETENTION_HOURS, "");
        retention = appSettingsService.getDeviceRetention();
        assertThat(retention.syncedRetentionHours()).isEqualTo(24);
        assertThat(retention.unsyncedRetentionHours()).isEqualTo(168);
    }

    @Test
    void anOutOfRangeStoredDurationIsClampedSoThePanelShowsWhatTabletsRunUnder() {
        stored(AppSettingsService.KEY_DEVICE_UNSYNCED_PURGE_ENABLED, "true");

        stored(AppSettingsService.KEY_DEVICE_SYNCED_RETENTION_HOURS, "100000");
        stored(AppSettingsService.KEY_DEVICE_UNSYNCED_RETENTION_HOURS, "1");
        AppSettingsService.DeviceRetention retention = appSettingsService.getDeviceRetention();
        assertThat(retention.syncedRetentionHours()).isEqualTo(720);
        // Below the floor is raised to it, never lowered: 1 hour for an undelivered round is
        // what the 72-hour minimum exists to prevent.
        assertThat(retention.unsyncedRetentionHours()).isEqualTo(72);

        stored(AppSettingsService.KEY_DEVICE_UNSYNCED_RETENTION_HOURS, "999999");
        assertThat(appSettingsService.getDeviceRetention().unsyncedRetentionHours()).isEqualTo(8760);
    }

    @Test
    void saveDeviceRetentionHoursRefusesEveryValueOutsideTheBounds() {
        assertThatThrownBy(() -> appSettingsService.saveDeviceRetentionHours(0, 168))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> appSettingsService.saveDeviceRetentionHours(721, 168))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> appSettingsService.saveDeviceRetentionHours(24, 71))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> appSettingsService.saveDeviceRetentionHours(24, 8761))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> appSettingsService.saveDeviceRetentionHours(-1, null))
                .isInstanceOf(IllegalArgumentException.class);
        // Nothing reached the repository: both are checked before either is written.
        org.mockito.Mockito.verifyNoInteractions(appSettingRepository);
    }

    @Test
    void aValidSyncedValueIsNotWrittenWhenTheOtherOneIsRefused() {
        assertThatThrownBy(() -> appSettingsService.saveDeviceRetentionHours(48, 10))
                .isInstanceOf(IllegalArgumentException.class);
        org.mockito.Mockito.verifyNoInteractions(appSettingRepository);
    }

    @Test
    void saveDeviceRetentionHoursAcceptsTheExactBounds() {
        absent(AppSettingsService.KEY_DEVICE_SYNCED_RETENTION_HOURS);
        absent(AppSettingsService.KEY_DEVICE_UNSYNCED_RETENTION_HOURS);

        appSettingsService.saveDeviceRetentionHours(1, 72);
        appSettingsService.saveDeviceRetentionHours(720, 8760);

        ArgumentCaptor<AppSetting> captor = ArgumentCaptor.forClass(AppSetting.class);
        org.mockito.Mockito.verify(appSettingRepository, org.mockito.Mockito.times(4)).save(captor.capture());
        assertThat(captor.getAllValues()).extracting(AppSetting::getValue)
                .containsExactly("1", "72", "720", "8760");
    }

    @Test
    void aNullDurationLeavesThatSettingAlone() {
        absent(AppSettingsService.KEY_DEVICE_SYNCED_RETENTION_HOURS);

        appSettingsService.saveDeviceRetentionHours(36, null);

        ArgumentCaptor<AppSetting> captor = ArgumentCaptor.forClass(AppSetting.class);
        verify(appSettingRepository).save(captor.capture());
        assertThat(captor.getValue().getSettingKey())
                .isEqualTo(AppSettingsService.KEY_DEVICE_SYNCED_RETENTION_HOURS);
        assertThat(captor.getValue().getValue()).isEqualTo("36");
    }

    @Test
    void theThreeRetentionKeysAreDistinctFromEachOtherAndFromEveryOtherSetting() {
        assertThat(java.util.Set.of(
                AppSettingsService.KEY_DEVICE_SYNCED_RETENTION_HOURS,
                AppSettingsService.KEY_DEVICE_UNSYNCED_PURGE_ENABLED,
                AppSettingsService.KEY_DEVICE_UNSYNCED_RETENTION_HOURS,
                AppSettingsService.KEY_DEVICE_ENROLLMENT_ENABLED,
                AppSettingsService.KEY_AUDIT_RETENTION_DAYS)).hasSize(5);
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

    // --- The offline-session ceiling (V28) ----------------------------------------------------

    @Test
    void theOfflineSessionCeilingIsOffWithSeventyTwoHoursWhenNothingIsStored() {
        absent(AppSettingsService.KEY_DEVICE_OFFLINE_SESSION_LIMIT_ENABLED);
        absent(AppSettingsService.KEY_DEVICE_OFFLINE_SESSION_LIMIT_HOURS);

        AppSettingsService.OfflineSessionLimit limit = appSettingsService.getOfflineSessionLimit();

        assertThat(limit.enabled()).isFalse();
        assertThat(limit.hours()).isEqualTo(72);
    }

    @Test
    void theOfflineSessionCeilingReadsStoredValuesAndAnUnreadableSwitchIsOff() {
        stored(AppSettingsService.KEY_DEVICE_OFFLINE_SESSION_LIMIT_ENABLED, "true");
        stored(AppSettingsService.KEY_DEVICE_OFFLINE_SESSION_LIMIT_HOURS, "24");
        assertThat(appSettingsService.getOfflineSessionLimit())
                .isEqualTo(new AppSettingsService.OfflineSessionLimit(true, 24));

        stored(AppSettingsService.KEY_DEVICE_OFFLINE_SESSION_LIMIT_ENABLED, "perhaps");
        assertThat(appSettingsService.getOfflineSessionLimit().enabled()).isFalse();
    }

    @Test
    void aStoredCeilingOutsideTheBoundsIsClampedAndNonsenseFallsBackToTheDefault() {
        stored(AppSettingsService.KEY_DEVICE_OFFLINE_SESSION_LIMIT_ENABLED, "true");

        // Below a shift is raised to a shift: one hour would lock an operator out mid-round.
        stored(AppSettingsService.KEY_DEVICE_OFFLINE_SESSION_LIMIT_HOURS, "1");
        assertThat(appSettingsService.getOfflineSessionLimit().hours()).isEqualTo(8);

        stored(AppSettingsService.KEY_DEVICE_OFFLINE_SESSION_LIMIT_HOURS, "100000");
        assertThat(appSettingsService.getOfflineSessionLimit().hours()).isEqualTo(720);

        stored(AppSettingsService.KEY_DEVICE_OFFLINE_SESSION_LIMIT_HOURS, "0");
        assertThat(appSettingsService.getOfflineSessionLimit().hours()).isEqualTo(72);

        stored(AppSettingsService.KEY_DEVICE_OFFLINE_SESSION_LIMIT_HOURS, "three days");
        assertThat(appSettingsService.getOfflineSessionLimit().hours()).isEqualTo(72);
    }

    @Test
    void anOutOfRangeCeilingIsRefusedBeforeAnythingIsWritten() {
        assertThatThrownBy(() -> appSettingsService.saveOfflineSessionLimitHours(7))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> appSettingsService.saveOfflineSessionLimitHours(721))
                .isInstanceOf(IllegalArgumentException.class);
        // Absent means «not asked»: nothing to validate, nothing written.
        AppSettingsService.validateOfflineSessionLimitHours(null);
        appSettingsService.saveOfflineSessionLimitHours(null);
        org.mockito.Mockito.verifyNoInteractions(appSettingRepository);
    }
}
