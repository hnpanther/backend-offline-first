-- =============================================================================
-- V7 — the permission behind «غیرفعال کردن فیلد» (switching a class parameter off)
--
-- WHY A NEW PERMISSION RATHER THAN FOLDING IT INTO THE EDIT ONE.
-- `field_definitions.deleted` has existed since V1, eight backend readers and the PWA have
-- always honoured it, and nothing has ever written it. Wiring the write half up means a new
-- endpoint, and in this codebase an endpoint is a permission: the Roles page is built by
-- reading this table, so a path with no row here is a path no administrator can grant and no
-- auditor can see. Hiding it inside «ویرایش فیلد کلاس» would bury a real decision — this
-- parameter stops being collected — inside one that looks cosmetic.
--
-- WHAT IT IS NOT. It is not a delete. The row stays, its bands stay, every sheet that froze
-- the parameter keeps rendering it, and every reading already stored keeps its definition.
-- The hard delete keeps its own permission and is, from this release, refused outright when
-- anything still refers to the key.
--
-- WHO GETS IT. Whoever may already remove a parameter, derived from that grant rather than
-- written as a list of role codes. An administrator who duplicated a role gets a role with a
-- NEW code and the same permissions; a hard-coded list would leave that copy able to delete a
-- parameter outright while unable to take the safe route — the exact shape of breakage
-- `docs/security.md` warns about. V1's blanket CROSS JOIN grant to ADMIN was a one-time
-- snapshot and does not reach rows inserted here, so ADMIN comes through the same rule.
-- (BranchSchemaAndSeedRegressionTest asserts ADMIN still holds every permission, and fails
-- this migration if that stops being true.)
-- =============================================================================

INSERT INTO permissions (code, name, category, http_method, endpoint_path) VALUES
('POST:/asset-classes/{classId}/fields/{fieldId}/state',
 'فعال/غیرفعال کردن فیلد کلاس',
 'master-data',
 'POST',
 '/asset-classes/{classId}/fields/{fieldId}/state');

INSERT INTO role_permissions (role_id, permission_id)
SELECT rp.role_id, state.id
  FROM role_permissions rp
  JOIN permissions del ON del.id = rp.permission_id
                      AND del.code = 'POST:/asset-classes/{classId}/fields/{fieldId}/delete'
 CROSS JOIN permissions state
 WHERE state.code = 'POST:/asset-classes/{classId}/fields/{fieldId}/state'
ON CONFLICT (role_id, permission_id) DO NOTHING;
