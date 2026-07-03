package com.hnp.backendofflinefirst.repository;

import com.hnp.backendofflinefirst.entity.MainFunction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MainFunctionRepository extends JpaRepository<MainFunction, Long> {
    List<MainFunction> findByUpdatedAtGreaterThanEqual(Long since);
    Optional<MainFunction> findByCode(String code);
    Optional<MainFunction> findByName(String name);
    List<MainFunction> findAllByOrderByIdDesc();
}
