package com.hnp.backendofflinefirst.service;

import com.hnp.backendofflinefirst.entity.User;
import com.hnp.backendofflinefirst.entity.UserAuthType;
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
import static org.mockito.ArgumentMatchers.anyString;
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

        User created = userService.create("operator1", "Operator One", "pass123", UserAuthType.LOCAL, true, List.of(50L));

        assertThat(created.getUsername()).isEqualTo("operator1");
        assertThat(created.getPasswordHash()).isEqualTo("hashed");
        verify(roleService).assignRolesToUser(created.getId(), List.of(50L));
    }

    @Test
    void createRejectsDuplicateUsername() {
        when(userRepository.existsByUsername("admin")).thenReturn(true);

        assertThatThrownBy(() -> userService.create("admin", "X", "pass", UserAuthType.LOCAL, true, null))
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
    void activeDirectoryUserDoesNotRequirePasswordOnCreate() {
        when(userRepository.existsByUsername("ad.user")).thenReturn(false);
        when(passwordEncoder.encode(anyString())).thenReturn("placeholder");
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        User created = userService.create("ad.user", "AD User", null, UserAuthType.ACTIVE_DIRECTORY, true, null);

        assertThat(created.getAuthType()).isEqualTo(UserAuthType.ACTIVE_DIRECTORY);
        assertThat(created.getPasswordHash()).isEqualTo("placeholder");
    }

    @Test
    void changePasswordBlockedForActiveDirectoryUser() {
        User user = new User();
        user.setId(2L);
        user.setAuthType(UserAuthType.ACTIVE_DIRECTORY);
        when(userRepository.findById(2L)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> userService.changePassword(2L, "newpass"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Active Directory");
    }

    @Test
    void changePasswordUpdatesHash() {
        User user = new User();
        user.setId(1L);
        user.setAuthType(UserAuthType.LOCAL);
        user.setPasswordHash("old");
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(passwordEncoder.encode("newpass")).thenReturn("newhash");

        userService.changePassword(1L, "newpass");

        assertThat(user.getPasswordHash()).isEqualTo("newhash");
        verify(userRepository).save(user);
    }
}
