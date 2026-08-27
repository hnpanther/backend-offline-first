package com.hnp.backendofflinefirst.integration;

import com.hnp.backendofflinefirst.entity.UserAuthType;
import com.hnp.backendofflinefirst.entity.User;
import com.hnp.backendofflinefirst.service.RoleService;
import com.hnp.backendofflinefirst.service.UserService;
import com.hnp.backendofflinefirst.support.AbstractPostgresIntegrationTest;
import com.hnp.backendofflinefirst.support.WithAppUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The /users list stopped asking two questions per row — "which roles does this user hold" and
 * "would deleting this user leave nobody able to administer the system" — and now asks each once
 * for the page.
 *
 * <p>The second is the one that matters. It decides whether a delete button is rendered, so getting
 * it wrong either hides a usable control or offers one that the server will refuse. It does
 * <em>not</em> decide whether the deletion is allowed: {@code UserService.delete} and
 * {@code setActive} still call the per-row {@code isLastActiveAdministrator} themselves, and that
 * path is untouched. {@link SystemRoleAndAdminLockIntegrationTest} covers the enforcement; what is
 * tested here is that the page agrees with it, so the two can never drift into showing a button
 * that does not work.
 */
class UserListBatchLabelsIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired WebApplicationContext context;
    @Autowired RoleService roleService;
    @Autowired UserService userService;
    @Autowired JdbcTemplate jdbcTemplate;

    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM user_roles WHERE user_id IN "
                + "(SELECT id FROM users WHERE username LIKE 'zzbatch-%')");
        jdbcTemplate.update("DELETE FROM users WHERE username LIKE 'zzbatch-%'");
    }

    // ── the admin guard: batch must equal per-row, in every shape ───────────────────────────

    @Test
    void theBatchAdminCheckAgreesWithThePerRowOneForEveryUserInTheSystem() {
        // The property, asserted over whatever the database actually holds rather than over a
        // hand-picked pair: for every user, both methods must reach the same verdict.
        Long adminRole = roleIdOf("ADMIN");
        Long operatorRole = roleIdOf("OPERATOR");
        createUser("zzbatch-admin-b", true, adminRole);
        createUser("zzbatch-admin-inactive", false, adminRole);
        createUser("zzbatch-operator", true, operatorRole);

        List<Long> everyone = jdbcTemplate.queryForList("SELECT id FROM users", Long.class);
        Set<Long> batch = userService.lastActiveAdministratorIds(everyone);

        for (Long id : everyone) {
            assertThat(batch.contains(id))
                    .as("user %d: batch said %s, per-row said %s",
                            id, batch.contains(id), userService.isLastActiveAdministrator(id))
                    .isEqualTo(userService.isLastActiveAdministrator(id));
        }
    }

    @Test
    void aSecondActiveAdministratorUnlocksTheFirst() {
        Long adminRole = roleIdOf("ADMIN");
        List<Long> adminsBefore = activeAdminIds();
        assertThat(adminsBefore).as("fixture expects exactly one active admin").hasSize(1);
        Long first = adminsBefore.getFirst();

        assertThat(userService.lastActiveAdministratorIds(List.of(first))).contains(first);

        Long second = createUser("zzbatch-admin-b", true, adminRole);

        assertThat(userService.lastActiveAdministratorIds(List.of(first, second)))
                .as("with two active admins, neither is the last one")
                .isEmpty();
    }

    @Test
    void anInactiveSecondAdministratorDoesNotUnlockTheFirst() {
        Long adminRole = roleIdOf("ADMIN");
        Long first = activeAdminIds().getFirst();
        Long inactive = createUser("zzbatch-admin-inactive", false, adminRole);

        Set<Long> last = userService.lastActiveAdministratorIds(List.of(first, inactive));

        assertThat(last).as("the active one is still the only one who can administer").contains(first);
        // And the asymmetry the per-row method already had: an inactive admin with no active
        // admin beside them is reported too. Pinned so a "tidy-up" does not quietly change it.
        assertThat(last.contains(inactive)).isEqualTo(userService.isLastActiveAdministrator(inactive));
    }

    @Test
    void anOrdinaryUserIsNeverReportedAsTheLastAdministrator() {
        Long operator = createUser("zzbatch-operator", true, roleIdOf("OPERATOR"));
        assertThat(userService.lastActiveAdministratorIds(List.of(operator))).isEmpty();
        assertThat(userService.isLastActiveAdministrator(operator)).isFalse();
    }

    @Test
    void anEmptyOrNullPageAsksNothingAndProtectsNobody() {
        assertThat(userService.lastActiveAdministratorIds(List.of())).isEmpty();
        assertThat(userService.lastActiveAdministratorIds(null)).isEmpty();
    }

    @Test
    void aUserIdThatIsNotOnThePageIsNeverReported() {
        // The batch answers only about the ids it was handed; the sole admin is real, but it was
        // not asked about them.
        Long operator = createUser("zzbatch-operator", true, roleIdOf("OPERATOR"));
        assertThat(userService.lastActiveAdministratorIds(List.of(operator)))
                .doesNotContainAnyElementsOf(activeAdminIds());
    }

    // ── the roles column ────────────────────────────────────────────────────────────────────

    @Test
    @WithAppUser(authorities = "GET:/users")
    void theRolesColumnStillNamesEveryRoleAUserHolds() throws Exception {
        Long adminRole = roleIdOf("ADMIN");
        Long operatorRole = roleIdOf("OPERATOR");
        User both = userService.create("zzbatch-two-roles", "zzbatch-two-roles",
                "PC-zzbatch-two-roles", null, null, null, null, null, null,
                "secret123", UserAuthType.LOCAL, true, List.of(adminRole, operatorRole));

        mockMvc.perform(get("/users").param("q", "zzbatch-two-roles").param("size", "250"))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("userRoleLabels", "undeletableUserIds"))
                .andExpect(content().string(containsString("zzbatch-two-roles")));

        var labels = roleService.roleIdsByUserId(List.of(both.getId()));
        assertThat(labels.get(both.getId()))
                .as("both assignments must survive the grouping")
                .containsExactlyInAnyOrder(adminRole, operatorRole);
    }

    @Test
    void theBatchRoleLookupAgreesWithThePerUserOne() {
        Long adminRole = roleIdOf("ADMIN");
        Long operatorRole = roleIdOf("OPERATOR");
        Long a = createUser("zzbatch-role-a", true, operatorRole);
        Long b = createUser("zzbatch-role-b", true, adminRole);

        var batch = roleService.roleIdsByUserId(List.of(a, b));

        for (Long id : List.of(a, b)) {
            assertThat(batch.getOrDefault(id, List.of()))
                    .as("user %d", id)
                    .containsExactlyInAnyOrderElementsOf(roleService.getRoleIdsForUser(id));
        }
    }

    @Test
    void aUserWithNoRolesIsAbsentRatherThanMappedToNull() {
        // The template joins this list; a null would render "null" in the roles column.
        User none = userService.create("zzbatch-no-roles", "zzbatch-no-roles",
                "PC-zzbatch-no-roles", null, null, null, null, null, null,
                "secret123", UserAuthType.LOCAL, true, List.of());

        var batch = roleService.roleIdsByUserId(List.of(none.getId()));

        assertThat(batch.get(none.getId())).isNull();
        assertThat(batch.getOrDefault(none.getId(), List.of())).isEmpty();
        assertThat(roleService.getRoleIdsForUser(none.getId())).isEmpty();
    }

    @Test
    void anEmptyPageOfUsersAsksForNoRolesAtAll() {
        assertThat(roleService.roleIdsByUserId(List.of())).isEmpty();
        assertThat(roleService.roleIdsByUserId(null)).isEmpty();
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────

    private Long roleIdOf(String code) {
        return jdbcTemplate.queryForObject("SELECT id FROM roles WHERE code = ?", Long.class, code);
    }

    private List<Long> activeAdminIds() {
        return jdbcTemplate.queryForList("""
                SELECT u.id FROM users u
                  JOIN user_roles ur ON ur.user_id = u.id
                  JOIN roles r ON r.id = ur.role_id
                 WHERE r.code = 'ADMIN' AND u.active = true
                """, Long.class);
    }

    private Long createUser(String username, boolean active, Long roleId) {
        User created = userService.create(username, username, "PC-" + username, null,
                null, null, null, null, null, "secret123", UserAuthType.LOCAL, active, List.of(roleId));
        return created.getId();
    }
}
