package com.hnp.backendofflinefirst.domain;

/**
 * Where a status change request stands.
 *
 * <p>{@link #PENDING} is «ثبت شده» — filed and waiting for a supervisor. {@link #APPROVED} is
 * the only state in which the asset actually carries the requested status; {@link #REJECTED}
 * leaves the asset alone, or puts it back if the request had been approved.
 *
 * <p>An approval can be undone — back to {@code PENDING} or straight to {@code REJECTED} — but
 * only for an asset's newest request. See {@code AssetStatusRequestService} for why.
 */
public enum AssetStatusRequestStatus {
    PENDING,
    APPROVED,
    REJECTED
}
