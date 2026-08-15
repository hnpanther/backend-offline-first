-- =============================================================================
-- V3 — capabilities: access rules that no longer key off a role's CODE
--
-- WHY THIS EXISTS
--
-- A number of rules used to ask "is this user an ADMIN?" by comparing the role code
-- (SecurityUtils.isAdmin(), hasRole("HIGH_USER"), isUnitScopedOnly() = !ADMIN && !HIGH_USER).
-- That made roles un-copyable: the "ساخت نقش مشابه" button copies a role's PERMISSIONS but
-- gives the copy a NEW CODE, so every rule written against the original's code stopped
-- recognising it. A duplicate of ADMIN held all 123 permissions and was still not an admin —
-- it could not view another user's import job, complete a sheet it was not assigned, or see
-- outside its own units.
--
-- Capabilities move those decisions out of the code and into data. They live in the existing
-- `permissions` table on purpose: role duplication already copies `role_permissions`, so the
-- copyability problem is solved by construction rather than by another mechanism that would
-- have to be taught to copy itself.
--
-- SHAPE
--
--   code           CAP:SOMETHING          (deliberately not METHOD:/path — nothing should try
--                                          to parse a capability as a route)
--   category       'capability'           (the Roles UI groups by this)
--   http_method    NULL                   (not an endpoint)
--   endpoint_path  NULL
--
-- THE GRANTS BELOW REPRODUCE TODAY'S BEHAVIOUR EXACTLY. This migration must not change who
-- can do what — the code change that reads these capabilities does that separately, and it is
-- verified by the test suite plus a divergence check. If a grant here is wrong, the failure is
-- silent: either an administrator quietly loses reach, or a scoped role quietly gains it.
--
-- ONE CONSEQUENCE WORTH KNOWING: ADMIN's power now lives in data and could in principle be
-- revoked from the Roles page. `RoleService` refuses to remove a capability from a system
-- role for exactly that reason — see its `assertSystemRoleCapabilitiesIntact`.
-- =============================================================================

INSERT INTO permissions (code, name, category, http_method, endpoint_path) VALUES
    -- The big one: 14 call sites. Replaces `!isUnitScopedOnly()`. Whoever holds it sees every
    -- operational unit; whoever does not is filtered to the units they are assigned to.
    ('CAP:SCOPE_PLANT_WIDE', 'دید سراسری کارخانه', 'capability', NULL, NULL),

    -- Template writes. Two separate questions, which is why there are two capabilities:
    -- "may write templates at all" and "may write them for a unit I do not supervise".
    ('CAP:TEMPLATE_MANAGE', 'مدیریت قالب لاگ‌شیت', 'capability', NULL, NULL),
    ('CAP:TEMPLATE_MANAGE_ANY_UNIT', 'مدیریت قالب در هر واحد', 'capability', NULL, NULL),

    -- Template reads, same split.
    ('CAP:TEMPLATE_VIEW_ANY_UNIT', 'مشاهده قالب همه واحدها', 'capability', NULL, NULL),
    ('CAP:TEMPLATE_VIEW_SUPERVISED', 'مشاهده قالب واحدهای تحت سرپرستی', 'capability', NULL, NULL),

    -- Deciding an asset's real-world state.
    ('CAP:ASSET_STATUS_DECIDE', 'تصمیم‌گیری تغییر وضعیت دارایی', 'capability', NULL, NULL),

    -- Web completion. _ANY skips the assignee check entirely; _SELF allows completing a sheet
    -- that is already assigned to you (what SENIOR_OPERATOR exists for). Supervisors reach the
    -- same place through isSupervisorOf, which stays a scope check, not a role check.
    ('CAP:LOGSHEET_COMPLETE_WEB_ANY', 'تکمیل هر لاگ‌شیت در وب', 'capability', NULL, NULL),
    ('CAP:LOGSHEET_COMPLETE_WEB_SELF', 'تکمیل لاگ‌شیت خود در وب', 'capability', NULL, NULL),

    -- Act as the supervisor of a unit you do not actually supervise: assign, reassign,
    -- takeover, extend, raise a fault report from the panel.
    ('CAP:SUPERVISE_ANY_UNIT', 'اختیارات سرپرست در همه واحدها', 'capability', NULL, NULL),

    -- Import jobs are private to whoever submitted them; this lifts that.
    ('CAP:IMPORT_JOB_VIEW_ALL', 'مشاهده عملیات ورود همه کاربران', 'capability', NULL, NULL),

    -- Marking an NFC fault report reviewed is an assertion, so it is deliberately narrower
    -- than being able to read the list.
    ('CAP:NFC_FAULT_REVIEW', 'بررسی گزارش خرابی NFC', 'capability', NULL, NULL);


-- ── Grants: an exact replay of the role-code logic being replaced ────────────────────────

-- ADMIN gets every capability. This is the code path `SecurityUtils.isAdmin()` used to serve.
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
  FROM roles r CROSS JOIN permissions p
 WHERE r.code = 'ADMIN' AND p.category = 'capability';

-- HIGH_USER: plant-wide sight and template writing, but NOT template writing outside the units
-- it supervises (assertCanManageUnit fell through to the isSupervisorOf check for it), and not
-- the admin-only overrides.
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
  FROM roles r CROSS JOIN permissions p
 WHERE r.code = 'HIGH_USER'
   AND p.code IN ('CAP:SCOPE_PLANT_WIDE',
                  'CAP:TEMPLATE_MANAGE',
                  'CAP:TEMPLATE_VIEW_SUPERVISED',
                  'CAP:ASSET_STATUS_DECIDE');

-- SUPERVISOR: may decide asset status (requireDecider named it explicitly) and may see the
-- templates of units it supervises. Everything else it does flows from isSupervisorOf, which
-- is a scope check on real unit assignments and is not affected by this migration.
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
  FROM roles r CROSS JOIN permissions p
 WHERE r.code = 'SUPERVISOR'
   AND p.code IN ('CAP:TEMPLATE_VIEW_SUPERVISED',
                  'CAP:ASSET_STATUS_DECIDE');

-- SENIOR_OPERATOR: exactly one thing distinguishes it from OPERATOR — completing its own
-- assigned sheet in the browser instead of only in the app.
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
  FROM roles r CROSS JOIN permissions p
 WHERE r.code = 'SENIOR_OPERATOR'
   AND p.code = 'CAP:LOGSHEET_COMPLETE_WEB_SELF';

-- OPERATOR gets no capability at all, which is the correct reading of the old code: every
-- role-code check it met evaluated to false.
