-- =============================================================================
-- V14 — a voided sync keeps the round's own answers, not only its assets
--
-- When the server refuses a tablet's final submission because the sheet moved on without it —
-- taken over, reassigned, released, cancelled, or completed by somebody else — it keeps the
-- submission in `log_sheet_void_submissions` instead of dropping it: the operator did the work,
-- and a timing problem must not destroy it. But it kept only `payload`, the per-asset readings.
-- The round's own answers (`sheetFormData`, V9) travel in the same request and were discarded on
-- that path, so a takeover of a round with questions lost them without trace — and an asset-less
-- round lost everything. Found on an operational sheet whose void held all 47 assets and nothing
-- the operator answered at round level (roadmap §34).
--
-- A column of its own rather than a reshaped `payload`: existing rows are a JSON array that every
-- reader iterates as assets, and a "round" element inside it would be read as an asset with no
-- name by any reader that was not taught otherwise.
--
-- Nullable, and NULL means "carried none" — a round with no questions sends no answers, exactly
-- as before. Nothing to backfill: the answers that were discarded are not anywhere to be copied.
-- =============================================================================

ALTER TABLE log_sheet_void_submissions
    ADD COLUMN sheet_form_data JSONB;

COMMENT ON COLUMN log_sheet_void_submissions.sheet_form_data IS
    'The round''s own answers exactly as the refused submission carried them; NULL when it carried none.';
