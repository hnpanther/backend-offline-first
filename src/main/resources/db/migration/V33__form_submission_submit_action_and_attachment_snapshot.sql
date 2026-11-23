-- =============================================================================
-- V33 — form submission history parity with log sheets
--
-- WHAT. (1) form_submission_action_log gains SUBMIT so the detail page shows a
--         registration row immediately after final submit, not only review actions.
--         (2) form_submission_revisions gains attachment_snapshot — the twin of
--         log_sheet_entry_revisions — so superseded answers that removed a photo
--         still describe it in history.
-- =============================================================================

ALTER TABLE form_submission_action_log DROP CONSTRAINT IF EXISTS ck_form_submission_action_log_action;
ALTER TABLE form_submission_action_log
    ADD CONSTRAINT ck_form_submission_action_log_action
        CHECK (action IN ('APPROVE', 'UNAPPROVE', 'VOID', 'UNVOID', 'REOPEN', 'SUBMIT'));

ALTER TABLE form_submission_revisions
    ADD COLUMN IF NOT EXISTS attachment_snapshot JSONB;

COMMENT ON COLUMN form_submission_revisions.attachment_snapshot IS
    'Metadata for attachment ids named in form_data at revision time — survives deletion.';