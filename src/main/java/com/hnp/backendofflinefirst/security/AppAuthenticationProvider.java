package com.hnp.backendofflinefirst.security;

import com.hnp.backendofflinefirst.entity.User;
import com.hnp.backendofflinefirst.entity.UserAuthType;
import com.hnp.backendofflinefirst.security.LdapAuthenticationService.BindResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
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
 *
 * <h2>The directory being down is not a wrong password</h2>
 *
 * <p>A HYBRID account's local password is checked <b>first</b>, so a correct one never reaches
 * the directory at all — that is what keeps administrators signed in through an outage, and it
 * is load-bearing rather than an optimisation. When the directory does have to be asked and
 * cannot be, the answer is an {@link AuthenticationServiceException} carrying
 * {@link #DIRECTORY_UNAVAILABLE}, not {@code BadCredentialsException}: the operator is told to
 * use a local password or come back later, and an ACTIVE_DIRECTORY account is not charged a
 * failed attempt for a password nobody tested.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AppAuthenticationProvider extends AbstractUserDetailsAuthenticationProvider {

    /**
     * The message on the exception raised when Active Directory could not be asked. Matched by
     * {@code LoginController} and translated by {@code ErrorTranslator}; the login page shows it
     * because, unlike a disabled or unknown account, it reveals nothing worth hiding.
     */
    public static final String DIRECTORY_UNAVAILABLE = "Active Directory is unreachable";

    private final UserDetailsService userDetailsService;
    private final PasswordEncoder passwordEncoder;
    private final LdapAuthenticationService ldapAuthenticationService;
    private final LoginAttemptService loginAttemptService;

    /** What checking a password found out, and whether a local password was among what was tried. */
    private enum Verdict {
        ACCEPTED,
        REJECTED,
        /** The directory could not be asked and nothing else was tested. */
        DIRECTORY_UNAVAILABLE,
        /** The local password was wrong and then the directory could not be asked. */
        DIRECTORY_UNAVAILABLE_AFTER_LOCAL_MISMATCH
    }

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

        // Checked before any password/LDAP verification so a locked username never reaches
        // the domain controller — that's the actual attack this throttle closes (see
        // LoginAttemptService).
        long remainingLockSeconds = loginAttemptService.remainingLockSeconds(user.getUsername());
        if (remainingLockSeconds > 0) {
            throw new LockedException(lockedMessage(remainingLockSeconds));
        }

        String rawPassword = authentication.getCredentials() != null
                ? authentication.getCredentials().toString()
                : "";

        switch (verifyPassword(user, rawPassword)) {
            case ACCEPTED -> loginAttemptService.recordSuccess(user.getUsername());
            case REJECTED -> {
                loginAttemptService.recordFailure(user.getUsername());
                throw new BadCredentialsException("Bad credentials");
            }
            case DIRECTORY_UNAVAILABLE ->
                // No password was tested, so nothing is counted against the throttle: an
                // outage must not turn every attempt during it into a step towards a lockout.
                    throw new AuthenticationServiceException(DIRECTORY_UNAVAILABLE);
            case DIRECTORY_UNAVAILABLE_AFTER_LOCAL_MISMATCH -> {
                // The local hash WAS tested and refused, which an attacker could be probing
                // through the outage — so that attempt counts exactly as it always did.
                loginAttemptService.recordFailure(user.getUsername());
                throw new AuthenticationServiceException(DIRECTORY_UNAVAILABLE);
            }
        }
    }

    private static String lockedMessage(long remainingSeconds) {
        long minutes = Math.max(1, (remainingSeconds + 59) / 60);
        return "Too many failed login attempts. Try again in " + minutes + " minute(s).";
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

    private Verdict verifyPassword(User user, String rawPassword) {
        UserAuthType authType = user.getAuthType() != null ? user.getAuthType() : UserAuthType.LOCAL;
        return switch (authType) {
            case LOCAL -> matchesLocalPassword(user, rawPassword) ? Verdict.ACCEPTED : Verdict.REJECTED;
            case ACTIVE_DIRECTORY -> switch (bind(user, rawPassword)) {
                case AUTHENTICATED -> Verdict.ACCEPTED;
                case REJECTED -> Verdict.REJECTED;
                case UNAVAILABLE -> Verdict.DIRECTORY_UNAVAILABLE;
            };
            case HYBRID -> {
                // Local first, and the directory is not contacted at all when it matches.
                if (matchesLocalPassword(user, rawPassword)) {
                    yield Verdict.ACCEPTED;
                }
                yield switch (bind(user, rawPassword)) {
                    case AUTHENTICATED -> Verdict.ACCEPTED;
                    case REJECTED -> Verdict.REJECTED;
                    case UNAVAILABLE -> Verdict.DIRECTORY_UNAVAILABLE_AFTER_LOCAL_MISMATCH;
                };
            }
        };
    }

    /**
     * The local hash, if there is one. An account created as ACTIVE_DIRECTORY carries a random
     * placeholder hash, which simply never matches; a row with no hash at all must not reach
     * the encoder, whose answer to a null is encoder-specific.
     */
    private boolean matchesLocalPassword(User user, String rawPassword) {
        String hash = user.getPasswordHash();
        if (hash == null || hash.isBlank()) {
            return false;
        }
        return passwordEncoder.matches(rawPassword, hash);
    }

    /**
     * The directory's verdict, with one more net under it: the service already turns every
     * failure into a verdict, but should anything still escape, it is treated as the directory
     * being unavailable rather than becoming a 500 — and it can never touch a local password,
     * which was tested before this was called.
     */
    private BindResult bind(User user, String rawPassword) {
        try {
            return ldapAuthenticationService.bind(user.getUsername(), rawPassword);
        } catch (RuntimeException e) {
            log.error("Active Directory bind threw for {}; treating the directory as unavailable",
                    user.getUsername(), e);
            return BindResult.UNAVAILABLE;
        }
    }
}
