package com.hnp.backendofflinefirst.repository;

import com.hnp.backendofflinefirst.entity.RolePermission;
import com.hnp.backendofflinefirst.entity.RolePermissionId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RolePermissionRepository extends JpaRepository<RolePermission, RolePermissionId> {
    List<RolePermission> findByRoleId(Long roleId);
    void deleteByRoleId(Long roleId);
}
