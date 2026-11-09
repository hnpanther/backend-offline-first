package com.hnp.backendofflinefirst.domain;

/**
 * Where a handheld stands with this server. Only {@code APPROVED} may log in while device
 * enrolment is on.
 *
 * <p>There is no way back from {@code REVOKED}: a device that has to be trusted again makes a
 * new key and a new request, so the revocation stays on record next to the approval that
 * preceded it.
 */
public enum DeviceStatus {
    /** Asked to be enrolled; waiting for an approver to issue a one-time code. */
    PENDING,
    /** May log in. */
    APPROVED,
    /** Rejected while pending, or withdrawn after approval. Final. */
    REVOKED
}
