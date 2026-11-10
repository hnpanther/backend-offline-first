package com.hnp.backendofflinefirst.domain;

/**
 * A webhook delivery's state.
 *
 * <p>{@code FAILED} is the give-up after the configured number of attempts — left visible, with
 * its last error, and retried only when an administrator asks. Retrying for ever would hide a
 * receiver that has been down for a week behind a queue that merely looks busy.
 */
public enum AlarmWebhookState {
    PENDING,
    DELIVERED,
    FAILED
}
