package com.hnp.backendofflinefirst.repository;

/**
 * Read-only projection of persisted plant-system placement fields.
 * Used before flush so in-memory mutations do not mask the prior DB state.
 */
public interface PlantSystemAncestry {
    Long getLocationId();
    Long getParentId();
}
