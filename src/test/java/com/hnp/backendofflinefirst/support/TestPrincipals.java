package com.hnp.backendofflinefirst.support;

import com.hnp.backendofflinefirst.entity.User;
import com.hnp.backendofflinefirst.security.AppUserDetails;
import com.hnp.backendofflinefirst.security.SystemRoleCapabilities;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Builds an {@link AppUserDetails} the way logging in would.
 *
 * <h2>Why tests cannot just call the constructor any more</h2>
 * Access used to be decided from the role <em>code</em> — {@code isAdmin()},
 * {@code hasRole("HIGH_USER")} — so a test could say "this user is an ADMIN" by passing
 * {@code Set.of("ADMIN")} and nothing else, and every rule would fall into place. Those rules
 * are now capabilities carried in {@code role_permissions}, which is what makes a duplicated
 * role behave like its original.
 *
 * <p>A hand-built principal touches no database, so it receives no capabilities, and every one
 * of those checks silently evaluates <b>false</b>. That does not make a test fail loudly — it
 * makes it assert the opposite of production while still passing wherever the expectation was
 * "denied". This helper closes that gap by granting exactly what the V3 seed grants the named
 * roles.
 *
 * <p>Extra authorities are merged on top, so a test can still hand-pick a capability, or
 * deliberately withhold one by naming a role that does not carry it — which is how the
 * "a duplicate of ADMIN is not an admin" cases are written.
 */
public final class TestPrincipals {

    private TestPrincipals() {}

    /** Mirrors the {@link AppUserDetails} constructor, plus the capabilities of those roles. */
    public static AppUserDetails of(User user, Set<String> roleCodes, Collection<String> authorities) {
        Set<String> granted = new LinkedHashSet<>(SystemRoleCapabilities.forRoles(roleCodes));
        if (authorities != null) {
            granted.addAll(authorities);
        }
        return new AppUserDetails(user, roleCodes, granted);
    }

    /** For the callers that pass {@code GrantedAuthority} objects rather than strings. */
    public static AppUserDetails ofAuthorities(User user, Set<String> roleCodes,
                                               Collection<? extends GrantedAuthority> authorities) {
        Set<String> codes = new LinkedHashSet<>();
        if (authorities != null) {
            authorities.forEach(a -> codes.add(a.getAuthority()));
        }
        return of(user, roleCodes, codes);
    }

    /** A principal holding a capability its roles would not normally grant. */
    public static AppUserDetails withExtraCapability(User user, Set<String> roleCodes, String capability) {
        return of(user, roleCodes, Set.of(capability));
    }

    /**
     * A principal with the roles named but <b>no</b> capabilities at all — what a role
     * duplicated from a system role looks like before anything is granted to the copy.
     */
    public static AppUserDetails withoutCapabilities(User user, Set<String> roleCodes) {
        return new AppUserDetails(user, roleCodes, Set.of());
    }

    /** Convenience for the common "just make me a granted authority" case. */
    public static SimpleGrantedAuthority authority(String code) {
        return new SimpleGrantedAuthority(code);
    }
}
