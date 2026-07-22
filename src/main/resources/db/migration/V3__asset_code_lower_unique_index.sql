-- =============================================================================
-- Case-insensitive uniqueness + lookup support for asset_entries.asset_code
-- App already treats AST-100 and ast-100 as duplicates via IgnoreCase queries;
-- this index enforces the same rule in the database and speeds LOWER(asset_code) lookups.
-- =============================================================================

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM asset_entries
        WHERE asset_code IS NOT NULL
        GROUP BY LOWER(asset_code)
        HAVING COUNT(*) > 1
    ) THEN
        RAISE EXCEPTION
            'Cannot create ux_asset_entries_asset_code_lower: case-insensitive duplicate asset_code values exist. Resolve duplicates before migrating.';
    END IF;
END $$;

CREATE UNIQUE INDEX IF NOT EXISTS ux_asset_entries_asset_code_lower
    ON asset_entries (LOWER(asset_code));
