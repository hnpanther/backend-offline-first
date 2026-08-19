package com.hnp.backendofflinefirst.security;

import com.hnp.backendofflinefirst.entity.ApiKey;

/**
 * The principal on an integration request: a system, not a person.
 *
 * <p>Carries only what the request path and the usage log need. Notably it is <b>not</b> an
 * {@link AppUserDetails}, which is what makes {@code SecurityUtils.currentUser()} return
 * {@code null} on this chain — the correct answer, since there is no user. Every helper that
 * reads the principal already tolerates null (see {@code SecurityUtils}, {@code UserMdcFilter}),
 * and {@code SecurityUtils.isUnitScopedOnly()} answers {@code true} for it, i.e. "restricted",
 * because the capability is absent. That default is the safe direction: a future caller that
 * reaches a scoped query from here gets nothing rather than everything.
 *
 * <p>No secret and no hash: this object is held in the security context for the life of the
 * request and must stay harmless if it is ever serialised.
 */
public record IntegrationClient(Long apiKeyId, String keyId, String clientName) {

    public static IntegrationClient from(ApiKey key) {
        return new IntegrationClient(key.getId(), key.getKeyId(), key.getClientName());
    }

    @Override
    public String toString() {
        return clientName + " (" + keyId + ")";
    }
}
