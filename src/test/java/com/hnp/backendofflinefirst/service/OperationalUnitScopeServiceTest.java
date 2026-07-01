package com.hnp.backendofflinefirst.service;

import com.hnp.backendofflinefirst.entity.OperationalUnit;
import com.hnp.backendofflinefirst.entity.UnitOperator;
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
        link.setUnitId("parent");
        link.setUserId("u1");
        when(unitSupervisorRepository.findByUserId("u1")).thenReturn(List.of());
        when(unitOperatorRepository.findByUserId("u1")).thenReturn(List.of(link));

        OperationalUnit parent = new OperationalUnit();
        parent.setId("parent");
        OperationalUnit child = new OperationalUnit();
        child.setId("child");
        child.setParentId("parent");
        when(operationalUnitRepository.findAll()).thenReturn(List.of(parent, child));

        Set<String> accessible = scopeService.getAccessibleUnitIds("u1");

        assertThat(accessible).containsExactlyInAnyOrder("parent", "child");
    }

    @Test
    void getAccessibleUnitIdsEmptyWhenUserHasNoUnits() {
        when(unitSupervisorRepository.findByUserId("u1")).thenReturn(List.of());
        when(unitOperatorRepository.findByUserId("u1")).thenReturn(List.of());

        assertThat(scopeService.getAccessibleUnitIds("u1")).isEmpty();
    }
}
