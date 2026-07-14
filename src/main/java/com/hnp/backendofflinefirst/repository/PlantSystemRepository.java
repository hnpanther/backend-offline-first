package com.hnp.backendofflinefirst.repository;

import com.hnp.backendofflinefirst.entity.PlantSystem;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PlantSystemRepository extends JpaRepository<PlantSystem, Long> {

    @Query("""
            SELECT p FROM PlantSystem p
            WHERE LOWER(p.code) LIKE LOWER(CONCAT('%', :q, '%'))
                OR LOWER(p.name) LIKE LOWER(CONCAT('%', :q, '%'))
            """)
    Page<PlantSystem> search(@Param("q") String q, Pageable pageable);
    List<PlantSystem> findByUpdatedAtGreaterThanEqual(Long since);
    Optional<PlantSystem> findByCode(String code);
    Optional<PlantSystem> findByName(String name);
    List<PlantSystem> findAllByOrderByIdDesc();
    List<PlantSystem> findByParentId(Long parentId);
}
