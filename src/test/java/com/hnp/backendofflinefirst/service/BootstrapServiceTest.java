package com.hnp.backendofflinefirst.service;

import com.hnp.backendofflinefirst.dto.BootstrapResponse;
import com.hnp.backendofflinefirst.entity.OperationalUnit;
import com.hnp.backendofflinefirst.repository.OperationalUnitRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BootstrapServiceTest {

    @Mock OperationalUnitScopeService unitScopeService;
    @Mock OperationalUnitRepository operationalUnitRepository;
    // Bootstrap now also ships the attachment ceilings to the device. Lenient because the
    // cases below are about unit scoping and never assert on the limits.
    @Mock(lenient = true) AppSettingsService appSettingsService;
    // V29: form lookup lease list. Empty for these unit-scope cases.
    @Mock(lenient = true) LookupGrantService lookupGrantService;

    @InjectMocks BootstrapService service;

    @org.junit.jupiter.api.BeforeEach
    void stubLimits() {
        when(appSettingsService.getAttachmentLimits())
                .thenReturn(new AppSettingsService.AttachmentLimits(3, 1, 1, 120, 120));
        when(appSettingsService.getDeviceRetention())
                .thenReturn(new AppSettingsService.DeviceRetention(24, false, 168));
        when(appSettingsService.getOfflineSessionLimit())
                .thenReturn(new AppSettingsService.OfflineSessionLimit(false, 72));
        when(lookupGrantService.requiredListsForFillableForms(org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(List.of());
    }

    /**
     * Roadmap §41. The tablet's purge reads these and nothing else, so a payload that forgot the
     * block would leave every device on the constants it shipped with — which delete an
     * undelivered round after a week, the behaviour the switch exists to turn off.
     */
    @Test
    void bootstrapCarriesTheDeviceRetentionExactlyAsStored() {
        when(unitScopeService.getAccessibleUnitIds(304L)).thenReturn(Set.of());
        when(unitScopeService.getSupervisorScopeUnitIds(304L)).thenReturn(Set.of());
        when(unitScopeService.getPrimaryUnitId(304L)).thenReturn(null);
        when(operationalUnitRepository.findAllById(Set.of())).thenReturn(List.of());

        BootstrapResponse.DeviceRetentionDto defaults =
                service.getBootstrap(304L, true).getDeviceRetention();
        assertThat(defaults).isNotNull();
        assertThat(defaults.getSyncedRetentionHours()).isEqualTo(24);
        assertThat(defaults.isUnsyncedPurgeEnabled()).isFalse();
        assertThat(defaults.getUnsyncedRetentionHours()).isEqualTo(168);

        when(appSettingsService.getDeviceRetention())
                .thenReturn(new AppSettingsService.DeviceRetention(6, true, 96));

        BootstrapResponse.DeviceRetentionDto changed =
                service.getBootstrap(304L, true).getDeviceRetention();
        assertThat(changed.getSyncedRetentionHours()).isEqualTo(6);
        assertThat(changed.isUnsyncedPurgeEnabled()).isTrue();
        assertThat(changed.getUnsyncedRetentionHours()).isEqualTo(96);
    }

    @Test
    void unitScopedUserGetsAccessibleUnitsOnly() {
        OperationalUnit unit = new OperationalUnit();
        unit.setId(10L);
        unit.setCode("U1");
        unit.setName("Unit 1");

        when(unitScopeService.getAccessibleUnitIds(100L)).thenReturn(Set.of(10L));
        when(unitScopeService.getSupervisorScopeUnitIds(100L)).thenReturn(Set.of());
        when(unitScopeService.getPrimaryUnitId(100L)).thenReturn(10L);
        when(operationalUnitRepository.findAllById(Set.of(10L))).thenReturn(List.of(unit));

        BootstrapResponse response = service.getBootstrap(100L, true);

        assertThat(response.getUserId()).isEqualTo(100L);
        assertThat(response.getOperationalUnits()).containsExactly(unit);
        assertThat(response.getAccessibleUnitIds()).containsExactly(10L);
        assertThat(response.getSupervisorScopeUnitIds()).isEmpty();
        assertThat(response.getPrimaryUnitId()).isEqualTo(10L);
        assertThat(response.getServerTime()).isPositive();
    }

    @Test
    void globalUserGetsAllOperationalUnits() {
        OperationalUnit a = new OperationalUnit();
        a.setId(1L);
        OperationalUnit b = new OperationalUnit();
        b.setId(2L);

        when(operationalUnitRepository.findAll()).thenReturn(List.of(b, a));
        when(unitScopeService.getSupervisorScopeUnitIds(1L)).thenReturn(Set.of(1L, 2L));
        when(unitScopeService.getPrimaryUnitId(1L)).thenReturn(null);
        when(operationalUnitRepository.findAllById(Set.of(1L, 2L))).thenReturn(List.of(a, b));

        BootstrapResponse response = service.getBootstrap(1L, false);

        assertThat(response.getAccessibleUnitIds()).containsExactlyInAnyOrder(1L, 2L);
        assertThat(response.getOperationalUnits()).containsExactly(a, b);
        assertThat(response.getSupervisorScopeUnitIds()).containsExactlyInAnyOrder(1L, 2L);
    }

    @Test
    void unitScopedUserWithNoUnitsGetsEmptyLists() {
        when(unitScopeService.getAccessibleUnitIds(200L)).thenReturn(Set.of());
        when(unitScopeService.getSupervisorScopeUnitIds(200L)).thenReturn(Set.of());
        when(unitScopeService.getPrimaryUnitId(200L)).thenReturn(null);
        when(operationalUnitRepository.findAllById(Set.of())).thenReturn(List.of());

        BootstrapResponse response = service.getBootstrap(200L, true);

        assertThat(response.getOperationalUnits()).isEmpty();
        assertThat(response.getAccessibleUnitIds()).isEmpty();
        assertThat(response.getPrimaryUnitId()).isNull();
    }

    /**
     * The device-facing policies. Bootstrap is the only call that carries them, so a payload
     * that forgets one leaves every tablet running on whatever it last stored — which is the
     * failure mode these cases exist to catch.
     */
    @Test
    void bootstrapCarriesTheMobilePolicyToTheDevice() {
        when(unitScopeService.getAccessibleUnitIds(300L)).thenReturn(Set.of());
        when(unitScopeService.getSupervisorScopeUnitIds(300L)).thenReturn(Set.of());
        when(unitScopeService.getPrimaryUnitId(300L)).thenReturn(null);
        when(operationalUnitRepository.findAllById(Set.of())).thenReturn(List.of());
        when(appSettingsService.isImageAnnotationEnabled()).thenReturn(true);
        when(appSettingsService.isNfcStrictSerialMatch()).thenReturn(true);

        BootstrapResponse response = service.getBootstrap(300L, true);

        assertThat(response.getMobilePolicy()).isNotNull();
        assertThat(response.getMobilePolicy().isImageAnnotationEnabled()).isTrue();
        assertThat(response.getMobilePolicy().isNfcStrictSerialMatch()).isTrue();
    }

    @Test
    void bootstrapCarriesTheManualEntryPolicyBothWays() {
        // The device cannot derive this one locally — it is a plant decision — so a payload that
        // forgot it would leave every tablet on whatever it last stored, which for a capability
        // means an administrator's tightening never arriving.
        when(unitScopeService.getAccessibleUnitIds(303L)).thenReturn(Set.of());
        when(unitScopeService.getSupervisorScopeUnitIds(303L)).thenReturn(Set.of());
        when(unitScopeService.getPrimaryUnitId(303L)).thenReturn(null);
        when(operationalUnitRepository.findAllById(Set.of())).thenReturn(List.of());
        when(appSettingsService.isImageAnnotationEnabled()).thenReturn(true);
        when(appSettingsService.isNfcStrictSerialMatch()).thenReturn(true);
        when(appSettingsService.isNfcManualEntryEnabled()).thenReturn(true);

        assertThat(service.getBootstrap(303L, true).getMobilePolicy().isNfcManualEntryEnabled())
                .isTrue();

        when(appSettingsService.isNfcManualEntryEnabled()).thenReturn(false);

        assertThat(service.getBootstrap(303L, true).getMobilePolicy().isNfcManualEntryEnabled())
                .isFalse();
    }

    @Test
    void bootstrapCarriesARelaxedScanRuleToo() {
        // The whole reason it is a setting rather than a property: a site with no serials
        // recorded needs the tablets to hear about the change on their next bootstrap, not
        // after a redeploy.
        when(unitScopeService.getAccessibleUnitIds(302L)).thenReturn(Set.of());
        when(unitScopeService.getSupervisorScopeUnitIds(302L)).thenReturn(Set.of());
        when(unitScopeService.getPrimaryUnitId(302L)).thenReturn(null);
        when(operationalUnitRepository.findAllById(Set.of())).thenReturn(List.of());
        when(appSettingsService.isImageAnnotationEnabled()).thenReturn(true);
        when(appSettingsService.isNfcStrictSerialMatch()).thenReturn(false);

        BootstrapResponse response = service.getBootstrap(302L, true);

        assertThat(response.getMobilePolicy().isNfcStrictSerialMatch()).isFalse();
    }

    @Test
    void bootstrapReportsTheAnnotationSwitchAsAdministratorsLeftIt() {
        when(unitScopeService.getAccessibleUnitIds(301L)).thenReturn(Set.of());
        when(unitScopeService.getSupervisorScopeUnitIds(301L)).thenReturn(Set.of());
        when(unitScopeService.getPrimaryUnitId(301L)).thenReturn(null);
        when(operationalUnitRepository.findAllById(Set.of())).thenReturn(List.of());
        when(appSettingsService.isImageAnnotationEnabled()).thenReturn(false);
        when(appSettingsService.isNfcStrictSerialMatch()).thenReturn(true);

        BootstrapResponse response = service.getBootstrap(301L, true);

        assertThat(response.getMobilePolicy().isImageAnnotationEnabled()).isFalse();
        // The two are independent: switching the annotation step off must not touch the scan rule.
        assertThat(response.getMobilePolicy().isNfcStrictSerialMatch()).isTrue();
    }
}
