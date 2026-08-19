package com.hnp.backendofflinefirst.service;

import com.hnp.backendofflinefirst.entity.ApiKey;
import com.hnp.backendofflinefirst.repository.ApiKeyRepository;
import com.hnp.backendofflinefirst.security.ApiKeyCredentials;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Administrative lifecycle of integration API keys: issue, pause, resume, revoke.
 *
 * <p>Verification is deliberately <b>not</b> here — see
 * {@link com.hnp.backendofflinefirst.security.ApiKeyAuthenticator} for why the hot path lives
 * outside the packages {@code LoggingAspect} advises.
 *
 * <p>Disable and revoke are separate actions because they answer different questions.
 * "Pause this integration while we migrate their server" must be reversible; "this key was
 * pasted into a public ticket" must not be. Collapsing them into one switch means either an
 * administrator cannot un-pause, or a leaked key can be quietly turned back on.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ApiKeyService {

    private static final int MAX_CLIENT_NAME_LENGTH = 255;
    private static final int MAX_DESCRIPTION_LENGTH = 1000;
    private static final int MAX_REVOKE_REASON_LENGTH = 500;

    private final ApiKeyRepository apiKeyRepository;

    /**
     * The created key and its plaintext, which exists in this object and nowhere else.
     *
     * <p>The field is named {@code apiKey} so {@code LogSanitizer} masks it if this record ever
     * reaches a log line.
     */
    public record IssuedApiKey(ApiKey key, String apiKey) {}

    /**
     * Issues a key. The returned plaintext is shown to the administrator once and is then
     * unrecoverable — only its SHA-256 is stored. A lost key is re-issued, never recovered,
     * which is the property that makes the store worthless to whoever reads the database.
     */
    @Transactional
    public IssuedApiKey create(String clientName, String description, Long expiresAt, Long actorUserId) {
        String name = require(clientName, "Client name is required.");
        if (name.length() > MAX_CLIENT_NAME_LENGTH) {
            throw new IllegalArgumentException("Client name is too long.");
        }
        if (apiKeyRepository.existsLiveByClientName(name)) {
            throw new IllegalArgumentException("An active API key already exists for this client.");
        }
        long now = System.currentTimeMillis();
        if (expiresAt != null && expiresAt <= now) {
            throw new IllegalArgumentException("API key expiry must be in the future.");
        }

        ApiKeyCredentials credentials = ApiKeyCredentials.generate();
        ApiKey key = new ApiKey();
        key.setClientName(name);
        key.setDescription(trimToNull(description, MAX_DESCRIPTION_LENGTH));
        key.setKeyId(credentials.keyId());
        key.setSecretHash(credentials.secretHash());
        key.setPrefix(credentials.displayPrefix());
        key.setActive(true);
        key.setCreatedAt(now);
        key.setCreatedByUserId(actorUserId);
        key.setExpiresAt(expiresAt);
        apiKeyRepository.save(key);

        // The key id, never the key. Whoever can read this log line can already read the
        // admin page; what they must not be able to do is replay the credential.
        log.info("Issued integration API key {} (keyId={}) for client '{}' by user {}",
                key.getId(), key.getKeyId(), key.getClientName(), actorUserId);
        return new IssuedApiKey(key, credentials.presentedKey());
    }

    /** Reversible pause / resume. Refused on a revoked key — revocation is final. */
    @Transactional
    public ApiKey setActive(Long id, boolean active, Long actorUserId) {
        ApiKey key = require(id);
        if (key.isRevoked()) {
            throw new IllegalStateException("This API key is revoked and cannot be changed.");
        }
        if (key.isActive() == active) {
            throw new IllegalStateException(active
                    ? "This API key is already active."
                    : "This API key is already disabled.");
        }
        key.setActive(active);
        apiKeyRepository.save(key);
        log.info("User {} {} integration API key {} (client='{}')",
                actorUserId, active ? "enabled" : "disabled", id, key.getClientName());
        return key;
    }

    /**
     * Permanent. The row stays so past {@code api_key_usage} rows remain attributable and so
     * the client name is not silently freed for reuse under a different owner.
     */
    @Transactional
    public ApiKey revoke(Long id, String reason, Long actorUserId) {
        ApiKey key = require(id);
        if (key.isRevoked()) {
            throw new IllegalStateException("This API key is already revoked.");
        }
        key.setRevokedAt(System.currentTimeMillis());
        key.setRevokedBy(actorUserId);
        key.setRevokeReason(trimToNull(reason, MAX_REVOKE_REASON_LENGTH));
        // Cleared as well, so a reader checking only `active` still sees a dead key.
        key.setActive(false);
        apiKeyRepository.save(key);
        log.info("User {} revoked integration API key {} (client='{}', reason='{}')",
                actorUserId, id, key.getClientName(), key.getRevokeReason());
        return key;
    }

    @Transactional(readOnly = true)
    public Page<ApiKey> list(String q, Pageable pageable) {
        return apiKeyRepository.search(q == null || q.isBlank() ? null : q.trim(), pageable);
    }

    @Transactional(readOnly = true)
    public ApiKey get(Long id) {
        return require(id);
    }

    private ApiKey require(Long id) {
        return apiKeyRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("API key not found."));
    }

    private static String require(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    private static String trimToNull(String value, int maxLength) {
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
