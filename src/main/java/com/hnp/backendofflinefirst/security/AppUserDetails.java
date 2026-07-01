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

    public String getUserId() {
        return user.getId();
    }

    public boolean hasRole(String roleCode) {
        return roleCodes.contains(roleCode);
    }

    public boolean hasPermission(String permissionCode) {
        return authorities.stream().anyMatch(a -> a.getAuthority().equals(permissionCode));
    }

    /** USER role without ADMIN/HIGH_USER — log sheets filtered by operational unit subtree. */
    public boolean isUnitScopedOnly() {
        return !hasRole("ADMIN") && !hasRole("HIGH_USER");
    }
}
