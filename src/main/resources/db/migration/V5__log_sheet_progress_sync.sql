-- =============================================================================
-- V5 — a round in progress becomes visible before it is finished
--
-- THE GAP THIS CLOSES
--
-- A tablet pushes completions and nothing else. `draft_saved_at` exists but only
-- `saveDraftFromWeb` ever wrote it, so a round being walked in the field was invisible to the
-- server for its whole duration: an operator could fill twenty assets in the first hour, be
-- online the entire time, and a supervisor looking at the sheet would see PENDING or
-- IN_PROGRESS with no data at all. If the sheet then changed hands, the second operator started
-- from an empty form and re-walked ground that had already been covered.
--
-- Worth stating because it makes the asymmetry obvious: **the photographs were already
-- arriving.** Attachments upload as soon as they are captured (`getPendingAttachments` waits
-- for a `logSheetServerId`, which a server-generated sheet always has), so the server held a
-- half-finished round's media and none of its numbers.
--
-- WHAT IS ADDED HERE
--
--   1. `log_sheets.draft_saved_by_user_id` / `draft_source` — who last saved partial values and
--      from which surface. `draft_saved_at` keeps its meaning and its column; it now has two
--      writers instead of one.
--   2. `POST:/api/log-sheets/progress` — the mobile progress endpoint, granted to every role
--      that already holds `POST:/api/log-sheets/batch`, including roles an administrator
--      duplicated.
--   3. A partial index behind the "N of M assets recorded" column.
--
-- WHAT IS NOT ADDED, DELIBERATELY
--
-- No new table for the values. Progress is written straight into `log_sheet_entries` through
-- the same merge the mobile submit uses, for three reasons that a separate per-assignee draft
-- table cannot answer:
--
--   * The handover this system actually performs — operator 1, then operator 2, then the
--     supervisor — requires operator 1's partial work to *be* operator 2's starting point. A
--     side table would have to be promoted into the entries anyway.
--   * Every rule that governs a partial write already exists and is hard-won: `storableFormData`,
--     `wouldBlankUnseenAnswer`, `EntrySeverityEvaluator`, and the `formDataChanged` gate that
--     decides authorship. A second copy of those is how they drift apart.
--   * It changes no report. Every entry-level reporting query filters
--     `log_sheets.status = 'SUBMITTED'` — the two overview counters, the manual-entry split, the
--     silent-asset scan, both exception queries and both asset-parameter queries — and the
--     integration API only ever exposes finished sheets. Verified query by query before writing
--     this file.
-- =============================================================================


-- -----------------------------------------------------------------------------
-- 1. Who saved the partial values, and from where
-- -----------------------------------------------------------------------------

-- The account whose save produced the current `draft_saved_at`. Nullable: rows that predate
-- this column have a timestamp and no attribution, and there is nothing to recover for them.
ALTER TABLE log_sheets ADD COLUMN draft_saved_by_user_id BIGINT REFERENCES users(id) ON DELETE RESTRICT;

-- WEB (the panel's «ذخیره پیش‌نویس») or MOBILE (a tablet's progress push).
--
-- This exists so the two can never be confused by a later change. The expiry scheduler used to
-- branch on `draft_saved_at IS NOT NULL` and auto-submit the round; that branch is removed in
-- this release, but if a site ever wants it back it must come back for web drafts only — a
-- mobile round that has merely reported its progress has not been "saved for later" by anyone,
-- and auto-submitting it at the deadline would finalise a round the operator is still walking.
ALTER TABLE log_sheets ADD COLUMN draft_source VARCHAR(20);

ALTER TABLE log_sheets ADD CONSTRAINT ck_log_sheets_draft_source
    CHECK (draft_source IS NULL OR draft_source IN ('WEB','MOBILE'));

-- Every draft that exists today came from the web panel — it was the only writer.
UPDATE log_sheets SET draft_source = 'WEB' WHERE draft_saved_at IS NOT NULL AND draft_source IS NULL;

COMMENT ON COLUMN log_sheets.draft_saved_at IS
    'Last time partial values were stored on this sheet without a submission, from either '
    'surface. See draft_source for which one.';


-- -----------------------------------------------------------------------------
-- 2. The progress endpoint's permission
-- -----------------------------------------------------------------------------

INSERT INTO permissions (code, name, category, http_method, endpoint_path) VALUES
('POST:/api/log-sheets/progress', 'API — ثبت پیشرفت لاگ‌شیت (پیش‌نویس)', 'api', 'POST', '/api/log-sheets/progress');

-- Granted to whoever may already deliver a round from a tablet.
--
-- Derived from the existing grant rather than written as a list of the five system role codes,
-- and that is deliberate: an administrator who duplicated OPERATOR gets a role with a NEW code
-- and the same permissions, and a hard-coded list would leave that copy unable to report
-- progress while still able to submit — the exact class of breakage capabilities were
-- introduced to end. V1's blanket CROSS JOIN grant was a one-time snapshot and does not cover
-- rows inserted here, so ADMIN and HIGH_USER are reached through the same rule.
INSERT INTO role_permissions (role_id, permission_id)
SELECT rp.role_id, progress.id
FROM role_permissions rp
JOIN permissions batch    ON batch.id = rp.permission_id AND batch.code = 'POST:/api/log-sheets/batch'
CROSS JOIN permissions progress
WHERE progress.code = 'POST:/api/log-sheets/progress'
ON CONFLICT (role_id, permission_id) DO NOTHING;


-- -----------------------------------------------------------------------------
-- 3. "N of M assets recorded"
-- -----------------------------------------------------------------------------

-- The log-sheet list and the sheet's own page both show how far a round has got, counted as
-- entries that carry a reading. `max_severity IS NOT NULL` is the exact test for that — the
-- evaluator nulls it when form_data holds no answer and always writes at least 'OK' when it
-- does — and this is the third partial index built on that predicate, alongside
-- idx_log_sheet_entries_asset_read and idx_log_sheet_entries_breaches.
--
-- Partial rather than plain: the count is asked for one page of sheets at a time, and the rows
-- that matter are the filled minority. The existing idx_log_sheet_entries_log_sheet_id still
-- serves the denominator.
CREATE INDEX idx_log_sheet_entries_filled ON log_sheet_entries (log_sheet_id)
    WHERE max_severity IS NOT NULL;
