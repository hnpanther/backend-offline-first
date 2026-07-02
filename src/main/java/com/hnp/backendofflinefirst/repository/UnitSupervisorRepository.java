package com.hnp.backendofflinefirst.repository;

import com.hnp.backendofflinefirst.entity.UnitSupervisor;
import com.hnp.backendofflinefirst.entity.UnitUserId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface UnitSupervisorRepository extends JpaRepository<UnitSupervisor, UnitUserId> {
    List<UnitSupervisor> findByUnitId(Long unitId);
    List<UnitSupervisor> findByUserId(Long userId);
    void deleteByUnitId(Long unitId);
    boolean existsByUserId(Long userId);
}
