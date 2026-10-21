-- =============================================================================
-- V5 — one-level asset sub-classes, and per-asset parameter / band overrides
--
-- WHY: until now a warning or danger band belonged to a CLASS and to nothing finer, so every
-- pump in the plant was judged against one range. The operational data says that does not hold:
-- 1,555 pumps carry 507 distinct names, 162 of which cover a single pump, and all eight numeric
-- pump fields still sit on the placeholder band -100..100 with `danger` identical to `warning`.
-- A band that cannot be set per machine is a band nobody sets at all.
--
-- The reasoning, the alternatives weighed, and the measurements that chose this shape over a
-- private class per asset (which would have cost 135 KB of duplicated snapshot per 47-entry
-- sheet instead of ~11 KB) are docs/roadmap.md §20.
--
-- WHAT IS IN HERE:
--
--   1. `asset_classes.parent_id` — a sub-class. ONE LEVEL ONLY, enforced by trigger: a class
--      that has a parent may not itself become one. A sub-class inherits its parent's fields and
--      overrides them by `field_key`.
--   2. `asset_entries.field_source` + `field_overrides` — the per-asset layer, as JSONB on the
--      asset itself rather than as rows in a child table or (worse) as a one-member class.
--   3. `log_sheets.field_overrides_snapshot` — the per-asset bands frozen at generation, beside
--      the field definitions that have been frozen there since V1.
--
-- NOTHING HERE IS DESTRUCTIVE. Every column is nullable or carries a default that reproduces
-- today's behaviour exactly: `parent_id IS NULL` is a top-level class, `field_source='INHERIT'`
-- is "take everything from the class", and a null snapshot means "this sheet predates the
-- feature". An existing row that is never touched behaves on V5 precisely as it did on V4.
-- =============================================================================


-- -----------------------------------------------------------------------------------------
-- 1. Sub-classes
-- -----------------------------------------------------------------------------------------

ALTER TABLE asset_classes
    ADD COLUMN parent_id BIGINT REFERENCES asset_classes(id) ON DELETE RESTRICT;

-- ON DELETE RESTRICT, matching every other reference to this table: deleting a class that still
-- has children must fail loudly rather than orphan them or cascade into field definitions.

-- The scope queries resolve "this class or any of its sub-classes" with
--     WHERE class_id IN (SELECT id FROM asset_classes WHERE id = :c OR parent_id = :c)
-- so the sub-select needs the index. Partial: the overwhelming majority of rows are top-level
-- and a NULL parent is never searched for.
CREATE INDEX idx_asset_classes_parent_id ON asset_classes (parent_id) WHERE parent_id IS NOT NULL;

-- Depth is capped at one, and it is capped HERE rather than in Java because the application is
-- not the only thing that writes this table: the Excel import does, and so will a DBA fixing
-- something at 3 a.m. A two-level chain would silently break the scope queries above, which
-- look exactly one level down.
--
-- A CHECK constraint cannot express this (it may not read other rows), so it is a trigger.
CREATE OR REPLACE FUNCTION asset_classes_enforce_single_level() RETURNS trigger AS $$
BEGIN
    IF NEW.parent_id IS NOT NULL THEN
        IF NEW.parent_id = NEW.id THEN
            RAISE EXCEPTION 'A class cannot be its own parent (class_id=%)', NEW.id
                USING ERRCODE = 'check_violation';
        END IF;
        -- The intended parent must itself be top-level.
        IF EXISTS (SELECT 1 FROM asset_classes p
                    WHERE p.id = NEW.parent_id AND p.parent_id IS NOT NULL) THEN
            RAISE EXCEPTION 'Asset class nesting is limited to one level: class % cannot be a parent because it already has one',
                NEW.parent_id USING ERRCODE = 'check_violation';
        END IF;
    END IF;

    -- ...and this row must not already be somebody's parent if it is being given a parent.
    IF NEW.parent_id IS NOT NULL
       AND EXISTS (SELECT 1 FROM asset_classes c WHERE c.parent_id = NEW.id) THEN
        RAISE EXCEPTION 'Class % already has sub-classes and cannot itself become a sub-class', NEW.id
            USING ERRCODE = 'check_violation';
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_asset_classes_single_level
    BEFORE INSERT OR UPDATE OF parent_id, id ON asset_classes
    FOR EACH ROW EXECUTE FUNCTION asset_classes_enforce_single_level();


-- -----------------------------------------------------------------------------------------
-- 2. Per-asset overrides
-- -----------------------------------------------------------------------------------------

-- INHERIT      — fields and bands both from the class / sub-class. Today's behaviour, and the
--                default, so every one of the 10,900 existing assets is unchanged.
-- RANGES_ONLY  — fields still from the class, so a field added to the class REACHES this asset;
--                only the warning/danger bands come from field_overrides.
-- FULL         — the asset carries its own complete field list and is deliberately detached:
--                a field added to the class does NOT reach it.
ALTER TABLE asset_entries
    ADD COLUMN field_source VARCHAR(16) NOT NULL DEFAULT 'INHERIT';

ALTER TABLE asset_entries
    ADD CONSTRAINT ck_asset_entries_field_source
        CHECK (field_source IN ('INHERIT', 'RANGES_ONLY', 'FULL'));

-- Shape, for RANGES_ONLY:
--   {"Ampere": {"warning": {"min": 18, "max": 42}, "danger": {"min": 12, "max": 55}}}
-- and for FULL, the same keys plus the definition itself (label, dataType, unit, required,
-- sortOrder, validation) so the asset can add and remove fields, with `"removed": true` marking
-- one of the class's fields as not applicable to this asset.
--
-- JSONB on the asset rather than a child table: these are always read as a whole for one asset
-- and never queried individually, the AssetEntry row is already loaded when a sheet is
-- generated (so resolution costs no extra query), and deletion cascades for free.
ALTER TABLE asset_entries
    ADD COLUMN field_overrides JSONB;

-- An asset that claims a non-default source must actually carry overrides, and one that claims
-- INHERIT must not — otherwise a half-finished edit leaves a row whose stored intent and stored
-- data disagree, and the resolver has to guess. Guessing about a danger band is not acceptable.
ALTER TABLE asset_entries
    ADD CONSTRAINT ck_asset_entries_field_overrides_match_source
        CHECK (
            (field_source = 'INHERIT' AND field_overrides IS NULL)
            OR (field_source <> 'INHERIT' AND field_overrides IS NOT NULL)
        );

-- "Which assets override anything at all" is asked once per sheet generation, against a table
-- of 10,900 rows of which most will never override. Partial, so the index stays tiny.
CREATE INDEX idx_asset_entries_field_source ON asset_entries (field_source)
    WHERE field_source <> 'INHERIT';


-- -----------------------------------------------------------------------------------------
-- 3. The frozen per-asset bands
-- -----------------------------------------------------------------------------------------

-- Beside field_definitions_snapshot, and for the same reason: re-tuning a band next month must
-- not silently re-judge a reading taken today. Keyed by asset id, holding only what differs
-- from the class definition the other column already carries —
--   {"4711": {"Ampere": {"warning": {...}, "danger": {...}}}}
-- — because a 47-entry sheet that inlined whole definitions per asset would carry 135 KB
-- instead of ~11 KB, on every bundle fetch, uncompressed. See roadmap.md §20.
--
-- NULL means "this sheet was generated before V5", which resolves exactly as it did then.
ALTER TABLE log_sheets
    ADD COLUMN field_overrides_snapshot JSONB;
