-- =============================================================================
-- V10 — a round-level question may carry media, and a custom sheet may ask questions
--
-- Three changes, all additive, none touching a stored row:
--
--   1. `attachments.asset_id` becomes nullable. NULL means "this photo belongs to the round's
--      own form", which is the one thing a log sheet's attachment can belong to besides an
--      asset. That is what unlocks image / audio / video / location on a round-level question,
--      and it is the whole of what roadmap §22c called "the largest piece".
--   2. Permissions for editing a CUSTOM sheet's own questions. The table has carried
--      `log_sheet_id` as an owner since V9 and nothing wrote it; this is the editor.
--   3. Nothing for `AssetSelectionMode.NONE` — `asset_selection_mode` is VARCHAR(20) with no
--      CHECK, so the third value needs no DDL and no backfill.
-- =============================================================================


-- -----------------------------------------------------------------------------
-- 1. An attachment that belongs to the round rather than to an asset
--
-- WHY NULL RATHER THAN AN `owner_type` DISCRIMINATOR. Inside a log sheet there are exactly two
-- owners an attachment can have — one of its assets, or the sheet's own form — and
-- `log_sheet_id` is NOT NULL either way. A second column would therefore carry one bit of
-- information that `asset_id IS NULL` already carries, and every existing query would have to
-- learn to read it. §22c sketched the general `owner_type`/`owner_id` shape for a form that is
-- not a log sheet at all; this is not that, and paying for it here would be paying early.
--
-- NOTHING ELSE CHANGES. `log_sheet_id` stays NOT NULL and still cascades, the storage-key scheme
-- is per-attachment-id and never mentioned the asset, and the nightly orphan sweep reconciles
-- disk against this table without reading either column.
-- -----------------------------------------------------------------------------
ALTER TABLE attachments ALTER COLUMN asset_id DROP NOT NULL;

COMMENT ON COLUMN attachments.asset_id IS
    'The asset this media belongs to, or NULL when it answers the ROUND''s own question — '
    'a question asked once per sheet rather than once per asset (V9/V10).';

-- The render and ceiling query for the round-level form: all media for one field of one sheet's
-- own form. Partial, because the rows it serves are the rare ones — every attachment taken
-- against an asset is excluded, so the index stays proportional to round-level media rather
-- than to the whole table.
CREATE INDEX idx_attachments_sheet_field
    ON attachments (log_sheet_id, field_key) WHERE asset_id IS NULL;


-- -----------------------------------------------------------------------------
-- 2. The custom sheet's own questions
--
-- A template-raised round gets its questions from the template (V9). A CUSTOM sheet has no
-- template, so it owns them directly through `log_sheet_fields.log_sheet_id` — the column V9
-- created and left without a writer. These are its endpoints.
--
-- Granted to whoever may already CREATE a custom log sheet, derived from that grant rather than
-- written as a list of role codes: a duplicated role has a new code and the same permissions,
-- and a code list would leave the copy able to raise a custom round and unable to put any
-- questions on it. That grant reaches SUPERVISOR as well as ADMIN/HIGH_USER, which is correct
-- and deliberate — a custom sheet is a supervisor's own one-off round, and its questions are
-- part of raising it. The service re-checks the unit the same way `createCustom` does.
-- -----------------------------------------------------------------------------
INSERT INTO permissions (code, name, category, http_method, endpoint_path) VALUES
('GET:/log-sheets/{id}/fields',
 'مشاهده پرسش‌های لاگ‌شیت سفارشی', 'log-sheet', 'GET',  '/log-sheets/{id}/fields'),
('POST:/log-sheets/{id}/fields',
 'افزودن پرسش به لاگ‌شیت سفارشی', 'log-sheet', 'POST', '/log-sheets/{id}/fields'),
('POST:/log-sheets/{id}/fields/{fieldId}',
 'ویرایش پرسش لاگ‌شیت سفارشی', 'log-sheet', 'POST', '/log-sheets/{id}/fields/{fieldId}'),
('POST:/log-sheets/{id}/fields/{fieldId}/state',
 'فعال/غیرفعال کردن پرسش لاگ‌شیت سفارشی', 'log-sheet', 'POST', '/log-sheets/{id}/fields/{fieldId}/state'),
('POST:/log-sheets/{id}/fields/{fieldId}/delete',
 'حذف پرسش لاگ‌شیت سفارشی', 'log-sheet', 'POST', '/log-sheets/{id}/fields/{fieldId}/delete');

INSERT INTO role_permissions (role_id, permission_id)
SELECT rp.role_id, p.id
  FROM role_permissions rp
  JOIN permissions custom ON custom.id = rp.permission_id
                         AND custom.code = 'POST:/log-sheets/custom'
 CROSS JOIN permissions p
 WHERE p.code IN ('GET:/log-sheets/{id}/fields',
                  'POST:/log-sheets/{id}/fields',
                  'POST:/log-sheets/{id}/fields/{fieldId}',
                  'POST:/log-sheets/{id}/fields/{fieldId}/state',
                  'POST:/log-sheets/{id}/fields/{fieldId}/delete')
ON CONFLICT (role_id, permission_id) DO NOTHING;
