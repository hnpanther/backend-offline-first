package com.hnp.backendofflinefirst.repository;

import com.hnp.backendofflinefirst.entity.AssetClass;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AssetClassRepository extends JpaRepository<AssetClass, String> {
    List<AssetClass> findByUpdatedAtGreaterThanEqual(Long since);
    Optional<AssetClass> findByName(String name);
}
