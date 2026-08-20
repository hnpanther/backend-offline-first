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
     * Expires every browser session belonging to one user.
     *
     * <p>The counterpart of {@code ApiSessionService.revokeAllForUser} for the panel, and it
     * exists for the same reason: deactivating or deleting an account has to take effect
     * <em>now</em>, and the panel's own session holds an {@code AppUserDetails} captured at
     * login. Nothing re-reads the account per request, so without this the person keeps their
     * old roles and their access until the 60-minute idle timeout — which is an idle timeout,
     * not an absolute one, so an active browser can hold them indefinitely.
     *
     * <h2>By user id, and never by username</h2>
     *
     * <p>The first version of this matched on the username, which is the one field that can
     * change. The registry holds the principal captured <em>at login</em>, so renaming an
     * account and then deactivating it searched under the new name, matched nothing, and left
     * the old browser fully live — with a comment above the call site cheerfully claiming the
     * opposite. Same for a rename in one edit followed by a deactivation in another, and for a
     * delete after a rename.
     *
     * <p>A user id survives every one of those. {@code AppUserDetails} now identifies itself by
     * id too, so the registry's own bookkeeping agrees with this lookup rather than diverging
     * from it.
     *
     * @return how many sessions were expired
     */
    public int expireByUserId(Long userId, Long actorUserId) {
        if (userId == null) {
            return 0;
        }
        int expired = 0;
        String username = null;
        for (Object principal : sessionRegistry.getAllPrincipals()) {
            if (!(principal instanceof AppUserDetails user) || !userId.equals(user.getUserId())) {
                continue;
            }
            username = user.getUsername();
            // false: expired sessions are already dead and must not be counted again, or a
            // second deactivation of the same user would report sessions it did not close.
            for (SessionInformation info : sessionRegistry.getAllSessions(principal, false)) {
                info.expireNow();
                expired++;
            }
        }
        if (expired > 0) {
            log.info("Expired {} web session(s) of user {} ({}) (actor={})",
                    expired, userId, username, actorUserId);
        }
        return expired;
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
