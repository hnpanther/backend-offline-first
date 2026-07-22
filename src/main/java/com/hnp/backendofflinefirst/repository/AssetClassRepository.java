package com.hnp.backendofflinefirst.repository;

import com.hnp.backendofflinefirst.entity.AssetClass;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface AssetClassRepository extends JpaRepository<AssetClass, Long> {

    @Query("SELECT a FROM AssetClass a WHERE LOWER(a.name) LIKE LOWER(CONCAT('%', :q, '%'))")
    Page<AssetClass> search(@Param("q") String q, Pageable pageable);
    List<AssetClass> findByUpdatedAtGreaterThanEqual(Long since);
    Optional<AssetClass> findByName(String name);
    Optional<AssetClass> findByNameIgnoreCase(String name);
    List<AssetClass> findAllByOrderByIdDesc();
}
