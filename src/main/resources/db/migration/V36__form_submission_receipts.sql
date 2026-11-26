-- Each SUBMIT entry identifies one immutable delivery cycle, including after reopen.
ALTER TABLE form_submission_action_log ADD COLUMN client_action_id varchar(255);
ALTER TABLE form_submission_action_log ADD CONSTRAINT ck_form_action_receipt
    CHECK (client_action_id IS NULL OR action = 'SUBMIT');
CREATE UNIQUE INDEX ux_form_submit_receipt ON form_submission_action_log (client_action_id)
    WHERE client_action_id IS NOT NULL;

WITH latest AS (
    SELECT DISTINCT ON (form_submission_id) id, form_submission_id
    FROM form_submission_action_log WHERE action = 'SUBMIT'
    ORDER BY form_submission_id, id DESC
)
UPDATE form_submission_action_log a SET client_action_id = s.client_action_id
FROM latest l JOIN form_submissions s ON s.id = l.form_submission_id
WHERE a.id = l.id AND s.client_action_id IS NOT NULL;

-- Pre-V33 submissions have no reliable final actor. Reserve their key without guessing one.
INSERT INTO form_submission_action_log
    (form_submission_id, action, source, action_at, recorded_at, client_action_id)
SELECT s.id, 'SUBMIT', s.source, coalesce(s.submitted_at, s.updated_at), s.updated_at, s.client_action_id
FROM form_submissions s WHERE s.client_action_id IS NOT NULL AND NOT EXISTS (
    SELECT 1 FROM form_submission_action_log a WHERE a.client_action_id = s.client_action_id
);
