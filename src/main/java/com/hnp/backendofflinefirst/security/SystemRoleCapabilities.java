package com.hnp.backendofflinefirst.security;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Which capabilities each <b>system</b> role is seeded with.
 *
 * <h2>This is not where authorization reads from</h2>
 * Access is decided from {@code role_permissions} in the database, never from this map. It
 * exists for two things that need to know the intended shape rather than the live state:
 *
 * <ul>
 *   <li><b>Protecting system roles.</b> Capabilities now live in data, so the Roles page could
 *       in principle strip {@code CAP:SCOPE_PLANT_WIDE} from ADMIN and leave nobody able to see
 *       the plant. Previously that was impossible because ADMIN's power was compiled in.
 *       {@code RoleService} uses this map to refuse such an edit.</li>
 *   <li><b>Test fixtures.</b> {@code @WithAppUser(roles = "ADMIN")} builds a principal without
 *       touching the database, so it has to know what an ADMIN is supposed to hold. Without
 *       this, every capability check in every test would silently evaluate false and the suite
 *       would go green while asserting the opposite of production behaviour.</li>
 * </ul>
 *
 * <h2>Keep it in step with V3</h2>
 * The migration is the source of truth for a real database; this map must agree with it.
 * {@code CapabilitySeedIntegrationTest} compares the two against a live schema and fails if
 * they drift, which is the only thing that makes it safe to have the information twice.
 */
public final class SystemRoleCapabilities {

    private SystemRoleCapabilities() {}

    public static final String ADMIN = "ADMIN";
    public static final String HIGH_USER = "HIGH_USER";
    public static final String SUPERVISOR = "SUPERVISOR";
    public static final String SENIOR_OPERATOR = "SENIOR_OPERATOR";
    public static final String OPERATOR = "OPERATOR";

    private static final Map<String, Set<String>> BY_ROLE = Map.of(
            // Everything. This is what SecurityUtils.isAdmin() used to stand for.
            ADMIN, Set.copyOf(Capabilities.ALL),

            // Plant-wide sight and template writing — but not across units it does not
            // supervise, and none of the admin-only overrides.
            HIGH_USER, Set.of(
                    Capabilities.SCOPE_PLANT_WIDE,
                    Capabilities.TEMPLATE_MANAGE,
                    Capabilities.TEMPLATE_VIEW_SUPERVISED,
                    Capabilities.ASSET_STATUS_DECIDE),

            // Everything else a supervisor does flows from isSupervisorOf, which is a scope
            // check against real unit assignments and is not a capability.
            SUPERVISOR, Set.of(
                    Capabilities.TEMPLATE_VIEW_SUPERVISED,
                    Capabilities.ASSET_STATUS_DECIDE),

            // The single thing that separates it from OPERATOR.
            SENIOR_OPERATOR, Set.of(Capabilities.LOGSHEET_COMPLETE_WEB_SELF),

            // Every role-code check an operator met used to evaluate false.
            OPERATOR, Set.of());

    /** Capabilities seeded for {@code roleCode}, or empty for a custom/unknown role. */
    public static Set<String> forRole(String roleCode) {
        return BY_ROLE.getOrDefault(roleCode, Set.of());
    }

    /** Union of the capabilities seeded for all of {@code roleCodes}. */
    public static Set<String> forRoles(Iterable<String> roleCodes) {
        java.util.Set<String> all = new java.util.LinkedHashSet<>();
        for (String code : roleCodes) {
            all.addAll(forRole(code));
        }
        return all;
    }

    /** The role codes this map knows about — the five system roles. */
    public static List<String> systemRoles() {
        return List.of(ADMIN, HIGH_USER, SUPERVISOR, SENIOR_OPERATOR, OPERATOR);
    }
}
