package com.hnp.backendofflinefirst.domain;

/**
 * What happened to an asset's {@code active} flag.
 *
 * <p>{@link #CREATED} is the baseline row written when the asset is registered. Without it the
 * timeline would start at the first toggle, leaving "was this ever active to begin with?"
 * unanswerable for an asset that has never been switched.
 *
 * <p>Deliberately unrelated to {@link AssetStatusChangeType}: activation is a registry decision
 * and is never reversed by a log sheet, so the two vocabularies stay separate.
 */
public enum AssetActivationChangeType {
    CREATED,
    ACTIVATED,
    DEACTIVATED
}
