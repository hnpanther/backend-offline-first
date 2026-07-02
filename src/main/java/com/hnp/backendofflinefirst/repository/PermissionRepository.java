package com.hnp.backendofflinefirst.repository;

import com.hnp.backendofflinefirst.entity.Permission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface PermissionRepository extends JpaRepository<Permission, Long> {
    Optional<Permission> findByCode(String code);
    List<Permission> findAllByOrderByCategoryAscHttpMethodAscEndpointPathAsc();

    @Query("""
            SELECT DISTINCT p.code FROM Permission p
            JOIN RolePermission rp ON rp.permissionId = p.id
            JOIN UserRole ur ON ur.roleId = rp.roleId
            WHERE ur.userId = :userId
            """)
    Set<String> findPermissionCodesByUserId(Long userId);
}
