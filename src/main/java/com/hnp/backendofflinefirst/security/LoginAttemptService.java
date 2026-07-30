package com.hnp.backendofflinefirst.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * In-memory failed-login throttle keyed by (normalized, lower-cased) username.
 * <p>
 * The main threat this closes isn't local BCrypt brute-force — it's an attacker using
 * repeated wrong-password attempts through this app to trip Active Directory's own
 * account-lockout policy against a real employee. {@link AppAuthenticationProvider} checks
 * {@link #remainingLockSeconds(String)} <em>before</em> calling {@code LdapAuthenticationService},
 * so a locked username never reaches the domain controller.
 * <p>
 * In-memory and per-instance — matching {@code SessionRegistryImpl} / {@code WebSessionMetadataStore}'s
 * existing non-persistent pattern in this codebase. Resets on restart; that's acceptable for a
 * temporary throttle, not a permanent security record.
 * <p>
 * Every read ({@link #remainingLockSeconds}, {@link #snapshot}) recomputes lock state from the
 * current clock rather than caching a "locked" flag, so {@link #unlock} is always safe to call —
 * whether the username is still actively locked or its lock already expired naturally in the
 * meantime, the call is a plain map removal with no special-case handling required.
 */
@Component
public class LoginAttemptService {

    @Value("${app.auth.login-attempt.max-attempts}")
    private int maxAttempts;

    @Value("${app.auth.login-attempt.lock-minutes}")
    private int lockMinutes;

    private final ConcurrentHashMap<String, Attempt> attempts = new ConcurrentHashMap<>();
    private final LongSupplier clock;

    public LoginAttemptService() {
        this(System::currentTimeMillis);
    }

    /** Visible for tests — a fake clock lets lock-expiry be simulated without sleeping. */
    LoginAttemptService(LongSupplier clock) {
        this.clock = clock;
    }

    public void recordFailure(String username) {
        String display = displayName(username);
        attempts.compute(key(username), (k, existing) -> {
            long now = clock.getAsLong();
            if (existing == null || existing.isStale(now, lockMinutes)) {
                return new Attempt(display, 1, now);
            }
            return new Attempt(display, existing.count() + 1, now);
        });
    }

    public void recordSuccess(String username) {
        attempts.remove(key(username));
    }

    /**
     * Admin-triggered early unlock (same effect as {@link #recordSuccess}, named separately
     * for the admin UI). Safe to call at any time, including after the lock has already
     * expired naturally — a plain map removal either way, never throws.
     */
    public void unlock(String username) {
        attempts.remove(key(username));
    }

    public boolean isLocked(String username) {
        return remainingLockSeconds(username) > 0;
    }

    /** Seconds until the lock clears, or 0 if the username isn't currently locked. */
    public long remainingLockSeconds(String username) {
        return remainingLockSeconds(attempts.get(key(username)), clock.getAsLong());
    }

    /**
     * Admin view of everyone with at least one recent failure — both currently-locked
     * usernames and ones approaching the threshold. Entries whose lock window has fully
     * elapsed are left out; they're indistinguishable from "no recent failures".
     */
    public List<Status> snapshot() {
        long now = clock.getAsLong();
        List<Status> result = new ArrayList<>();
        for (var entry : attempts.entrySet()) {
            Attempt attempt = entry.getValue();
            if (attempt.isStale(now, lockMinutes)) {
                continue;
            }
            boolean locked = attempt.count() >= maxAttempts;
            result.add(new Status(attempt.displayUsername(), attempt.count(), maxAttempts,
                    locked, remainingLockSeconds(attempt, now), attempt.lastFailureAt()));
        }
        return result;
    }

    private long remainingLockSeconds(Attempt attempt, long now) {
        if (attempt == null || attempt.count() < maxAttempts) {
            return 0;
        }
        long remainingMs = attempt.lastFailureAt() + lockMinutes * 60_000L - now;
        return remainingMs > 0 ? (remainingMs + 999) / 1000 : 0;
    }

    private static String key(String username) {
        return username == null ? "" : username.trim().toLowerCase();
    }

    private static String displayName(String username) {
        return username == null ? "" : username.trim();
    }

    private record Attempt(String displayUsername, int count, long lastFailureAt) {
        boolean isStale(long now, int lockMinutes) {
            return now - lastFailureAt > lockMinutes * 60_000L;
        }
    }

    /** Read-only admin view of one username's throttle state. */
    public record Status(String username, int failureCount, int maxAttempts,
                          boolean locked, long remainingLockSeconds, long lastFailureAt) {

        /** e.g. "3 دقیقه" — used by the admin login-attempts page. */
        public String remainingLockMinutesDisplay() {
            long minutes = (remainingLockSeconds + 59) / 60;
            return minutes + " دقیقه";
        }
    }
}
