package com.hnp.backendofflinefirst.security;

import com.hnp.backendofflinefirst.domain.ApiKeyUsageOutcome;
import com.hnp.backendofflinefirst.entity.ApiKey;
import com.hnp.backendofflinefirst.repository.ApiKeyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Turns an {@code X-API-Key} header into a decision.
 *
 * <p><b>This lives in {@code security} rather than {@code service} on purpose.</b>
 * {@code LoggingAspect} advises {@code service..*} and serialises every argument; the argument
 * here is a live credential. Keeping verification out of the advised packages means the raw key
 * never reaches the aspect at all, which is a stronger guarantee than relying on
 * {@code LogSanitizer} to mask it afterwards. (The sanitizer covers it too — belt and braces,
 * because the next person may not know this.)
 *
 * <p>Verification is one indexed read plus one hash comparison. The order of the checks is the
 * order of the requirement: unknown key, wrong secret, disabled, revoked, expired — each its
 * own {@link ApiKeyUsageOutcome} so the usage log can tell an integrator that forgot to rotate
 * from one that never had a key. The <em>caller</em> sees the same 401 either way.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ApiKeyAuthenticator {

    /**
     * How often {@code last_used_at} is rewritten for a key that keeps calling.
     *
     * <p>Same value and same reasoning as {@code ApiSessionService.LAST_SEEN_THROTTLE_MS}: the
     * column exists so an administrator can see that an integration is alive, and one-minute
     * resolution answers that completely. Without the throttle a client polling every second
     * turns a read-only API into a write per request.
     */
    static final long LAST_USED_THROTTLE_MS = 60_000L;

    private final ApiKeyRepository apiKeyRepository;

    /**
     * @param outcome why the request was accepted or refused — always present
     * @param key     the matched row; present for every outcome except {@code MISSING_KEY} and
     *                {@code INVALID_KEY}, so a rejected-but-known key is still attributable
     */
    public record Result(ApiKeyUsageOutcome outcome, ApiKey key) {

        public boolean isAuthenticated() {
            return outcome == ApiKeyUsageOutcome.OK;
        }

        static Result rejected(ApiKeyUsageOutcome outcome) {
            return new Result(outcome, null);
        }

        static Result rejected(ApiKeyUsageOutcome outcome, ApiKey key) {
            return new Result(outcome, key);
        }
    }

    @Transactional
    public Result authenticate(String presentedKey, long now) {
        if (presentedKey == null || presentedKey.isBlank()) {
            return Result.rejected(ApiKeyUsageOutcome.MISSING_KEY);
        }

        Optional<ApiKeyCredentials> parsed = ApiKeyCredentials.parse(presentedKey);
        if (parsed.isEmpty()) {
            return Result.rejected(ApiKeyUsageOutcome.INVALID_KEY);
        }
        ApiKeyCredentials credentials = parsed.get();

        Optional<ApiKey> found = apiKeyRepository.findByKeyId(credentials.keyId());
        if (found.isEmpty()) {
            return Result.rejected(ApiKeyUsageOutcome.INVALID_KEY);
        }
        ApiKey key = found.get();

        // The secret is checked before the state flags, deliberately. Reversing them would let
        // somebody with only a key_id — which appears in the admin list and in every usage row —
        // learn whether that key is currently active, without ever holding the secret.
        if (!credentials.matches(key.getSecretHash())) {
            return Result.rejected(ApiKeyUsageOutcome.INVALID_KEY);
        }

        if (key.isRevoked()) {
            return Result.rejected(ApiKeyUsageOutcome.REVOKED_KEY, key);
        }
        if (key.isExpiredAt(now)) {
            return Result.rejected(ApiKeyUsageOutcome.EXPIRED_KEY, key);
        }
        if (!key.isActive()) {
            return Result.rejected(ApiKeyUsageOutcome.DISABLED_KEY, key);
        }

        touchLastUsed(key, now);
        return new Result(ApiKeyUsageOutcome.OK, key);
    }

    private void touchLastUsed(ApiKey key, long now) {
        Long lastUsed = key.getLastUsedAt();
        if (lastUsed != null && now - lastUsed < LAST_USED_THROTTLE_MS) {
            return;
        }
        // A @Modifying UPDATE, never save(): save() would go through RepositoryAuditAspect and
        // give a polling integration one audit_log row per minute, for a column whose whole
        // purpose is "is this thing still alive".
        apiKeyRepository.touchLastUsed(key.getId(), now);
        key.setLastUsedAt(now);
    }
}
