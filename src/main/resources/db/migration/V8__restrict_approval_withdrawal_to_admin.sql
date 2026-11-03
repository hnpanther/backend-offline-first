-- =============================================================================
-- V8 — withdrawing an approval becomes the system administrator's alone
--
-- WHAT CHANGES. `POST:/log-sheets/{id}/unapprove` is revoked from every role but ADMIN.
-- `POST:/log-sheets/{id}/approve` is NOT touched: a supervisor still reviews and accepts their
-- own unit's rounds, which is the whole point of the approval step. Only the door back closes.
--
-- WHY. An approval is the record that somebody read a delivered round. Until now V4 granted the
-- withdrawal to whoever may already `void` a completed round — ADMIN, HIGH_USER and SUPERVISOR —
-- so the record could be unmade by the same person who made it, with nothing above them
-- involved. The plant asked for the opposite: once a round is approved, taking that back is an
-- administrator's decision.
--
-- REVOKED FROM EVERY OTHER ROLE, NOT FROM A LIST OF ROLE CODES. An administrator's duplicated
-- role has a NEW code and the same permissions (docs/security.md §3), so `WHERE r.code IN
-- ('SUPERVISOR','HIGH_USER')` would leave a `SUPERVISOR_COPY` holding exactly what this
-- migration exists to take away. Anything that is not ADMIN loses it.
--
-- IT ALSO REMOVES GRANTS SOMEBODY MADE BY HAND, and that is deliberate rather than overlooked:
-- `role_permissions` records that a role HAS a permission, never WHY, so a deliberate grant and
-- an inherited one are indistinguishable here. This is a change of default; an administrator who
-- wants it back grants it in the Roles page, which is the mechanism the request itself asked for.
-- Note that the grant alone is not enough — see below.
--
-- GRANTING IT BACK TAKES TWO THINGS, NOT ONE. `LogSheetAssignmentService.unapprove` calls
-- `requireSupervisorOrAdmin` underneath the authority check, so a role holding this permission
-- and nothing else is still refused, with a message about a relationship rather than a
-- permission. To make a role able to withdraw approvals, grant it BOTH this permission AND
-- `CAP:SUPERVISE_ANY_UNIT` — or give its users an actual supervisor relationship with the unit
-- (`unit_supervisors`). Both are grantable from the Roles page; capabilities are ordinary
-- permission rows in the `capability` category. (AGENTS.md #161.)
--
-- WHAT STAYS TRUE. ADMIN keeps every permission, so
-- `BranchSchemaAndSeedRegressionTest.adminStillHoldsEveryPermissionAfterTheSeedEdits` still
-- passes; this migration only deletes rows belonging to other roles. No `log_sheets` row is
-- touched and no round changes status — an approval already recorded stays recorded.
-- =============================================================================

DELETE FROM role_permissions rp
      USING permissions p, roles admin
      WHERE rp.permission_id = p.id
        AND p.code = 'POST:/log-sheets/{id}/unapprove'
        AND admin.code = 'ADMIN'
        AND rp.role_id <> admin.id;
