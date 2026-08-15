package com.hnp.backendofflinefirst.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/** Helpers to read the authenticated user and permission state from the security context. */
public final class SecurityUtils {

    private SecurityUtils() {}

    public static AppUserDetails currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AppUserDetails details) {
            return details;
        }
        return null;
    }

    public static Long currentUserId() {
        AppUserDetails user = currentUser();
        return user != null ? user.getUserId() : null;
    }

    public static boolean hasPermission(String permission) {
        AppUserDetails user = currentUser();
        return user != null && user.hasPermission(permission);
    }

    /**
     * Whether the current user holds a capability from {@link Capabilities}.
     *
     * <p>Mechanically identical to {@link #hasPermission} — a capability <em>is</em> a
     * permission row — but kept as its own method so a reader can tell at the call site that
     * the decision is about what someone may do rather than about which route they may call.
     *
     * <p><b>There is deliberately no {@code isAdmin()} or {@code hasRole()} any more.</b> Those
     * compared the role's code, which meant a duplicated role did not inherit the behaviour its
     * copied permissions implied. If you need a new rule of that shape, add a capability — see
     * {@link Capabilities} for why, and note that it must be phrased positively.
     */
    public static boolean hasCapability(String capability) {
        return hasPermission(capability);
    }

    /**
     * True when this user's view must be filtered to the operational units they are assigned to.
     *
     * <p>The inverse of {@link Capabilities#SCOPE_PLANT_WIDE}, and the one place the negation is
     * written, so no call site has to get it the right way round. Absence of the capability
     * means restricted — including for a user with no authentication at all.
     */
    public static boolean isUnitScopedOnly() {
        return !hasCapability(Capabilities.SCOPE_PLANT_WIDE);
    }
}
