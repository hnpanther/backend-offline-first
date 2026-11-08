package com.hnp.backendofflinefirst.repository;

import com.hnp.backendofflinefirst.entity.UnitOperator;
import com.hnp.backendofflinefirst.entity.UnitUserId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface UnitOperatorRepository extends JpaRepository<UnitOperator, UnitUserId> {
    List<UnitOperator> findByUnitId(Long unitId);

    /** Every assignment for a page of units in one query — see {@code OperationalUnitService.operatorIdsByUnit}. */
    List<UnitOperator> findByUnitIdIn(Collection<Long> unitIds);
    List<UnitOperator> findByUserId(Long userId);

    /** Every assigned unit for a whole page of users, in one query rather than per row. */
    List<UnitOperator> findByUserIdIn(Collection<Long> userIds);
    void deleteByUnitId(Long unitId);
    boolean existsByUserId(Long userId);
}
