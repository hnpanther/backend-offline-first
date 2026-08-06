package com.hnp.backendofflinefirst.service;

import com.hnp.backendofflinefirst.entity.User;
import com.hnp.backendofflinefirst.entity.UserAuthType;
import com.hnp.backendofflinefirst.repository.AuditLogRepository;
import com.hnp.backendofflinefirst.repository.ImportJobRepository;
import com.hnp.backendofflinefirst.repository.LogSheetActionLogRepository;
import com.hnp.backendofflinefirst.repository.LogSheetRepository;
import com.hnp.backendofflinefirst.repository.LogSheetVoidSubmissionRepository;
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
    @Mock LogSheetRepository logSheetRepository;
    @Mock LogSheetActionLogRepository logSheetActionLogRepository;
    @Mock LogSheetVoidSubmissionRepository logSheetVoidSubmissionRepository;
    @Mock AuditLogRepository auditLogRepository;
    @Mock ImportJobRepository importJobRepository;
    @Mock RoleService roleService;
    @Mock PasswordEncoder passwordEncoder;

    @InjectMocks UserService userService;

    @Test
    void createPersistsUserAndAssignsRoles() {
        when(userRepository.existsByUsername("operator1")).thenReturn(false);
        when(passwordEncoder.encode("pass123")).thenReturn("hashed");
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        User created = userService.create("operator1", "Operator One", uniquePersonnelCode(), "شیفت A", null, null, null,
                "pass123", UserAuthType.LOCAL, true, List.of(50L));

        assertThat(created.getUsername()).isEqualTo("operator1");
        assertThat(created.getPasswordHash()).isEqualTo("hashed");
        verify(roleService).assignRolesToUser(created.getId(), List.of(50L));
    }

    @Test
    void createRejectsDuplicateUsername() {
        when(userRepository.existsByUsername("admin")).thenReturn(true);

        assertThatThrownBy(() -> userService.create("admin", "X", uniquePersonnelCode(), "شیفت A", null, null, null,
                "pass", UserAuthType.LOCAL, true, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("admin");
    }

    @Test
    void createPersistsOptionalContactFields() {
        when(userRepository.existsByUsername("op2")).thenReturn(false);
        when(passwordEncoder.encode("pass123")).thenReturn("hashed");
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        User created = userService.create("op2", "Op", uniquePersonnelCode(), "شیفت A", "0012345678", "09121234567", "NFC-USER-1",
                "pass123", UserAuthType.LOCAL, true, null);

        assertThat(created.getNationalCode()).isEqualTo("0012345678");
        assertThat(created.getPhoneNumber()).isEqualTo("09121234567");
        assertThat(created.getNfcTagId()).isEqualTo("NFC-USER-1");
    }

    @Test
    void createRejectsContactFieldsLongerThanLimit() {
        when(userRepository.existsByUsername("op3")).thenReturn(false);

        assertThatThrownBy(() -> userService.create("op3", "Op", uniquePersonnelCode(), "شیفت A", "1234567890123456", null, null,
                "pass", UserAuthType.LOCAL, true, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("National code");

        assertThatThrownBy(() -> userService.create("op3", "Op", uniquePersonnelCode(), "شیفت A", null, "1234567890123456", null,
                "pass", UserAuthType.LOCAL, true, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Phone number");

        assertThatThrownBy(() -> userService.create("op3", "Op", uniquePersonnelCode(), "شیفت A", null, null, "x".repeat(51),
                "pass", UserAuthType.LOCAL, true, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("NFC tag");
    }

    @Test
    void createRejectsDuplicateNationalCode() {
        when(userRepository.existsByUsername("op4")).thenReturn(false);
        User existing = new User();
        existing.setId(1L);
        when(userRepository.findByNationalCode("0012345678")).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> userService.create("op4", "Op", uniquePersonnelCode(), "شیفت A", "0012345678", null, null,
                "pass", UserAuthType.LOCAL, true, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("0012345678");
    }

    @Test
    void createRejectsDuplicatePhoneNumber() {
        when(userRepository.existsByUsername("op5")).thenReturn(false);
        User existing = new User();
        existing.setId(1L);
        when(userRepository.findByPhoneNumber("09121234567")).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> userService.create("op5", "Op", uniquePersonnelCode(), "شیفت A", null, "09121234567", null,
                "pass", UserAuthType.LOCAL, true, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("09121234567");
    }

    @Test
    void createRejectsDuplicateNfcTagCaseInsensitive() {
        when(userRepository.existsByUsername("op6")).thenReturn(false);
        User existing = new User();
        existing.setId(1L);
        when(userRepository.findByNfcTagIdIgnoreCase("NFC-USER-1")).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> userService.create("op6", "Op", uniquePersonnelCode(), "شیفت A", null, null, "NFC-USER-1",
                "pass", UserAuthType.LOCAL, true, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("NFC-USER-1");
    }

    @Test
    void updateAllowsKeepingOwnContactFieldValues() {
        User existing = new User();
        existing.setId(7L);
        existing.setUsername("op7");
        existing.setPersonnelCode("PC-" + java.util.UUID.randomUUID());
        when(userRepository.findById(7L)).thenReturn(Optional.of(existing));
        // Same user owns these values already — must not be flagged as a duplicate of itself.
        when(userRepository.findByNationalCode("0012345678")).thenReturn(Optional.of(existing));
        when(userRepository.findByPhoneNumber("09121234567")).thenReturn(Optional.of(existing));
        when(userRepository.findByNfcTagIdIgnoreCase("NFC-USER-1")).thenReturn(Optional.of(existing));

        userService.update(7L, "op7", "Op", uniquePersonnelCode(), null, "0012345678", "09121234567", "NFC-USER-1",
                UserAuthType.LOCAL, true, null);

        assertThat(existing.getNationalCode()).isEqualTo("0012345678");
        verify(userRepository).save(existing);
    }

    @Test
    void updateRejectsContactFieldOwnedByAnotherUser() {
        User existing = new User();
        existing.setId(7L);
        existing.setUsername("op7");
        existing.setPersonnelCode("PC-" + java.util.UUID.randomUUID());
        User other = new User();
        other.setId(8L);
        when(userRepository.findById(7L)).thenReturn(Optional.of(existing));
        when(userRepository.findByNfcTagIdIgnoreCase("NFC-USER-1")).thenReturn(Optional.of(other));

        assertThatThrownBy(() -> userService.update(7L, "op7", "Op", uniquePersonnelCode(), null, null, null, "NFC-USER-1",
                UserAuthType.LOCAL, true, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("NFC-USER-1");
    }

    @Test
    void deleteBlockedWhenUserAssignedToUnit() {
        when(unitSupervisorRepository.existsByUserId(1L)).thenReturn(true);

        assertThatThrownBy(() -> userService.delete(1L))
                .isInstanceOf(IllegalStateException.class);

        verify(userRepository, never()).deleteById(anyLong());
    }

    @Test
    void deleteBlockedWhenUserHasLogSheetActivity() {
        when(unitSupervisorRepository.existsByUserId(1L)).thenReturn(false);
        when(unitOperatorRepository.existsByUserId(1L)).thenReturn(false);
        when(logSheetRepository.existsByCompletedByUserId(1L)).thenReturn(true);

        assertThatThrownBy(() -> userService.delete(1L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Deactivate the user instead");

        verify(userRepository, never()).deleteById(anyLong());
    }

    @Test
    void deleteBlockedWhenUserHasAuditActivity() {
        when(unitSupervisorRepository.existsByUserId(1L)).thenReturn(false);
        when(unitOperatorRepository.existsByUserId(1L)).thenReturn(false);
        when(logSheetRepository.existsByAssigneeUserId(1L)).thenReturn(false);
        when(logSheetRepository.existsByAssignedByUserId(1L)).thenReturn(false);
        when(logSheetRepository.existsByCompletedByUserId(1L)).thenReturn(false);
        when(logSheetActionLogRepository.existsByActorUserId(1L)).thenReturn(false);
        when(logSheetActionLogRepository.existsByFromUserId(1L)).thenReturn(false);
        when(logSheetActionLogRepository.existsByToUserId(1L)).thenReturn(false);
        when(logSheetVoidSubmissionRepository.existsBySubmittedByUserId(1L)).thenReturn(false);
        when(auditLogRepository.existsByActorUserId(1L)).thenReturn(true);

        assertThatThrownBy(() -> userService.delete(1L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Deactivate the user instead");
        verify(userRepository, never()).deleteById(anyLong());
    }

    @Test
    void deleteBlockedWhenUserHasImportActivity() {
        when(unitSupervisorRepository.existsByUserId(1L)).thenReturn(false);
        when(unitOperatorRepository.existsByUserId(1L)).thenReturn(false);
        when(logSheetRepository.existsByAssigneeUserId(1L)).thenReturn(false);
        when(logSheetRepository.existsByAssignedByUserId(1L)).thenReturn(false);
        when(logSheetRepository.existsByCompletedByUserId(1L)).thenReturn(false);
        when(logSheetActionLogRepository.existsByActorUserId(1L)).thenReturn(false);
        when(logSheetActionLogRepository.existsByFromUserId(1L)).thenReturn(false);
        when(logSheetActionLogRepository.existsByToUserId(1L)).thenReturn(false);
        when(logSheetVoidSubmissionRepository.existsBySubmittedByUserId(1L)).thenReturn(false);
        when(auditLogRepository.existsByActorUserId(1L)).thenReturn(false);
        when(importJobRepository.existsBySubmittedByUserId(1L)).thenReturn(true);

        assertThatThrownBy(() -> userService.delete(1L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Deactivate the user instead");
        verify(userRepository, never()).deleteById(anyLong());
    }

    @Test
    void deleteSucceedsWhenUserHasNoActivity() {
        when(unitSupervisorRepository.existsByUserId(1L)).thenReturn(false);
        when(unitOperatorRepository.existsByUserId(1L)).thenReturn(false);
        when(logSheetRepository.existsByAssigneeUserId(1L)).thenReturn(false);
        when(logSheetRepository.existsByAssignedByUserId(1L)).thenReturn(false);
        when(logSheetRepository.existsByCompletedByUserId(1L)).thenReturn(false);
        when(logSheetActionLogRepository.existsByActorUserId(1L)).thenReturn(false);
        when(logSheetActionLogRepository.existsByFromUserId(1L)).thenReturn(false);
        when(logSheetActionLogRepository.existsByToUserId(1L)).thenReturn(false);
        when(logSheetVoidSubmissionRepository.existsBySubmittedByUserId(1L)).thenReturn(false);
        when(auditLogRepository.existsByActorUserId(1L)).thenReturn(false);
        when(importJobRepository.existsBySubmittedByUserId(1L)).thenReturn(false);

        userService.delete(1L);

        verify(userRoleRepository).deleteByUserId(1L);
        verify(userRepository).deleteById(1L);
    }

    @Test
    void activeDirectoryUserDoesNotRequirePasswordOnCreate() {
        when(userRepository.existsByUsername("ad.user")).thenReturn(false);
        when(passwordEncoder.encode(anyString())).thenReturn("placeholder");
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        User created = userService.create("ad.user", "AD User", uniquePersonnelCode(), "شیفت A", null, null, null,
                null, UserAuthType.ACTIVE_DIRECTORY, true, null);

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

    // ── Personnel code (required + unique) and shift (optional) ──────────────

    /** Unique per call so the mocked duplicate lookup below is the only thing under test. */
    private static String uniquePersonnelCode() {
        return "PC-" + java.util.UUID.randomUUID();
    }

    @Test
    void createRequiresAPersonnelCode() {
        assertThatThrownBy(() -> userService.create("op-nopc", "Op", null, null, null, null, null,
                "pass123", UserAuthType.LOCAL, true, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Personnel code is required.");

        assertThatThrownBy(() -> userService.create("op-blankpc", "Op", "   ", null, null, null, null,
                "pass123", UserAuthType.LOCAL, true, List.of()))
                .as("whitespace is not a personnel code")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Personnel code is required.");
    }

    @Test
    void createRejectsADuplicatePersonnelCodeCaseInsensitively() {
        User existing = new User();
        existing.setId(9L);
        when(userRepository.findByPersonnelCodeIgnoreCase("emp-1")).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> userService.create("op-dup", "Op", "emp-1", null, null, null, null,
                "pass123", UserAuthType.LOCAL, true, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate personnel code");
    }

    @Test
    void updateLetsAUserKeepTheirOwnPersonnelCode() {
        User existing = new User();
        existing.setId(7L);
        existing.setUsername("op7");
        existing.setPersonnelCode("EMP-7");
        when(userRepository.findById(7L)).thenReturn(Optional.of(existing));
        // The lookup returns the very same user — self-match must not be a duplicate.
        when(userRepository.findByPersonnelCodeIgnoreCase("EMP-7")).thenReturn(Optional.of(existing));

        userService.update(7L, "op7", "Op", "EMP-7", null, null, null, null,
                UserAuthType.LOCAL, true, List.of());

        assertThat(existing.getPersonnelCode()).isEqualTo("EMP-7");
    }

    @Test
    void personnelCodeIsTrimmedAndLengthLimited() {
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        User created = userService.create("op-trim", "Op", "  EMP-42  ", null, null, null, null,
                "pass123", UserAuthType.LOCAL, true, List.of());
        assertThat(created.getPersonnelCode()).isEqualTo("EMP-42");

        assertThatThrownBy(() -> userService.create("op-long", "Op", "x".repeat(51), null, null, null, null,
                "pass123", UserAuthType.LOCAL, true, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Personnel code must be at most");
    }

    @Test
    void shiftIsOptionalFreeTextAndBlankBecomesNull() {
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        User withShift = userService.create("op-shift", "Op", uniquePersonnelCode(), " شیفت شب ", null, null, null,
                "pass123", UserAuthType.LOCAL, true, List.of());
        assertThat(withShift.getShift()).isEqualTo("شیفت شب");

        User blankShift = userService.create("op-noshift", "Op", uniquePersonnelCode(), "   ", null, null, null,
                "pass123", UserAuthType.LOCAL, true, List.of());
        assertThat(blankShift.getShift()).as("blank normalises to null, never empty string").isNull();

        assertThatThrownBy(() -> userService.create("op-longshift", "Op", uniquePersonnelCode(), "x".repeat(101),
                null, null, null, "pass123", UserAuthType.LOCAL, true, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Shift must be at most");
    }
}
