package com.hnp.backendofflinefirst.integration;

import com.hnp.backendofflinefirst.entity.Role;
import com.hnp.backendofflinefirst.entity.User;
import com.hnp.backendofflinefirst.security.AppUserDetails;
import com.hnp.backendofflinefirst.security.Capabilities;
import com.hnp.backendofflinefirst.security.SecurityUtils;
import com.hnp.backendofflinefirst.service.RoleService;
import com.hnp.backendofflinefirst.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The point of the whole capability model: <b>a duplicated role behaves like its original.</b>
 *
 * <p>Before this, "ساخت نقش مشابه" copied a role's permissions and gave the copy a new code,
 * while the rules that mattered most compared the <em>code</em> —
 * {@code isAdmin()}, {@code hasRole("HIGH_USER")}, {@code isUnitScopedOnly()}. A duplicate of
 * ADMIN therefore held all 123 permissions and still could not see outside its own units,
 * could not view another user's import job, and could not complete a sheet it was not assigned.
 * It produced bug reports of the form "this role has the permission and still gets access
 * denied", and there was no way to fix it from the Roles page.
 *
 * <p>These tests copy a real system role through the real service and then assert the copy is
 * indistinguishable from the original where it matters.
 */
class DuplicatedRoleCapabilityIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired RoleService roleService;
    @Autowired JdbcTemplate jdbcTemplate;

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
        jdbcTemplate.update("DELETE FROM role_permissions WHERE role_id IN "
                + "(SELECT id FROM roles WHERE code LIKE 'ZZCOPY-%')");
        jdbcTemplate.update("DELETE FROM roles WHERE code LIKE 'ZZCOPY-%'");
    }

    @Test
    void aCopyOfAdminInheritsEveryCapability() {
        Role copy = duplicate("ADMIN");

        assertThat(capabilityCodesOf(copy))
                .as("a copy of ADMIN must hold everything ADMIN holds")
                .containsExactlyInAnyOrderElementsOf(Capabilities.ALL);
    }

    @Test
    void aCopyOfAdminIsPlantWideAndNotUnitScoped() {
        authenticateAs(duplicate("ADMIN"));

        // This is the exact regression: isUnitScopedOnly() used to be !ADMIN && !HIGH_USER, so
        // the copy — with a code of its own — was silently confined to its own units.
        assertThat(SecurityUtils.isUnitScopedOnly()).isFalse();
        assertThat(SecurityUtils.hasCapability(Capabilities.SCOPE_PLANT_WIDE)).isTrue();
    }

    @Test
    void aCopyOfAdminCanDoTheAdminOnlyThings() {
        authenticateAs(duplicate("ADMIN"));

        assertThat(SecurityUtils.hasCapability(Capabilities.IMPORT_JOB_VIEW_ALL)).isTrue();
        assertThat(SecurityUtils.hasCapability(Capabilities.LOGSHEET_COMPLETE_WEB_ANY)).isTrue();
        assertThat(SecurityUtils.hasCapability(Capabilities.SUPERVISE_ANY_UNIT)).isTrue();
        assertThat(SecurityUtils.hasCapability(Capabilities.NFC_FAULT_REVIEW)).isTrue();
        assertThat(SecurityUtils.hasCapability(Capabilities.TEMPLATE_MANAGE_ANY_UNIT)).isTrue();
    }

    @Test
    void aCopyOfHighUserInheritsExactlyHighUsersCapabilities() {
        Role copy = duplicate("HIGH_USER");

        // Including the asymmetry: plant-wide sight, template writing, but NOT across units it
        // does not supervise.
        assertThat(capabilityCodesOf(copy))
                .containsExactlyInAnyOrder(
                        Capabilities.SCOPE_PLANT_WIDE,
                        Capabilities.TEMPLATE_MANAGE,
                        Capabilities.TEMPLATE_VIEW_SUPERVISED,
                        Capabilities.ASSET_STATUS_DECIDE);
    }

    @Test
    void aCopyOfSeniorOperatorInheritsWebCompletion() {
        authenticateAs(duplicate("SENIOR_OPERATOR"));

        assertThat(SecurityUtils.hasCapability(Capabilities.LOGSHEET_COMPLETE_WEB_SELF)).isTrue();
        // …and nothing more. Web completion of somebody else's sheet stays out of reach.
        assertThat(SecurityUtils.hasCapability(Capabilities.LOGSHEET_COMPLETE_WEB_ANY)).isFalse();
        assertThat(SecurityUtils.isUnitScopedOnly()).isTrue();
    }

    @Test
    void aCopyOfOperatorIsStillRestricted() {
        authenticateAs(duplicate("OPERATOR"));

        // Copying does not launder privilege upward: an operator's copy is an operator.
        assertThat(SecurityUtils.isUnitScopedOnly()).isTrue();
        assertThat(SecurityUtils.hasCapability(Capabilities.SCOPE_PLANT_WIDE)).isFalse();
        assertThat(SecurityUtils.hasCapability(Capabilities.SUPERVISE_ANY_UNIT)).isFalse();
    }

    @Test
    void theCopyIsNotItselfASystemRole() {
        // Unchanged rule, re-pinned here because the copy now carries real power.
        assertThat(duplicate("ADMIN").isSystemRole()).isFalse();
    }

    @Test
    void aRoleWithNoCapabilitiesIsUnitScopedEvenIfItHoldsEveryEndpoint() {
        // Fail-safe direction: absence of CAP:SCOPE_PLANT_WIDE means restricted, so a custom
        // role built by ticking every endpoint box does not accidentally become plant-wide.
        List<String> everyEndpoint = jdbcTemplate.queryForList(
                "SELECT code FROM permissions WHERE category <> ?", String.class, Capabilities.CATEGORY);
        authenticate(everyEndpoint);

        assertThat(SecurityUtils.isUnitScopedOnly()).isTrue();
    }

    @Test
    void systemRoleCapabilitiesCannotBeStrippedThroughTheRolesPage() {
        Long adminRoleId = jdbcTemplate.queryForObject(
                "SELECT id FROM roles WHERE code = 'ADMIN'", Long.class);
        // Everything except the capabilities — i.e. somebody unticking the capability section.
        List<Long> withoutCapabilities = jdbcTemplate.queryForList(
                "SELECT id FROM permissions WHERE category <> ?", Long.class, Capabilities.CATEGORY);

        // System roles are now closed to editing outright, so this is refused before the
        // capability set is even inspected. The narrower "capabilities cannot be removed" rule
        // it replaced is subsumed: see SystemRoleAndAdminLockIntegrationTest.
        assertThatThrownBy(() -> roleService.updateRole(adminRoleId, "مدیر سیستم", null, withoutCapabilities))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("System roles cannot be edited");

        assertThat(capabilityCodesOf(adminRoleId))
                .containsExactlyInAnyOrderElementsOf(Capabilities.ALL);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private Role duplicate(String sourceRoleCode) {
        Long sourceId = jdbcTemplate.queryForObject(
                "SELECT id FROM roles WHERE code = ?", Long.class, sourceRoleCode);
        return roleService.duplicateRole(sourceId, "ZZCOPY-" + sourceRoleCode,
                "کپی " + sourceRoleCode, null);
    }

    private Set<String> capabilityCodesOf(Role role) {
        return capabilityCodesOf(role.getId());
    }

    private Set<String> capabilityCodesOf(Long roleId) {
        return Set.copyOf(jdbcTemplate.queryForList("""
                SELECT p.code FROM role_permissions rp
                  JOIN permissions p ON p.id = rp.permission_id
                 WHERE rp.role_id = ? AND p.category = ?
                """, String.class, roleId, Capabilities.CATEGORY));
    }

    /** Signs in as a holder of {@code role}'s permissions — with the copy's own code. */
    private void authenticateAs(Role role) {
        List<String> codes = jdbcTemplate.queryForList("""
                SELECT p.code FROM role_permissions rp
                  JOIN permissions p ON p.id = rp.permission_id
                 WHERE rp.role_id = ?
                """, String.class, role.getId());
        authenticate(codes, role.getCode());
    }

    private void authenticate(List<String> authorities) {
        authenticate(authorities, "ZZCOPY-CUSTOM");
    }

    private void authenticate(List<String> authorities, String roleCode) {
        User user = new User();
        user.setId(999L);
        user.setUsername("copy-holder");
        user.setPersonnelCode("PC-COPY");
        user.setActive(true);
        AppUserDetails principal = new AppUserDetails(user, Set.of(roleCode), Set.copyOf(authorities));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null,
                        authorities.stream().map(SimpleGrantedAuthority::new).collect(Collectors.toList())));
    }
}
