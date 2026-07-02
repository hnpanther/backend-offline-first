package com.hnp.backendofflinefirst.repository;

import com.hnp.backendofflinefirst.entity.SubFunction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SubFunctionRepository extends JpaRepository<SubFunction, Long> {
    List<SubFunction> findByUpdatedAtGreaterThanEqual(Long since);
    Optional<SubFunction> findByCode(String code);
    Optional<SubFunction> findByName(String name);
}
