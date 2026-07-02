package com.hnp.backendofflinefirst.service;

import com.hnp.backendofflinefirst.entity.OperationalUnit;
import com.hnp.backendofflinefirst.entity.UnitOperator;
import com.hnp.backendofflinefirst.entity.UnitSupervisor;
import com.hnp.backendofflinefirst.repository.LocationRepository;
import com.hnp.backendofflinefirst.repository.OperationalUnitRepository;
import com.hnp.backendofflinefirst.repository.UnitOperatorRepository;
import com.hnp.backendofflinefirst.repository.UnitSupervisorRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class OperationalUnitService {

    private final OperationalUnitRepository operationalUnitRepository;
    private final UnitSupervisorRepository unitSupervisorRepository;
    private final UnitOperatorRepository unitOperatorRepository;
    private final LocationRepository locationRepository;

    public List<OperationalUnit> findAll() {
        return operationalUnitRepository.findAll();
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
                .orElseThrow(() -> new IllegalArgumentException("واحد عملیاتی یافت نشد."));
        if (id.equals(form.getParentId())) {
            throw new IllegalArgumentException("واحد نمی‌تواند والد خودش باشد.");
        }
        unit.setCode(form.getCode());
        unit.setName(form.getName());
        unit.setParentId(form.getParentId());
        unit.setUpdatedAt(System.currentTimeMillis());
        operationalUnitRepository.save(unit);
        saveAssignments(id, supervisorIds, operatorIds);
    }

    @Transactional
    public void delete(Long id) {
        if (operationalUnitRepository.existsByParentId(id)) {
            throw new IllegalStateException("این واحد دارای زیرمجموعه است و قابل حذف نیست.");
        }
        if (locationRepository.existsByUnitId(id)) {
            throw new IllegalStateException("این واحد دارای مکان است و قابل حذف نیست.");
        }
        unitSupervisorRepository.deleteByUnitId(id);
        unitOperatorRepository.deleteByUnitId(id);
        operationalUnitRepository.deleteById(id);
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
