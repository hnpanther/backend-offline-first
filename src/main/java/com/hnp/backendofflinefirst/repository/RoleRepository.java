package com.hnp.backendofflinefirst.repository;

import com.hnp.backendofflinefirst.entity.Role;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RoleRepository extends JpaRepository<Role, String> {
    Optional<Role> findByCode(String code);
    List<Role> findAllByOrderByCodeAsc();
    boolean existsByCode(String code);
}
