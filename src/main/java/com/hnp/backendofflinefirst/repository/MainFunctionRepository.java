package com.hnp.backendofflinefirst.repository;

import com.hnp.backendofflinefirst.entity.MainFunction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface MainFunctionRepository extends JpaRepository<MainFunction, Long> {

    @Query("""
            SELECT m FROM MainFunction m
            WHERE LOWER(m.code) LIKE LOWER(CONCAT('%', :q, '%'))
                OR LOWER(m.name) LIKE LOWER(CONCAT('%', :q, '%'))
            """)
    Page<MainFunction> search(@Param("q") String q, Pageable pageable);
    List<MainFunction> findByUpdatedAtGreaterThanEqual(Long since);
    Optional<MainFunction> findByCode(String code);
    Optional<MainFunction> findByName(String name);
    List<MainFunction> findAllByOrderByIdDesc();

    List<MainFunction> findBySystemId(Long systemId);
}
