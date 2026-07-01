package com.hnp.backendofflinefirst.service;

import com.hnp.backendofflinefirst.entity.Role;
import com.hnp.backendofflinefirst.entity.UserRole;
import com.hnp.backendofflinefirst.repository.RolePermissionRepository;
import com.hnp.backendofflinefirst.repository.RoleRepository;
import com.hnp.backendofflinefirst.repository.UserRoleRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RoleServiceTest {

    @Mock RoleRepository roleRepository;
    @Mock com.hnp.backendofflinefirst.repository.PermissionRepository permissionRepository;
    @Mock RolePermissionRepository rolePermissionRepository;
    @Mock UserRoleRepository userRoleRepository;

    @InjectMocks RoleService roleService;

    @Test
    void deleteRejectsSystemRole() {
        Role role = new Role();
        role.setId("role-admin");
        role.setSystemRole(true);
        when(roleRepository.findById("role-admin")).thenReturn(Optional.of(role));

        assertThatThrownBy(() -> roleService.deleteRole("role-admin"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("سیستمی");
    }

    @Test
    void deleteRejectsRoleAssignedToUsers() {
        Role role = new Role();
        role.setId("custom");
        role.setSystemRole(false);
        when(roleRepository.findById("custom")).thenReturn(Optional.of(role));
        when(userRoleRepository.findByRoleId("custom")).thenReturn(List.of(new UserRole()));

        assertThatThrownBy(() -> roleService.deleteRole("custom"))
                .isInstanceOf(IllegalStateException.class);

        verify(rolePermissionRepository).deleteByRoleId("custom");
    }
}
