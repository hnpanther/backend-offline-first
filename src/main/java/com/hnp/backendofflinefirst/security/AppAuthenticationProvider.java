package com.hnp.backendofflinefirst.security;

import com.hnp.backendofflinefirst.entity.User;
import com.hnp.backendofflinefirst.entity.UserAuthType;
import com.hnp.backendofflinefirst.security.LdapAuthenticationService.BindResult;
import jakarta.annotation.PostConstruct;
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
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.UUID;

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

    /**
     * A real hash of a value nobody will ever present, used to make a login for a username that
     * does not exist cost what a login for one that does costs.
     *
     * <p>Encoded through the configured {@link PasswordEncoder} rather than written here as a
     * literal, so it automatically carries whatever cost the encoder is configured with — a
     * literal would stop matching the moment somebody raised BCrypt's strength or swapped the
     * encoder, and the equaliser would quietly stop equalising.
     *
     * <p>Random per boot. Nothing needs to reproduce it: the comparison's <em>result</em> is
     * never used to decide anything, only its <em>duration</em> matters.
     */
    private String absentUserHash;

    @PostConstruct
    void init() {
        absentUserHash = passwordEncoder.encode(UUID.randomUUID().toString());
        log.info("Login timing equaliser armed: a username nobody holds is still compared against "
                + "a dummy hash, so the response time does not disclose that the account is absent "
                + "(docs/roadmap.md §18h).");
    }

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
            // Defensive duplicate: Spring's own preAuthenticationChecks reads
            // AppUserDetails.isEnabled() (which is user.isActive()) and throws this before we are
            // called, which is why the timing equaliser for a deactivated account lives in
            // retrieveUser rather than here.
            throw new DisabledException("User account is disabled");
        }

        // Checked before any password/LDAP verification so a locked username never reaches
        // the domain controller — that's the actual attack this throttle closes (see
        // LoginAttemptService).
        long remainingLockSeconds = loginAttemptService.remainingLockSeconds(user.getUsername());
        if (remainingLockSeconds > 0) {
            throw new LockedException(lockedMessage(remainingLockSeconds));
        }

        String rawPassword = rawCredentials(authentication);

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

    /**
     * Resolves the account, and makes a username nobody holds cost what one that exists costs.
     *
     * <p>Without the comparison below, the two answers are identical in body and status code and
     * differ only in duration: an existing account pays BCrypt (tens to hundreds of milliseconds)
     * and an absent one returns as fast as a {@code SELECT} by unique index. That difference is a
     * username oracle, and it matters here more than it would elsewhere, because
     * {@link LoginAttemptService} locks <b>by username</b> — a caller who can list the real
     * usernames can lock out every operator on a shift with five requests each, without guessing
     * a single password. Nothing throttles how often this endpoint may be asked
     * ({@code docs/roadmap.md} §2), so the oracle can be consulted as fast as the network allows.
     *
     * <p>One disclosure is left in place deliberately: a locked account is told how long it has
     * left to wait, which is information the person at the keyboard needs and which a generic
     * message cannot carry.
     */
    @Override
    protected UserDetails retrieveUser(String username, UsernamePasswordAuthenticationToken authentication)
            throws AuthenticationException {
        try {
            UserDetails details = userDetailsService.loadUserByUsername(normalizeUsername(username));
            if (!details.isEnabled()) {
                // A deactivated account is refused by Spring's preAuthenticationChecks, before any
                // hash is computed, and the login page gives it the same generic message as a
                // wrong password. Priced here, where the presented password is still in hand, so
                // that the clock does not become the one thing that says the username is real.
                compareAgainstDummyHash(rawCredentials(authentication));
            }
            return details;
        } catch (UsernameNotFoundException notFound) {
            compareAgainstDummyHash(rawCredentials(authentication));
            log.info("No account named {} — the presented password was still compared against this "
                    + "instance's dummy hash, so this refusal costs what a wrong password costs.",
                    forLog(username));
            throw notFound;
        }
    }

    /**
     * Spends what a password check spends, and decides nothing.
     *
     * <p>The result is read rather than discarded on purpose: a comparison whose outcome is
     * unused is a comparison a compiler is entitled to remove, and removing it would silently
     * restore the timing difference this method exists to erase.
     */
    private void compareAgainstDummyHash(String presented) {
        if (absentUserHash == null) {
            // A provider built with `new` rather than by Spring — a plain unit test — never got
            // @PostConstruct. Build the hash now rather than skip the comparison, so a test
            // exercises the same code path production does.
            init();
        }
        if (passwordEncoder.matches(presented, absentUserHash)) {
            // Unreachable: absentUserHash is a random UUID encoded at startup and never shown to
            // anybody. Logged rather than ignored, because "unreachable" is a claim, and because
            // this is also what reads the result and so keeps the comparison from being elided.
            log.error("The login timing dummy hash matched a presented password. That should be "
                    + "impossible; treat this instance's password encoder as suspect.");
        }
    }

    /** The submitted password, or an empty string — never null, so the comparison always runs. */
    private static String rawCredentials(UsernamePasswordAuthenticationToken authentication) {
        Object credentials = authentication != null ? authentication.getCredentials() : null;
        return credentials != null ? credentials.toString() : "";
    }

    /**
     * A username on its way into a log line.
     *
     * <p>It arrives from an unauthenticated form field and has been validated by nothing, so it
     * is stripped of control characters and capped: a value carrying a newline would otherwise
     * write a second line into the log, in this application's own format, saying whatever the
     * caller chose.
     */
    static String forLog(String username) {
        if (username == null || username.isBlank()) {
            return "<blank>";
        }
        String cleaned = username.strip().replaceAll("\\p{Cntrl}", "?");
        return cleaned.length() <= 64 ? cleaned : cleaned.substring(0, 64) + "…";
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
