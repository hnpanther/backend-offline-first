package com.hnp.backendofflinefirst.repository;

/**
 * Read-only projection of persisted sub-function placement fields.
 * Used before flush so in-memory mutations do not mask the prior DB state.
 */
public interface SubFunctionAncestry {
    Long getMainFunctionId();
    Long getSystemId();
    Long getLocationId();
    Long getParentId();
}
