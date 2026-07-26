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

    /** Enforces the one-active-session-per-user rule. */
    private void supersedePreviousSessions(Long userId, long now) {
        List<ApiSession> previous = apiSessionRepository.findActiveByUserId(userId, now);
        for (ApiSession old : previous) {
            old.setRevokedAt(now);
            old.setRevokeReason(ApiSessionRevokeReason.SUPERSEDED);
        }
        if (!previous.isEmpty()) {
            apiSessionRepository.saveAll(previous);
            log.info("Superseded {} previous API session(s) for user {}", previous.size(), userId);
        }
    }

    /**
     * Per-request gate: is this {@code jti} still a live session? Also refreshes
     * {@code last_seen_at} (throttled) so the admin list shows recent activity.
     */
    @Transactional
    public boolean isSessionActive(String jti, long now) {
        if (jti == null || jti.isBlank()) {
            // Tokens minted before this feature carry no jti and can no longer be trusted.
            return false;
        }
        Optional<ApiSession> found = apiSessionRepository.findByJti(jti);
        if (found.isEmpty()) {
            return false;
        }
        ApiSession session = found.get();
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
        List<ApiSession> active = apiSessionRepository.findActiveByUserId(userId, now);
        for (ApiSession session : active) {
            session.setRevokedAt(now);
            session.setRevokedBy(actorUserId);
            session.setRevokeReason(ApiSessionRevokeReason.ADMIN);
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
