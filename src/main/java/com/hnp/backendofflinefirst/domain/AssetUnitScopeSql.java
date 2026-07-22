package com.hnp.backendofflinefirst.domain;

/**
 * Shared SQL for resolving assets visible under operational-unit scope entirely in PostgreSQL.
 * Matches {@code AssetHierarchyService.subFunctionIdsForOperationalUnits} + under-locations walk:
 * unit → location tree → systems on those locations → main-function trees → sub-functions.
 */
public final class AssetUnitScopeSql {

    private AssetUnitScopeSql() {}

    /**
     * CTE ending with {@code scoped_sf(id)}. Caller must bind {@code :unitIds}.
     */
    public static final String SCOPED_SUBFUNCTIONS_CTE = """
            WITH RECURSIVE loc_roots AS (
                SELECT id FROM locations WHERE unit_id IN (:unitIds)
            ),
            loc_tree AS (
                SELECT id FROM loc_roots
                UNION ALL
                SELECT l.id FROM locations l
                INNER JOIN loc_tree t ON l.parent_id = t.id
            ),
            systems AS (
                SELECT id FROM plant_systems WHERE location_id IN (SELECT id FROM loc_tree)
            ),
            main_roots AS (
                SELECT id FROM main_functions
                WHERE location_id IN (SELECT id FROM loc_tree)
                   OR system_id IN (SELECT id FROM systems)
            ),
            main_tree AS (
                SELECT id FROM main_roots
                UNION ALL
                SELECT mf.id FROM main_functions mf
                INNER JOIN main_tree t ON mf.parent_id = t.id
            ),
            scoped_sf AS (
                SELECT id FROM sub_functions
                WHERE location_id IN (SELECT id FROM loc_tree)
                   OR system_id IN (SELECT id FROM systems)
                   OR main_function_id IN (SELECT id FROM main_tree)
            )
            """;
}
