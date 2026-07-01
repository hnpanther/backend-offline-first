package com.hnp.backendofflinefirst.repository;

import com.hnp.backendofflinefirst.entity.Location;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface LocationRepository extends JpaRepository<Location, String> {
    List<Location> findByUpdatedAtGreaterThanEqual(Long since);
    Optional<Location> findByCode(String code);
    Optional<Location> findByName(String name);
    boolean existsByUnitId(String unitId);
}
