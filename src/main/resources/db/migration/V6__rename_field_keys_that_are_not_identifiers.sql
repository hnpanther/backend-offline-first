-- =============================================================================
-- V6 — give four pump parameters identifier-safe keys, and carry every reading with them
--
-- WHY: `field_definitions.field_key` is an identifier. It is the key of every value in
-- `log_sheet_entries.form_data`, a member of `breached_fields`, the `key` of each object in a
-- sheet's frozen `field_definitions_snapshot`, and a key inside both per-asset override maps.
-- Four pump parameters were created with SPACES in them, which `MasterDataUniquenessValidator`
-- now refuses — but renaming one through the admin panel rewrites the definition and nothing
-- else, so the readings taken under the old name stop matching anything.
--
-- That has already happened to two of them. Measured on the operational database before
-- writing this:
--
--     NDE- Bearing Housing-Level       1201 entries hold readings no definition matches
--     DE- Mechanical Seal-TEMPERETURE    14 entries
--
-- Those readings are not lost — they sit in `form_data` under the old key — but no report,
-- no chart and no severity evaluation can see them, because every one of those resolves a
-- value by looking its key up among the definitions.
--
-- WHAT THIS DOES, for each of the four keys:
--   1. renames the definition (for the two not yet renamed), and
--   2. renames the key everywhere the data refers to it.
--
-- NOTHING IS DELETED. A value is moved from one key to another, never dropped, and a key that
-- is not in the mapping is not touched. Rows that do not contain the old key are skipped by
-- the `?` operator, so this is cheap on a database where it does not apply and a no-op on a
-- fresh one.
--
-- THE SNAPSHOTS ARE REWRITTEN TOO, and that is deliberate even though they are meant to be
-- frozen. `field_definitions_snapshot` freezes a *schema* so that re-tuning a band does not
-- re-judge history — it is about VALUES. A key is not a value, it is the identity by which a
-- reading finds its definition; leaving the old one in a frozen sheet means that sheet can
-- never display its own readings again. See AGENTS.md #149.
--
-- IDEMPOTENT. Every statement matches on the OLD key, which no longer exists afterwards, so a
-- re-run changes nothing. (Flyway will not re-run it; this is for the hand-repair case.)
-- =============================================================================


-- The mapping, in one place, so every statement below reads from the same four pairs. The new
-- names follow the convention already used on the two that were renamed by hand: the spaces
-- are removed, nothing else changes.
-- Dropped explicitly at the end rather than with ON COMMIT DROP: Flyway wraps a migration in
-- a transaction, but this file is also the hand-repair procedure, and under psql's autocommit
-- ON COMMIT DROP takes the table away the moment CREATE returns.
DROP TABLE IF EXISTS field_key_rename;
CREATE TEMPORARY TABLE field_key_rename (old_key TEXT PRIMARY KEY, new_key TEXT NOT NULL);

INSERT INTO field_key_rename (old_key, new_key) VALUES
    -- Already renamed in `field_definitions`; only the data is behind.
    ('NDE- Bearing Housing-Level',       'NDE-BearingHousing-Level'),
    ('DE- Mechanical Seal-TEMPERETURE',  'DE-MechanicalSeal-TEMPERETURE'),
    -- Not yet renamed: definition and data both move here, together, so no window exists in
    -- which a reading is orphaned.
    ('DE- Bearing Housing-Level',        'DE-BearingHousing-Level'),
    ('NDE- Mechanical Seal-TEMPERETURE', 'NDE-MechanicalSeal-TEMPERETURE');


-- Refuse to run if a new name is already taken by a *different* definition in the same class.
-- `ux_field_definitions_class_key_lower` would reject the UPDATE anyway; failing here says
-- which pair is the problem instead of printing an index name.
DO $$
DECLARE clash TEXT;
BEGIN
    SELECT string_agg(r.old_key || ' -> ' || r.new_key, ', ')
      INTO clash
      FROM field_key_rename r
      JOIN field_definitions old_fd ON old_fd.field_key = r.old_key
      JOIN field_definitions other  ON lower(other.field_key) = lower(r.new_key)
                                   AND other.class_id = old_fd.class_id
                                   AND other.id <> old_fd.id;
    IF clash IS NOT NULL THEN
        RAISE EXCEPTION 'Renaming would collide with an existing field key: %', clash;
    END IF;
END $$;


-- -----------------------------------------------------------------------------------------
-- The renames, ONE PAIR AT A TIME.
--
-- This is a loop rather than seven `UPDATE … FROM field_key_rename` joins, and the reason is
-- a trap that cost a run: **`UPDATE … FROM` applies at most one matching source row to each
-- target row.** A pump entry holds all four of these parameters, so joining the mapping table
-- produced four candidate updates for the same `log_sheet_entries` row and PostgreSQL silently
-- kept one — renaming one key and leaving the other three. It does not warn; the row count
-- even looks plausible. The verification block at the end is what caught it.
--
-- Iterating gives each pair its own statement, so a row is rewritten once per pair and all
-- four land.
-- -----------------------------------------------------------------------------------------
DO $$
DECLARE r RECORD;
BEGIN
FOR r IN SELECT old_key, new_key FROM field_key_rename LOOP

    -- 1. The definition itself (only the ones that still carry a space).
    UPDATE field_definitions
       SET field_key = r.new_key,
           updated_at = (EXTRACT(EPOCH FROM now()) * 1000)::BIGINT
     WHERE field_key = r.old_key;

    -- 2. The readings. Add under the new key, then drop the old — never drop first.
    UPDATE log_sheet_entries
       SET form_data = (form_data - r.old_key)
                       || jsonb_build_object(r.new_key, form_data -> r.old_key)
     WHERE form_data ? r.old_key;

    -- 3. `breached_fields` is an ARRAY of keys, not an object.
    UPDATE log_sheet_entries
       SET breached_fields = (
               SELECT jsonb_agg(CASE WHEN value #>> '{}' = r.old_key
                                     THEN to_jsonb(r.new_key) ELSE value END)
                 FROM jsonb_array_elements(breached_fields) AS value)
     WHERE jsonb_typeof(breached_fields) = 'array'
       AND breached_fields @> to_jsonb(r.old_key);

    -- 4. The frozen schema: an array of objects whose `key` member carries the field key.
    UPDATE log_sheets
       SET field_definitions_snapshot = (
               SELECT jsonb_agg(
                          CASE WHEN element ->> 'key' = r.old_key
                               THEN jsonb_set(element, '{key}', to_jsonb(r.new_key))
                               ELSE element END
                          ORDER BY ordinality)
                 FROM jsonb_array_elements(field_definitions_snapshot)
                      WITH ORDINALITY AS t(element, ordinality))
     WHERE jsonb_typeof(field_definitions_snapshot) = 'array'
       AND EXISTS (SELECT 1 FROM jsonb_array_elements(field_definitions_snapshot) e
                    WHERE e ->> 'key' = r.old_key);

    -- 5. The frozen per-asset bands:
    --      { "<assetId>": { "source": …, "fields": { "<fieldKey>": {…} } } }
    --    `(entry -> 'fields')` must be parenthesised before `-`: `->` binds looser, so without
    --    it PostgreSQL reads `entry -> ('fields' - r.old_key)` and fails parsing the literal.
    UPDATE log_sheets
       SET field_overrides_snapshot = (
               SELECT jsonb_object_agg(
                          asset_id,
                          CASE WHEN (entry -> 'fields') ? r.old_key
                               THEN jsonb_set(entry, '{fields}',
                                       ((entry -> 'fields') - r.old_key)
                                       || jsonb_build_object(r.new_key,
                                              entry #> ARRAY['fields', r.old_key]))
                               ELSE entry END)
                 FROM jsonb_each(field_overrides_snapshot) AS t(asset_id, entry))
     WHERE jsonb_typeof(field_overrides_snapshot) = 'object'
       AND EXISTS (SELECT 1 FROM jsonb_each(field_overrides_snapshot) AS t(asset_id, entry)
                    WHERE (entry -> 'fields') ? r.old_key);

    -- 6. The live per-asset bands, keyed by field key directly.
    UPDATE asset_entries
       SET field_overrides = (field_overrides - r.old_key)
                             || jsonb_build_object(r.new_key, field_overrides -> r.old_key)
     WHERE field_overrides ? r.old_key;

    -- 7. The superseded readings a correction replaced — what an audit of "what did the
    --    operator actually write" reads. Same two shapes as the entry itself.
    UPDATE log_sheet_entry_revisions
       SET form_data = (form_data - r.old_key)
                       || jsonb_build_object(r.new_key, form_data -> r.old_key)
     WHERE form_data ? r.old_key;

    UPDATE log_sheet_entry_revisions
       SET breached_fields = (
               SELECT jsonb_agg(CASE WHEN value #>> '{}' = r.old_key
                                     THEN to_jsonb(r.new_key) ELSE value END)
                 FROM jsonb_array_elements(breached_fields) AS value)
     WHERE jsonb_typeof(breached_fields) = 'array'
       AND breached_fields @> to_jsonb(r.old_key);

END LOOP;
END $$;


-- -----------------------------------------------------------------------------------------
-- 8. Prove it. A reading whose key matches no definition is exactly the defect this migration
--    exists to clear, so refuse to commit if any of the four survives anywhere.
--
--    Deliberately narrow: it checks only the four keys being renamed. Other orphans exist on
--    this database (`Video`, `Audio` — from a field that was DELETED, not renamed) and are
--    none of this migration's business; they are left exactly as they are.
-- -----------------------------------------------------------------------------------------
DO $$
DECLARE leftover TEXT;
BEGIN
    -- The column is named by the subquery alias, not by the first branch: `AS remaining(found)`
    -- rather than `SELECT k AS found`, or `DISTINCT found` is ambiguous between the two.
    SELECT string_agg(DISTINCT remaining.found, ', ') INTO leftover FROM (
        SELECT k FROM log_sheet_entries e, jsonb_object_keys(e.form_data) k
         WHERE k IN (SELECT old_key FROM field_key_rename)
        UNION ALL
        SELECT fd.field_key FROM field_definitions fd
         WHERE fd.field_key IN (SELECT old_key FROM field_key_rename)
        UNION ALL
        SELECT k FROM asset_entries a, jsonb_object_keys(a.field_overrides) k
         WHERE a.field_overrides IS NOT NULL
           AND k IN (SELECT old_key FROM field_key_rename)
    ) AS remaining(found);

    IF leftover IS NOT NULL THEN
        RAISE EXCEPTION 'V6 left old field keys behind: %', leftover;
    END IF;
END $$;


DROP TABLE field_key_rename;
