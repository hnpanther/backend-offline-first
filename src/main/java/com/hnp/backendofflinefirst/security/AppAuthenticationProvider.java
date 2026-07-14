package com.hnp.backendofflinefirst.security;

import com.hnp.backendofflinefirst.entity.User;
import com.hnp.backendofflinefirst.entity.UserAuthType;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.dao.AbstractUserDetailsAuthenticationProvider;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Authenticates against the application DB (user must exist) using per-user {@link UserAuthType}:
 * LOCAL — BCrypt only; ACTIVE_DIRECTORY — LDAP bind only; HYBRID — local first, then AD.
 */
@Component
@RequiredArgsConstructor
public class AppAuthenticationProvider extends AbstractUserDetailsAuthenticationProvider {

    private final UserDetailsService userDetailsService;
    private final PasswordEncoder passwordEncoder;
    private final LdapAuthenticationService ldapAuthenticationService;

    @Override
    protected void additionalAuthenticationChecks(UserDetails userDetails,
                                                  UsernamePasswordAuthenticationToken authentication)
            throws AuthenticationException {
        if (!(userDetails instanceof AppUserDetails appUser)) {
            throw new BadCredentialsException("Bad credentials");
        }
        User user = appUser.getUser();
        if (!user.isActive()) {
            throw new DisabledException("User account is disabled");
        }

        String rawPassword = authentication.getCredentials() != null
                ? authentication.getCredentials().toString()
                : "";

        if (!verifyPassword(user, rawPassword)) {
            throw new BadCredentialsException("Bad credentials");
        }
    }

    @Override
    protected UserDetails retrieveUser(String username, UsernamePasswordAuthenticationToken authentication)
            throws AuthenticationException {
        return userDetailsService.loadUserByUsername(normalizeUsername(username));
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return UsernamePasswordAuthenticationToken.class.isAssignableFrom(authentication);
    }

    static String normalizeUsername(String username) {
        if (username == null) {
            return "";
        }
        String trimmed = username.trim();
        int at = trimmed.indexOf('@');
        if (at > 0) {
            return trimmed.substring(0, at);
        }
        return trimmed;
    }

    private boolean verifyPassword(User user, String rawPassword) {
        UserAuthType authType = user.getAuthType() != null ? user.getAuthType() : UserAuthType.LOCAL;
        return switch (authType) {
            case LOCAL -> passwordEncoder.matches(rawPassword, user.getPasswordHash());
            case ACTIVE_DIRECTORY -> ldapAuthenticationService.authenticate(user.getUsername(), rawPassword);
            case HYBRID -> passwordEncoder.matches(rawPassword, user.getPasswordHash())
                    || ldapAuthenticationService.authenticate(user.getUsername(), rawPassword);
        };
    }
}
