-- =============================================================================
-- V27 — the permission behind «رد همه‌ی درخواست‌های در انتظار» on the devices page (issue B-12)
--
-- WHY. The enrolment request endpoint is public — the tablet asking is by definition not yet
-- trusted — and fifty live pending requests in total is a ceiling a handful of addresses can hold
-- full, which stops every real tablet enrolling for half an hour at a time. nginx bounds the
-- rate (deploy/); this is the approver's way out of a flood that got through: one click turns
-- down every request still waiting, instead of fifty.
--
-- WHY A NEW PERMISSION. In this codebase an endpoint is a permission: the Roles page is built by
-- reading this table, so a path with no row here is a path no administrator can grant.
--
-- WHO GETS IT. Whoever may already turn down one request, derived from that grant rather than
-- written as a list of role codes, for the reason V7 gives (a duplicated role gets a NEW code and
-- the same permissions). Rejecting all of them is that same decision, made for each.
-- =============================================================================

INSERT INTO permissions (code, name, category, http_method, endpoint_path) VALUES
('POST:/devices/pending/reject',
 'رد همه‌ی درخواست‌های ثبت دستگاه در انتظار',
 'admin',
 'POST',
 '/devices/pending/reject');

INSERT INTO role_permissions (role_id, permission_id)
SELECT rp.role_id, bulk.id
  FROM role_permissions rp
  JOIN permissions one ON one.id = rp.permission_id
                      AND one.code = 'POST:/devices/{id}/revoke'
 CROSS JOIN permissions bulk
 WHERE bulk.code = 'POST:/devices/pending/reject'
ON CONFLICT (role_id, permission_id) DO NOTHING;
