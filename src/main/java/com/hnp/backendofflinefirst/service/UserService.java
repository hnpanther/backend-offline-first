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
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class UserService {

    private static final String AD_PLACEHOLDER_SECRET = "{AD_NO_LOCAL_PASSWORD}";
    public static final int NATIONAL_CODE_MAX_LEN = 15;
    public static final int PHONE_NUMBER_MAX_LEN = 15;
    public static final int NFC_TAG_MAX_LEN = 50;

    private final UserRepository userRepository;
    private final UnitSupervisorRepository unitSupervisorRepository;
    private final UnitOperatorRepository unitOperatorRepository;
    private final UserRoleRepository userRoleRepository;
    private final LogSheetRepository logSheetRepository;
    private final LogSheetActionLogRepository logSheetActionLogRepository;
    private final LogSheetVoidSubmissionRepository logSheetVoidSubmissionRepository;
    private final AuditLogRepository auditLogRepository;
    private final ImportJobRepository importJobRepository;
    private final RoleService roleService;
    private final PasswordEncoder passwordEncoder;

    public List<User> findAll() {
        return userRepository.findAllByOrderByIdDesc();
    }

    public Optional<User> findById(Long id) {
        return userRepository.findById(id);
    }

    @Transactional
    public User create(String username, String fullName, String nationalCode, String phoneNumber, String nfcTagId,
                       String password, UserAuthType authType, boolean active, List<Long> roleIds) {
        if (userRepository.existsByUsername(username)) {
            throw new IllegalArgumentException("Duplicate username: " + username.trim());
        }
        UserAuthType resolvedAuthType = authType != null ? authType : UserAuthType.LOCAL;
        long now = System.currentTimeMillis();
        User user = new User();
        user.setUsername(username.trim());
        user.setFullName(trimToNull(fullName));
        applyContactFields(user, nationalCode, phoneNumber, nfcTagId);
        user.setPasswordHash(resolvePasswordHash(password, resolvedAuthType));
        user.setAuthType(resolvedAuthType);
        user.setActive(active);
        user.setCreatedAt(now);
        user.setUpdatedAt(now);
        userRepository.save(user);
        roleService.assignRolesToUser(user.getId(), roleIds);
        return user;
    }

    @Transactional
    public void update(Long id, String username, String fullName, String nationalCode, String phoneNumber,
                       String nfcTagId, UserAuthType authType, boolean active, List<Long> roleIds) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("User not found."));
        if (!user.getUsername().equals(username.trim()) && userRepository.existsByUsername(username.trim())) {
            throw new IllegalArgumentException("Duplicate username: " + username.trim());
        }
        user.setUsername(username.trim());
        user.setFullName(trimToNull(fullName));
        applyContactFields(user, nationalCode, phoneNumber, nfcTagId);
        user.setAuthType(authType != null ? authType : UserAuthType.LOCAL);
        user.setActive(active);
        user.setUpdatedAt(System.currentTimeMillis());
        userRepository.save(user);
        roleService.assignRolesToUser(id, roleIds);
    }

    /** Validates and sets optional contact fields (blank → null). */
    public void applyContactFields(User user, String nationalCode, String phoneNumber, String nfcTagId) {
        user.setNationalCode(normalizeOptional(nationalCode, "National code", NATIONAL_CODE_MAX_LEN));
        user.setPhoneNumber(normalizeOptional(phoneNumber, "Phone number", PHONE_NUMBER_MAX_LEN));
        user.setNfcTagId(normalizeOptional(nfcTagId, "NFC tag", NFC_TAG_MAX_LEN));
    }

    public static String normalizeOptional(String value, String fieldLabel, int maxLen) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.length() > maxLen) {
            throw new IllegalArgumentException(fieldLabel + " must be at most " + maxLen + " characters.");
        }
        return trimmed;
    }

    private static String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    @Transactional
    public void changePassword(Long id, String newPassword) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("User not found."));
        if (user.getAuthType() == UserAuthType.ACTIVE_DIRECTORY) {
            throw new IllegalArgumentException("Password cannot be changed for Active Directory users.");
        }
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setUpdatedAt(System.currentTimeMillis());
        userRepository.save(user);
    }

    /**
     * Hard-delete is only allowed when the user has never been linked to units or
     * recorded any app activity (log sheets, audits, imports). Otherwise deactivate.
     */
    @Transactional
    public void delete(Long id) {
        if (unitSupervisorRepository.existsByUserId(id) || unitOperatorRepository.existsByUserId(id)) {
            throw new IllegalStateException("This user is assigned to operational units and cannot be deleted.");
        }
        if (hasAppActivity(id)) {
            throw new IllegalStateException(
                    "This user has performed actions in the app and cannot be deleted. Deactivate the user instead.");
        }
        userRoleRepository.deleteByUserId(id);
        userRepository.deleteById(id);
    }

    private boolean hasAppActivity(Long userId) {
        return logSheetRepository.existsByAssigneeUserId(userId)
                || logSheetRepository.existsByAssignedByUserId(userId)
                || logSheetRepository.existsByCompletedByUserId(userId)
                || logSheetActionLogRepository.existsByActorUserId(userId)
                || logSheetActionLogRepository.existsByFromUserId(userId)
                || logSheetActionLogRepository.existsByToUserId(userId)
                || logSheetVoidSubmissionRepository.existsBySubmittedByUserId(userId)
                || auditLogRepository.existsByActorUserId(userId)
                || importJobRepository.existsBySubmittedByUserId(userId);
    }

    String resolvePasswordHash(String password, UserAuthType authType) {
        if (authType == UserAuthType.ACTIVE_DIRECTORY) {
            return passwordEncoder.encode(AD_PLACEHOLDER_SECRET + UUID.randomUUID());
        }
        if (password == null || password.isBlank()) {
            throw new IllegalArgumentException("Password is required for LOCAL and HYBRID users.");
        }
        return passwordEncoder.encode(password);
    }

    public static UserAuthType parseAuthType(String raw) {
        if (raw == null || raw.isBlank()) {
            return UserAuthType.LOCAL;
        }
        return UserAuthType.valueOf(raw.trim().toUpperCase());
    }
}
