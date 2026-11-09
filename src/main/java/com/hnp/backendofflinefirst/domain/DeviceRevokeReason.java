package com.hnp.backendofflinefirst.domain;

/** Why a {@code devices} row became {@link DeviceStatus#REVOKED}. */
public enum DeviceRevokeReason {
    /** An approved device was withdrawn from the panel — lost, stolen, handed back. */
    ADMIN,
    /** A pending request was turned down before it was ever approved. */
    REJECTED
}
