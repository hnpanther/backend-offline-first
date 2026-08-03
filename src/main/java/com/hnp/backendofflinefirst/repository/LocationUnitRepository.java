package com.hnp.backendofflinefirst.repository;

import com.hnp.backendofflinefirst.entity.LocationUnit;
import com.hnp.backendofflinefirst.entity.LocationUnitId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface LocationUnitRepository extends JpaRepository<LocationUnit, LocationUnitId> {

    List<LocationUnit> findByLocationId(Long locationId);

    List<LocationUnit> findByUnitId(Long unitId);

    List<LocationUnit> findByLocationIdIn(Collection<Long> locationIds);

    void deleteByLocationId(Long locationId);

    /** Delete-guard: a unit that still owns locations must not be removed. */
    boolean existsByUnitId(Long unitId);

    @Query("SELECT DISTINCT lu.locationId FROM LocationUnit lu WHERE lu.unitId IN :unitIds")
    List<Long> findLocationIdsByUnitIdIn(@Param("unitIds") Collection<Long> unitIds);
}
