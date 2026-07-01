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
        return roleRepository.findAllByOrderByCodeAsc();
    }

    public List<Permission> findAllPermissions() {
        return permissionRepository.findAllByOrderByCategoryAscHttpMethodAscEndpointPathAsc();
    }

    private static final Map<String, String> CATEGORY_LABELS = Map.of(
            "general", "عمومی",
            "admin", "مدیریت سیستم",
            "organization", "سازمان",
            "master-data", "داده پایه",
            "operational", "عملیاتی",
            "reports", "گزارش‌ها",
            "api", "API موبایل",
            "other", "سایر"
    );

    public Map<String, List<Permission>> permissionsByCategory() {
        return findAllPermissions().stream()
                .collect(Collectors.groupingBy(
                        p -> CATEGORY_LABELS.getOrDefault(
                                p.getCategory() != null ? p.getCategory() : "other", p.getCategory()),
                        LinkedHashMap::new, Collectors.toList()));
    }

    public List<String> getRoleIdsForUser(String userId) {
        return userRoleRepository.findByUserId(userId).stream()
                .map(UserRole::getRoleId).toList();
    }

    public List<String> getPermissionIdsForRole(String roleId) {
        return rolePermissionRepository.findByRoleId(roleId).stream()
                .map(RolePermission::getPermissionId).toList();
    }

    public Optional<Role> findById(String id) {
        return roleRepository.findById(id);
    }

    @Transactional
    public Role createRole(String code, String name, String description, List<String> permissionIds) {
        if (roleRepository.existsByCode(code.trim())) {
            throw new IllegalArgumentException("کد نقش «" + code + "» قبلاً ثبت شده است.");
        }
        long now = System.currentTimeMillis();
        Role role = new Role();
        role.setId(UUID.randomUUID().toString());
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
    public void updateRole(String id, String name, String description, List<String> permissionIds) {
        Role role = roleRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("نقش یافت نشد."));
        role.setName(name);
        role.setDescription(description);
        role.setUpdatedAt(System.currentTimeMillis());
        roleRepository.save(role);
        savePermissions(id, permissionIds);
    }

    @Transactional
    public void deleteRole(String id) {
        Role role = roleRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("نقش یافت نشد."));
        if (role.isSystemRole()) {
            throw new IllegalStateException("نقش سیستمی قابل حذف نیست.");
        }
        rolePermissionRepository.deleteByRoleId(id);
        if (!userRoleRepository.findByRoleId(id).isEmpty()) {
            throw new IllegalStateException("این نقش به کاربران اختصاص داده شده و قابل حذف نیست.");
        }
        roleRepository.deleteById(id);
    }

    @Transactional
    public void assignRolesToUser(String userId, List<String> roleIds) {
        userRoleRepository.deleteByUserId(userId);
        if (roleIds == null) return;
        for (String roleId : roleIds) {
            if (roleId == null || roleId.isBlank()) continue;
            UserRole ur = new UserRole();
            ur.setUserId(userId);
            ur.setRoleId(roleId);
            userRoleRepository.save(ur);
        }
    }

    private void savePermissions(String roleId, List<String> permissionIds) {
        rolePermissionRepository.deleteByRoleId(roleId);
        if (permissionIds == null) return;
        for (String permId : permissionIds) {
            if (permId == null || permId.isBlank()) continue;
            RolePermission rp = new RolePermission();
            rp.setRoleId(roleId);
            rp.setPermissionId(permId);
            rolePermissionRepository.save(rp);
        }
    }

    public Map<String, String> roleNameById() {
        return roleRepository.findAll().stream()
                .collect(Collectors.toMap(Role::getId, r -> r.getName() != null ? r.getName() : r.getCode()));
    }
}
