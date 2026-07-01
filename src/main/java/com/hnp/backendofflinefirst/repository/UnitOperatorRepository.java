package com.hnp.backendofflinefirst.repository;

import com.hnp.backendofflinefirst.entity.UnitOperator;
import com.hnp.backendofflinefirst.entity.UnitUserId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface UnitOperatorRepository extends JpaRepository<UnitOperator, UnitUserId> {
    List<UnitOperator> findByUnitId(String unitId);
    List<UnitOperator> findByUserId(String userId);
    void deleteByUnitId(String unitId);
    boolean existsByUserId(String userId);
}
