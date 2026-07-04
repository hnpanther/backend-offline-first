package com.hnp.backendofflinefirst.repository;

import com.hnp.backendofflinefirst.entity.Role;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface RoleRepository extends JpaRepository<Role, Long> {

    @Query("""
            SELECT r FROM Role r
            WHERE LOWER(r.code) LIKE LOWER(CONCAT('%', :q, '%'))
                OR LOWER(COALESCE(r.name, '')) LIKE LOWER(CONCAT('%', :q, '%'))
            """)
    Page<Role> search(@Param("q") String q, Pageable pageable);
    Optional<Role> findByCode(String code);
    List<Role> findAllByOrderByCodeAsc();
    List<Role> findAllByOrderByIdDesc();
    boolean existsByCode(String code);
}
