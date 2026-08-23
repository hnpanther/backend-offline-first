package com.hnp.backendofflinefirst.service;

import com.hnp.backendofflinefirst.domain.ApiSessionRevokeReason;
import com.hnp.backendofflinefirst.entity.ApiSession;
import com.hnp.backendofflinefirst.repository.ApiSessionRepository;
import com.hnp.backendofflinefirst.security.AppUserDetails;
import com.hnp.backendofflinefirst.security.JwtService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * Server-side registry of issued mobile-API JWTs.
 * <p>
 * The API chain is still stateless in the Spring sense (no HTTP session), but every
 * token is now backed by an {@code api_sessions} row, which buys three things the
 * plain stateless setup could not offer: administrators can see who is logged in on
 * which device, they can revoke a token before it expires, and a user is limited to
 * <b>one</b> active session — a second login supersedes the first.
 * <p>
 * The per-request lookup is a single indexed read on a unique key. At this system's
 * scale (tens of concurrent tablets) that cost is negligible next to the control gained.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ApiSessionService {

    /** Only rewrite {@code last_seen_at} this often, so syncing does not cause a write per request. */
    static final long LAST_SEEN_THROTTLE_MS = 60_000L;

    /**
     * Namespace for the per-user advisory lock, so it cannot collide with any other use of
     * Postgres advisory locks added later. Arbitrary but fixed.
     */
    private static final int SESSION_LOCK_NAMESPACE = 0x4150_4953; // "APIS"

    private static final int MAX_USER_AGENT_LENGTH = 512;
    private static final int MAX_DEVICE_LABEL_LENGTH = 255;

    private final ApiSessionRepository apiSessionRepository;

    /**
     * Records a freshly issued token and closes any other session the user still holds.
     *
     * @return the persisted session row
     */
    @Transactional
    public ApiSession register(AppUserDetails user, JwtService.JwtToken token,
                               String deviceLabel, String userAgent, String ipAddress) {
        // Everything below is read-then-write on one user's sessions, so it has to be serialised
        // against a concurrent login by the same user. Without this, two logins both read "none
        // active", both insert, and the one-device rule silently stops holding.
        apiSessionRepository.lockUserForSessionChange(
                SESSION_LOCK_NAMESPACE, user.getUserId().intValue());

        supersedePreviousSessions(user.getUserId(), token.issuedAt());

        ApiSession session = new ApiSession();
        session.setJti(token.jti());
        session.setUserId(user.getUserId());
        session.setUsername(user.getUsername());
        session.setDeviceLabel(trim(deviceLabel, MAX_DEVICE_LABEL_LENGTH));
        session.setUserAgent(trim(userAgent, MAX_USER_AGENT_LENGTH));
        session.setIpAddress(trim(ipAddress, 64));
        session.setIssuedAt(token.issuedAt());
        session.setExpiresAt(token.expiresAt());
        session.setLastSeenAt(token.issuedAt());
        apiSessionRepository.save(session);

        log.info("Registered API session {} for user {} (device={})",
                session.getId(), user.getUsername(), session.getDeviceLabel());
        return session;
    }

    /**
     * Enforces the one-active-session-per-user rule.
     *
     * <p>A single bulk statement, which is load-bearing rather than an optimisation — see
     * {@link ApiSessionRepository#supersedeAllForUser} for why loading the entities instead both
     * ran in the wrong order and missed expired rows.
     */
    private void supersedePreviousSessions(Long userId, long now) {
        int superseded = apiSessionRepository.supersedeAllForUser(
                userId, now, ApiSessionRevokeReason.SUPERSEDED);
        if (superseded > 0) {
            log.info("Superseded {} previous API session(s) for user {}", superseded, userId);
        }
    }

    /**
     * Per-request gate: is this {@code jti} a live session <b>belonging to this user</b>? Also
     * refreshes {@code last_seen_at} (throttled) so the admin list shows recent activity.
     *
     * <h2>Why the user id is a parameter and not a detail of the row</h2>
     *
     * <p>This used to take the {@code jti} alone, and that was the hole. A token's {@code uid} is
     * attacker-chosen if the signing key leaks — and the key ships as a working default — so
     * "the jti names a live session" and "the token names the user that session belongs to" are
     * two different questions. Asking only the first let an ordinary operator log in, take their
     * own genuine {@code jti}, and re-sign it claiming to be somebody else: the session existed,
     * it was live, and the check passed.
     *
     * <p>Binding the two closes it. A forged token can now only ever name the user whose session
     * it borrowed — which is the forger. Together with authorities being read from the database
     * ({@link com.hnp.backendofflinefirst.security.ApiTokenAuthenticator}) that leaves a leaked
     * signing key granting no escalation at all: the holder can mint tokens, and every one of
     * them is a token for themselves.
     *
     * <p>A mismatch is logged at WARN. It cannot happen by accident — the pairing is written by
     * {@link #register} and never changes — so it means either a forgery attempt or a bug, and
     * both are worth a line in the log.
     */
    @Transactional
    public boolean isSessionActive(String jti, Long userId, long now) {
        if (jti == null || jti.isBlank() || userId == null) {
            // Tokens minted before this feature carry no jti and can no longer be trusted.
            return false;
        }
        Optional<ApiSession> found = apiSessionRepository.findByJti(jti);
        if (found.isEmpty()) {
            return false;
        }
        ApiSession session = found.get();
        if (!userId.equals(session.getUserId())) {
            log.warn("Rejected API token: session {} belongs to user {}, but the token claims user {}",
                    jti, session.getUserId(), userId);
            return false;
        }
        if (!session.isActiveAt(now)) {
            return false;
        }
        Long lastSeen = session.getLastSeenAt();
        if (lastSeen == null || now - lastSeen >= LAST_SEEN_THROTTLE_MS) {
            session.setLastSeenAt(now);
            apiSessionRepository.save(session);
        }
        return true;
    }

    /** Admin action: kill one session. */
    @Transactional
    public void revoke(Long sessionId, Long actorUserId, long now) {
        ApiSession session = apiSessionRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("API session not found."));
        if (session.isRevoked()) {
            throw new IllegalStateException("This API session is already revoked.");
        }
        session.setRevokedAt(now);
        session.setRevokedBy(actorUserId);
        session.setRevokeReason(ApiSessionRevokeReason.ADMIN);
        apiSessionRepository.save(session);
        log.info("Admin {} revoked API session {} (user={})", actorUserId, sessionId, session.getUserId());
    }

    /** Admin action: kill every live session of one user. */
    @Transactional
    public int revokeAllForUser(Long userId, Long actorUserId, long now) {
        return revokeAllForUser(userId, actorUserId, now, ApiSessionRevokeReason.ADMIN);
    }

    /**
     * Kills every live session of one user, recording <em>why</em>.
     *
     * <p>The reason is not decoration. Every rejected request afterwards looks identical to the
     * device — the token simply stops working — so the {@code api_sessions} row is the only
     * place that can answer "was this revoked by hand, superseded by another device, or closed
     * because the account was deactivated?". An administrator fielding "my tablet logged itself
     * out" needs that distinction, and it is unrecoverable if it was never written.
     *
     * @param reason why these sessions are being closed
     * @return how many were live and are now revoked
     */
    @Transactional
    public int revokeAllForUser(Long userId, Long actorUserId, long now, ApiSessionRevokeReason reason) {
        List<ApiSession> active = apiSessionRepository.findActiveByUserId(userId, now);
        for (ApiSession session : active) {
            session.setRevokedAt(now);
            session.setRevokedBy(actorUserId);
            session.setRevokeReason(reason);
        }
        if (!active.isEmpty()) {
            apiSessionRepository.saveAll(active);
            log.info("Admin {} revoked {} API session(s) of user {}", actorUserId, active.size(), userId);
        }
        return active.size();
    }

    @Transactional(readOnly = true)
    public Page<ApiSession> list(String q, boolean activeOnly, long now, Pageable pageable) {
        String term = q == null || q.isBlank() ? null : q.trim();
        if (activeOnly) {
            return term == null
                    ? apiSessionRepository.findActive(now, pageable)
                    : apiSessionRepository.searchActive(term, now, pageable);
        }
        return term == null
                ? apiSessionRepository.findAll(pageable)
                : apiSessionRepository.search(term, pageable);
    }

    @Transactional(readOnly = true)
    public long countActive(long now) {
        return apiSessionRepository.countByRevokedAtIsNullAndExpiresAtGreaterThan(now);
    }

    private static String trim(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.length() <= maxLength ? trimmed : trimmed.substring(0, maxLength);
    }
}
