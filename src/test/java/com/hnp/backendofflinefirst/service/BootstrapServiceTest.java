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

    @InjectMocks BootstrapService service;

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
}
