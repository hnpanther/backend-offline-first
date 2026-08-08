package com.hnp.backendofflinefirst.domain;

/** Whether a status history row set a value or put an earlier one back. */
public enum AssetStatusChangeType {
    /** A completed log sheet wrote a new status onto the asset. */
    APPLIED,
    /** That completion was undone (void / reopen) and the previous status was restored. */
    REVERTED
}
