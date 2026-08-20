-- =============================================================================
-- V3 — everything between the V2 release and the next one
--
-- V2 is the last version any operational database has run. Everything since then was developed
-- as separate migrations while unreleased, and is consolidated here into ONE file so production
-- advances by a single version rather than by several for changes it has never seen.
--
-- The rule behind that: a migration that has never reached an operational database is still
-- editable; one that has is not. V1 and V2 are closed. This file is closed from the moment it
-- is deployed.
--
-- WHAT IS IN HERE, in the order it appears:
--
--   1. Capabilities — access rules that no longer key off a role's CODE, so a duplicated role
--      inherits them (permissions rows + the grants that replay today's behaviour).
--   2. users.org_unit / users.org_position — personnel attributes that grant nothing.
--   3. Mobile policy settings seeded into app_settings.
--   4. The Integration API — api_keys, api_key_usage, four admin permissions, one index.
--   5. audit_log.actor_user_id -> ON DELETE SET NULL, so an audit row outlives its actor.
--   6. log_sheet_entries.form_data holds only fields that were actually answered — the data
--      repair for the blank keys the web fill form used to write onto every entry of a sheet.
--
-- Each section keeps its own original header; nothing below has been rewritten, only joined.
-- Ordering matters in one place only: sections 1 and 4 both INSERT into `permissions`, and both
-- are explicit about which role gets what, so neither depends on the other having run. Section 6
-- is last because it is the only one that touches operational data rather than structure.
--
-- REPAIRING A DATABASE THAT ALREADY APPLIED THE EARLIER FILES
--
-- Development and test databases had these as several migrations. They were realigned by hand:
-- the extra history rows deleted, and the surviving V3 row's `script`/`description`/`checksum`
-- pointed at this file. Nothing was re-executed — every object below already existed there. See
-- AGENTS.md gotcha #86 for the exact procedure, including the stale copies in `target/classes`
-- that will otherwise make Flyway refuse to start.
-- =============================================================================


-- #############################################################################
-- SECTION 1-3 — capabilities, user org fields, mobile policy settings
-- #############################################################################

-- =============================================================================
-- Capabilities: access rules that no longer key off a role's CODE
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

-- =============================================================================
-- Users: organizational unit and position
--
-- Folded into V3 deliberately. Production has only ever run V1 → V2, so V3 is still an
-- unreleased migration there and arrives as one unit; keeping these as separate V4/V5 files
-- would have added two versions to production for changes it has never seen. Test and
-- development databases that had already applied the earlier V3/V4 were repaired by hand
-- (checksum realigned, the V4 history row removed) — see AGENTS.md.
--
--   org_unit      The organizational unit from the org chart ("مهندسی نگهداری و تعمیرات").
--                 **Free text, and deliberately unrelated to `operational_units`.** That table
--                 is an access-control structure: it decides which log sheets a user can see
--                 and act on, maintained through unit_supervisors / unit_operators. This column
--                 is a personnel attribute that appears on lists and exports and grants
--                 nothing. Making it a foreign key would quietly turn a typo in an HR
--                 spreadsheet into a change of access scope.
--
--   org_position  Job title ("کارشناس ارشد ابزار دقیق"). Also free text, also grants nothing;
--                 permissions come from roles.
--
-- Both optional, and nullable rather than NOT NULL DEFAULT '' so "not recorded" stays
-- distinguishable from "recorded as blank". 150 characters is generous for real values while
-- staying short enough that an import cannot paste an essay into a column lists render inline.
-- =============================================================================

ALTER TABLE users ADD COLUMN org_unit     VARCHAR(150);
ALTER TABLE users ADD COLUMN org_position VARCHAR(150);

-- =============================================================================
-- The two mobile policies, seeded like every other setting
--
-- Both work without a row — AppSettingsService falls back to the same default — but that left
-- them as the only settings invisible in the database: `SELECT * FROM app_settings` did not
-- list them, and a pg_dump carried an absence rather than an answer.
--
-- ON CONFLICT DO NOTHING because an installation that has already used the Settings page holds
-- these rows with whatever the administrator chose. A plain INSERT would fail on the primary
-- key and block the upgrade; an UPSERT would be worse — it would overwrite a deliberate
-- decision (including a scan rule someone relaxed on purpose while serials are being recorded)
-- with the shipped default on every deployment.
--
-- Both default ON. For the scan rule that is the point: the strict check is what makes a scan
-- mean "I stood in front of this equipment", so a fresh or restored database comes up strict.
-- =============================================================================

INSERT INTO app_settings (setting_key, value, updated_at) VALUES
('attachments.image_annotation_enabled', 'true', 0),
('nfc.strict_serial_match', 'true', 0)
ON CONFLICT (setting_key) DO NOTHING;

-- =============================================================================
-- A site switch ABOVE the manual-tag-entry permission
--
-- Manual entry has always been a permission (SUPERVISOR and SENIOR_OPERATOR hold it). This is
-- the switch above it, and the two are an AND: with it off nobody may type a tag id however
-- privileged, and an asset can only be opened by scanning it or by filing an NFC fault report.
--
-- It restricts, never grants — the opposite of the device-side switch of the same name it
-- replaces, which handed manual entry to every caller.
--
-- Seeded ON so upgrading changes nothing for a site already relying on manual entry; tightening
-- is then a deliberate administrative act, visible and audited on the Settings page. The code's
-- fallback for a missing or unreadable row is OFF, which is the opposite on purpose: a seeded
-- value is an administrator's recorded choice, while a value nobody can read is not an
-- authorisation to grant.
-- =============================================================================

INSERT INTO app_settings (setting_key, value, updated_at) VALUES
('nfc.manual_entry_enabled', 'true', 0)
ON CONFLICT (setting_key) DO NOTHING;

-- #############################################################################
-- SECTION 4 — the Integration API for third-party systems
-- #############################################################################

-- =============================================================================
-- Integration API for third-party systems
--
-- WHAT THIS ADDS
--
--   api_keys        One credential per external system. Verified by the /integration/**
--                   filter chain; never by a user session and never by a JWT.
--   api_key_usage   One row per integration request — the audit trail the requirement asks
--                   for ("API Key usage should be auditable ... including the client/system
--                   and request time").
--   permissions     Four admin authorities for the key-management page, granted to ADMIN.
--   one index       On log_sheets, for the date-range query the integration polls.
--
-- WHY A SEPARATE CREDENTIAL TABLE RATHER THAN A SERVICE USER
--
-- A service account would inherit the whole user model: a role, unit assignments, a password
-- policy, a login-attempt lock, an api_sessions row, and — worst — the three access layers in
-- docs/security.md, every one of which assumes a human principal. The requirement is the
-- opposite of that: "third-party endpoints must be separate from normal user authentication
-- APIs". A key is not a user, so it is not stored as one.
--
-- THE KEY IS SHOWN ONCE
--
-- Presented format: lsk_<key_id>_<secret>
--   key_id      public, indexed, unique — the lookup column
--   secret      256 bits of SecureRandom, never stored
--   secret_hash SHA-256 of the secret
--
-- Splitting the key into a public id and a secret is what makes verification ONE indexed read
-- plus ONE hash comparison. The alternative — hashing the presented key and scanning — either
-- forces a table scan or makes the hash the primary key, and then an administrator cannot be
-- shown which key a usage row belongs to.
--
-- SHA-256 and not BCrypt, deliberately. A slow KDF exists to make low-entropy passwords
-- expensive to guess. This secret is 256 random bits: there is nothing to guess, and BCrypt's
-- ~100 ms would be paid on every request of an integration that may poll every minute.
-- This is the same reasoning behind GitHub's and Stripe's key formats.
--
-- REVOKE, NEVER DELETE
--
-- The row survives revocation so past api_key_usage rows stay attributable, and so that
-- disabling one integration provably cannot affect another (each key is an independent row;
-- there is no shared state between them).
--
-- DISABLE vs REVOKE — two different admin actions, on purpose:
--   active = false   reversible pause. "Stop this integration until we finish the migration."
--   revoked_at set   permanent. The key can never be re-enabled; a new one must be issued.
-- Both are refused at the same place in the filter, so neither depends on the other.
-- =============================================================================


-- =============================================================================
-- TABLE: api_keys
-- One credential per third-party system.
--
-- Columns:
--   client_name    Identifies the system/client this key belongs to (required, unique
--                  case-insensitively — two keys called "ERP" is how the wrong one gets
--                  revoked). Re-issuing for the same client means revoking and creating,
--                  which is why uniqueness is partial on non-revoked rows only.
--   description    Free-text note: who owns the integration, which ticket asked for it.
--   key_id         Public half of the presented key. The per-request lookup key.
--   secret_hash    SHA-256 (hex) of the secret half. The secret itself is never stored.
--   prefix         First few characters of the presented key, kept so the admin list can
--                  show "lsk_a1b2c3d4…" — enough for a human to match a key against what
--                  the integrator has, and useless to an attacker.
--   active         Reversible enable/disable switch.
--   expires_at     Optional hard expiry (epoch millis). NULL = does not expire.
--   revoked_*      Permanent revocation, with who and why.
--   last_used_at   Throttled touch (see ApiKeyService.LAST_USED_THROTTLE_MS) so a polling
--                  integration does not cause a write per request. Updated with a
--                  @Modifying query, never save(), so it never reaches the audit aspect.
-- =============================================================================
CREATE TABLE api_keys (
    id                 BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    client_name        VARCHAR(255) NOT NULL,
    description        VARCHAR(1000),
    key_id             VARCHAR(64)  NOT NULL,
    secret_hash        VARCHAR(64)  NOT NULL,
    prefix             VARCHAR(32)  NOT NULL,
    active             BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at         BIGINT       NOT NULL,
    created_by_user_id BIGINT,
    expires_at         BIGINT,
    revoked_at         BIGINT,
    revoked_by         BIGINT,
    revoke_reason      VARCHAR(500),
    last_used_at       BIGINT,
    CONSTRAINT uk_api_keys_key_id UNIQUE (key_id),
    CONSTRAINT fk_api_keys_created_by FOREIGN KEY (created_by_user_id) REFERENCES users (id),
    CONSTRAINT fk_api_keys_revoked_by FOREIGN KEY (revoked_by) REFERENCES users (id)
);

-- One live key per client name. Partial on non-revoked rows: revoking and re-issuing for the
-- same client is the normal rotation path and must not collide with the retired row.
CREATE UNIQUE INDEX ux_api_keys_client_name_live
    ON api_keys (LOWER(client_name)) WHERE revoked_at IS NULL;

CREATE INDEX idx_api_keys_revoked_at ON api_keys (revoked_at);


-- =============================================================================
-- TABLE: api_key_usage
-- One row per request that reached the integration chain, including the rejected ones.
--
-- A dedicated table rather than audit_log, for two reasons that both matter:
--   • audit_log records CHANGES to rows. An integration only reads, so every one of these
--     would be a change record for a change that did not happen.
--   • an integration polling every minute writes ~1,400 rows a day. Mixed into audit_log
--     that drowns the record of who edited what, which is the one question that table
--     exists to answer.
--
-- api_key_id is nullable ON PURPOSE: a request presenting an unknown key has no key to point
-- at, and those are precisely the rows an administrator wants to see. key_id and client_name
-- are denormalised so the row still reads correctly after the key row is gone, and so the
-- listing needs no join.
--
-- Written asynchronously on auditExecutor. The response does not wait for it, and its
-- CallerRunsPolicy means a burst degrades to a synchronous insert rather than to a dropped
-- audit row (see AsyncConfig, and gotcha #68).
-- =============================================================================
CREATE TABLE api_key_usage (
    id           BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    api_key_id   BIGINT,
    key_id       VARCHAR(64),
    client_name  VARCHAR(255),
    method       VARCHAR(10)  NOT NULL,
    path         VARCHAR(512) NOT NULL,
    query_string VARCHAR(1000),
    status_code  INT          NOT NULL,
    outcome      VARCHAR(32)  NOT NULL,
    result_count INT,
    duration_ms  BIGINT,
    ip_address   VARCHAR(64),
    user_agent   VARCHAR(512),
    requested_at BIGINT       NOT NULL,
    CONSTRAINT fk_api_key_usage_key FOREIGN KEY (api_key_id) REFERENCES api_keys (id)
);

CREATE INDEX idx_api_key_usage_requested_at ON api_key_usage (requested_at);
CREATE INDEX idx_api_key_usage_key_requested ON api_key_usage (api_key_id, requested_at);


-- =============================================================================
-- INDEX: the integration's date-range query
--
-- The read pattern here is the opposite of the rest of the application. A tablet syncs a
-- handful of sheets it owns; an integration asks "everything that finished between these two
-- instants", possibly every minute, and gets back a page of a much larger set.
--
-- The expression matches IntegrationLogSheetRepository's ORDER BY and WHERE exactly, which is
-- what lets PostgreSQL use it for both. It is deliberately NOT partial on status: a partial
-- index's predicate has to be *proved* implied by the query, and PostgreSQL cannot prove that
-- for a bound parameter list — the index would silently never be used.
--
-- Why COALESCE at all: "when did this sheet finish" is a different column per state, and each
-- one is written exactly once, so the fallback chain is unambiguous rather than a guess.
--   SUBMITTED / VOIDED   completed_at  (always set — every completion path writes it)
--   EXPIRED              expired_at
--   CANCELLED            cancelled_at
-- =============================================================================
CREATE INDEX idx_log_sheets_status_finalized_at
    ON log_sheets (status, (COALESCE(completed_at, expired_at, cancelled_at)));


-- =============================================================================
-- PERMISSIONS — the admin page that manages keys
--
-- The integration endpoints themselves get NO permission row. They are not reachable by any
-- role: the /integration/** chain has no user principal at all, so there is nothing for a
-- role to grant. That separation is the requirement, not an omission.
--
-- V1's blanket CROSS JOIN grant to ADMIN was a one-time snapshot and does not cover rows
-- inserted here — see gotcha #22. Every grant below is therefore explicit.
-- =============================================================================
INSERT INTO permissions (code, name, category, http_method, endpoint_path) VALUES
    ('GET:/integration-keys',               'مشاهده کلیدهای یکپارچه‌سازی', 'admin', 'GET',  '/integration-keys'),
    ('POST:/integration-keys',              'ایجاد کلید یکپارچه‌سازی',     'admin', 'POST', '/integration-keys'),
    ('POST:/integration-keys/{id}/status',  'فعال/غیرفعال کردن کلید',      'admin', 'POST', '/integration-keys/{id}/status'),
    ('POST:/integration-keys/{id}/revoke',  'ابطال کلید یکپارچه‌سازی',     'admin', 'POST', '/integration-keys/{id}/revoke');

-- ADMIN only. Issuing a credential that reads every completed round in the plant is an
-- administrative act; HIGH_USER deliberately does not get it.
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
  FROM roles r CROSS JOIN permissions p
 WHERE r.code = 'ADMIN'
   AND p.code IN ('GET:/integration-keys',
                  'POST:/integration-keys',
                  'POST:/integration-keys/{id}/status',
                  'POST:/integration-keys/{id}/revoke');

-- #############################################################################
-- SECTION 5 — the audit trail must outlive the user it describes
-- #############################################################################

-- =============================================================================
-- The audit trail must outlive the user it describes
--
-- THE PROBLEM
--
-- `audit_log.actor_user_id` was ON DELETE RESTRICT, and audit rows are written ASYNCHRONOUSLY
-- (AuditWriteService, @Async on auditExecutor, REQUIRES_NEW). Those two facts together lose
-- audit history:
--
--   1. a user performs an action; the audit INSERT is queued
--   2. an administrator deletes that user
--   3. `UserService.hasAppActivity` asks `auditLogRepository.existsByActorUserId` — which sees
--      only rows already WRITTEN, not the one still sitting in the queue — and answers "no
--      activity", so the delete is allowed
--   4. the queued INSERT then fails on the foreign key, and the row is gone for good
--
-- Observed for real: the FK violation appears in the log of an integration test that deletes a
-- fixture user, while the test itself stays green — which is exactly how this would behave in
-- production, silently.
--
-- WHY ON DELETE SET NULL RATHER THAN A SYNCHRONOUS FLUSH
--
-- Draining the queue before every delete would fix step 3 and leave the shape of the problem
-- intact: the trail would still depend on a race being won. And it would couple deleting a user
-- to the health of the audit executor, which is the coupling that already cost this project an
-- import job stuck at RUNNING (see AsyncConfig's CallerRunsPolicy note).
--
-- The deeper point is that an audit row is a record of something that HAPPENED. It should not
-- be deletable — or fail to be writable — because the actor's account was later removed. The
-- row already carries `actor_username`, denormalised for precisely this reason: so the trail
-- stays readable when the user is gone or renamed. SET NULL makes the id follow the same rule
-- the username already followed.
--
-- log_sheet_action_log is left alone: its `actor_user_id` has no FK at all, so it never had
-- this failure mode.
--
-- WHAT THIS DOES NOT CHANGE
--
-- `UserService.delete` still refuses to remove a user with recorded activity — that guard is
-- about not orphaning meaningful history, and it stays. This migration is about the rows that
-- were in flight when the guard ran, which the guard could never have seen.
-- =============================================================================

ALTER TABLE audit_log
    DROP CONSTRAINT fk_audit_log_actor_user;

ALTER TABLE audit_log
    ADD CONSTRAINT fk_audit_log_actor_user
        FOREIGN KEY (actor_user_id) REFERENCES users (id) ON DELETE SET NULL;

COMMENT ON COLUMN audit_log.actor_user_id IS
    'Who made the change. ON DELETE SET NULL: an audit row records something that happened and '
    'must survive the removal of the account that did it. actor_username is denormalised '
    'alongside and stays readable after the id is cleared.';

-- #############################################################################
-- SECTION 6 — form_data stores only the fields that were actually answered
-- #############################################################################

-- =============================================================================
-- form_data stores only the fields that were actually answered
--
-- THE INVARIANT THIS RESTORES
--
--   log_sheet_entries.form_data contains a key ONLY when that field has a real answer.
--   An asset nobody filled is `{}` — not `{"Bar": "", "Status": ""}`.
--
-- Generation has always honoured that: LogSheetGenerationService.prepopulateEntries and
-- CustomLogSheetService.prepopulateEntries both write `new HashMap<>()`. The web fill form
-- broke it. That form posts EVERY entry of the sheet on every save, and applyWebEntryValues
-- stored what it was posted; `retainKnownFormData` dropped unknown keys and unparseable
-- locations but kept empty strings. So one supervisor save on a 40-asset sheet turned all 40
-- entries from `{}` into `{"Bar": "", "Status": ""}`.
--
-- WHY THAT WAS NOT COSMETIC
--
-- The PWA decided "does this device hold anything worth keeping for this asset?" with
-- `Object.keys(localForm).length > 0` — key presence, not value presence — while everything
-- else in that codebase asks `hasEntryFormData()`, which ignores "" and []. Once a device had
-- one of these all-blank maps, that test was permanently true for every asset in the sheet, so
-- the local copy always won the merge and the server side of it was dead. An operator handed a
-- reopened sheet could not see the values a supervisor had entered in the browser, and their
-- next submit sent the blanks back and overwrote those values for good.
--
-- The code fixes are in the same commit as this file: the emptiness test is now shared
-- (FormDataValidationSupport.isAnswered), both storage paths drop unanswered keys before
-- writing, the PWA merge asks about values rather than keys, and the mobile merge refuses a
-- write that would blank a stored answer the device never saw. This migration is only about
-- the rows that were already written.
--
-- WHAT THIS DOES
--
-- Rewrites form_data without its unanswered keys. "Unanswered" is the same rule the Java side
-- now uses, expressed in SQL:
--
--   JSON null                                     -> drop
--   a string that is empty or only whitespace     -> drop
--   an empty array                                -> drop
--   an attachment reference with no ids           -> drop   {"type":"attachment","ids":[]}
--
-- Everything else is kept byte-for-byte, including 0, false, and a location object — all three
-- are answers. An entry whose keys all go becomes `{}`, which is what generation would have
-- left it as.
--
-- Idempotent: running it twice changes nothing the second time, and the `<> c.form_data` guard
-- means untouched rows are not rewritten at all.
--
-- WHAT THIS DELIBERATELY DOES NOT DO
--
-- It does not clear filled_by_user_id / entry_source / created_at on the entries it empties.
-- Those rows read oddly — attributed to someone, with nothing in them — and that oddness is
-- the only surviving trace that a reading was destroyed there. The values themselves are not
-- recoverable; nothing recorded the previous content. Tidying the attribution away would erase
-- the evidence and answer nobody's question.
-- =============================================================================

WITH cleaned AS (
    SELECT e.id,
           COALESCE(
               (SELECT jsonb_object_agg(kv.key, kv.value)
                  FROM jsonb_each(e.form_data) kv
                 WHERE jsonb_typeof(kv.value) <> 'null'
                   AND NOT (jsonb_typeof(kv.value) = 'string'
                            AND btrim(kv.value #>> '{}') = '')
                   AND NOT (jsonb_typeof(kv.value) = 'array'
                            AND jsonb_array_length(kv.value) = 0)
                   AND NOT (jsonb_typeof(kv.value) = 'object'
                            AND kv.value ? 'ids'
                            AND jsonb_typeof(kv.value -> 'ids') = 'array'
                            AND jsonb_array_length(kv.value -> 'ids') = 0)),
               '{}'::jsonb) AS form_data
      FROM log_sheet_entries e
     WHERE e.form_data IS NOT NULL
       AND e.form_data <> '{}'::jsonb
)
UPDATE log_sheet_entries e
   SET form_data = c.form_data
  FROM cleaned c
 WHERE e.id = c.id
   AND e.form_data <> c.form_data;

COMMENT ON COLUMN log_sheet_entries.form_data IS
    'Answers keyed by field key. A key is present only when the field was actually answered: '
    'an unfilled asset is {}. Empty strings, empty arrays and attachment references with no ids '
    'are never stored — both write paths strip them (FormDataValidationSupport.isAnswered), and '
    'the mobile merge treats "no answers" as the signal that a device holds nothing for an asset.';
