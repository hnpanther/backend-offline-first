package com.hnp.backendofflinefirst.repository;

import com.hnp.backendofflinefirst.entity.UnitSupervisor;
import com.hnp.backendofflinefirst.entity.UnitUserId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface UnitSupervisorRepository extends JpaRepository<UnitSupervisor, UnitUserId> {
    List<UnitSupervisor> findByUnitId(Long unitId);

    /** Every assignment for a page of units in one query — see {@code OperationalUnitService.supervisorIdsByUnit}. */
    List<UnitSupervisor> findByUnitIdIn(Collection<Long> unitIds);
    List<UnitSupervisor> findByUserId(Long userId);
    void deleteByUnitId(Long unitId);
    boolean existsByUserId(Long userId);
}
