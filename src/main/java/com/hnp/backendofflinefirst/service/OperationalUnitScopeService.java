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

    public Set<Long> getAssignedUnitIds(Long userId) {
        Set<Long> ids = new HashSet<>();
        unitSupervisorRepository.findByUserId(userId).forEach(l -> ids.add(l.getUnitId()));
        unitOperatorRepository.findByUserId(userId).forEach(l -> ids.add(l.getUnitId()));
        return ids;
    }

    /** Units where the user is a supervisor (directly assigned, no expansion). */
    public Set<Long> getSupervisedUnitIds(Long userId) {
        Set<Long> ids = new HashSet<>();
        unitSupervisorRepository.findByUserId(userId).forEach(l -> ids.add(l.getUnitId()));
        return ids;
    }

    /** Units where the user is an operator (directly assigned, no expansion). */
    public Set<Long> getOperatedUnitIds(Long userId) {
        Set<Long> ids = new HashSet<>();
        unitOperatorRepository.findByUserId(userId).forEach(l -> ids.add(l.getUnitId()));
        return ids;
    }

    public Set<Long> getAccessibleUnitIds(Long userId) {
        return expandDownward(getAssignedUnitIds(userId));
    }

    /** Supervisor authority extends to the supervised units and all their sub-units. */
    public Set<Long> getSupervisorScopeUnitIds(Long userId) {
        return expandDownward(getSupervisedUnitIds(userId));
    }

    /** Operator authority extends to the operated units and all their sub-units. */
    public Set<Long> getOperatorScopeUnitIds(Long userId) {
        return expandDownward(getOperatedUnitIds(userId));
    }

    /** Expands a set of seed units to include every descendant unit (downward closure). */
    private Set<Long> expandDownward(Set<Long> seeds) {
        if (seeds.isEmpty()) return Set.of();
        Set<Long> accessible = new HashSet<>(seeds);
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

    public boolean isSupervisorOf(Long userId, Long unitId) {
        if (userId == null || unitId == null) return false;
        return getSupervisorScopeUnitIds(userId).contains(unitId);
    }

    public boolean isOperatorOf(Long userId, Long unitId) {
        if (userId == null || unitId == null) return false;
        return getOperatorScopeUnitIds(userId).contains(unitId);
    }

    public Long getPrimaryUnitId(Long userId) {
        List<Long> supervisor = unitSupervisorRepository.findByUserId(userId).stream()
                .map(l -> l.getUnitId()).toList();
        if (!supervisor.isEmpty()) return supervisor.get(0);
        List<Long> operator = unitOperatorRepository.findByUserId(userId).stream()
                .map(l -> l.getUnitId()).toList();
        if (!operator.isEmpty()) return operator.get(0);
        return null;
    }

    public boolean canAccessUnit(Long userId, Long unitId) {
        if (unitId == null) return false;
        return getAccessibleUnitIds(userId).contains(unitId);
    }
}
