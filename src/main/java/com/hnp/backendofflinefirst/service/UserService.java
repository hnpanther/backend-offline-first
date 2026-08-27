package com.hnp.backendofflinefirst.service;

import com.hnp.backendofflinefirst.domain.ApiSessionRevokeReason;
import com.hnp.backendofflinefirst.entity.User;
import com.hnp.backendofflinefirst.entity.UserAuthType;
import com.hnp.backendofflinefirst.entity.UserRole;
import com.hnp.backendofflinefirst.repository.AuditLogRepository;
import com.hnp.backendofflinefirst.repository.ImportJobRepository;
import com.hnp.backendofflinefirst.repository.LogSheetActionLogRepository;
import com.hnp.backendofflinefirst.repository.LogSheetRepository;
import com.hnp.backendofflinefirst.repository.LogSheetVoidSubmissionRepository;
import com.hnp.backendofflinefirst.repository.UnitOperatorRepository;
import com.hnp.backendofflinefirst.repository.UnitSupervisorRepository;
import com.hnp.backendofflinefirst.repository.UserRepository;
import com.hnp.backendofflinefirst.repository.UserRoleRepository;
import com.hnp.backendofflinefirst.security.SecurityUtils;
import com.hnp.backendofflinefirst.security.SystemRoleCapabilities;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;

@Service
@RequiredArgsConstructor
@Slf4j
public class UserService {

    private static final String AD_PLACEHOLDER_SECRET = "{AD_NO_LOCAL_PASSWORD}";
    public static final int PERSONNEL_CODE_MAX_LEN = 50;
    public static final int SHIFT_MAX_LEN = 100;
    public static final int NATIONAL_CODE_MAX_LEN = 15;
    public static final int PHONE_NUMBER_MAX_LEN = 15;
    public static final int NFC_TAG_MAX_LEN = 50;
    /** Must match the column widths in V4 — a longer value would be a database error, not a message. */
    public static final int ORG_UNIT_MAX_LEN = 150;
    public static final int ORG_POSITION_MAX_LEN = 150;

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
    private final ApiSessionService apiSessionService;
    private final WebSessionService webSessionService;

    /**
     * What an edit did beyond writing the row, so the page can tell the administrator.
     *
     * @param deactivated       the account went from active to inactive in this edit
     * @param revokedApiSessions mobile sessions closed as a result
     * @param expiredWebSessions browser sessions closed as a result
     * @param rolesChanged      the role set differs from what it was
     */
    public record UserUpdateOutcome(boolean deactivated, int revokedApiSessions,
                                    int expiredWebSessions, boolean rolesChanged) {

        /**
         * True when the administrator should be told where their change has and has not landed.
         *
         * <p>Mobile sessions apply it immediately — API authorities are read from the database
         * per request — so the warning is about the <b>browser</b>, which holds the principal it
         * captured at login. See {@code FaMessages.rolesChangedWebSessionStillOpen}.
         */
        public boolean needsSessionWarning() {
            return rolesChanged && !deactivated;
        }
    }

    public List<User> findAll() {
        return userRepository.findAllByOrderByIdDesc();
    }

    public Optional<User> findById(Long id) {
        return userRepository.findById(id);
    }

    @Transactional
    public User create(String username, String fullName, String personnelCode, String shift,
                       String nationalCode, String phoneNumber, String nfcTagId,
                       String orgUnit, String orgPosition,
                       String password, UserAuthType authType, boolean active, List<Long> roleIds) {
        if (userRepository.existsByUsername(username)) {
            throw new IllegalArgumentException("Duplicate username: " + username.trim());
        }
        UserAuthType resolvedAuthType = authType != null ? authType : UserAuthType.LOCAL;
        long now = System.currentTimeMillis();
        User user = new User();
        user.setUsername(username.trim());
        user.setFullName(trimToNull(fullName));
        applyStaffFields(user, personnelCode, shift);
        applyContactFields(user, nationalCode, phoneNumber, nfcTagId);
        applyOrganizationFields(user, orgUnit, orgPosition);
        user.setPasswordHash(resolvePasswordHash(password, resolvedAuthType));
        user.setAuthType(resolvedAuthType);
        user.setActive(active);
        user.setCreatedAt(now);
        user.setUpdatedAt(now);
        userRepository.save(user);
        roleService.assignRolesToUser(user.getId(), roleIds);
        return user;
    }

    /**
     * Edits a user, and closes their live sessions <b>if and only if</b> this edit deactivated
     * them.
     *
     * <h2>Why deactivation revokes, and a role change does not</h2>
     *
     * <p>Neither used to. A mobile token carries the user's roles and permissions as claims, the
     * principal rebuilt from it is hard-coded to {@code active = true}, and the per-request
     * check only asks whether the {@code jti} still has a live {@code api_sessions} row — so a
     * deactivated account kept full access for the remaining life of its token, up to
     * {@code auth.jwt.expiry_minutes} (8 hours by default). The panel was no better: its session
     * holds an {@code AppUserDetails} captured at login, and the 60-minute timeout is an
     * <em>idle</em> one, so an active browser holds the old identity indefinitely.
     *
     * <p>Deactivation therefore revokes, because "this person no longer has access" is not a
     * statement that can be true in eight hours' time.
     *
     * <p><b>A role change deliberately does not</b>, and that is a decision rather than an
     * omission. Roles are edited routinely — adding a permission, fixing a typo in an
     * assignment — and logging every affected operator out of their tablet mid-round for a
     * widening of access would make the system hostile to administer, on a fleet that is
     * offline-first precisely because reconnecting is not always possible.
     *
     * <p>Not revoking the session no longer means the change waits, which it used to. A mobile
     * request resolves its authorities from the database every time
     * ({@code ApiTokenAuthenticator}), so the new access is in force on the tablet's next
     * request while its login survives — the two things an administrator wants at once, and
     * previously a trade-off. What still waits is the <b>browser</b>: a web session holds the
     * principal captured at login. That is what
     * {@link UserUpdateOutcome#needsSessionWarning()} now warns about, and {@code /web-sessions}
     * is the lever.
     *
     * @return what happened, so the caller can report it
     */
    @Transactional
    public UserUpdateOutcome update(Long id, String username, String fullName, String personnelCode, String shift,
                       String nationalCode, String phoneNumber,
                       String nfcTagId, String orgUnit, String orgPosition,
                       UserAuthType authType, boolean active, List<Long> roleIds) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("User not found."));
        if (!user.getUsername().equals(username.trim()) && userRepository.existsByUsername(username.trim())) {
            throw new IllegalArgumentException("Duplicate username: " + username.trim());
        }
        // Read before anything is written: both answers are about what this edit *changed*, and
        // the row is about to stop remembering.
        boolean wasActive = user.isActive();
        boolean rolesChanged = rolesDiffer(id, roleIds);

        user.setUsername(username.trim());
        user.setFullName(trimToNull(fullName));
        applyStaffFields(user, personnelCode, shift);
        applyContactFields(user, nationalCode, phoneNumber, nfcTagId);
        applyOrganizationFields(user, orgUnit, orgPosition);
        assertNotOrphaningAdministration(id, active, roleIds);
        user.setAuthType(authType != null ? authType : UserAuthType.LOCAL);
        user.setActive(active);
        user.setUpdatedAt(System.currentTimeMillis());
        userRepository.save(user);
        roleService.assignRolesToUser(id, roleIds);

        boolean deactivated = wasActive && !active;
        int revokedApi = 0;
        int expiredWeb = 0;
        if (deactivated) {
            // By id, never by name. The browser session registry holds the principal captured at
            // login, so an edit that renames *and* deactivates in one go would search under the
            // new name and match nothing — leaving the old browser live. The id is the one thing
            // a rename cannot move.
            revokedApi = closeAllSessions(id, user.getUsername(),
                    ApiSessionRevokeReason.USER_DEACTIVATED);
            expiredWeb = webSessionService.expireByUserId(id, SecurityUtils.currentUserId());
        }
        return new UserUpdateOutcome(deactivated, revokedApi, expiredWeb, rolesChanged);
    }

    /** Whether the requested role set differs from the one currently stored. */
    private boolean rolesDiffer(Long userId, List<Long> roleIds) {
        Set<Long> current = userRoleRepository.findByUserId(userId).stream()
                .map(UserRole::getRoleId)
                .collect(Collectors.toSet());
        Set<Long> requested = roleIds == null ? Set.of()
                : roleIds.stream().filter(Objects::nonNull).collect(Collectors.toSet());
        return !current.equals(requested);
    }

    /**
     * Closes every live mobile session of a user.
     *
     * <p>Web sessions are handled separately by the caller because they are in-memory and not
     * transactional — rolling this transaction back would not bring an expired browser session
     * back, so the two are kept visibly distinct rather than looking like one atomic act.
     */
    private int closeAllSessions(Long userId, String username, ApiSessionRevokeReason reason) {
        int revoked = apiSessionService.revokeAllForUser(
                userId, SecurityUtils.currentUserId(), System.currentTimeMillis(), reason);
        if (revoked > 0) {
            log.info("Closed {} mobile session(s) of user {} ({}) — reason {}",
                    revoked, userId, username, reason);
        }
        return revoked;
    }

    /**
     * Refuses an edit that would leave the system with no active administrator.
     *
     * <p>Three edits reach the same dead end and are therefore checked together: deactivating
     * the last admin, removing the ADMIN role from them, or (in {@link #delete}) deleting them
     * outright. Any of the three locks everyone out of user and role administration, and the
     * only way back is editing the database by hand — the person who did it cannot undo it
     * through the screen that did it.
     *
     * <p>The rule is deliberately "the last <b>active</b> admin" rather than "the account named
     * admin". Pinning it to one username protects the wrong thing: a site that renames the
     * bootstrap account, or creates a second administrator and retires the first, is doing
     * something perfectly reasonable and should not be blocked — while a site left with exactly
     * one admin should be, whatever that account is called.
     */
    private void assertNotOrphaningAdministration(Long userId, boolean willBeActive, List<Long> roleIds) {
        boolean keepsAdminRole = roleIds != null && roleIds.stream()
                .filter(Objects::nonNull)
                .map(roleService::findById)
                .flatMap(Optional::stream)
                .anyMatch(role -> SystemRoleCapabilities.ADMIN.equals(role.getCode()));
        if (willBeActive && keepsAdminRole) {
            return;
        }
        if (!isLastActiveAdministrator(userId)) {
            return;
        }
        throw new IllegalStateException(
                "This is the last active administrator and must keep the ADMIN role while active.");
    }

    /**
     * True when {@code userId} is an active ADMIN and no other active user holds that role.
     *
     * <p>Public so the users page can grey out the delete button rather than offering one that
     * always fails. The service check stays regardless: the page is a courtesy, the guard is
     * the rule.
     */
    @Transactional(readOnly = true)
    /**
     * The page-wide answer to {@link #isLastActiveAdministrator}, in two queries instead of two
     * per row. Returns the subset of {@code candidateIds} whose deletion would leave the system
     * with no administrator.
     *
     * <p>The semantics are copied exactly, including one asymmetry worth naming: holding the role
     * is checked <em>regardless of whether the user is active</em>, while "is there anyone else"
     * counts only active holders. So an inactive administrator is still reported as the last one
     * when no active administrator exists — which is the safe direction to err, and is what the
     * per-row method already did.
     */
    public Set<Long> lastActiveAdministratorIds(Collection<Long> candidateIds) {
        if (candidateIds == null || candidateIds.isEmpty()) {
            return Set.of();
        }
        Set<Long> admins = new HashSet<>(userRoleRepository.findUserIdsWithRole(SystemRoleCapabilities.ADMIN));
        Set<Long> activeAdmins =
                new HashSet<>(userRoleRepository.findActiveUserIdsWithRole(SystemRoleCapabilities.ADMIN));
        Set<Long> last = new LinkedHashSet<>();
        for (Long id : candidateIds) {
            if (id == null || !admins.contains(id)) {
                continue;
            }
            // "no OTHER active administrator" — the excluded-self form of findOtherActive...
            boolean anotherActiveAdminExists =
                    activeAdmins.stream().anyMatch(other -> !other.equals(id));
            if (!anotherActiveAdminExists) {
                last.add(id);
            }
        }
        return last;
    }

    public boolean isLastActiveAdministrator(Long userId) {
        boolean isAdminNow = userRoleRepository.findRoleCodesByUserId(userId)
                .contains(SystemRoleCapabilities.ADMIN);
        if (!isAdminNow) {
            return false;
        }
        return userRoleRepository
                .findOtherActiveUserIdsWithRole(SystemRoleCapabilities.ADMIN, userId)
                .isEmpty();
    }

    /**
      * Validates and sets the staff fields.
      *
      * <p>{@code personnelCode} is the one identity field besides {@code username} that is
      * mandatory, so unlike {@link #applyContactFields} a blank value is rejected rather than
      * stored as null. Uniqueness is case-insensitive to match the DB index
      * {@code ux_users_personnel_code_lower} — checked here so an administrator gets a Persian
      * message instead of a raw constraint violation, with the index still the source of truth
      * for races. {@code shift} is free text and only length-checked; it is descriptive today
      * and deliberately left open for future scheduling use.
      */
    public void applyStaffFields(User user, String personnelCode, String shift) {
        if (personnelCode == null || personnelCode.isBlank()) {
            throw new IllegalArgumentException("Personnel code is required.");
        }
        String normalized = personnelCode.trim();
        if (normalized.length() > PERSONNEL_CODE_MAX_LEN) {
            throw new IllegalArgumentException(
                    "Personnel code must be at most " + PERSONNEL_CODE_MAX_LEN + " characters.");
        }
        userRepository.findByPersonnelCodeIgnoreCase(normalized).ifPresent(existing -> {
            if (!Objects.equals(user.getId(), existing.getId())) {
                throw new IllegalArgumentException("Duplicate personnel code: " + normalized);
            }
        });
        user.setPersonnelCode(normalized);
        user.setShift(normalizeOptional(shift, "Shift", SHIFT_MAX_LEN));
    }

    /**
     * Validates and sets the optional organizational fields (blank → null).
     *
     * <p>No uniqueness check, unlike the contact fields: a hundred people share one department
     * and one job title, and that is the normal case rather than a data error. These are
     * descriptive attributes — nothing keys off them, and in particular {@code orgUnit} has no
     * relationship to {@code operational_units}, which is what actually scopes access.
     */
    public void applyOrganizationFields(User user, String orgUnit, String orgPosition) {
        user.setOrgUnit(normalizeOptional(orgUnit, "Organizational unit", ORG_UNIT_MAX_LEN));
        user.setOrgPosition(normalizeOptional(orgPosition, "Organizational position", ORG_POSITION_MAX_LEN));
    }

    /** Validates and sets optional contact fields (blank → null; each must be blank or unique). */
    public void applyContactFields(User user, String nationalCode, String phoneNumber, String nfcTagId) {
        String normalizedNationalCode = normalizeOptional(nationalCode, "National code", NATIONAL_CODE_MAX_LEN);
        String normalizedPhoneNumber = normalizeOptional(phoneNumber, "Phone number", PHONE_NUMBER_MAX_LEN);
        String normalizedNfcTagId = normalizeOptional(nfcTagId, "NFC tag", NFC_TAG_MAX_LEN);

        if (normalizedNationalCode != null) {
            userRepository.findByNationalCode(normalizedNationalCode).ifPresent(existing -> {
                if (!Objects.equals(user.getId(), existing.getId())) {
                    throw new IllegalArgumentException("Duplicate national code: " + normalizedNationalCode);
                }
            });
        }
        if (normalizedPhoneNumber != null) {
            userRepository.findByPhoneNumber(normalizedPhoneNumber).ifPresent(existing -> {
                if (!Objects.equals(user.getId(), existing.getId())) {
                    throw new IllegalArgumentException("Duplicate phone number: " + normalizedPhoneNumber);
                }
            });
        }
        if (normalizedNfcTagId != null) {
            userRepository.findByNfcTagIdIgnoreCase(normalizedNfcTagId).ifPresent(existing -> {
                if (!Objects.equals(user.getId(), existing.getId())) {
                    throw new IllegalArgumentException("Duplicate NFC tag: " + normalizedNfcTagId);
                }
            });
        }

        user.setNationalCode(normalizedNationalCode);
        user.setPhoneNumber(normalizedPhoneNumber);
        user.setNfcTagId(normalizedNfcTagId);
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
        // Checked first: it is the only one of these guards whose message is about the system
        // as a whole rather than about this row, and the only one with no way back.
        if (isLastActiveAdministrator(id)) {
            throw new IllegalStateException("This is the last active administrator and cannot be deleted.");
        }
        if (unitSupervisorRepository.existsByUserId(id) || unitOperatorRepository.existsByUserId(id)) {
            throw new IllegalStateException("This user is assigned to operational units and cannot be deleted.");
        }
        if (hasAppActivity(id)) {
            throw new IllegalStateException(
                    "This user has performed actions in the app and cannot be deleted. Deactivate the user instead.");
        }
        // Before the row goes: the sessions outlive it otherwise. api_sessions rows survive
        // deliberately (they are login history) and their user_id would then point at nothing,
        // but a *live* one would still authenticate — the filter checks the row, not the user.
        User user = userRepository.findById(id).orElse(null);
        String username = user != null ? user.getUsername() : null;
        closeAllSessions(id, username, ApiSessionRevokeReason.USER_DELETED);
        // By id: a user renamed at some earlier point still has their old name in the session
        // registry, so a name lookup here would miss the very session being deleted.
        webSessionService.expireByUserId(id, SecurityUtils.currentUserId());

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
