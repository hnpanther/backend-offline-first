-- =============================================================================
-- V25 — late work is kept, and a tablet reports what it has not delivered
--
-- Two changes that ship together with the PWA (issue.md B-01, B-02, B-03 here; P-01, P-02, P-03
-- in the PWA). Nothing existing is rewritten.
--
-- 1. log_sheet_void_submissions.late_progress
--
--    Readings of an UNFINISHED round that reached the server after its deadline (B-02). They are
--    still not applied to the round — a live report cannot belong to an earlier moment — but they
--    are now kept for the supervisor, in the same table as every other refused submission, so the
--    review page, the attachment cleanup and the integration exclusions already know about them.
--
--    A progress push has no clientActionId and is re-sent by design, so the rule is ONE row per
--    (sheet, operator), updated in place. The partial unique index makes that a database guarantee
--    rather than a service habit: two pushes racing from the same tablet cannot leave two rows.
--    Ordinary submissions (late_progress = false) are untouched by the index.
--
-- 2. api_sessions.undelivered_count / undelivered_oldest_at / backlog_reported_at
--
--    How many rounds this tablet holds whose readings the server has not received in any form,
--    and the oldest one — reported by the tablet after a sync pass (POST /api/sync/backlog) and
--    shown on «نشست‌های اپ موبایل». Kept on the session because a session exists whether or not
--    device enrolment is on; when it is on, api_sessions.device_id already names the device.
--    NULL means the tablet has never reported (an older app), which the page shows as such.
--
-- 3. POST:/api/sync/backlog, granted to whoever may already deliver a round from a tablet —
--    derived from POST:/api/log-sheets/batch, not listed by role code (V4's reason: a duplicated
--    role gets a new code and the same permissions).
-- =============================================================================

ALTER TABLE log_sheet_void_submissions
    ADD COLUMN late_progress BOOLEAN NOT NULL DEFAULT FALSE;

CREATE UNIQUE INDEX ux_lsvs_late_progress_per_user
    ON log_sheet_void_submissions (log_sheet_id, submitted_by_user_id)
    WHERE late_progress;

ALTER TABLE api_sessions ADD COLUMN undelivered_count INTEGER;
ALTER TABLE api_sessions ADD COLUMN undelivered_oldest_at BIGINT;
ALTER TABLE api_sessions ADD COLUMN backlog_reported_at BIGINT;

ALTER TABLE api_sessions
    ADD CONSTRAINT ck_api_sessions_undelivered_count CHECK (undelivered_count IS NULL OR undelivered_count >= 0);

INSERT INTO permissions (code, name, category, http_method, endpoint_path) VALUES
('POST:/api/sync/backlog', 'API — گزارش راندهای ارسال‌نشده دستگاه', 'api', 'POST', '/api/sync/backlog')
ON CONFLICT (code) DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT rp.role_id, backlog.id
FROM role_permissions rp
JOIN permissions batch ON batch.id = rp.permission_id AND batch.code = 'POST:/api/log-sheets/batch'
CROSS JOIN permissions backlog
WHERE backlog.code = 'POST:/api/sync/backlog'
ON CONFLICT (role_id, permission_id) DO NOTHING;
