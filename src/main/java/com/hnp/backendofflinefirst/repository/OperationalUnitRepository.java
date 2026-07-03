package com.hnp.backendofflinefirst.repository;

import com.hnp.backendofflinefirst.entity.OperationalUnit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface OperationalUnitRepository extends JpaRepository<OperationalUnit, Long> {
    List<OperationalUnit> findByUpdatedAtGreaterThanEqual(Long since);
    Optional<OperationalUnit> findByCode(String code);
    Optional<OperationalUnit> findByName(String name);
    List<OperationalUnit> findByParentId(Long parentId);
    boolean existsByParentId(Long parentId);
    List<OperationalUnit> findAllByOrderByIdDesc();
}
