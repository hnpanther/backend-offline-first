-- =============================================================================
-- V6 — a supervisor's approval, a deadline change that says what it changed,
--      and the metadata of an attachment a correction removed
--
-- Four unrelated changes in one file because they ship together. Each keeps its own header.
-- =============================================================================


-- #############################################################################
-- SECTION 1 — approval
-- #############################################################################
--
-- A completed round now has one more step: a supervisor reads it and accepts it. The status
-- reaches APPROVED from SUBMITTED and returns only to SUBMITTED; nothing else touches it.
--
-- WHY THIS IS A STATUS AND NOT A FLAG
--
-- It is a lifecycle state, and this schema already models lifecycle in `status` with a companion
-- timestamp per transition (claimed_at, started_at, completed_at, expired_at, cancelled_at). A
-- nullable `approved_at` alone would have been cheaper — no condition anywhere would have needed
-- touching — but it would also have left the question "what does status=VOIDED with approved_at
-- set mean?" permanently open, and every future reader would have to reconstruct the answer.
--
-- THE COST, AND HOW IT IS PAID
--
-- Adding a status means every condition that asks "was this round completed" has to accept both
-- values. There are about forty of them and **a missed one is silent** — no error, just a smaller
-- number in a report about a plant. So the rule is mechanical rather than remembered:
-- `LogSheetStatus.COMPLETED_STATUSES` is the only way to ask, and
-- `CompletedStatusConditionTest` fails the build for anything that names SUBMITTED alone outside
-- a short allow-list of transition guards.
--
-- NO BACKFILL, DELIBERATELY
--
-- Every existing SUBMITTED sheet stays SUBMITTED. Nobody approved them, and stamping an approval
-- nobody performed would be inventing a review. Expect the "awaiting approval" figure to be large
-- on the first day; that number is true.

ALTER TABLE log_sheets ADD COLUMN approved_at         BIGINT;
ALTER TABLE log_sheets ADD COLUMN approved_by_user_id BIGINT REFERENCES users(id) ON DELETE RESTRICT;

COMMENT ON COLUMN log_sheets.approved_at IS
    'When a supervisor accepted this completed round. Set with status = APPROVED and cleared '
    'when the approval is withdrawn. The approver may be the same person who completed it.';

-- The two endpoints. Their own permission rows because approving is a distinct authority from
-- voiding even though the same people usually hold both — a site that wants a separate reviewer
-- role needs to be able to say so.
INSERT INTO permissions (code, name, category, http_method, endpoint_path) VALUES
('POST:/log-sheets/{id}/approve',   'تأیید لاگ‌شیت',      'log-sheet', 'POST', '/log-sheets/{id}/approve'),
('POST:/log-sheets/{id}/unapprove', 'لغو تأیید لاگ‌شیت', 'log-sheet', 'POST', '/log-sheets/{id}/unapprove');

-- Granted to whoever may already void a completed round — the same supervisory judgement.
--
-- Derived from the existing grant rather than written as a list of system role codes, for the
-- reason V5 records: an administrator's duplicated role has a NEW code and the same permissions,
-- and a hard-coded list would leave that copy able to void a round and unable to approve one.
-- V1's blanket CROSS JOIN grant was a one-time snapshot and does not cover rows inserted here, so
-- ADMIN and HIGH_USER are reached through the same rule.
INSERT INTO role_permissions (role_id, permission_id)
SELECT rp.role_id, p.id
FROM role_permissions rp
JOIN permissions v ON v.id = rp.permission_id AND v.code = 'POST:/log-sheets/{id}/void'
CROSS JOIN permissions p
WHERE p.code IN ('POST:/log-sheets/{id}/approve', 'POST:/log-sheets/{id}/unapprove')
ON CONFLICT (role_id, permission_id) DO NOTHING;

-- "Completed but not yet reviewed" is the supervisor's queue, and it is a small slice of a table
-- that grows with every round ever run. Partial so the index stays proportional to the backlog
-- rather than to the history.
CREATE INDEX idx_log_sheets_awaiting_approval ON log_sheets (operational_unit_id, completed_at)
    WHERE status = 'SUBMITTED';


-- #############################################################################
-- SECTION 2 — what an attachment was, after it is gone
-- #############################################################################
--
-- `log_sheet_entry_revisions` (V4) keeps the reading a correction replaced. When that reading was
-- a photo or a voice note, the row holds the attachment's **id** — and `AttachmentService.delete`
-- removes the row and the file outright, so the id resolves to nothing and the history panel can
-- only say «فایل پیوست در دسترس نیست».
--
-- That is honest but useless: it cannot distinguish "a photo was deleted here" from "the file is
-- missing from storage", and it says nothing about what the deleted evidence was.
--
-- This column carries the metadata forward: for each attachment id referenced by the superseded
-- value, its kind, mime type, size, duration and who captured it when. The bytes are still gone —
-- keeping those is a soft-delete design with a disk-retention story behind it, deliberately not
-- taken on here — but the record can now say "a 20-second voice note recorded by X at 08:15 was
-- removed", which is what a reviewer actually needs to know.

ALTER TABLE log_sheet_entry_revisions ADD COLUMN attachment_snapshot JSONB;

COMMENT ON COLUMN log_sheet_entry_revisions.attachment_snapshot IS
    'Metadata for each attachment the superseded value referenced, keyed by attachment id: '
    'kind, mimeType, sizeBytes, durationMs, width, height, uploadedAt, createdByUserId. Written '
    'at revision time because the rows it describes may be deleted afterwards. Null when the '
    'superseded value referenced no attachments.';


-- #############################################################################
-- SECTION 3 — pagination for the two review queues
-- #############################################################################
--
-- `/nfc-fault-reports` loaded every row ever filed and rendered them all. It grows without bound
-- in normal use — one row per broken chip, nothing ever deletes them — and it is read most on the
-- days it is longest. It now filters and pages in SQL, which needs its ordering indexed or the
-- database sorts the whole table to produce twenty-five rows.
--
-- Both columns, in the query's own order. `created_at` is the reporting clock and repeats freely
-- (a phone syncing a backlog files several reports in the same millisecond), so the queue breaks
-- ties on the id — and an index on `created_at` alone would leave the database re-sorting each
-- group of ties, which is exactly the boundary a page break can fall inside.

CREATE INDEX idx_nfc_fault_reports_created_at ON nfc_fault_reports (created_at DESC, id DESC);

-- `/asset-status-requests` was already paged and filtered in SQL, and orders by `id DESC` — the
-- primary key's own index serves that, so it needs nothing here. Its defect was in the pager:
-- `fragments/list-toolbar :: pagination` built links from a hard-coded list of parameter names
-- and dropped every filter called anything else, so page two of a filtered list was page two of
-- the unfiltered one. Fixed in `ListFilterAdvice`, which is not a schema change.
