-- =============================================================================
-- V15 — a voided sync's photographs can reach the server, as that void's evidence
--
-- A late submission from somebody the sheet no longer belongs to is kept as a void (V14 added the
-- round's answers to it), and its readings name their photographs by id. The photographs travel
-- separately, after the submit — and an upload is accepted only from somebody who may still write
-- the sheet. Once a takeover, reassignment or release had moved it, every one of them was refused
-- with 403: the review page said «فایل پیوست در دسترس نیست», the files stayed on the tablet, and
-- the tablet retried them for ever (roadmap §35; PWA roadmap §16). Found on an operational sheet
-- whose void named two photographs and whose `attachments` held no row at all.
--
-- `void_submission_id` marks a row as evidence for one void rather than part of the live round.
-- The upload path accepts such a row only from that void's submitter and only for an id that void
-- names; everything that reads the live round — the per-field ceiling, the web fill page's
-- reference adoption, the bundle, the integration API — leaves it out, and it cannot be deleted.
--
-- Nullable, NULL for every existing row: they are all part of a live round. ON DELETE CASCADE
-- like `log_sheet_id`, since a void is only ever removed together with its sheet.
-- =============================================================================

ALTER TABLE attachments
    ADD COLUMN void_submission_id BIGINT
        REFERENCES log_sheet_void_submissions(id) ON DELETE CASCADE;

COMMENT ON COLUMN attachments.void_submission_id IS
    'Set when the file is evidence for one voided submission rather than part of the live round; NULL otherwise.';
