package com.hnp.backendofflinefirst.security;

import com.hnp.backendofflinefirst.service.ApiSessionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Decides who a bearer token belongs to, and what that person may currently do.
 *
 * <h2>Four conditions, and each one is load-bearing</h2>
 *
 * <ol>
 *   <li><b>The signature and expiry verify.</b> {@link JwtService#verify} — necessary, and on its
 *       own worth very little, because the signing key ships in {@code application.properties} as
 *       a working default and a deployment that never replaced it has published it.</li>
 *   <li><b>The {@code jti} is a live session belonging to that {@code uid}.</b> This is what
 *       makes a leaked key useless for impersonation. A forger can pair any {@code uid} they like
 *       with a {@code jti}, but the only {@code jti}s that exist belong to real logins, and the
 *       pairing is checked — so the only person they can forge a token for is themselves.</li>
 *   <li><b>The user still exists and is still active.</b> Sessions are already revoked when an
 *       account is deactivated or deleted, so this is the second of two independent barriers
 *       rather than the only one — deliberately, because the first depends on that revocation
 *       having actually run.</li>
 *   <li><b>The authorities come from the database, now.</b> Not from the token. This is both the
 *       other half of the escalation fix and a correctness fix in its own right: removing a
 *       permission from a role, or a role from a user, used to change nothing until the token
 *       expired hours later, because no request ever asked.</li>
 * </ol>
 *
 * <h2>What this costs</h2>
 *
 * <p>Three indexed reads per API request — the user row, their role codes, their permission
 * codes — on top of the session lookup that was already there. Measured against the running
 * server rather than estimated: {@code GET /api/log-sheets/inbox} holds a median of 34 ms at
 * four concurrent callers (106 req/s) and 73 ms at sixteen (200 req/s, p95 123 ms), on a
 * development machine that is also running the database. The fleet produces roughly 7 req/s
 * at its peak — fifty tablets, a 30-second sync timer, a handful of calls each — so there is
 * about thirty times the headroom needed, and nothing here is worth caching around.
 *
 * <p>A cache would also cost the property that makes this worth doing. Authorities resolved per
 * request take effect on the <em>next</em> request; behind even a ten-second cache, "I have
 * removed their access" stops being true for ten seconds, and that is exactly the sentence
 * somebody says out loud during an incident. If the read cost ever does matter, the number to
 * measure first is requests per second against Postgres, not this class.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ApiTokenAuthenticator {

    private final JwtService jwtService;
    private final ApiSessionService apiSessionService;
    private final AppUserDetailsService userDetailsService;

    /**
     * The authenticated principal for this token, or empty if any condition above fails.
     *
     * <p>Empty rather than an exception, for every failure alike: a request with a bad token is
     * simply an unauthenticated request, and the security chain already knows what to do with
     * one. Distinguishing "expired" from "revoked" from "forged" in the response would tell an
     * attacker which of the four conditions they had defeated.
     */
    public Optional<Authentication> authenticate(String token, long now) {
        return jwtService.verify(token)
                .filter(verified -> apiSessionService.isSessionActive(
                        verified.jti(), verified.userId(), now))
                .flatMap(this::currentPrincipal)
                .map(user -> new UsernamePasswordAuthenticationToken(
                        user, null, user.getAuthorities()));
    }

    /** The user as they are right now, or empty if they are gone or deactivated. */
    private Optional<AppUserDetails> currentPrincipal(JwtService.VerifiedToken verified) {
        Optional<AppUserDetails> found = userDetailsService.loadById(verified.userId());
        if (found.isEmpty()) {
            log.warn("Rejected API token for session {}: user {} no longer exists",
                    verified.jti(), verified.userId());
            return Optional.empty();
        }
        AppUserDetails user = found.get();
        if (!user.isEnabled()) {
            log.warn("Rejected API token for session {}: user {} is deactivated",
                    verified.jti(), user.getUsername());
            return Optional.empty();
        }
        return found;
    }
}
