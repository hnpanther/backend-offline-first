package com.hnp.backendofflinefirst.repository;

import com.hnp.backendofflinefirst.entity.OperationalUnit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface OperationalUnitRepository extends JpaRepository<OperationalUnit, String> {
    List<OperationalUnit> findByUpdatedAtGreaterThanEqual(Long since);
    Optional<OperationalUnit> findByCode(String code);
    Optional<OperationalUnit> findByName(String name);
    List<OperationalUnit> findByParentId(String parentId);
    boolean existsByParentId(String parentId);
}
