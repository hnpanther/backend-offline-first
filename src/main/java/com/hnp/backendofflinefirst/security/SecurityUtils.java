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

    /**
     * Where "home" is for this user — the first landing page they may actually open.
     *
     * <p>The dashboard is not everyone's home. {@code GET:/} is granted to {@code ADMIN} and
     * {@code HIGH_USER}; the three field roles ({@code SUPERVISOR}, {@code SENIOR_OPERATOR},
     * {@code OPERATOR}) do not hold it. Sending them to {@code /} produces an access-denied
     * message on a link every page shows, which is how the navbar brand behaved: it pointed at
     * {@code /} for everyone, so three of the five system roles were bounced by the one control
     * that is supposed to mean "take me back".
     *
     * <p><b>Decided by permission, not by role or scope.</b> The login handler used to ask
     * {@link #isUnitScopedOnly()}, which is a different question and gets two cases wrong: a
     * custom unit-scoped role that *was* granted {@code GET:/} was sent to its inbox for no
     * reason, and a plant-wide role *without* {@code GET:/} was sent to the dashboard and denied
     * — an access-denied page immediately after a successful login. Asking for the authority
     * itself is the rule that cannot drift from what the page actually requires, and it is what
     * security.md means by keying off permissions rather than role codes.
     *
     * <p>The order below is "most complete view first". Every field role holds
     * {@code GET:/my-inbox}, so the list is exhaustive in practice; the final fallback matters
     * only for a hand-built role granted none of them, and it is better to land on a page that
     * explains itself than on a blank redirect loop.
     */
    public static String homePath() {
        if (hasPermission("GET:/")) return "/";
        if (hasPermission("GET:/my-inbox")) return "/my-inbox";
        if (hasPermission("GET:/log-sheets")) return "/log-sheets";
        return "/my-inbox";
    }
}
