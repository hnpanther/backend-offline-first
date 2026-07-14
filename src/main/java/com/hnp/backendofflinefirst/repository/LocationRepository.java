package com.hnp.backendofflinefirst.repository;

import com.hnp.backendofflinefirst.entity.Location;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface LocationRepository extends JpaRepository<Location, Long> {

    @Query("""
            SELECT l FROM Location l
            WHERE LOWER(l.code) LIKE LOWER(CONCAT('%', :q, '%'))
                OR LOWER(l.name) LIKE LOWER(CONCAT('%', :q, '%'))
            """)
    Page<Location> search(@Param("q") String q, Pageable pageable);
    List<Location> findByUpdatedAtGreaterThanEqual(Long since);
    Optional<Location> findByCode(String code);
    Optional<Location> findByName(String name);
    boolean existsByUnitId(Long unitId);
    boolean existsByParentId(Long parentId);
    List<Location> findAllByOrderByIdDesc();
}
