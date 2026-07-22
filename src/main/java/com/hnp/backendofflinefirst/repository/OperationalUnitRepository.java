package com.hnp.backendofflinefirst.repository;

import com.hnp.backendofflinefirst.entity.OperationalUnit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface OperationalUnitRepository extends JpaRepository<OperationalUnit, Long> {

    @Query("""
            SELECT u FROM OperationalUnit u
            WHERE LOWER(COALESCE(u.code, '')) LIKE LOWER(CONCAT('%', :q, '%'))
                OR LOWER(COALESCE(u.name, '')) LIKE LOWER(CONCAT('%', :q, '%'))
            """)
    Page<OperationalUnit> search(@Param("q") String q, Pageable pageable);
    List<OperationalUnit> findByUpdatedAtGreaterThanEqual(Long since);
    Optional<OperationalUnit> findByCode(String code);
    Optional<OperationalUnit> findByCodeIgnoreCase(String code);
    Optional<OperationalUnit> findByName(String name);
    List<OperationalUnit> findByParentId(Long parentId);
    boolean existsByParentId(Long parentId);
    List<OperationalUnit> findAllByOrderByIdDesc();
}
