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

    private static OperationalUnit unit(long id, Long parentId) {
        OperationalUnit u = new OperationalUnit();
        u.setId(id);
        u.setCode("U" + id);
        u.setName("Unit " + id);
        u.setParentId(parentId);
        return u;
    }

    @Test
    void updateRejectsMakingAUnitItsOwnParent() {
        OperationalUnit existing = unit(1L, null);
        when(operationalUnitRepository.findById(1L)).thenReturn(Optional.of(existing));

        OperationalUnit form = unit(1L, 1L);
        assertThatThrownBy(() -> service.update(1L, form, List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unit cannot be its own parent.");
        verify(operationalUnitRepository, never()).save(any(OperationalUnit.class));
    }

    @Test
    void updateRejectsAParentThatIsAlreadyADescendant() {
        // A → B. Making A a child of B would close a loop, and the unit tree drives access
        // control (a supervisor's authority expands downward through it).
        OperationalUnit a = unit(1L, null);
        OperationalUnit b = unit(2L, 1L);
        when(operationalUnitRepository.findById(1L)).thenReturn(Optional.of(a));
        when(operationalUnitRepository.findById(2L)).thenReturn(Optional.of(b));
        when(operationalUnitRepository.count()).thenReturn(2L);

        OperationalUnit form = unit(1L, 2L);
        assertThatThrownBy(() -> service.update(1L, form, List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unit parent chain would create a cycle");
        verify(operationalUnitRepository, never()).save(any(OperationalUnit.class));
    }

    @Test
    void updateRejectsAParentThatIsADeeperDescendant() {
        // A → B → C: making A a child of C is a three-hop loop.
        when(operationalUnitRepository.findById(1L)).thenReturn(Optional.of(unit(1L, null)));
        when(operationalUnitRepository.findById(2L)).thenReturn(Optional.of(unit(2L, 1L)));
        when(operationalUnitRepository.findById(3L)).thenReturn(Optional.of(unit(3L, 2L)));
        when(operationalUnitRepository.count()).thenReturn(3L);

        OperationalUnit form = unit(1L, 3L);
        assertThatThrownBy(() -> service.update(1L, form, List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unit parent chain would create a cycle");
    }

    @Test
    void updateAcceptsALegitimateParentElsewhereInTheTree() {
        // A → B, and an unrelated Z. Moving B under Z is fine.
        when(operationalUnitRepository.findById(2L)).thenReturn(Optional.of(unit(2L, 1L)));
        when(operationalUnitRepository.findById(9L)).thenReturn(Optional.of(unit(9L, null)));
        when(operationalUnitRepository.count()).thenReturn(3L);
        when(operationalUnitRepository.findByCodeIgnoreCase("U2")).thenReturn(Optional.empty());

        OperationalUnit form = unit(2L, 9L);
        service.update(2L, form, List.of(), List.of());

        verify(operationalUnitRepository).save(any(OperationalUnit.class));
    }
}
