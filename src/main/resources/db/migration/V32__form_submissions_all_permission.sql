-- =============================================================================
-- V32 — «همهٔ ارسال‌های فرم» is an administrator page
--
-- WHAT. GET:/form-submissions stays the per-form menu (unit VIEW, derived in V29 from
-- GET:/log-sheets). The unfiltered list — every form, and «ثبت جدید» that may pick any
-- of them — is a separate authority, GET:/form-submissions/all. It is not a second URL:
-- the handler is still GET /form-submissions with no formId. Granted to whoever may
-- create users (ADMIN today; a role duplicated from ADMIN inherits it). Supervisors and
-- operators keep their form menus and do not see this page.
-- =============================================================================

INSERT INTO permissions (code, name, category, http_method, endpoint_path) VALUES
('GET:/form-submissions/all', 'ارسال‌های فرم — همه', 'log-sheet', 'GET', '/form-submissions/all');

INSERT INTO role_permissions (role_id, permission_id)
SELECT rp.role_id, p.id
FROM role_permissions rp
JOIN permissions seed ON seed.id = rp.permission_id AND seed.code = 'POST:/users'
CROSS JOIN permissions p
WHERE p.code = 'GET:/form-submissions/all'
ON CONFLICT (role_id, permission_id) DO NOTHING;
