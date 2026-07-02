package com.hnp.backendofflinefirst.repository;

import com.hnp.backendofflinefirst.entity.PlantSystem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PlantSystemRepository extends JpaRepository<PlantSystem, Long> {
    List<PlantSystem> findByUpdatedAtGreaterThanEqual(Long since);
    Optional<PlantSystem> findByCode(String code);
    Optional<PlantSystem> findByName(String name);
}
