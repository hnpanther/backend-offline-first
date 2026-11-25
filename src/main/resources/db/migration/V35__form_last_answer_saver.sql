-- Snapshot the actor on successful answer saves. A review or a reopen changes only status.
ALTER TABLE form_submissions
    ADD COLUMN last_saved_by_user_id bigint REFERENCES users(id) ON DELETE RESTRICT,
    ADD COLUMN last_saved_by_name varchar(255);

-- Recover only known saves. A revision taken on REOPEN has status SUBMITTED and names
-- the reviewer, so only revisions superseded while the filing was a DRAFT are evidence.
WITH saves AS (
    SELECT form_submission_id, actor_user_id AS user_id, action_at AS saved_at, id, 1 AS priority
    FROM form_submission_action_log WHERE action = 'SUBMIT'
    UNION ALL
    SELECT form_submission_id, superseded_by_user_id, superseded_at, id, 0
    FROM form_submission_revisions WHERE submission_status = 'DRAFT'
), latest AS (
    SELECT DISTINCT ON (form_submission_id) form_submission_id, user_id
    FROM saves
    ORDER BY form_submission_id, saved_at DESC, priority DESC, id DESC
)
UPDATE form_submissions AS f
SET last_saved_by_user_id = u.id, last_saved_by_name = coalesce(nullif(trim(u.full_name), ''), u.username)
FROM latest JOIN users AS u ON u.id = latest.user_id
WHERE f.id = latest.form_submission_id;

CREATE INDEX idx_form_submissions_last_saved_by ON form_submissions (last_saved_by_user_id)
    WHERE last_saved_by_user_id IS NOT NULL;
