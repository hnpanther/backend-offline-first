package com.hnp.backendofflinefirst.repository;

import com.hnp.backendofflinefirst.entity.FieldDefinition;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface FieldDefinitionRepository extends JpaRepository<FieldDefinition, Long> {

    Page<FieldDefinition> findByClassId(Long classId, Pageable pageable);

    @Query("""
            SELECT f FROM FieldDefinition f
            WHERE f.classId = :classId
              AND (LOWER(f.key) LIKE LOWER(CONCAT('%', :q, '%'))
                OR LOWER(f.label) LIKE LOWER(CONCAT('%', :q, '%')))
            """)
    Page<FieldDefinition> searchByClassId(@Param("classId") Long classId,
                                          @Param("q") String q,
                                          Pageable pageable);
    List<FieldDefinition> findByUpdatedAtGreaterThanEqual(Long since);
    List<FieldDefinition> findByClassId(Long classId);
    List<FieldDefinition> findByClassIdOrderByIdDesc(Long classId);

    List<FieldDefinition> findByClassIdIn(Collection<Long> classIds);

    Optional<FieldDefinition> findByClassIdAndKeyIgnoreCase(Long classId, String key);

    Optional<FieldDefinition> findByIdAndClassId(Long id, Long classId);
}
