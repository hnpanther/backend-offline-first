package com.hnp.backendofflinefirst.repository;

import com.hnp.backendofflinefirst.entity.FormDefinition;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface FormDefinitionRepository extends JpaRepository<FormDefinition, Long> {

    Optional<FormDefinition> findByCodeIgnoreCase(String code);

    boolean existsByCodeIgnoreCase(String code);

    boolean existsByCodeIgnoreCaseAndIdNot(String code, Long id);

    @Query("""
            SELECT f FROM FormDefinition f
            WHERE (:q = '' OR LOWER(f.code) LIKE LOWER(CONCAT('%', :q, '%'))
                   OR LOWER(f.title) LIKE LOWER(CONCAT('%', :q, '%')))
            ORDER BY f.id DESC
            """)
    Page<FormDefinition> search(@Param("q") String q, Pageable pageable);

    List<FormDefinition> findByActiveTrueOrderByTitleAsc();

    @Query("""
            SELECT DISTINCT f FROM FormDefinition f
            JOIN FormDefinitionUnit u ON u.formDefinitionId = f.id
            WHERE f.active = TRUE
              AND u.operationalUnitId IN :unitIds
            ORDER BY f.title ASC
            """)
    List<FormDefinition> findActiveLinkedToAnyUnit(@Param("unitIds") Collection<Long> unitIds);
}
