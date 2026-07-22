package com.hnp.backendofflinefirst.service;

import com.hnp.backendofflinefirst.entity.OperationalUnit;
import com.hnp.backendofflinefirst.repository.LocationRepository;
import com.hnp.backendofflinefirst.repository.LogSheetRepository;
import com.hnp.backendofflinefirst.repository.LogSheetTemplateRepository;
import com.hnp.backendofflinefirst.repository.OperationalUnitRepository;
import com.hnp.backendofflinefirst.repository.UnitOperatorRepository;
import com.hnp.backendofflinefirst.repository.UnitSupervisorRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OperationalUnitServiceTest {

    @Mock OperationalUnitRepository operationalUnitRepository;
    @Mock UnitSupervisorRepository unitSupervisorRepository;
    @Mock UnitOperatorRepository unitOperatorRepository;
    @Mock LocationRepository locationRepository;
    @Mock LogSheetTemplateRepository logSheetTemplateRepository;
    @Mock LogSheetRepository logSheetRepository;

    @InjectMocks OperationalUnitService service;

    @Test
    void createRejectsBlankCode() {
        OperationalUnit unit = new OperationalUnit();
        unit.setCode("  ");
        unit.setName("Unit A");

        assertThatThrownBy(() -> service.create(unit, List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Operational unit code is required.");
        verify(operationalUnitRepository, never()).save(any());
    }

    @Test
    void createRejectsCaseInsensitiveDuplicate() {
        OperationalUnit existing = new OperationalUnit();
        existing.setId(2L);
        existing.setCode("UNIT1");
        when(operationalUnitRepository.findByCodeIgnoreCase("unit1")).thenReturn(Optional.of(existing));

        OperationalUnit unit = new OperationalUnit();
        unit.setCode("unit1");
        unit.setName("Unit A");

        assertThatThrownBy(() -> service.create(unit, List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate operational unit code");
    }

    @Test
    void createTrimsAndSavesUniqueCode() {
        when(operationalUnitRepository.findByCodeIgnoreCase("UNIT-01")).thenReturn(Optional.empty());
        when(operationalUnitRepository.save(any(OperationalUnit.class))).thenAnswer(inv -> {
            OperationalUnit saved = inv.getArgument(0);
            saved.setId(10L);
            return saved;
        });

        OperationalUnit unit = new OperationalUnit();
        unit.setCode("  UNIT-01  ");
        unit.setName("Unit A");

        OperationalUnit saved = service.create(unit, List.of(), List.of());

        assertThat(saved.getCode()).isEqualTo("UNIT-01");
        assertThat(saved.getId()).isEqualTo(10L);
    }
}
