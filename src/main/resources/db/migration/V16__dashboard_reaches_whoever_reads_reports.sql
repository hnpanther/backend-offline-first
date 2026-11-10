-- =============================================================================
-- V16 — the landing dashboard reaches whoever may already read a report
--
-- WHAT CHANGES. `GET:/` — the panel's landing page, permission category `general` — is granted
-- to every role that already holds `GET:/reports`. On the seeded roles that is SUPERVISOR, which
-- did not hold it before; ADMIN and HIGH_USER already did, so for them this inserts nothing.
-- SENIOR_OPERATOR and OPERATOR hold neither and are untouched: they keep landing on «کارتابل من».
--
-- WHY. `GET:/` used to guard an inventory counter — how many locations, assets, users and log
-- sheets exist plant-wide — which is an administrator's view of a database rather than anybody's
-- view of their work, and which a unit-scoped reader must not be shown at all: those counts are
-- whole-plant totals with no scope in them. That page is now a periodic management dashboard
-- broken down by operational unit, narrowed by the viewer's own units through the same
-- `visibleUnitIds()` every report uses. A supervisor is the reader it was rebuilt for.
--
-- DERIVED FROM AN EXISTING GRANT, NOT FROM A LIST OF ROLE CODES. `GET:/reports` is the authority
-- that already means «this role may read aggregate numbers about work it is responsible for»,
-- and the dashboard is exactly that question asked about one period. Deriving the grant is also
-- the only shape that survives role duplication: a copy of SUPERVISOR has a NEW code and the
-- same permissions (docs/security.md §3), so `WHERE r.code IN ('SUPERVISOR')` would leave a
-- `SUPERVISOR_COPY` able to open every report and refused by the landing page — the class of
-- breakage capabilities were introduced to end. Same rule as V4's progress permission and V9's
-- template-question permissions.
--
-- WHAT ELSE THIS CHANGES, AND IT IS INTENDED. `SecurityUtils.homePath()` answers «where does
-- this user land» by asking for `GET:/` and nothing else — deliberately, because a rule written
-- against a role or a scope drifts from what the page actually requires (docs/security.md §2).
-- So a supervisor now lands on the dashboard after login, the navbar brand and every
-- breadcrumb's «خانه» step point there for them, and the sidebar's dashboard entry appears.
-- That follows from the grant rather than from a second rule, which is the property worth
-- keeping: there is still exactly one place that decides it.
--
-- NO SCHEMA CHANGE. No table, column, index or constraint is touched — this file is seed data
-- only. The dashboard's two aggregate queries read `log_sheets` and `log_sheet_entries` through
-- the plans the report suite already measured (docs/performance.md §2), so nothing new needed
-- an index.
--
-- WHAT STAYS TRUE. ADMIN keeps every permission, so
-- `BranchSchemaAndSeedRegressionTest.adminStillHoldsEveryPermissionAfterTheSeedEdits` is
-- unaffected; this migration only inserts, and only for roles that asked for it by already
-- holding `GET:/reports`.
-- =============================================================================

INSERT INTO role_permissions (role_id, permission_id)
SELECT rp.role_id, dashboard.id
FROM role_permissions rp
JOIN permissions reports ON reports.id = rp.permission_id AND reports.code = 'GET:/reports'
CROSS JOIN permissions dashboard
WHERE dashboard.code = 'GET:/'
ON CONFLICT (role_id, permission_id) DO NOTHING;
