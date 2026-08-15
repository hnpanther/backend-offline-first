package com.hnp.backendofflinefirst.integration;

import com.hnp.backendofflinefirst.security.Capabilities;
import com.hnp.backendofflinefirst.security.SystemRoleCapabilities;
import com.hnp.backendofflinefirst.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The seed in V3 and the map in {@link SystemRoleCapabilities} must say the same thing.
 *
 * <p>The information exists twice on purpose — the database decides access, and the Java map is
 * what protects system roles from being stripped and what lets a hand-built test principal look
 * like a real login. Two copies drift, so this test is the thing that makes keeping them
 * tolerable. If it fails, one of the two was edited alone.
 *
 * <p>It also pins the properties that make a capability distinguishable from an endpoint
 * permission, because the Roles page groups on them and {@code @PreAuthorize} would happily
 * accept a malformed one without complaint.
 */
class CapabilitySeedIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired JdbcTemplate jdbcTemplate;

    @Test
    void everyDeclaredCapabilityIsSeededAsAPermissionRow() {
        List<String> seeded = jdbcTemplate.queryForList(
                "SELECT code FROM permissions WHERE category = ?", String.class, Capabilities.CATEGORY);

        assertThat(seeded).containsExactlyInAnyOrderElementsOf(Capabilities.ALL);
    }

    @Test
    void capabilitiesCarryNoMethodOrPath() {
        // A capability is not a route. Leaving these set would make it show up in the Roles UI
        // as though it guarded an endpoint, and invite someone to @PreAuthorize on it.
        Integer malformed = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM permissions WHERE category = ? "
                        + "AND (http_method IS NOT NULL OR endpoint_path IS NOT NULL)",
                Integer.class, Capabilities.CATEGORY);

        assertThat(malformed).isZero();
    }

    @Test
    void everyCapabilityCodeUsesTheCapPrefix() {
        // The prefix is what stops anything parsing a capability as METHOD:/path.
        assertThat(Capabilities.ALL).allSatisfy(code -> assertThat(Capabilities.isCapability(code)).isTrue());
    }

    @Test
    void theSeededGrantsMatchTheJavaMapForEverySystemRole() {
        for (String role : SystemRoleCapabilities.systemRoles()) {
            Set<String> fromDatabase = Set.copyOf(jdbcTemplate.queryForList("""
                    SELECT p.code
                      FROM roles r
                      JOIN role_permissions rp ON rp.role_id = r.id
                      JOIN permissions p ON p.id = rp.permission_id
                     WHERE r.code = ? AND p.category = ?
                    """, String.class, role, Capabilities.CATEGORY));

            assertThat(fromDatabase)
                    .as("capabilities seeded for %s", role)
                    .isEqualTo(SystemRoleCapabilities.forRole(role));
        }
    }

    @Test
    void adminHoldsEveryCapabilityAndOperatorHoldsNone() {
        // The two ends of the range, stated explicitly: ADMIN is what isAdmin() used to mean,
        // and OPERATOR is what every role-code check used to evaluate to false for.
        assertThat(SystemRoleCapabilities.forRole(SystemRoleCapabilities.ADMIN))
                .containsExactlyInAnyOrderElementsOf(Capabilities.ALL);
        assertThat(SystemRoleCapabilities.forRole(SystemRoleCapabilities.OPERATOR)).isEmpty();
    }

    @Test
    void onlyAdminMayReachAcrossUnitsForTemplatesAndSupervision() {
        // HIGH_USER is plant-wide for *sight* but still confined to units it supervises when it
        // *writes* a template — that asymmetry existed before and must survive the migration.
        assertThat(SystemRoleCapabilities.forRole(SystemRoleCapabilities.HIGH_USER))
                .contains(Capabilities.SCOPE_PLANT_WIDE, Capabilities.TEMPLATE_MANAGE)
                .doesNotContain(Capabilities.TEMPLATE_MANAGE_ANY_UNIT, Capabilities.SUPERVISE_ANY_UNIT);
    }

    @Test
    void aCustomRoleCodeGetsNothingByDefault() {
        // Fail-safe: an unknown role is restricted, never plant-wide.
        assertThat(SystemRoleCapabilities.forRole("SOME_CUSTOM_ROLE")).isEmpty();
    }
}
