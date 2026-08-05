package com.hnp.backendofflinefirst.service;

import com.hnp.backendofflinefirst.entity.OperationalUnit;
import com.hnp.backendofflinefirst.entity.UnitOperator;
import com.hnp.backendofflinefirst.entity.UnitSupervisor;
import com.hnp.backendofflinefirst.repository.LocationUnitRepository;
import com.hnp.backendofflinefirst.repository.LogSheetRepository;
import com.hnp.backendofflinefirst.repository.LogSheetTemplateRepository;
import com.hnp.backendofflinefirst.repository.OperationalUnitRepository;
import com.hnp.backendofflinefirst.repository.UnitOperatorRepository;
import com.hnp.backendofflinefirst.repository.UnitSupervisorRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class OperationalUnitService {

    private final OperationalUnitRepository operationalUnitRepository;
    private final UnitSupervisorRepository unitSupervisorRepository;
    private final UnitOperatorRepository unitOperatorRepository;
    private final LocationUnitRepository locationUnitRepository;
    private final LogSheetTemplateRepository logSheetTemplateRepository;
    private final LogSheetRepository logSheetRepository;

    public List<OperationalUnit> findAll() {
        return operationalUnitRepository.findAllByOrderByIdDesc();
    }

    public List<Long> getSupervisorIds(Long unitId) {
        return unitSupervisorRepository.findByUnitId(unitId).stream()
                .map(UnitSupervisor::getUserId)
                .toList();
    }

    public List<Long> getOperatorIds(Long unitId) {
        return unitOperatorRepository.findByUnitId(unitId).stream()
                .map(UnitOperator::getUserId)
                .toList();
    }

    @Transactional
    public OperationalUnit create(OperationalUnit unit, List<Long> supervisorIds, List<Long> operatorIds) {
        String code = requireUniqueCode(null, unit.getCode());
        unit.setCode(code);
        long now = System.currentTimeMillis();
        unit.setCreatedAt(now);
        unit.setUpdatedAt(now);
        OperationalUnit saved = operationalUnitRepository.save(unit);
        saveAssignments(saved.getId(), supervisorIds, operatorIds);
        return saved;
    }

    @Transactional
    public void update(Long id, OperationalUnit form, List<Long> supervisorIds, List<Long> operatorIds) {
        OperationalUnit unit = operationalUnitRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Operational unit not found."));
        if (id.equals(form.getParentId())) {
            throw new IllegalArgumentException("Unit cannot be its own parent.");
        }
        requireNoParentCycle(id, form.getParentId());
        unit.setCode(requireUniqueCode(id, form.getCode()));
        unit.setName(form.getName());
        unit.setParentId(form.getParentId());
        unit.setUpdatedAt(System.currentTimeMillis());
        operationalUnitRepository.save(unit);
        saveAssignments(id, supervisorIds, operatorIds);
    }

    /**
     * Rejects a parent that is already a descendant of this unit (A → B → A).
     *
     * <p>Blocking only the direct self-parent is not enough: the unit tree drives access control
     * — a supervisor's authority expands downward through it — so a cycle would make two units
     * each other's descendant and hand every supervisor on the loop the other's scope. It would
     * also make any ancestor walk non-terminating.
     */
    private void requireNoParentCycle(Long id, Long proposedParentId) {
        Long cursor = proposedParentId;
        // Bounded by the number of units: a pre-existing loop can never spin forever here.
        int guard = 0, maxHops = (int) operationalUnitRepository.count() + 1;
        while (cursor != null && guard++ <= maxHops) {
            if (cursor.equals(id)) {
                throw new IllegalArgumentException("Unit parent chain would create a cycle");
            }
            cursor = operationalUnitRepository.findById(cursor)
                    .map(OperationalUnit::getParentId)
                    .orElse(null);
        }
    }

    @Transactional
    public void delete(Long id) {
        if (operationalUnitRepository.existsByParentId(id)) {
            throw new IllegalStateException("This unit has child units and cannot be deleted.");
        }
        if (locationUnitRepository.existsByUnitId(id)) {
            throw new IllegalStateException("This unit has locations and cannot be deleted.");
        }
        if (logSheetTemplateRepository.existsByOperationalUnitId(id)) {
            throw new IllegalStateException("This unit has log sheet templates and cannot be deleted.");
        }
        if (logSheetRepository.existsByOperationalUnitId(id)) {
            throw new IllegalStateException("This unit has log sheets and cannot be deleted.");
        }
        unitSupervisorRepository.deleteByUnitId(id);
        unitOperatorRepository.deleteByUnitId(id);
        operationalUnitRepository.deleteById(id);
    }

    private String requireUniqueCode(Long id, String code) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("Operational unit code is required.");
        }
        String trimmed = code.trim();
        operationalUnitRepository.findByCodeIgnoreCase(trimmed).ifPresent(existing -> {
            if (!Objects.equals(id, existing.getId())) {
                throw new IllegalArgumentException("Duplicate operational unit code: " + trimmed);
            }
        });
        return trimmed;
    }

    private void saveAssignments(Long unitId, List<Long> supervisorIds, List<Long> operatorIds) {
        unitSupervisorRepository.deleteByUnitId(unitId);
        unitOperatorRepository.deleteByUnitId(unitId);

        if (supervisorIds != null) {
            for (Long userId : supervisorIds) {
                if (userId == null) continue;
                UnitSupervisor link = new UnitSupervisor();
                link.setUnitId(unitId);
                link.setUserId(userId);
                unitSupervisorRepository.save(link);
            }
        }
        if (operatorIds != null) {
            for (Long userId : operatorIds) {
                if (userId == null) continue;
                UnitOperator link = new UnitOperator();
                link.setUnitId(unitId);
                link.setUserId(userId);
                unitOperatorRepository.save(link);
            }
        }
    }

    public List<String> formatUserNames(List<Long> userIds, Map<Long, String> userNameById) {
        if (userIds == null || userIds.isEmpty()) return List.of();
        List<String> names = new ArrayList<>();
        for (Long userId : userIds) {
            names.add(userNameById.getOrDefault(userId, String.valueOf(userId)));
        }
        return names;
    }
}
