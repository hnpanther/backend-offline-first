package com.hnp.backendofflinefirst.service;

import com.hnp.backendofflinefirst.entity.*;
import com.hnp.backendofflinefirst.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/** CRUD for roles and assignment of permissions / user roles. */
@Service
@RequiredArgsConstructor
public class RoleService {

    private final RoleRepository roleRepository;
    private final PermissionRepository permissionRepository;
    private final RolePermissionRepository rolePermissionRepository;
    private final UserRoleRepository userRoleRepository;

    public List<Role> findAllRoles() {
        return roleRepository.findAllByOrderByIdDesc();
    }

    public List<Permission> findAllPermissions() {
        return permissionRepository.findAllByOrderByCategoryAscHttpMethodAscEndpointPathAsc();
    }

    public Map<String, List<Permission>> permissionsByCategory() {
        return findAllPermissions().stream()
                .collect(Collectors.groupingBy(
                        p -> p.getCategory() != null && !p.getCategory().isBlank() ? p.getCategory() : "other",
                        LinkedHashMap::new, Collectors.toList()));
    }

    public List<Long> getRoleIdsForUser(Long userId) {
        return userRoleRepository.findByUserId(userId).stream()
                .map(UserRole::getRoleId).toList();
    }

    public List<Long> getPermissionIdsForRole(Long roleId) {
        return rolePermissionRepository.findByRoleId(roleId).stream()
                .map(RolePermission::getPermissionId).toList();
    }

    public Optional<Role> findById(Long id) {
        return roleRepository.findById(id);
    }

    @Transactional
    public Role createRole(String code, String name, String description, List<Long> permissionIds) {
        if (roleRepository.existsByCode(code.trim())) {
            throw new IllegalArgumentException("Duplicate role code: " + code.trim());
        }
        long now = System.currentTimeMillis();
        Role role = new Role();
        role.setCode(code.trim());
        role.setName(name);
        role.setDescription(description);
        role.setSystemRole(false);
        role.setCreatedAt(now);
        role.setUpdatedAt(now);
        roleRepository.save(role);
        savePermissions(role.getId(), permissionIds);
        return role;
    }

    @Transactional
    public void updateRole(Long id, String name, String description, List<Long> permissionIds) {
        Role role = roleRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Role not found."));
        role.setName(name);
        role.setDescription(description);
        role.setUpdatedAt(System.currentTimeMillis());
        roleRepository.save(role);
        savePermissions(id, permissionIds);
    }

    @Transactional
    public void deleteRole(Long id) {
        Role role = roleRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Role not found."));
        if (role.isSystemRole()) {
            throw new IllegalStateException("System roles cannot be deleted.");
        }
        rolePermissionRepository.deleteByRoleId(id);
        if (!userRoleRepository.findByRoleId(id).isEmpty()) {
            throw new IllegalStateException("This role is assigned to users and cannot be deleted.");
        }
        roleRepository.deleteById(id);
    }

    @Transactional
    public void assignRolesToUser(Long userId, List<Long> roleIds) {
        userRoleRepository.deleteByUserId(userId);
        if (roleIds == null) return;
        for (Long roleId : roleIds) {
            if (roleId == null) continue;
            UserRole ur = new UserRole();
            ur.setUserId(userId);
            ur.setRoleId(roleId);
            userRoleRepository.save(ur);
        }
    }

    private void savePermissions(Long roleId, List<Long> permissionIds) {
        rolePermissionRepository.deleteByRoleId(roleId);
        if (permissionIds == null) return;
        for (Long permId : permissionIds) {
            if (permId == null) continue;
            RolePermission rp = new RolePermission();
            rp.setRoleId(roleId);
            rp.setPermissionId(permId);
            rolePermissionRepository.save(rp);
        }
    }

    public Map<Long, String> roleNameById() {
        return roleRepository.findAll().stream()
                .collect(Collectors.toMap(Role::getId, r -> r.getName() != null ? r.getName() : r.getCode()));
    }
}
