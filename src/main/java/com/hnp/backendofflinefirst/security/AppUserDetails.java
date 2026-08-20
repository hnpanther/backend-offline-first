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
     * Identity for Spring Security's concurrent-session control ({@code maximumSessions}): two
     * logins of the same user must compare equal in the {@code SessionRegistry}, or the
     * one-session-per-user limit never triggers.
     *
     * <h2>By user id, not by username — and the difference is not cosmetic</h2>
     *
     * <p>This compared usernames, which made the identity of a session change when the account
     * was renamed. Three consequences, all real:
     *
     * <ul>
     *   <li><b>The one-session limit was bypassable.</b> Rename the account, log in again: the
     *       registry saw a different principal and both browsers stayed live.</li>
     *   <li><b>Sessions could not be found afterwards.</b> The registry holds the principal
     *       captured at login, so an administrator deactivating a renamed user searched under
     *       the new name and matched nothing — the old browser kept working.</li>
     *   <li><b>Two users could be conflated.</b> Free up a username, give it to somebody else,
     *       and their principal compared equal to the first user's live session.</li>
     * </ul>
     *
     * <p>A user id is the one thing that does not change under any of that.
     *
     * <p><b>Falls back to the username when either id is null</b> so nothing regresses for a
     * principal built outside persistence — test fixtures do this, and an unsaved user has no
     * id to compare. Two ids present and equal is the only way to be equal by id; anything else
     * falls through to the previous rule rather than silently deciding two id-less principals
     * are the same person.
     */
    @Override
    public boolean equals(Object obj) {
        return this == obj
                || (obj instanceof AppUserDetails other && identityKey().equals(other.identityKey()));
    }

    @Override
    public int hashCode() {
        return identityKey().hashCode();
    }

    /**
     * The single value {@link #equals} and {@link #hashCode} both derive from.
     *
     * <p>Computing one key rather than branching inside {@code equals} is what keeps the two in
     * agreement. A branching {@code equals} ("by id when both have one, else by name") cannot be
     * hashed consistently — a renamed user's principals are equal by id but hash differently by
     * name, and the only value that survives both branches is a constant, which would turn the
     * registry's map into a linear scan.
     *
     * <p>The {@code id:} / {@code name:} prefixes keep the two namespaces apart, so a user whose
     * id is 5 can never collide with one whose username happens to be {@code "5"}.
     */
    private String identityKey() {
        Long id = getUserId();
        return id != null ? "id:" + id : "name:" + getUsername();
    }
}
