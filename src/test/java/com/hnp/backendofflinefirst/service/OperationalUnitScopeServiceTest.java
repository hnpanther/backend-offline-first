package com.hnp.backendofflinefirst.service;

import com.hnp.backendofflinefirst.entity.OperationalUnit;
import com.hnp.backendofflinefirst.entity.UnitOperator;
import com.hnp.backendofflinefirst.entity.UnitSupervisor;
import com.hnp.backendofflinefirst.repository.OperationalUnitRepository;
import com.hnp.backendofflinefirst.repository.UnitOperatorRepository;
import com.hnp.backendofflinefirst.repository.UnitSupervisorRepository;
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
class OperationalUnitScopeServiceTest {

    @Mock UnitSupervisorRepository unitSupervisorRepository;
    @Mock UnitOperatorRepository unitOperatorRepository;
    @Mock OperationalUnitRepository operationalUnitRepository;

    @InjectMocks OperationalUnitScopeService scopeService;

    @Test
    void getAccessibleUnitIdsIncludesChildUnits() {
        UnitOperator link = new UnitOperator();
        link.setUnitId(1L);
        link.setUserId(100L);
        when(unitSupervisorRepository.findByUserId(100L)).thenReturn(List.of());
        when(unitOperatorRepository.findByUserId(100L)).thenReturn(List.of(link));

        OperationalUnit parent = new OperationalUnit();
        parent.setId(1L);
        OperationalUnit child = new OperationalUnit();
        child.setId(2L);
        child.setParentId(1L);
        when(operationalUnitRepository.findAll()).thenReturn(List.of(parent, child));

        Set<Long> accessible = scopeService.getAccessibleUnitIds(100L);

        assertThat(accessible).containsExactlyInAnyOrder(1L, 2L);
    }

    @Test
    void getAccessibleUnitIdsEmptyWhenUserHasNoUnits() {
        when(unitSupervisorRepository.findByUserId(100L)).thenReturn(List.of());
        when(unitOperatorRepository.findByUserId(100L)).thenReturn(List.of());

        assertThat(scopeService.getAccessibleUnitIds(100L)).isEmpty();
    }

    @Test
    void supervisorScopeExtendsToSubUnitsButNotOperatorRole() {
        UnitSupervisor sup = new UnitSupervisor();
        sup.setUnitId(1L);
        sup.setUserId(200L);
        when(unitSupervisorRepository.findByUserId(200L)).thenReturn(List.of(sup));

        OperationalUnit parent = new OperationalUnit();
        parent.setId(1L);
        OperationalUnit child = new OperationalUnit();
        child.setId(2L);
        child.setParentId(1L);
        when(operationalUnitRepository.findAll()).thenReturn(List.of(parent, child));

        assertThat(scopeService.isSupervisorOf(200L, 1L)).isTrue();
        assertThat(scopeService.isSupervisorOf(200L, 2L)).isTrue();
        assertThat(scopeService.isSupervisorOf(200L, 9L)).isFalse();
    }

    @Test
    void operatorIsNotSupervisor() {
        UnitOperator op = new UnitOperator();
        op.setUnitId(1L);
        op.setUserId(300L);
        when(unitSupervisorRepository.findByUserId(300L)).thenReturn(List.of());
        when(unitOperatorRepository.findByUserId(300L)).thenReturn(List.of(op));
        when(operationalUnitRepository.findAll()).thenReturn(List.of());

        assertThat(scopeService.isOperatorOf(300L, 1L)).isTrue();
        assertThat(scopeService.isSupervisorOf(300L, 1L)).isFalse();
    }
}
