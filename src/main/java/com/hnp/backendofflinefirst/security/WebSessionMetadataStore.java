package com.hnp.backendofflinefirst.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.context.event.EventListener;
import org.springframework.security.core.session.SessionDestroyedEvent;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory login metadata (IP, User-Agent, login time) per web session id.
 * <p>
 * Spring's {@code SessionRegistry} only knows the principal and last-request time;
 * this store supplies the device details shown on the admin {@code /web-sessions}
 * page. Entries live exactly as long as the session: recorded by the login success
 * handler, removed on the container's session-destroyed event. Like the registry —
 * and the session store itself (`server.servlet.session.persistent=false`) — it is
 * intentionally in-memory and empties on restart.
 */
@Component
public class WebSessionMetadataStore {

    private static final int MAX_USER_AGENT_LENGTH = 512;

    public record WebSessionMeta(String ipAddress, String userAgent, long loginAt) {}

    private final Map<String, WebSessionMeta> bySessionId = new ConcurrentHashMap<>();

    /** Called by the form-login success handler (after session-fixation migration). */
    public void recordLogin(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            return;
        }
        bySessionId.put(session.getId(), new WebSessionMeta(
                clientIp(request),
                truncate(request.getHeader("User-Agent")),
                System.currentTimeMillis()));
    }

    public WebSessionMeta get(String sessionId) {
        return sessionId != null ? bySessionId.get(sessionId) : null;
    }

    @EventListener
    public void onSessionDestroyed(SessionDestroyedEvent event) {
        bySessionId.remove(event.getId());
    }

    /** Prefers the proxy-forwarded address so sessions behind a reverse proxy stay identifiable. */
    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private static String truncate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() <= MAX_USER_AGENT_LENGTH
                ? trimmed
                : trimmed.substring(0, MAX_USER_AGENT_LENGTH);
    }
}
