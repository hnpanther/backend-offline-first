package com.hnp.backendofflinefirst.integration;

import com.hnp.backendofflinefirst.entity.Role;
import com.hnp.backendofflinefirst.entity.User;
import com.hnp.backendofflinefirst.entity.UserAuthType;
import com.hnp.backendofflinefirst.service.RoleService;
import com.hnp.backendofflinefirst.service.UserService;
import com.hnp.backendofflinefirst.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Two locks that exist to stop an administrator making the system unadministrable.
 *
 * <h2>System roles are immutable</h2>
 * The five seeded roles are the reference the migrations, {@code SystemRoleCapabilities} and the
 * documentation all describe. Editing one makes those three disagree silently, and since
 * capabilities became data rather than compiled-in role checks, unticking one from ADMIN would
 * leave nobody able to see the plant — with no way back through the page that did it.
 * Customising goes through duplication, which produces a genuinely equivalent role.
 *
 * <h2>The last active administrator is protected</h2>
 * Deleting them, deactivating them, or taking the ADMIN role away all reach the same dead end:
 * nobody can administer users or roles, and the only repair is editing the database by hand.
 * The rule is about the last <em>active admin</em>, not about an account named {@code admin} —
 * renaming the bootstrap account or retiring it in favour of another administrator is
 * reasonable and stays allowed.
 */
class SystemRoleAndAdminLockIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired RoleService roleService;
    @Autowired UserService userService;
    @Autowired JdbcTemplate jdbcTemplate;

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM user_roles WHERE user_id IN "
                + "(SELECT id FROM users WHERE username LIKE 'zztest-%')");
        jdbcTemplate.update("DELETE FROM users WHERE username LIKE 'zztest-%'");
        jdbcTemplate.update("DELETE FROM role_permissions WHERE role_id IN "
                + "(SELECT id FROM roles WHERE code LIKE 'ZZLOCK-%')");
        jdbcTemplate.update("DELETE FROM roles WHERE code LIKE 'ZZLOCK-%'");
    }

    // ── System roles ──────────────────────────────────────────────────────────

    @Test
    void noSystemRoleCanBeEdited() {
        for (String code : List.of("ADMIN", "HIGH_USER", "SUPERVISOR", "SENIOR_OPERATOR", "OPERATOR")) {
            Long roleId = roleIdOf(code);
            List<Long> currentPermissions = roleService.getPermissionIdsForRole(roleId);

            assertThatThrownBy(() -> roleService.updateRole(roleId, "تغییر نام", "x", currentPermissions))
                    .as("editing system role %s", code)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("System roles cannot be edited");
        }
    }

    @Test
    void notEvenARenameWithTheExactSamePermissionsGoesThrough() {
        Long adminId = roleIdOf("ADMIN");
        String nameBefore = jdbcTemplate.queryForObject(
                "SELECT name FROM roles WHERE id = ?", String.class, adminId);

        assertThatThrownBy(() -> roleService.updateRole(
                adminId, "مدیر ارشد", null, roleService.getPermissionIdsForRole(adminId)))
                .isInstanceOf(IllegalStateException.class);

        assertThat(jdbcTemplate.queryForObject("SELECT name FROM roles WHERE id = ?", String.class, adminId))
                .isEqualTo(nameBefore);
    }

    @Test
    void noSystemRoleCanBeDeleted() {
        for (String code : List.of("ADMIN", "HIGH_USER", "SUPERVISOR", "SENIOR_OPERATOR", "OPERATOR")) {
            Long roleId = roleIdOf(code);
            assertThatThrownBy(() -> roleService.deleteRole(roleId))
                    .as("deleting system role %s", code)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("System roles cannot be deleted");
        }
    }

    @Test
    void aCopyOfASystemRoleIsFullyEditableAndDeletable() {
        // The escape hatch that makes the lock reasonable rather than merely restrictive.
        Role copy = roleService.duplicateRole(roleIdOf("SUPERVISOR"), "ZZLOCK-SUP", "کپی سرپرست", null);

        assertThatCode(() -> roleService.updateRole(
                copy.getId(), "کپی ویرایش‌شده", "توضیح", roleService.getPermissionIdsForRole(copy.getId())))
                .doesNotThrowAnyException();
        assertThatCode(() -> roleService.deleteRole(copy.getId())).doesNotThrowAnyException();
    }

    // ── The last active administrator ─────────────────────────────────────────

    @Test
    void theLastActiveAdministratorCannotBeDeleted() {
        Long adminUserId = soleActiveAdminId();

        assertThat(userService.isLastActiveAdministrator(adminUserId)).isTrue();
        assertThatThrownBy(() -> userService.delete(adminUserId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("last active administrator");
    }

    @Test
    void theLastActiveAdministratorCannotBeDeactivated() {
        Long adminUserId = soleActiveAdminId();
        List<Long> adminRoles = roleService.getRoleIdsForUser(adminUserId);

        assertThatThrownBy(() -> userService.update(adminUserId, "admin", "مدیر", "ADMIN", null,
                null, null, null, UserAuthType.LOCAL, false, adminRoles))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("last active administrator");

        assertThat(jdbcTemplate.queryForObject(
                "SELECT active FROM users WHERE id = ?", Boolean.class, adminUserId)).isTrue();
    }

    @Test
    void theAdminRoleCannotBeTakenFromTheLastAdministrator() {
        // The third route to the same lockout, and the least obvious one.
        Long adminUserId = soleActiveAdminId();

        assertThatThrownBy(() -> userService.update(adminUserId, "admin", "مدیر", "ADMIN", null,
                null, null, null, UserAuthType.LOCAL, true, List.of(roleIdOf("OPERATOR"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("last active administrator");
    }

    @Test
    void theyCanStillBeEditedAsLongAsTheyStayAnActiveAdmin() {
        Long adminUserId = soleActiveAdminId();

        assertThatCode(() -> userService.update(adminUserId, "admin", "مدیر سامانه", "ADMIN", "شیفت A",
                null, null, null, UserAuthType.LOCAL, true, roleService.getRoleIdsForUser(adminUserId)))
                .doesNotThrowAnyException();
    }

    @Test
    void onceASecondAdministratorExistsTheFirstIsNoLongerLocked() {
        // The rule is about the system having an administrator, not about one sacred account.
        Long firstAdminId = soleActiveAdminId();
        Long secondAdminId = createUser("zztest-admin2", true, roleIdOf("ADMIN"));

        assertThat(userService.isLastActiveAdministrator(firstAdminId)).isFalse();
        assertThatCode(() -> userService.update(firstAdminId, "admin", "مدیر", "ADMIN", null,
                null, null, null, UserAuthType.LOCAL, false, roleService.getRoleIdsForUser(firstAdminId)))
                .doesNotThrowAnyException();

        // Put it back so the rest of the suite sees the usual world.
        userService.update(firstAdminId, "admin", "مدیر", "ADMIN", null, null, null, null,
                UserAuthType.LOCAL, true, roleService.getRoleIdsForUser(firstAdminId));
        assertThat(secondAdminId).isNotNull();
    }

    @Test
    void anInactiveSecondAdministratorDoesNotCount() {
        // An account nobody can log into is not a fallback.
        Long firstAdminId = soleActiveAdminId();
        createUser("zztest-admin-inactive", false, roleIdOf("ADMIN"));

        assertThat(userService.isLastActiveAdministrator(firstAdminId)).isTrue();
    }

    @Test
    void anOrdinaryUserIsNotProtected() {
        Long operatorId = createUser("zztest-plain", true, roleIdOf("OPERATOR"));

        assertThat(userService.isLastActiveAdministrator(operatorId)).isFalse();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private Long roleIdOf(String code) {
        return jdbcTemplate.queryForObject("SELECT id FROM roles WHERE code = ?", Long.class, code);
    }

    private Long soleActiveAdminId() {
        List<Long> admins = jdbcTemplate.queryForList("""
                SELECT u.id FROM users u
                  JOIN user_roles ur ON ur.user_id = u.id
                  JOIN roles r ON r.id = ur.role_id
                 WHERE r.code = 'ADMIN' AND u.active = true
                """, Long.class);
        assertThat(admins).as("fixture expects exactly one active admin").hasSize(1);
        return admins.getFirst();
    }

    private Long createUser(String username, boolean active, Long roleId) {
        User created = userService.create(username, username, "PC-" + username, null,
                null, null, null, "secret123", UserAuthType.LOCAL, active, List.of(roleId));
        return created.getId();
    }
}
