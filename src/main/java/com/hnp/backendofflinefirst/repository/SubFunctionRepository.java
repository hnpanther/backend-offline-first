package com.hnp.backendofflinefirst.repository;

import com.hnp.backendofflinefirst.entity.SubFunction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface SubFunctionRepository extends JpaRepository<SubFunction, Long> {

    @Query("""
            SELECT s FROM SubFunction s
            WHERE LOWER(s.code) LIKE LOWER(CONCAT('%', :q, '%'))
                OR LOWER(s.name) LIKE LOWER(CONCAT('%', :q, '%'))
            """)
    Page<SubFunction> search(@Param("q") String q, Pageable pageable);
    List<SubFunction> findByUpdatedAtGreaterThanEqual(Long since);
    Optional<SubFunction> findByCode(String code);
    Optional<SubFunction> findByName(String name);
    List<SubFunction> findAllByOrderByIdDesc();

    List<SubFunction> findByMainFunctionId(Long mainFunctionId);

    List<SubFunction> findBySystemIdAndMainFunctionIdIsNull(Long systemId);
}
