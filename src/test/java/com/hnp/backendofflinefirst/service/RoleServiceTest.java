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

import static org.assertj.core.api.Assertions.assertThat;
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

    // ── Duplicating a role ───────────────────────────────────────────────────

    private static Role sourceRole(long id, String code, boolean systemRole) {
        Role role = new Role();
        role.setId(id);
        role.setCode(code);
        role.setName("سرپرست");
        role.setDescription("توضیح اصلی");
        role.setSystemRole(systemRole);
        return role;
    }

    private static com.hnp.backendofflinefirst.entity.RolePermission link(long roleId, long permissionId) {
        com.hnp.backendofflinefirst.entity.RolePermission rp =
                new com.hnp.backendofflinefirst.entity.RolePermission();
        rp.setRoleId(roleId);
        rp.setPermissionId(permissionId);
        return rp;
    }

    @Test
    void duplicateCopiesEveryPermissionOfTheSourceRole() {
        when(roleRepository.findById(5L)).thenReturn(Optional.of(sourceRole(5L, "SUPERVISOR", true)));
        when(roleRepository.existsByCode("SUPERVISOR_RO")).thenReturn(false);
        when(rolePermissionRepository.findByRoleId(5L))
                .thenReturn(List.of(link(5L, 100L), link(5L, 200L), link(5L, 300L)));
        when(roleRepository.save(org.mockito.ArgumentMatchers.any(Role.class)))
                .thenAnswer(inv -> {
                    Role saved = inv.getArgument(0);
                    saved.setId(77L);
                    return saved;
                });

        Role copy = roleService.duplicateRole(5L, "SUPERVISOR_RO", "سرپرست فقط‌خواندنی", null);

        assertThat(copy.getCode()).isEqualTo("SUPERVISOR_RO");
        org.mockito.ArgumentCaptor<com.hnp.backendofflinefirst.entity.RolePermission> captor =
                org.mockito.ArgumentCaptor.forClass(com.hnp.backendofflinefirst.entity.RolePermission.class);
        verify(rolePermissionRepository, org.mockito.Mockito.times(3)).save(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(com.hnp.backendofflinefirst.entity.RolePermission::getPermissionId)
                .containsExactlyInAnyOrder(100L, 200L, 300L);
        assertThat(captor.getAllValues())
                .extracting(com.hnp.backendofflinefirst.entity.RolePermission::getRoleId)
                .containsOnly(77L);
    }

    @Test
    void aCopyOfASystemRoleIsNotItselfASystemRole() {
        // Otherwise duplicating SUPERVISOR would silently create a second undeletable role.
        when(roleRepository.findById(5L)).thenReturn(Optional.of(sourceRole(5L, "SUPERVISOR", true)));
        when(roleRepository.existsByCode("SUPERVISOR_RO")).thenReturn(false);
        when(rolePermissionRepository.findByRoleId(5L)).thenReturn(List.of());
        when(roleRepository.save(org.mockito.ArgumentMatchers.any(Role.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        Role copy = roleService.duplicateRole(5L, "SUPERVISOR_RO", "کپی", null);

        assertThat(copy.isSystemRole()).isFalse();
    }

    @Test
    void anEmptyDescriptionInheritsTheSourceDescription() {
        when(roleRepository.findById(5L)).thenReturn(Optional.of(sourceRole(5L, "SUPERVISOR", false)));
        when(roleRepository.existsByCode("X")).thenReturn(false);
        when(rolePermissionRepository.findByRoleId(5L)).thenReturn(List.of());
        when(roleRepository.save(org.mockito.ArgumentMatchers.any(Role.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        assertThat(roleService.duplicateRole(5L, "X", "کپی", "   ").getDescription())
                .as("blank means 'not supplied', not 'deliberately empty'")
                .isEqualTo("توضیح اصلی");
    }

    @Test
    void anExplicitDescriptionWins() {
        when(roleRepository.findById(5L)).thenReturn(Optional.of(sourceRole(5L, "SUPERVISOR", false)));
        when(roleRepository.existsByCode("X")).thenReturn(false);
        when(rolePermissionRepository.findByRoleId(5L)).thenReturn(List.of());
        when(roleRepository.save(org.mockito.ArgumentMatchers.any(Role.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        assertThat(roleService.duplicateRole(5L, "X", "کپی", "توضیح تازه").getDescription())
                .isEqualTo("توضیح تازه");
    }

    @Test
    void duplicateRejectsACodeThatAlreadyExists() {
        when(roleRepository.findById(5L)).thenReturn(Optional.of(sourceRole(5L, "SUPERVISOR", false)));
        when(roleRepository.existsByCode("ADMIN")).thenReturn(true);

        assertThatThrownBy(() -> roleService.duplicateRole(5L, "ADMIN", "کپی", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate role code");
    }

    @Test
    void duplicateRejectsAnUnknownSourceRole() {
        when(roleRepository.findById(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> roleService.duplicateRole(404L, "X", "کپی", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Role not found");
    }

    @Test
    void duplicateDoesNotCopyUserAssignments() {
        // Granting the new role to everyone who held the original is the opposite of what an
        // administrator building a narrower variant wants.
        when(roleRepository.findById(5L)).thenReturn(Optional.of(sourceRole(5L, "SUPERVISOR", false)));
        when(roleRepository.existsByCode("X")).thenReturn(false);
        when(rolePermissionRepository.findByRoleId(5L)).thenReturn(List.of());
        when(roleRepository.save(org.mockito.ArgumentMatchers.any(Role.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        roleService.duplicateRole(5L, "X", "کپی", null);

        org.mockito.Mockito.verifyNoInteractions(userRoleRepository);
    }
}
