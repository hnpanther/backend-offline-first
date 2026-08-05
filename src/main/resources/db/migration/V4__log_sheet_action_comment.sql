-- =============================================================================
-- Optional free-text explanation attached to a log-sheet lifecycle action.
--
-- The history already recorded *that* a sheet was extended / cancelled / voided
-- and by whom, but never *why*. This column carries the actor's own words so a
-- later reader can tell "cancelled because the unit was shut down for
-- maintenance" apart from "cancelled by mistake".
--
-- Deliberately nullable and unconstrained beyond a length cap: the comment is
-- ALWAYS optional and the action must succeed without it. Populated today by
-- EXTEND / CANCEL / VOID from the web panel; the column is action-agnostic, so
-- wiring another action (UNVOID, ADMIN_REOPEN, …) later needs no schema change.
--
-- `comment` is a non-reserved keyword in PostgreSQL, so it is legal unquoted as
-- a column name. Named `comment` rather than `notes` to keep it distinct from
-- log_sheets.notes, which is a different thing (a note on the sheet itself, not
-- on one action).
-- =============================================================================

ALTER TABLE log_sheet_action_log ADD COLUMN comment VARCHAR(1000);
