package com.hnp.backendofflinefirst.service;

import com.hnp.backendofflinefirst.entity.OperationalUnit;
import com.hnp.backendofflinefirst.repository.OperationalUnitRepository;
import com.hnp.backendofflinefirst.repository.UnitOperatorRepository;
import com.hnp.backendofflinefirst.repository.UnitSupervisorRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class OperationalUnitScopeService {

    private final UnitSupervisorRepository unitSupervisorRepository;
    private final UnitOperatorRepository unitOperatorRepository;
    private final OperationalUnitRepository operationalUnitRepository;

    public Set<String> getAssignedUnitIds(String userId) {
        Set<String> ids = new HashSet<>();
        unitSupervisorRepository.findByUserId(userId).forEach(l -> ids.add(l.getUnitId()));
        unitOperatorRepository.findByUserId(userId).forEach(l -> ids.add(l.getUnitId()));
        return ids;
    }

    public Set<String> getAccessibleUnitIds(String userId) {
        Set<String> assigned = getAssignedUnitIds(userId);
        if (assigned.isEmpty()) return Set.of();

        Set<String> accessible = new HashSet<>(assigned);
        List<OperationalUnit> allUnits = operationalUnitRepository.findAll();
        boolean changed = true;
        while (changed) {
            changed = false;
            for (OperationalUnit unit : allUnits) {
                if (unit.getParentId() != null && accessible.contains(unit.getParentId()) && accessible.add(unit.getId())) {
                    changed = true;
                }
            }
        }
        return accessible;
    }

    public String getPrimaryUnitId(String userId) {
        List<String> supervisor = unitSupervisorRepository.findByUserId(userId).stream()
                .map(l -> l.getUnitId()).toList();
        if (!supervisor.isEmpty()) return supervisor.get(0);
        List<String> operator = unitOperatorRepository.findByUserId(userId).stream()
                .map(l -> l.getUnitId()).toList();
        if (!operator.isEmpty()) return operator.get(0);
        return null;
    }

    public boolean canAccessUnit(String userId, String unitId) {
        if (unitId == null) return false;
        return getAccessibleUnitIds(userId).contains(unitId);
    }
}
