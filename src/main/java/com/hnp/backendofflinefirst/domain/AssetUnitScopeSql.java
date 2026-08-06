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
                SELECT DISTINCT location_id AS id FROM location_units WHERE unit_id IN (:unitIds)
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

    /**
     * CTE ending with {@code reportable_assets(id)}. Caller must bind {@code :unitIds}.
     * <p>
     * Reporting scope is deliberately <strong>wider</strong> than the registry scope above,
     * because the two answer different questions. {@link #SCOPED_SUBFUNCTIONS_CTE} answers
     * "which assets sit in locations this unit owns" — the right rule for master-data lists.
     * Reporting has to answer "which assets is this user responsible for", and responsibility
     * arrives through the log sheet: a sheet is reachable via {@code log_sheets.operational_unit_id}
     * alone, and a template with {@code restrict_scope_to_unit = false} deliberately puts assets
     * from outside the unit's own locations onto that sheet (see the log-sheet scope rules).
     * Filtering reports by location ownership therefore hid the readings of work the user had
     * just been required to perform — including, when {@code location_units} is not populated
     * at all, every reading in the system for every unit-scoped user.
     * <p>
     * The union keeps the location-owned assets (so an asset with no log sheet yet is still
     * reportable by its owning unit) and adds the ones reached through accessible log sheets.
     * {@code :unitIds} is already the caller's downward-expanded supervisor scope plus their
     * operated units, so a parent-unit supervisor picks up their children's sheets for free.
     */
    public static final String REPORTABLE_ASSETS_CTE = SCOPED_SUBFUNCTIONS_CTE + """
            ,
            reportable_assets AS (
                SELECT a.id FROM asset_entries a
                INNER JOIN scoped_sf s ON a.sub_function_id = s.id
                UNION
                SELECT e.asset_id AS id
                FROM log_sheet_entries e
                INNER JOIN log_sheets ls ON ls.id = e.log_sheet_id
                WHERE ls.operational_unit_id IN (:unitIds)
                  AND e.asset_id IS NOT NULL
            )
            """;
}
