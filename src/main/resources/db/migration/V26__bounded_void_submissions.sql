-- =============================================================================
-- V26 — what one person can leave on a sheet they hold is bounded (issue.md B-04 §2)
--
-- A refused submission is kept as a row in log_sheet_void_submissions. V25 made sure only
-- somebody with a claim on the sheet can leave one; this bounds how many:
--
--   * the same content sent again is kept ONCE — the existing row's synced_at moves instead of a
--     second row appearing. "The same" is a SHA-256 of the canonical payload and round answers
--     (`VoidPayloadFingerprint`), stored here so the comparison is one indexed lookup;
--   * a small number of DIFFERENT refused submissions per (sheet, person) are kept; past that the
--     server answers the refusal without keeping it, and the tablet — which still has the
--     readings — counts the round as undelivered.
--
-- Rows written before V26 have no hash. They are never matched as duplicates, and they count
-- towards the per-person number like any other row.
-- =============================================================================

ALTER TABLE log_sheet_void_submissions ADD COLUMN payload_hash VARCHAR(64);

CREATE INDEX idx_lsvs_sheet_submitter
    ON log_sheet_void_submissions (log_sheet_id, submitted_by_user_id);
