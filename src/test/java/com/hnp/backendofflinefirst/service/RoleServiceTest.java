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
        role.setId(1L);
        role.setSystemRole(true);
        when(roleRepository.findById(1L)).thenReturn(Optional.of(role));

        assertThatThrownBy(() -> roleService.deleteRole(1L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("System roles cannot be deleted");
    }

    @Test
    void deleteRejectsRoleAssignedToUsers() {
        Role role = new Role();
        role.setId(50L);
        role.setSystemRole(false);
        when(roleRepository.findById(50L)).thenReturn(Optional.of(role));
        when(userRoleRepository.findByRoleId(50L)).thenReturn(List.of(new UserRole()));

        assertThatThrownBy(() -> roleService.deleteRole(50L))
                .isInstanceOf(IllegalStateException.class);

        verify(rolePermissionRepository).deleteByRoleId(50L);
    }
}
