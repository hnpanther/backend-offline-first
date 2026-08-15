package com.hnp.backendofflinefirst.security;

import com.hnp.backendofflinefirst.entity.User;
import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * Spring Security principal wrapping {@link User} with resolved role codes and permission authorities.
 */
@Getter
public class AppUserDetails implements UserDetails {

    private final User user;
    private final Set<String> roleCodes;
    private final List<GrantedAuthority> authorities;

    public AppUserDetails(User user, Set<String> roleCodes, Set<String> permissionCodes) {
        this.user = user;
        this.roleCodes = roleCodes;
        this.authorities = permissionCodes.stream()
                .map(SimpleGrantedAuthority::new)
                .map(GrantedAuthority.class::cast)
                .toList();
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return authorities;
    }

    @Override
    public String getPassword() {
        return user.getPasswordHash();
    }

    @Override
    public String getUsername() {
        return user.getUsername();
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return user.isActive();
    }

    public Long getUserId() {
        return user.getId();
    }

    public boolean hasPermission(String permissionCode) {
        return authorities.stream().anyMatch(a -> a.getAuthority().equals(permissionCode));
    }

    /**
     * Whether this user's view is filtered to the units they are assigned to.
     *
     * <p>This used to be {@code !hasRole("ADMIN") && !hasRole("HIGH_USER")} — a comparison
     * against role <em>codes</em>, which is why a duplicated ADMIN was silently treated as
     * unit-scoped despite holding every permission. It is now the absence of
     * {@link Capabilities#SCOPE_PLANT_WIDE}, so a copy behaves exactly like its original.
     *
     * <p>There is intentionally no {@code hasRole} on this class any more. Role codes are still
     * carried (see {@link #getRoleCodes()}) because the mobile login response reports them and
     * the UI displays them — but nothing decides access from them.
     */
    public boolean isUnitScopedOnly() {
        return !hasPermission(Capabilities.SCOPE_PLANT_WIDE);
    }

    /**
     * Identity by username — required for Spring Security's concurrent-session control
     * ({@code maximumSessions}): two logins of the same user must compare equal in the
     * {@code SessionRegistry}, otherwise the one-session-per-user limit never triggers.
     */
    @Override
    public boolean equals(Object obj) {
        return obj instanceof AppUserDetails other
                && java.util.Objects.equals(getUsername(), other.getUsername());
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hashCode(getUsername());
    }
}
