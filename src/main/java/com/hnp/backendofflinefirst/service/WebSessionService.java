package com.hnp.backendofflinefirst.service;

import com.hnp.backendofflinefirst.security.AppUserDetails;
import com.hnp.backendofflinefirst.security.WebSessionMetadataStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

/**
 * Admin view over live web (form-login) sessions, backed by Spring Security's
 * in-memory {@link SessionRegistry}.
 * <p>
 * Counterpart of {@code ApiSessionService} for the browser panel: lists who is logged
 * in from where and lets an administrator expire a session, which logs that browser
 * out on its next request. One session per user is enforced by
 * {@code maximumSessions(1)} in {@code WebSecurityConfig}, not here.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WebSessionService {

    private final SessionRegistry sessionRegistry;
    private final WebSessionMetadataStore metadataStore;

    /** One row on the admin page. {@code key} is a digest — raw session ids never leave the server. */
    public record WebSessionView(String key, String username, String fullName, String ipAddress,
                                 String userAgent, Long loginAt, Long lastRequestAt,
                                 boolean currentSession) {}

    public List<WebSessionView> listActiveSessions(String currentSessionId) {
        List<WebSessionView> rows = new ArrayList<>();
        for (Object principal : sessionRegistry.getAllPrincipals()) {
            if (!(principal instanceof AppUserDetails user)) {
                continue;
            }
            for (SessionInformation info : sessionRegistry.getAllSessions(principal, false)) {
                WebSessionMetadataStore.WebSessionMeta meta = metadataStore.get(info.getSessionId());
                rows.add(new WebSessionView(
                        sessionKey(info.getSessionId()),
                        user.getUsername(),
                        user.getUser().getFullName(),
                        meta != null ? meta.ipAddress() : null,
                        meta != null ? meta.userAgent() : null,
                        meta != null ? meta.loginAt() : null,
                        info.getLastRequest() != null ? info.getLastRequest().getTime() : null,
                        info.getSessionId().equals(currentSessionId)));
            }
        }
        rows.sort(Comparator.comparing(WebSessionView::loginAt,
                Comparator.nullsLast(Comparator.reverseOrder())));
        return rows;
    }

    public int countActiveSessions() {
        int count = 0;
        for (Object principal : sessionRegistry.getAllPrincipals()) {
            count += sessionRegistry.getAllSessions(principal, false).size();
        }
        return count;
    }

    /**
     * Marks the session addressed by {@code key} as expired; the browser is logged out
     * (redirected to {@code /login?expired}) on its next request.
     */
    public void expireByKey(String key, Long actorUserId) {
        for (Object principal : sessionRegistry.getAllPrincipals()) {
            for (SessionInformation info : sessionRegistry.getAllSessions(principal, false)) {
                if (sessionKey(info.getSessionId()).equals(key)) {
                    info.expireNow();
                    String username = principal instanceof AppUserDetails user ? user.getUsername() : "?";
                    log.info("Admin {} expired web session of user {}", actorUserId, username);
                    return;
                }
            }
        }
        throw new IllegalArgumentException("Web session not found.");
    }

    /**
     * Opaque row address for the UI. Exposing raw {@code JSESSIONID} values to any page —
     * even an admin page — would hand out ready-to-use hijack cookies, so rows are
     * addressed by a truncated SHA-256 digest instead.
     */
    static String sessionKey(String sessionId) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(sessionId.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
