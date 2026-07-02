package com.hnp.backendofflinefirst.service;

import com.hnp.backendofflinefirst.entity.User;
import com.hnp.backendofflinefirst.repository.UnitOperatorRepository;
import com.hnp.backendofflinefirst.repository.UnitSupervisorRepository;
import com.hnp.backendofflinefirst.repository.UserRepository;
import com.hnp.backendofflinefirst.repository.UserRoleRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock UserRepository userRepository;
    @Mock UnitSupervisorRepository unitSupervisorRepository;
    @Mock UnitOperatorRepository unitOperatorRepository;
    @Mock UserRoleRepository userRoleRepository;
    @Mock RoleService roleService;
    @Mock PasswordEncoder passwordEncoder;

    @InjectMocks UserService userService;

    @Test
    void createPersistsUserAndAssignsRoles() {
        when(userRepository.existsByUsername("operator1")).thenReturn(false);
        when(passwordEncoder.encode("pass123")).thenReturn("hashed");
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        User created = userService.create("operator1", "Operator One", "pass123", true, List.of(50L));

        assertThat(created.getUsername()).isEqualTo("operator1");
        assertThat(created.getPasswordHash()).isEqualTo("hashed");
        verify(roleService).assignRolesToUser(created.getId(), List.of(50L));
    }

    @Test
    void createRejectsDuplicateUsername() {
        when(userRepository.existsByUsername("admin")).thenReturn(true);

        assertThatThrownBy(() -> userService.create("admin", "X", "pass", true, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("admin");
    }

    @Test
    void deleteBlockedWhenUserAssignedToUnit() {
        when(unitSupervisorRepository.existsByUserId(1L)).thenReturn(true);

        assertThatThrownBy(() -> userService.delete(1L))
                .isInstanceOf(IllegalStateException.class);

        verify(userRepository, never()).deleteById(anyLong());
    }

    @Test
    void changePasswordUpdatesHash() {
        User user = new User();
        user.setId(1L);
        user.setPasswordHash("old");
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(passwordEncoder.encode("newpass")).thenReturn("newhash");

        userService.changePassword(1L, "newpass");

        assertThat(user.getPasswordHash()).isEqualTo("newhash");
        verify(userRepository).save(user);
    }
}
