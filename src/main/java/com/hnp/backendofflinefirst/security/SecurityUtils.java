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

    public static String currentUserId() {
        AppUserDetails user = currentUser();
        return user != null ? user.getUserId() : null;
    }

    public static boolean hasPermission(String permission) {
        AppUserDetails user = currentUser();
        return user != null && user.hasPermission(permission);
    }

    public static boolean isUnitScopedOnly() {
        AppUserDetails user = currentUser();
        return user != null && user.isUnitScopedOnly();
    }
}
