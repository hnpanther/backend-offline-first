# Database Schema

The complete PostgreSQL schema, table by table: the DDL, what each column is for, and why
each index exists.

> **This file describes the schema as it is *now*, not as it was built.** Migrations are a
> history of edits; a table's definition is spread across however many of them touched it.
> When you want to know what `log_sheets` looks like today, reading `V1 → V2 → V3` and
> replaying them in your head is the slow, error-prone way. Read this instead — and when you
> add a migration, update the affected section here in the same commit.

**Migration files:** [`src/main/resources/db/migration/`](../src/main/resources/db/migration/)

| Version | File | What it did |
|---|---|---|
| V1 | `V1__initial_schema.sql` | Everything below except where noted. **Closed — never edit it.** |
| V2 | `V2__reading_time_and_import_heartbeat.sql` | Two changes, consolidated while the schema was still development-only: `asset_status_change_requests.reading_recorded_at`, and `import_jobs.heartbeat_at` + `ix_import_jobs_status_heartbeat` (lets a wedged import be detected without a restart). **Applied — do not merge into it again; the next change is V3.** |
| V3 | `V3__capabilities_integration_api_and_answered_form_data.sql` | **Everything between the V2 release and the next one.** Seven parts, in one file because production has run none of them — it is still on V2, so it advances by one version instead of several for changes it has never seen. **(a)** Eleven `CAP:*` rows in `permissions` (`category = 'capability'`, null method/path) plus the `role_permissions` grants that reproduce the previous role-code behaviour exactly — this is what makes a duplicated role behave like its original, see [security.md](security.md#3-capabilities--access-that-is-not-about-an-endpoint). **(b)** `users.org_unit` and `users.org_position`: optional free-text personnel attributes (150 chars, nullable, not unique), deliberately unrelated to `operational_units`. **(c)** Seeds `attachments.image_annotation_enabled`, `nfc.strict_serial_match` and `nfc.manual_entry_enabled` (all `true`) into `app_settings`, `ON CONFLICT DO NOTHING` so an installation that already chose a value keeps it. **(d)** The Integration API: `api_keys`, `api_key_usage`, four `/integration-keys` admin permissions granted to `ADMIN` only, and `idx_log_sheets_status_finalized_at`. **(e)** Drops `fk_audit_log_actor_user` entirely, so `audit_log.actor_user_id` has **no foreign key** — an audit row is written whether or not its actor still exists (gotcha #84). **(f)** Rewrites every `log_sheet_entries.form_data` without its **unanswered** keys — JSON null, blank or whitespace-only strings, empty arrays, and attachment references with no ids — so an asset nobody filled stores `{}`; repairs the rows the web fill form contaminated by posting every field of every entry on every save, and is idempotent. **(g)** `api_sessions` gains **`ux_api_sessions_one_active`**, a UNIQUE partial index on `(user_id) WHERE revoked_at IS NULL`, replacing V1's non-unique `idx_api_sessions_active`; duplicates the old race left behind are superseded first, newest row per user surviving. **Applied — the next change is V4.** Consolidated twice: first from `V3__role_capabilities.sql` + a user-fields migration, then from four unreleased files. Both times development databases were repaired by hand, because Flyway validates `description` and `script` as well as the checksum — see AGENTS.md gotcha #86 for the exact procedure, including the stale copies under `target/classes` that otherwise stop the boot. |
| V4 | `V4__log_sheet_entry_revisions.sql` | `log_sheet_entry_revisions` — the reading a correction replaced, append-only, with the two indexes the entry and sheet panels read it by. Before it, a supervisor editing a delivered round destroyed the operator's measurement with no trace anywhere on the server. **Applied — the next change is V5.** |
| V5 | `V5__log_sheet_progress_sync.sql` | Progress reporting from a round still being walked: `log_sheets.draft_saved_by_user_id` and `draft_source`, the `START` action's `started_at`, and `idx_log_sheet_entries_filled` (partial, `WHERE max_severity IS NOT NULL`) so the «پیشرفت» column costs one indexed count per sheet instead of a scan. **Applied — the next change is V6.** |
| V6 | `V6__log_sheet_approval_and_attachment_snapshots.sql` | Three unrelated changes that ship together, each with its own header in the file. **(a)** Approval: `log_sheets.approved_at` + `approved_by_user_id`, the two `/approve` and `/unapprove` permission rows granted *derived from the existing `void` grant* so a duplicated role inherits them, and `idx_log_sheets_awaiting_approval` (partial, `WHERE status = 'SUBMITTED'`) for the review queue. **No backfill** — existing `SUBMITTED` rows stayed `SUBMITTED`, because nobody approved them. **(b)** `log_sheet_entry_revisions.attachment_snapshot JSONB`: what each attachment a replaced value referenced actually *was*, so a photo the correction deleted can be described rather than reported missing. **(c)** `idx_nfc_fault_reports_created_at (created_at DESC, id DESC)` for the now-paginated fault-report queue. |

**V1 is a baseline.** Flyway records a checksum for every applied migration; editing an
applied file makes the checksum disagree with the database and the application refuses to
boot. Every schema change from now on is a **new** `V{n}__description.sql`.

`spring.jpa.hibernate.ddl-auto=validate` is set, so a successful boot is itself proof that
every entity matches the schema. A boot failure naming a column is almost always a migration
that was written but not applied, or an entity field with no matching column.

## Conventions used everywhere

- **`id BIGINT GENERATED BY DEFAULT AS IDENTITY`** — surrogate primary key. `BY DEFAULT`
  rather than `ALWAYS` so a data load can supply explicit ids.
- **Timestamps are `BIGINT`, epoch milliseconds, UTC.** Not `TIMESTAMP`. The mobile app
  works offline and sends times it recorded itself; an epoch integer crosses JSON, Dexie and
  JDBC without a timezone or a formatter getting a vote. Display-time conversion to the
  Jalali calendar happens in `DateUtils` / `persian-datetime-picker.js`, never in the database.
- **`ux_*` = unique index, `idx_*` = non-unique index, `uk_*` = unique *constraint*,
  `fk_*` = foreign key, `ck_*` = check constraint.**
- **`lower(col)` unique indexes.** Business codes are matched case-insensitively everywhere
  (`findByCodeIgnoreCase`), so uniqueness has to be case-insensitive too or `PUMP-01` and
  `pump-01` become two assets that every lookup treats as one.
- **`ON DELETE RESTRICT` is the default choice.** `CASCADE` appears only where the child row
  has no meaning without its parent (a log sheet's entries, an import job's errors). History
  and audit rows use RESTRICT so deleting a user cannot silently erase the record of what
  they did.
- **Enums are stored as `VARCHAR` with a `CHECK` constraint**, not as PostgreSQL enum types.
  Adding a value to a PG enum requires DDL and cannot be done in a transaction on older
  versions; a check constraint is a one-line migration. The Java side is a real `enum`.

---

# 1. Identity and access

## `users`

```sql
CREATE TABLE users (
    id             BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    username       VARCHAR(255) NOT NULL,
    password_hash  VARCHAR(255) NOT NULL,
    auth_type      VARCHAR(32)  NOT NULL DEFAULT 'LOCAL',
    full_name      VARCHAR(255),
    personnel_code VARCHAR(50)  NOT NULL,
    shift          VARCHAR(100),
    national_code  VARCHAR(15),
    phone_number   VARCHAR(15),
    nfc_tag_id     VARCHAR(50),
    org_unit       VARCHAR(150),                 -- V3
    org_position   VARCHAR(150),                 -- V3
    active         BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at     BIGINT,
    updated_at     BIGINT,
    CONSTRAINT uk_users_username UNIQUE (username),
    CONSTRAINT ck_users_auth_type CHECK (auth_type IN ('LOCAL','ACTIVE_DIRECTORY','HYBRID'))
);

CREATE UNIQUE INDEX ux_users_personnel_code_lower ON users (lower(personnel_code));
CREATE UNIQUE INDEX ux_users_nfc_tag_id_lower     ON users (lower(nfc_tag_id));
CREATE UNIQUE INDEX ux_users_national_code        ON users (national_code);
CREATE UNIQUE INDEX ux_users_phone_number         ON users (phone_number);
```

| Column | Meaning |
|---|---|
| `username` | Login name. For AD users this is the sAMAccountName. |
| `password_hash` | BCrypt. For `ACTIVE_DIRECTORY` users it holds an unusable placeholder — the password never lives here. |
| `auth_type` | `LOCAL` = check the hash. `ACTIVE_DIRECTORY` = bind against LDAP. `HYBRID` = try AD, fall back to the local hash (for the transition period, and so an AD outage does not lock the plant out). |
| `personnel_code` | The plant's own staff number. **Required and unique** — it is how operators are identified on paper, and reports group by it. |
| `shift` | Free text (e.g. "شیفت A"). Used for workforce reporting only; no logic depends on it. |
| `national_code`, `phone_number` | Optional; unique **when present** (a NULL never collides in a B-tree unique index). |
| `nfc_tag_id` | An operator badge, if the site uses them. Unique, case-insensitive. |
| `org_unit` | **V3.** Organizational unit from the org chart, free text, optional. |
| `org_position` | **V3.** Job title, free text, optional. |
| `active` | Soft delete. Users are never hard-deleted — every history table points at them with RESTRICT. |

**Why the `lower()` indexes:** logins and imports match case-insensitively. Without them
`A-1234` and `a-1234` would be two staff records that every lookup collapses into one.

**`org_unit` is not `operational_units`, and the resemblance is the hazard.** One is a label on
a person; the other is the structure that decides which log sheets that person can see, reached
through `unit_supervisors` / `unit_operators`. These two columns are deliberately free text with
no foreign key and no uniqueness: a whole department shares one unit and one title, and — more
importantly — a typo in an HR spreadsheet must not be able to change anybody's access scope.
Nothing in the application branches on either value; they exist for lists, exports and reports.

## `roles`, `permissions`, `role_permissions`, `user_roles`

```sql
CREATE TABLE roles (
    id          BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    code        VARCHAR(255) NOT NULL,
    name        VARCHAR(255),
    description VARCHAR(255),
    system_role BOOLEAN NOT NULL DEFAULT FALSE,
    created_at  BIGINT,
    updated_at  BIGINT,
    CONSTRAINT uk_roles_code UNIQUE (code)
);

CREATE TABLE permissions (
    id            BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    code          VARCHAR(255) NOT NULL,
    name          VARCHAR(255),
    category      VARCHAR(255),
    http_method   VARCHAR(10),
    endpoint_path VARCHAR(512),
    CONSTRAINT uk_permissions_code UNIQUE (code)
);

CREATE TABLE role_permissions (
    role_id       BIGINT NOT NULL REFERENCES roles(id)       ON DELETE RESTRICT,
    permission_id BIGINT NOT NULL REFERENCES permissions(id) ON DELETE RESTRICT,
    PRIMARY KEY (role_id, permission_id)
);
CREATE INDEX idx_role_permissions_permission_id ON role_permissions (permission_id);

CREATE TABLE user_roles (
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    role_id BIGINT NOT NULL REFERENCES roles(id) ON DELETE RESTRICT,
    PRIMARY KEY (user_id, role_id)
);
CREATE INDEX idx_user_roles_user_id ON user_roles (user_id);
```

**`permissions.code` is the endpoint itself** — `GET:/reports`,
`POST:/asset-status-requests/{id}/decide`, including the literal `{id}` braces. That exact
string appears in `@PreAuthorize("hasAuthority('…')")` and in `sec:authorize` in the
templates. There is no separate mapping layer: the permission *is* the route. Adding an
endpoint means inserting a permission row in a migration, or nobody but a superuser can
reach it.

**Not every row is an endpoint.** Rows with `category = 'capability'` carry a `CAP:` code and
leave `http_method` / `endpoint_path` null. They answer "what may this person do" rather than
"which route may they call" — plant-wide sight, completing an unassigned sheet, reviewing a
fault report. They live in this table so that duplicating a role copies them along with
everything else, which is what makes roles copyable at all. See
[security.md](security.md#3-capabilities--access-that-is-not-about-an-endpoint).

`system_role = true` marks roles the application depends on; the UI refuses to delete them, and
`RoleService` additionally refuses to remove a system role's **capabilities** — otherwise an
administrator could untick `CAP:SCOPE_PLANT_WIDE` from `ADMIN` and lock everybody out of the
plant-wide view with no way back through the page that did it.

**The two extra indexes exist because both directions are queried.** The composite primary
key serves `role → permissions`; `idx_role_permissions_permission_id` serves "who can do
this?" on the permission admin screen. Same reasoning for `idx_user_roles_user_id`, which is
hit on **every request** to build the authenticated principal.

## `api_sessions`

```sql
CREATE TABLE api_sessions (
    id            BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    jti           VARCHAR(64)  NOT NULL,
    user_id       BIGINT       NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    username      VARCHAR(255),
    device_label  VARCHAR(255),
    user_agent    VARCHAR(512),
    ip_address    VARCHAR(64),
    issued_at     BIGINT NOT NULL,
    expires_at    BIGINT NOT NULL,
    last_seen_at  BIGINT,
    revoked_at    BIGINT,
    revoked_by    BIGINT,
    revoke_reason VARCHAR(32),
    CONSTRAINT uk_api_sessions_jti UNIQUE (jti)
);

CREATE INDEX idx_api_sessions_user_id    ON api_sessions (user_id);
CREATE INDEX idx_api_sessions_expires_at ON api_sessions (expires_at);

-- V3. Replaces V1's non-unique idx_api_sessions_active: same columns and predicate, so it still
-- serves the login lookup, plus the guarantee.
CREATE UNIQUE INDEX ux_api_sessions_one_active
    ON api_sessions (user_id) WHERE revoked_at IS NULL;
```

**`ux_api_sessions_one_active` is the one-device rule, enforced.** It used to live only in
`ApiSessionService.register()`, which read the user's live sessions, revoked them and inserted the
new one — a read-then-write with nothing holding the three steps together. Under READ COMMITTED
two concurrent logins both read "nothing active", neither seeing the other's uncommitted insert,
and both inserted; the index was not unique, so the database had no opinion. Reproduced: eight
simultaneous logins left **eight** live tokens for one operator.

Two things follow from the predicate, and both are load-bearing:

- **A partial index cannot consult the clock**, so an *expired but unrevoked* row is still in it.
  `ApiSessionService` therefore supersedes **every** unrevoked row, not only the unexpired ones
  that `findActiveByUserId` returns — otherwise an expired row would block the user's next login
  forever. Marking it superseded changes nothing observable: a session past its expiry already
  authenticates nothing.
- **The index alone would turn the race into a failed login**, because the loser's insert violates
  it. `register()` takes a transaction-scoped advisory lock per user first, so the loser waits,
  sees the winner's row, supersedes it and succeeds. Last login wins, which is what the rule
  promises. Covered by `ApiSessionConcurrencyIntegrationTest`, which runs the logins on real
  threads because a single-threaded test cannot fail here.

One row per issued mobile JWT. The token carries `jti`; this table is what makes a **stateless
token revocable** — an admin can sign a lost tablet out without waiting for expiry.

`ON DELETE CASCADE` here (unlike everywhere else) because a session has no meaning or
audit value once its user is gone; the audit trail records the login separately.

**`ux_api_sessions_one_active` is partial.** The common query is "live sessions for this
user", and revoked rows accumulate forever. A partial index stays small no matter how much
history builds up — and here the same predicate is what carries the uniqueness, so one index
does both jobs.

`device_label`, `user_agent`, `ip_address` exist so the admin session list is legible —
"which of these five tablets am I about to sign out?"

---

# 2. Organisational structure

## `operational_units`

```sql
CREATE TABLE operational_units (
    id         BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    code       VARCHAR(255) NOT NULL,
    name       VARCHAR(255),
    parent_id  BIGINT REFERENCES operational_units(id) ON DELETE RESTRICT,
    created_at BIGINT,
    updated_at BIGINT
);

CREATE UNIQUE INDEX ux_operational_units_code_lower ON operational_units (lower(code));
CREATE INDEX idx_operational_units_parent_id  ON operational_units (parent_id);
CREATE INDEX idx_operational_units_updated_at ON operational_units (updated_at);
```

The **org chart**, self-referencing to any depth. This is the axis all access control turns
on. `parent_id` cycles are prevented in the service layer (`OperationalUnitService`), not by
the database — PostgreSQL cannot express "no cycles" as a constraint.

`idx_*_updated_at` appears on every table the mobile bootstrap syncs: the PWA asks for
"everything changed since T" and this index is what keeps that cheap.

## `unit_supervisors`, `unit_operators`

```sql
CREATE TABLE unit_supervisors (
    unit_id BIGINT NOT NULL REFERENCES operational_units(id) ON DELETE RESTRICT,
    user_id BIGINT NOT NULL REFERENCES users(id)             ON DELETE RESTRICT,
    PRIMARY KEY (unit_id, user_id)
);
CREATE INDEX idx_unit_supervisors_user_id ON unit_supervisors (user_id);

CREATE TABLE unit_operators (
    unit_id BIGINT NOT NULL REFERENCES operational_units(id) ON DELETE RESTRICT,
    user_id BIGINT NOT NULL REFERENCES users(id)             ON DELETE RESTRICT,
    PRIMARY KEY (unit_id, user_id)
);
CREATE INDEX idx_unit_operators_user_id ON unit_operators (user_id);
```

Two tables, not one with a `kind` column, because **the two roles resolve scope differently**:

- **A supervisor inherits downward.** Supervising unit X means seeing X and every unit
  beneath it — that is what supervising means.
- **An operator does not.** Being an operator of X means X only. Otherwise attaching an
  operator to a high-level unit would silently hand them the whole plant.

That asymmetry is implemented in `AssetUnitScopeSql` as a recursive CTE for supervisors and a
flat match for operators. See [hierarchy.md](hierarchy.md).

`idx_*_user_id` serves the hot direction: "which units does the person making this request
belong to?", resolved on nearly every scoped query.

---

# 3. Physical hierarchy

Five levels: **Location → Plant System → Main Function → Sub Function → Asset.**
Each level except Location may also self-nest. See [hierarchy.md](hierarchy.md) for how the
levels combine and what happens on update; this section is just the storage.

## `locations`

```sql
CREATE TABLE locations (
    id         BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    code       VARCHAR(255) NOT NULL,
    name       VARCHAR(255),
    name_fa    VARCHAR(255),
    parent_id  BIGINT REFERENCES locations(id) ON DELETE RESTRICT,
    status     VARCHAR(30),
    created_at BIGINT,
    updated_at BIGINT
);

CREATE UNIQUE INDEX ux_locations_code_lower ON locations (lower(code));
CREATE INDEX idx_locations_parent_id  ON locations (parent_id);
CREATE INDEX idx_locations_updated_at ON locations (updated_at);
```

`name` is the engineering name (usually English, from the P&ID); `name_fa` is the optional
Persian title shown in the UI. Both are kept because the drawings say one thing and the
operators say another, and reconciling them by hand loses the link to the drawing.

`status` is free text (`IN_SERVICE`, `OFF`, …) with no check constraint — sites label
equipment states differently and this must not need a migration to accommodate a new word.

## `location_units`

```sql
CREATE TABLE location_units (
    location_id BIGINT NOT NULL REFERENCES locations(id)         ON DELETE CASCADE,
    unit_id     BIGINT NOT NULL REFERENCES operational_units(id) ON DELETE RESTRICT,
    PRIMARY KEY (location_id, unit_id)
);
CREATE INDEX idx_location_units_unit_id ON location_units (unit_id);
```

**The join between the physical plant and the org chart, and the single most important table
for access control.** A location can belong to several operational units — a shared utilities
area is genuinely the responsibility of more than one team, and forcing a single owner meant
somebody had to be wrong.

`CASCADE` on the location side: the links are part of the location's own definition. RESTRICT
on the unit side: deleting a unit that still owns locations must fail loudly.

`idx_location_units_unit_id` drives the whole scoping direction — "which locations does this
unit own?" — which then walks down to assets.

## `plant_systems`, `main_functions`, `sub_functions`

```sql
CREATE TABLE plant_systems (
    id          BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    code        VARCHAR(255) NOT NULL,
    name        VARCHAR(255) NOT NULL,
    name_fa     VARCHAR(255),
    parent_id   BIGINT REFERENCES plant_systems(id) ON DELETE RESTRICT,
    location_id BIGINT REFERENCES locations(id)     ON DELETE RESTRICT,
    status      VARCHAR(30),
    created_at  BIGINT,
    updated_at  BIGINT
);
CREATE UNIQUE INDEX ux_plant_systems_code_lower ON plant_systems (lower(code));
CREATE INDEX idx_plant_systems_location_id ON plant_systems (location_id);
CREATE INDEX idx_plant_systems_parent_id   ON plant_systems (parent_id);
CREATE INDEX idx_plant_systems_updated_at  ON plant_systems (updated_at);

CREATE TABLE main_functions (
    id          BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    code        VARCHAR(255) NOT NULL,
    name        VARCHAR(255) NOT NULL,
    name_fa     VARCHAR(255),
    parent_id   BIGINT REFERENCES main_functions(id) ON DELETE RESTRICT,
    system_id   BIGINT REFERENCES plant_systems(id)  ON DELETE RESTRICT,
    location_id BIGINT REFERENCES locations(id)      ON DELETE RESTRICT,
    status      VARCHAR(30),
    created_at  BIGINT,
    updated_at  BIGINT
);
CREATE UNIQUE INDEX ux_main_functions_code_lower ON main_functions (lower(code));
CREATE INDEX idx_main_functions_location_id ON main_functions (location_id);
CREATE INDEX idx_main_functions_parent_id   ON main_functions (parent_id);
CREATE INDEX idx_main_functions_system_id   ON main_functions (system_id);
CREATE INDEX idx_main_functions_updated_at  ON main_functions (updated_at);

CREATE TABLE sub_functions (
    id               BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    code             VARCHAR(255) NOT NULL,
    name             VARCHAR(255),
    name_fa          VARCHAR(255),
    tag              VARCHAR(255) NOT NULL,
    parent_id        BIGINT REFERENCES sub_functions(id)  ON DELETE RESTRICT,
    main_function_id BIGINT REFERENCES main_functions(id) ON DELETE RESTRICT,
    system_id        BIGINT REFERENCES plant_systems(id)  ON DELETE RESTRICT,
    location_id      BIGINT REFERENCES locations(id)      ON DELETE RESTRICT,
    status           VARCHAR(30),
    created_at       BIGINT,
    updated_at       BIGINT
);
CREATE UNIQUE INDEX ux_sub_functions_code_lower ON sub_functions (lower(code));
CREATE UNIQUE INDEX ux_sub_functions_tag_lower  ON sub_functions (lower(tag));
CREATE INDEX idx_sub_functions_location_id      ON sub_functions (location_id);
CREATE INDEX idx_sub_functions_main_function_id ON sub_functions (main_function_id);
CREATE INDEX idx_sub_functions_parent_id        ON sub_functions (parent_id);
CREATE INDEX idx_sub_functions_system_id        ON sub_functions (system_id);
CREATE INDEX idx_sub_functions_updated_at       ON sub_functions (updated_at);
```

**Each level carries every ancestor id, not just its immediate parent.** `sub_functions` has
`location_id`, `system_id` *and* `main_function_id`. This is deliberate denormalisation: the
alternative is a four-way join or a recursive CTE on every scoped asset query, on the hottest
path in the application. The service layer keeps them consistent — see
[hierarchy.md](hierarchy.md), which is required reading before you change a parent.

**`sub_functions.tag` is the NFC identity.** It is what the physical chip contains, unique and
case-insensitive across the whole plant. An asset inherits its parent sub-function's tag when
no explicit tag is given, which is how a chip survives an asset being replaced: the chip is
bolted to the *position*, not to the pump.

---

# 4. Assets

## `asset_classes` and `field_definitions`

```sql
CREATE TABLE asset_classes (
    id         BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    name       VARCHAR(255) NOT NULL,
    created_at BIGINT,
    updated_at BIGINT
);
CREATE UNIQUE INDEX ux_asset_classes_name_lower ON asset_classes (lower(name));
CREATE INDEX idx_asset_classes_updated_at ON asset_classes (updated_at);

CREATE TABLE field_definitions (
    id         BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    class_id   BIGINT NOT NULL REFERENCES asset_classes(id) ON DELETE RESTRICT,
    field_key  VARCHAR(255) NOT NULL,
    label      VARCHAR(255),
    data_type  VARCHAR(255),
    unit       VARCHAR(255),
    required   BOOLEAN NOT NULL,
    validation JSONB,
    sort_order INTEGER,
    version    INTEGER,
    deleted    BOOLEAN NOT NULL,
    synced     BOOLEAN NOT NULL,
    created_at BIGINT,
    updated_at BIGINT
);
CREATE UNIQUE INDEX ux_field_definitions_class_key_lower
    ON field_definitions (class_id, lower(field_key));
CREATE INDEX idx_field_definitions_class_id  ON field_definitions (class_id);
CREATE INDEX idx_field_definitions_updated_at ON field_definitions (updated_at);
```

A **class** is a kind of equipment ("centrifugal pump"); its **field definitions** are the
readings that kind of equipment produces. This is the form schema, and it is data, not code —
an engineer adds a field through the admin panel without a deployment.

`data_type` values, in the order the editor offers them: `number`, `text`, `select`,
`multiselect`, `checkbox`, `textarea`, `image`, `audio`, `video`, `location`. The one list is
`FieldDataTypes` — both dropdowns in the field editor are built from it, and writes are checked
against it.

> Earlier revisions of this file listed `boolean` and `date`, which the editor has never offered
> and no fill control renders; they fall through to a plain text input. A row that somehow holds
> one is **kept**: the edit dropdown appends the field's own type when it is not a standard one,
> and the write check accepts a type that equals what the field already had. So a legacy field
> stays editable, but nothing can newly introduce such a type. The PWA's `FieldDataType` union
> must carry the same ten values; `FieldDataTypesTest` reads it across the two repositories and
> fails on drift.
>
> That guard exists because the drift was not theoretical. The editor's create and edit modals
> each carried their own hardcoded list, and the edit one was missing the four media/location
> types — so reopening an `image` field found no matching option, the browser selected the first
> (`number`), and saving retyped the field. Readings already stored against it are attachment
> references, which a numeric field can neither validate nor render.

`validation` (JSONB) holds the warning/danger bands, select options, min/max, and so on. See
[log-sheets.md](log-sheets.md#4-validation-and-severity) for how those bands are evaluated and
where the resulting severity is stored.

`deleted` is a soft delete: a field removed today must not erase readings taken under it last
month. `synced` is a leftover flag from the master-data-editing era of the mobile app; the PWA
no longer writes class definitions.

**`ux_field_definitions_class_key_lower` scopes uniqueness to the class**, so two different
classes may both have a `pressure` field, which they obviously should.

## `asset_entries`

```sql
CREATE TABLE asset_entries (
    id              BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    asset_code      VARCHAR(255) NOT NULL,
    nfc_tag_id      VARCHAR(255),
    nfc_serial      VARCHAR(255),
    class_id        BIGINT REFERENCES asset_classes(id) ON DELETE RESTRICT,
    asset_name      VARCHAR(255),
    asset_name_fa   VARCHAR(255),
    sub_function_id BIGINT NOT NULL REFERENCES sub_functions(id) ON DELETE RESTRICT,
    description     VARCHAR(500),
    active          BOOLEAN NOT NULL DEFAULT TRUE,
    status          VARCHAR(30),
    created_at      BIGINT,
    updated_at      BIGINT
);

CREATE UNIQUE INDEX ux_asset_entries_asset_code_lower ON asset_entries (lower(asset_code));
CREATE UNIQUE INDEX ux_asset_entries_nfc_tag_id_lower ON asset_entries (lower(nfc_tag_id));
CREATE UNIQUE INDEX ux_asset_entries_nfc_serial_lower ON asset_entries (lower(nfc_serial));
CREATE UNIQUE INDEX ux_asset_entries_active_sub_function
    ON asset_entries (sub_function_id) WHERE active;
CREATE INDEX idx_asset_entries_class_id             ON asset_entries (class_id);
CREATE INDEX idx_asset_entries_class_sub_function   ON asset_entries (class_id, sub_function_id);
CREATE INDEX idx_asset_entries_updated_at           ON asset_entries (updated_at);
```

| Column | Meaning |
|---|---|
| `asset_code` | The plant's equipment number. Unique, case-insensitive. |
| `nfc_tag_id` | The logical tag written into the chip's NDEF Record 1. Usually inherited from the sub-function. |
| `nfc_serial` | The chip's **hardware** UID, which cannot be rewritten. Verifying it defeats a cloned tag. |
| `class_id` | Which form this asset produces. Nullable — an asset can exist before anyone defines its readings. |
| `active` | Whether it is the equipment currently installed at this position. |
| `status` | Operational state (`IN_SERVICE`, `OFF`, …). **Only changed through an approved request** — see [log-sheets.md](log-sheets.md#5-asset-status-requests). |

**There is deliberately no `parent_id`.** An asset is a leaf: its parent is always a sub-function, never another asset. A component that belongs to a piece of equipment — the GPU in a PC, a pump's bearing — has no place in this table today. The options for changing that, what each costs, and the blocker that decides between them (the NFC flow assumes **one scan = one asset**) are worked out in [roadmap.md §1](roadmap.md). The cheapest answer needs no schema change, so do not add the column before reading it.

**`ux_asset_entries_active_sub_function` is the important one and it is partial.** A
sub-function is a *position* in the plant; only one asset can be installed there at a time.
Making it partial on `WHERE active` is what lets the history stay: replace a pump and the old
row remains, deactivated, keeping every reading ever taken against it, while the new pump
takes the position. Without `WHERE active` you would have to delete the old asset — and its
readings with it.

**`idx_asset_entries_class_sub_function`** is composite because log sheet generation asks
exactly that pair: "assets of class C under these sub-functions."

## `asset_activation_history`

```sql
CREATE TABLE asset_activation_history (
    id            BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    asset_id      BIGINT NOT NULL REFERENCES asset_entries(id) ON DELETE CASCADE,
    was_active    BOOLEAN,
    is_active     BOOLEAN NOT NULL,
    change_type   VARCHAR(20) NOT NULL,
    actor_user_id BIGINT REFERENCES users(id) ON DELETE RESTRICT,
    changed_at    BIGINT NOT NULL,
    CONSTRAINT ck_asset_activation_history_change_type
        CHECK (change_type IN ('CREATED','ACTIVATED','DEACTIVATED'))
);
CREATE INDEX idx_asset_activation_history_asset
    ON asset_activation_history (asset_id, changed_at DESC);
```

Installed / removed, kept **separate from operational status**. An asset being switched off
for maintenance and an asset being physically removed from the plant are different facts, and
merging them into one column would make both unanswerable.

`was_active` is NULL for the `CREATED` row — there was no previous state. Every asset gets a
`CREATED` row at registration, including imported ones, so a timeline starts when the asset
arrived rather than the first time somebody happened to toggle it.

`actor_user_id` is nullable: the async Excel import runs on its own executor with no security
context. NULL renders as «سیستم», which is true, rather than a wrong name.

**`(asset_id, changed_at DESC)`** matches the only query — one asset's timeline, newest first.

## `asset_status_history`

```sql
CREATE TABLE asset_status_history (
    id                 BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    asset_id           BIGINT NOT NULL REFERENCES asset_entries(id) ON DELETE CASCADE,
    old_status         VARCHAR(30),
    new_status         VARCHAR(30),
    change_type        VARCHAR(20) NOT NULL,
    source             VARCHAR(20) NOT NULL,
    log_sheet_id       BIGINT REFERENCES log_sheets(id) ON DELETE SET NULL,
    log_sheet_entry_id BIGINT,
    field_key          VARCHAR(255),
    actor_user_id      BIGINT REFERENCES users(id) ON DELETE RESTRICT,
    changed_at         BIGINT NOT NULL,
    reverted_at        BIGINT,
    request_id         BIGINT,
    CONSTRAINT ck_asset_status_history_change_type CHECK (change_type IN ('APPLIED','REVERTED')),
    CONSTRAINT ck_asset_status_history_source      CHECK (source IN ('LOG_SHEET','MANUAL'))
);
CREATE INDEX idx_asset_status_history_asset  ON asset_status_history (asset_id, changed_at DESC);
CREATE INDEX idx_asset_status_history_active ON asset_status_history (log_sheet_id)
    WHERE reverted_at IS NULL AND change_type = 'APPLIED';
```

Every operational-status transition, with its provenance: which log sheet, which entry, which
field, which approved request.

`changed_at` is **the time the reading was taken**, not the time it was approved. A supervisor
approving Monday's round on Wednesday must not stamp Wednesday onto the plant record. This is
what `asset_status_change_requests.reading_recorded_at` (V2) exists to carry.

`ON DELETE SET NULL` on the log sheet: deleting a sheet must not erase the fact that the
status changed — only the pointer to where it came from.

**`idx_asset_status_history_active` is partial** and answers the one question the revert logic
asks: "does this log sheet have a status change still standing?" Reverted rows and manual rows
are excluded from the index entirely, so it stays small.

## `asset_status_change_requests`

```sql
CREATE TABLE asset_status_change_requests (
    id                   BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    asset_id             BIGINT NOT NULL REFERENCES asset_entries(id) ON DELETE CASCADE,
    requested_status     VARCHAR(30),
    previous_status      VARCHAR(30),
    applied_old_status   VARCHAR(30),
    status               VARCHAR(20) NOT NULL,
    source               VARCHAR(20) NOT NULL,
    log_sheet_id         BIGINT REFERENCES log_sheets(id) ON DELETE SET NULL,
    log_sheet_entry_id   BIGINT,
    field_key            VARCHAR(255),
    reason               VARCHAR(1000),
    requested_by_user_id BIGINT REFERENCES users(id) ON DELETE RESTRICT,
    requested_at         BIGINT NOT NULL,
    decided_by_user_id   BIGINT REFERENCES users(id) ON DELETE RESTRICT,
    decided_at           BIGINT,
    decision_note        VARCHAR(1000),
    created_at           BIGINT,
    updated_at           BIGINT,
    reading_recorded_at  BIGINT,              -- V2; the entry's created_at, not updated_at
    CONSTRAINT ck_ascr_status CHECK (status IN ('PENDING','APPROVED','REJECTED')),
    CONSTRAINT ck_ascr_source CHECK (source IN ('LOG_SHEET','MANUAL'))
);
CREATE INDEX idx_ascr_asset     ON asset_status_change_requests (asset_id, id DESC);
CREATE INDEX idx_ascr_log_sheet ON asset_status_change_requests (log_sheet_id);
CREATE INDEX idx_ascr_pending   ON asset_status_change_requests (requested_at)
    WHERE status = 'PENDING';
```

**Completing a log sheet proposes a status change; it does not make one.** A reading taken in
the field is a claim. Only a supervisor's approval moves `asset_entries.status`.

The three status columns are not redundant:

| Column | Recorded when | Why it is separate |
|---|---|---|
| `previous_status` | the request is raised | what the asset read at the time of the **reading** — context for the decider |
| `requested_status` | the request is raised | the proposed new value |
| `applied_old_status` | the request is **approved** | what the approval actually **replaced** — the only safe value to restore on an undo |

`previous_status` and `applied_old_status` diverge whenever anything else moved the asset
between the reading and the decision, which is exactly when an undo would otherwise get it
wrong.

**`idx_ascr_asset (asset_id, id DESC)`** serves the *only-latest* guard: only an asset's newest
request may be undone, because undoing one in the middle would roll the asset back over
decisions taken since.

**`idx_ascr_pending` is partial** — the approval queue only ever asks for PENDING, and decided
requests accumulate forever.

`requested_by_user_id` is **nullable**, and this has bitten: a pool sheet with no assignee that
auto-submits at its deadline raises a request with no actor. Any template that renders the
name must guard the null. See the SpEL note in [AGENTS.md](../AGENTS.md).

---

# 5. Log sheets

The lifecycle, the states and the endpoints are in **[log-sheets.md](log-sheets.md)**. This
section is storage only.

## `log_sheet_templates`

```sql
CREATE TABLE log_sheet_templates (
    id                        BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    name                      VARCHAR(255) NOT NULL,
    description               VARCHAR(255),
    scope_type                VARCHAR(255),
    scope_id                  BIGINT,
    class_id                  BIGINT REFERENCES asset_classes(id) ON DELETE RESTRICT,
    asset_selection_mode      VARCHAR(20) NOT NULL DEFAULT 'SCOPE',
    operational_unit_id       BIGINT REFERENCES operational_units(id) ON DELETE RESTRICT,
    restrict_scope_to_unit    BOOLEAN NOT NULL DEFAULT TRUE,
    generation_mode           VARCHAR(20) NOT NULL DEFAULT 'MANUAL',
    recurrence_unit           VARCHAR(20),
    recurrence_every          INTEGER,
    schedule_start_at         BIGINT,
    schedule_active           BOOLEAN NOT NULL DEFAULT FALSE,
    next_run_at               BIGINT,
    last_run_at               BIGINT,
    completion_window_minutes INTEGER,
    active                    BOOLEAN NOT NULL DEFAULT TRUE,
    created_at                BIGINT,
    updated_at                BIGINT
);
CREATE UNIQUE INDEX ux_log_sheet_templates_name_lower ON log_sheet_templates (lower(name));
CREATE INDEX idx_log_sheet_templates_next_run_at         ON log_sheet_templates (next_run_at);
CREATE INDEX idx_log_sheet_templates_operational_unit_id ON log_sheet_templates (operational_unit_id);
CREATE INDEX idx_log_sheet_templates_updated_at          ON log_sheet_templates (updated_at);
```

| Column | Meaning |
|---|---|
| `scope_type` / `scope_id` | Where the round happens: `LOCATION`, `SYSTEM`, `MAIN_FUNCTION`, `SUB_FUNCTION` + that row's id. |
| `class_id` | Which class of asset is read on this round. |
| `asset_selection_mode` | `SCOPE` = resolve assets from the scope at generation time (new equipment joins automatically). `EXPLICIT` = a frozen list in `log_sheet_template_assets`. |
| `operational_unit_id` | Who is responsible for the round. |
| `restrict_scope_to_unit` | Whether the resolved assets are additionally filtered to that unit's locations. Off for shared areas one team reads on everyone's behalf. |
| `generation_mode` | `MANUAL` or `SCHEDULED`. |
| `recurrence_unit` / `recurrence_every` | `MINUTE`/`HOUR`/`DAY`/`WEEK`/`MONTH` × N. |
| `next_run_at` | When the scheduler will next generate. **The scheduler's only query predicate.** |
| `completion_window_minutes` | How long a generated sheet stays open before it expires. |
| `active` | Templates are deactivated, never deleted — generated sheets reference them. |

**`idx_log_sheet_templates_next_run_at`** is hit every 60 seconds by
`LogSheetScheduler.generateDueSheets()`; without it that is a full scan on a timer forever.

## `log_sheet_template_assets` and `log_sheet_template_guides`

```sql
CREATE TABLE log_sheet_template_assets (
    template_id BIGINT NOT NULL REFERENCES log_sheet_templates(id) ON DELETE CASCADE,
    asset_id    BIGINT NOT NULL REFERENCES asset_entries(id)       ON DELETE RESTRICT,
    PRIMARY KEY (template_id, asset_id)
);
CREATE INDEX idx_lsta_asset_id ON log_sheet_template_assets (asset_id);

CREATE TABLE log_sheet_template_guides (
    id                 BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    template_id        BIGINT NOT NULL REFERENCES log_sheet_templates(id) ON DELETE CASCADE,
    title              VARCHAR(255) NOT NULL,
    description        VARCHAR(1000),
    file_name          VARCHAR(255) NOT NULL,
    mime_type          VARCHAR(100) NOT NULL,
    size_bytes         BIGINT NOT NULL,
    sha256             VARCHAR(64),
    storage_key        VARCHAR(255) NOT NULL,
    sort_order         INTEGER NOT NULL DEFAULT 0,
    active             BOOLEAN NOT NULL DEFAULT TRUE,
    uploaded_at        BIGINT NOT NULL,
    created_by_user_id BIGINT REFERENCES users(id) ON DELETE RESTRICT,
    CONSTRAINT uk_template_guides_storage_key UNIQUE (storage_key)
);
CREATE INDEX idx_template_guides_template ON log_sheet_template_guides (template_id, sort_order);
```

`log_sheet_template_assets` is the frozen list used by `EXPLICIT` mode.

`log_sheet_template_guides` is **groundwork, not yet wired to a UI** — reference documents
(a procedure PDF, a photo of the correct valve position) to be attached to a template and
carried to the tablet. The table and the storage contract exist; no controller serves it yet.

## `log_sheets`

```sql
CREATE TABLE log_sheets (
    id                         BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    template_id                BIGINT REFERENCES log_sheet_templates(id) ON DELETE RESTRICT,
    template_name              VARCHAR(255),
    scope_summary              VARCHAR(255),
    operational_unit_id        BIGINT REFERENCES operational_units(id) ON DELETE RESTRICT,
    status                     VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    origin                     VARCHAR(20) NOT NULL DEFAULT 'MANUAL',
    assignee_user_id           BIGINT REFERENCES users(id) ON DELETE RESTRICT,
    assignment_type            VARCHAR(30),
    assigned_by_user_id        BIGINT REFERENCES users(id) ON DELETE RESTRICT,
    completed_by_user_id       BIGINT REFERENCES users(id) ON DELETE RESTRICT,
    operator_name              VARCHAR(255),
    notes                      VARCHAR(4000),
    due_at                     BIGINT,
    assigned_at                BIGINT,
    claimed_at                 BIGINT,
    started_at                 BIGINT,
    completed_at               BIGINT,
    expired_at                 BIGINT,
    cancelled_at               BIGINT,
    submitted_at               BIGINT,
    approved_at                BIGINT,
    approved_by_user_id        BIGINT REFERENCES users(id) ON DELETE RESTRICT,
    synced_at                  BIGINT,
    draft_saved_at             BIGINT,
    draft_saved_by_user_id     BIGINT REFERENCES users(id) ON DELETE RESTRICT,
    draft_source               VARCHAR(20),
    sync_status                VARCHAR(255),
    field_definitions_snapshot JSONB,
    created_at                 BIGINT,
    updated_at                 BIGINT
);
CREATE INDEX idx_log_sheets_assignee_user_id    ON log_sheets (assignee_user_id);
CREATE INDEX idx_log_sheets_due_at              ON log_sheets (due_at);
CREATE INDEX idx_log_sheets_operational_unit_id ON log_sheets (operational_unit_id);
CREATE INDEX idx_log_sheets_status              ON log_sheets (status);
CREATE INDEX idx_log_sheets_unit_status         ON log_sheets (operational_unit_id, status);

-- "Completed but not yet reviewed" is the supervisor's queue, and it is a small slice of a
-- table that grows with every round ever run. Partial, so the index stays proportional to the
-- backlog rather than to the history.
CREATE INDEX idx_log_sheets_awaiting_approval   ON log_sheets (operational_unit_id, completed_at)
    WHERE status = 'SUBMITTED';
```

`status` ∈ `PENDING`, `ASSIGNED`, `IN_PROGRESS`, `SUBMITTED`, `APPROVED`, `VOIDED`, `EXPIRED`,
`CANCELLED`. `origin` ∈ `MANUAL`, `SCHEDULED`. `assignment_type` ∈ `SELF_CLAIMED`,
`SUPERVISOR_ASSIGNED`.

**`approved_at` / `approved_by_user_id` (V6)** are the companion timestamp for `APPROVED`, set
and cleared with the status exactly as `completed_at`, `expired_at` and `cancelled_at` are for
theirs. The approver **may** be the same person as `completed_by_user_id`: on a small site the
supervisor often walks the round themselves, and forbidding it would only push people to complete
rounds under someone else's login.

A round reaches `APPROVED` from `SUBMITTED` and returns only to `SUBMITTED`; `void`, `reopen` and
`extend` all refuse it. Because the status carries the fact, **every condition asking "was this
round completed" has to accept two values** — see
[log-sheets.md § Approval](log-sheets.md#approval--the-review-step-after-completion) for the
build-failing guard that keeps that mechanical rather than remembered.

No backfill: existing `SUBMITTED` rows stayed `SUBMITTED`, because nobody approved them.

**`template_name` and `scope_summary` are copies, not joins.** Renaming a template must not
rewrite the history of rounds already run under the old name.

**`field_definitions_snapshot` (JSONB) is the same principle applied to the form.** The sheet
carries the field definitions as they were when it was generated. Change a warning band today
and last month's sheet still renders and re-validates exactly as the operator saw it. Without
this, historical data would silently re-interpret itself every time an engineer adjusts a limit.

**`draft_saved_at` has two writers, and `draft_source` says which.** It means "partial values
were stored on this sheet without a submission", and until V5 only the panel's «ذخیره پیش‌نویس»
ever wrote it. A tablet now writes it too, on every progress push from a round being walked —
which is what makes an in-flight round visible to a supervisor at all. `draft_saved_by_user_id`
names who; `draft_source` ∈ `WEB`, `MOBILE`.

> **The distinction is load-bearing, and it is why the column was not simply reused unlabelled.**
> The expiry scheduler used to branch on `draft_saved_at IS NOT NULL` and auto-submit the round.
> That branch is gone ([jobs.md](jobs.md#log-sheet-expiry)) — but if a site ever wants it back it
> must come back for `WEB` only. A mobile round that has merely reported its progress has not
> been "saved for later" by anybody, and finalising it at the deadline would close a round the
> operator is still walking.

All three are cleared together — by `reopen` and by every completion path — so the panel can
never name somebody for a save that no longer exists.

**The separate timestamp columns are the audit trail in situ** — `claimed_at` and
`assigned_at` distinguish a round somebody picked up from one they were given; `expired_at`
and `cancelled_at` distinguish a deadline that passed from a decision somebody made.

**`idx_log_sheets_unit_status` is composite** because that is the supervisor dashboard's exact
query: this unit's sheets in this state.

**`idx_log_sheets_status_finalized_at` is an expression index**, added by V4 for the third-party
integration API:

```sql
CREATE INDEX idx_log_sheets_status_finalized_at
    ON log_sheets (status, (COALESCE(completed_at, expired_at, cancelled_at)));
```

That COALESCE is "when did this sheet finish", and it is a different column per state — each
written exactly once, so the fallback chain is a fact about the lifecycle rather than a guess:
`completed_at` for `SUBMITTED`/`VOIDED` (every completion path writes it), `expired_at` for
`EXPIRED`, `cancelled_at` for `CANCELLED`. The expression matches
`LogSheetRepository.findExposableToIntegration` exactly, which is what lets one index serve both
its date-range filter and its ordering.

It is deliberately **not** partial on status. A partial index's predicate has to be *proved*
implied by the query, and PostgreSQL cannot prove that for a bound parameter list — the index
would simply never be used, silently.

The read pattern it serves is unlike anything else here: a tablet syncs the handful of sheets it
owns, while an integration asks "everything that finished between these two instants", possibly
every minute.

## `log_sheet_entries`

```sql
CREATE TABLE log_sheet_entries (
    id                BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    log_sheet_id      BIGINT REFERENCES log_sheets(id)    ON DELETE CASCADE,
    asset_id          BIGINT REFERENCES asset_entries(id) ON DELETE RESTRICT,
    asset_name        VARCHAR(255),
    nfc_tag_id        VARCHAR(255),
    nfc_serial        VARCHAR(255),
    sub_function_code VARCHAR(255),
    sub_function_tag  VARCHAR(255),
    class_id          BIGINT REFERENCES asset_classes(id) ON DELETE RESTRICT,
    form_data         JSONB,
    max_severity      VARCHAR(10),
    breached_fields   JSONB,
    entry_source      VARCHAR(20),
    filled_by_user_id BIGINT REFERENCES users(id) ON DELETE RESTRICT,
    created_at        BIGINT,
    updated_at        BIGINT,
    CONSTRAINT ck_log_sheet_entries_max_severity
        CHECK (max_severity IS NULL OR max_severity IN ('OK','WARNING','DANGER'))
);
CREATE INDEX idx_log_sheet_entries_asset_id     ON log_sheet_entries (asset_id);
CREATE INDEX idx_log_sheet_entries_log_sheet_id ON log_sheet_entries (log_sheet_id);
CREATE INDEX idx_log_sheet_entries_asset_read   ON log_sheet_entries (asset_id)
    WHERE max_severity IS NOT NULL;
CREATE INDEX idx_log_sheet_entries_breaches     ON log_sheet_entries (max_severity)
    WHERE max_severity IN ('WARNING','DANGER');
```

One row per asset on the round. `form_data` is the readings as `{fieldKey: value}`.

**`max_severity IS NOT NULL` is the exact test for "this entry carries a reading."**
`EntrySeverityEvaluator` sets it on every submit and nulls it when `form_data` is empty.
Counting rows instead of using this predicate is what once made the data-quality report claim
a 2% manual-entry rate when the truth was 67%, and show zero silent assets when 46 had gone
uninspected. Two of the four indexes exist to make that predicate cheap:

- `idx_log_sheet_entries_asset_read` — "when was this asset last actually read?"
- `idx_log_sheet_entries_breaches` — the exceptions report, which only ever wants the small
  minority of rows that breached.
- `idx_log_sheet_entries_filled` (V5) — `ON log_sheet_entries (log_sheet_id) WHERE max_severity
  IS NOT NULL`, behind the «پیشرفت» column: "how many of this round's assets carry a reading",
  asked for a whole page of sheets in one grouped query rather than one per row.

Both are partial, so they stay proportional to what is asked for rather than to the table.

`entry_source` ∈ `WEB`, `PWA_NFC`, `PWA_MANUAL` — whether the reading was typed in the office,
taken after a successful chip scan, or entered by hand on the tablet because the chip failed.
The data-quality report is built on this distinction.

`nfc_tag_id`, `nfc_serial`, `sub_function_code`, `asset_name` are again **copies**: what the
sheet was generated against, so re-tagging an asset does not rewrite what an operator scanned.

## `log_sheet_entry_revisions`

```sql
CREATE TABLE log_sheet_entry_revisions (
    id                    BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    log_sheet_entry_id    BIGINT NOT NULL REFERENCES log_sheet_entries(id) ON DELETE CASCADE,
    log_sheet_id          BIGINT NOT NULL REFERENCES log_sheets(id)        ON DELETE CASCADE,
    asset_id              BIGINT REFERENCES asset_entries(id) ON DELETE RESTRICT,
    form_data             JSONB NOT NULL,
    max_severity          VARCHAR(10),
    breached_fields       JSONB,
    entry_source          VARCHAR(20),
    recorded_by_user_id   BIGINT REFERENCES users(id) ON DELETE RESTRICT,
    recorded_at           BIGINT,
    superseded_by_user_id BIGINT REFERENCES users(id) ON DELETE RESTRICT,
    superseded_at         BIGINT NOT NULL,
    superseded_source     VARCHAR(20),
    sheet_status          VARCHAR(30),
    attachment_snapshot   JSONB,
    CONSTRAINT ck_lser_max_severity      CHECK (max_severity IS NULL OR max_severity IN ('OK','WARNING','DANGER')),
    CONSTRAINT ck_lser_entry_source      CHECK (entry_source IS NULL OR entry_source IN ('WEB','PWA_NFC','PWA_MANUAL')),
    CONSTRAINT ck_lser_superseded_source CHECK (superseded_source IS NULL OR superseded_source IN ('WEB','MOBILE','SERVER'))
);
CREATE INDEX idx_lser_entry ON log_sheet_entry_revisions (log_sheet_entry_id, id DESC);
CREATE INDEX idx_lser_sheet ON log_sheet_entry_revisions (log_sheet_id);
```

**Where a reading goes when a correction replaces it** (V4). Until this table existed, a
supervisor reopening a delivered round and editing an entry destroyed the operator's measurement
with no trace: the row kept the new value, and `entry_source`, `filled_by_user_id` and
`updated_at` all moved to the supervisor — attribution standing over a value nobody could see any
more.

It could not have been covered by `audit_log`. `LogSheetEntry` is in
`AuditEntitySupport.EXCLUDED_TYPES`, and even if it were not, `auditFields()` skips every
Map/Collection field, so `form_data` would never appear in a diff. Auditing the entity is also the
wrong shape: a mobile submit saves every entry on the sheet — up to 300 — on every push, which is
the volume problem that put `ImportJob` on that exclusion list. `LogSheetEntryRevision` is on it
too, for the opposite reason: it *is* the trail.

**`form_data` holds the value that was REPLACED, not the new one.** The current value is always in
`log_sheet_entries`, so an entry's full history is its revisions in `id` order followed by the
entry itself — no duplication, and no ambiguity about which end is current. Same reasoning as
`asset_status_change_requests.applied_old_status`: what a change actually replaced is the value
worth keeping, because it is the only one that cannot be read anywhere else.

**Filling an empty entry writes no row.** Nothing was replaced. A row exists only where a
non-empty answer was genuinely overwritten, so a normal round produces none at all and the table
grows with corrections rather than with readings. One writer —
`LogSheetEntryRevisionService.recordSupersededValue` — called from the three paths that mutate
`form_data` (mobile submit, mobile progress, web fill), each gated on the same `formDataChanged`
flag that decides re-attribution. Tying the two together is deliberate: an entry whose authorship
moved without a history row, and a row written for a save that changed nothing, are each a lie of
their own kind.

`recorded_at` is the **device** time of the replaced reading (the entry's own `updated_at`) — when
somebody was standing at the equipment. `superseded_at` is server time, because that is a fact
about the correction rather than about a measurement.

`ON DELETE CASCADE` from both parents: this is part of the sheet's record, not an independent fact
about the plant. It is **not** covered by `AuditRetentionService` — that purges audit noise, and
this is field data.

Rendered on the sheet's page as a «مقادیر پیشین» panel under each corrected asset, collapsed by
default and absent entirely where nothing was replaced.

### `attachment_snapshot` — what a photo was, after it is gone (V6)

The superseded `form_data` holds attachment **ids**, and `AttachmentService.delete` removes the
row and the file outright. So an id kept here resolves to nothing afterwards, and the history
panel could only ever say «فایل پیوست در دسترس نیست» — which cannot distinguish *a photo was
deleted here* from *the file is missing from storage*, and says nothing about what the evidence
was. That is precisely the case a reviewer is reading the panel to judge.

Shape — one entry per attachment id the replaced value referenced:

```json
{
  "9f1c…": {
    "kind": "AUDIO", "mimeType": "audio/webm",
    "sizeBytes": 40960, "durationMs": 20000,
    "width": null, "height": null,
    "uploadedAt": 1750000000000, "createdByUserId": 12
  }
}
```

**Written at revision time**, because by the time anybody reads the history the rows it describes
may be gone. **Null when the replaced value referenced no attachments** — the overwhelming
majority; a column of empty objects on every numeric correction would be pure noise in the one
table whose readability is the point. An id whose row was **already** missing is skipped rather
than recorded as an empty entry: this revision is not the place that lost it.

**The bytes are still gone.** Keeping those means soft-deleting attachments and owning a
disk-retention story, which is a larger change deliberately not taken on. What this buys is a
record that can say "a 20-second voice note recorded by X at 08:15 was removed" — enough to decide
something.

On the page, such an attachment renders through `form-data-display :: tableRevision` as a muted,
struck-through «حذف‌شده» chip carrying its kind, size and duration. It is deliberately **not** a
link and **not** an `<img>`: a thumbnail pointing at a 404 is worse than a description. A live
attachment still present wins over the snapshot — a correction that merely detached a photo from a
field left bytes that can still be opened.

## `log_sheet_action_log`

```sql
CREATE TABLE log_sheet_action_log (
    id               BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    log_sheet_id     BIGINT NOT NULL REFERENCES log_sheets(id) ON DELETE CASCADE,
    action           VARCHAR(30) NOT NULL,
    actor_user_id    BIGINT REFERENCES users(id) ON DELETE RESTRICT,
    from_user_id     BIGINT REFERENCES users(id) ON DELETE RESTRICT,
    to_user_id       BIGINT REFERENCES users(id) ON DELETE RESTRICT,
    source           VARCHAR(20),
    action_at        BIGINT NOT NULL,
    recorded_at      BIGINT NOT NULL,
    client_action_id VARCHAR(255),
    comment          VARCHAR(1000),
    CONSTRAINT uk_lsal_client_action_id UNIQUE (client_action_id)
);
CREATE INDEX idx_lsal_log_sheet_id ON log_sheet_action_log (log_sheet_id);
```

`action` ∈ `GENERATE`, `CLAIM`, `RELEASE`, `ASSIGN`, `REASSIGN`, `TAKEOVER`, `EXTEND`,
`ADMIN_REOPEN`, `VOID`, `UNVOID`, `CANCEL`, `START`, `COMPLETE`, `SUBMIT`, `EXPIRE`, `SUPERSEDE`.
`source` ∈ `WEB`, `MOBILE`, `SERVER`.

**`action_at` vs `recorded_at` is the offline-first distinction that matters most here.**
`action_at` is when the operator did it, on the tablet, possibly with no network.
`recorded_at` is when the server heard about it. A round claimed at 08:00 and synced at 14:00
must report 08:00.

**`uk_lsal_client_action_id` is the idempotency key.** The tablet generates it. A sync that
times out and retries writes the action once — the unique constraint is what makes replay
safe, and it is the reason a flaky link does not produce duplicate history.

## `log_sheet_void_submissions`

```sql
CREATE TABLE log_sheet_void_submissions (
    id                   BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    log_sheet_id         BIGINT NOT NULL REFERENCES log_sheets(id) ON DELETE CASCADE,
    submitted_by_user_id BIGINT REFERENCES users(id) ON DELETE RESTRICT,
    completed_at         BIGINT,
    synced_at            BIGINT,
    reason               VARCHAR(255),
    payload              JSONB
);
CREATE INDEX idx_lsvs_log_sheet_id ON log_sheet_void_submissions (log_sheet_id);
```

**Where a rejected submission goes instead of being dropped.** When a tablet syncs a round
the server will not accept — the sheet expired, was cancelled, or was already submitted by
somebody else — the entire payload is preserved here rather than discarded. The operator did
the work; refusing to store it would destroy real field data because of a timing problem.

`payload` is the complete submission as it arrived. A supervisor can review it and, if it
should stand, un-void it.

---

# 6. Media

## `attachments`

```sql
CREATE TABLE attachments (
    id                 VARCHAR(36) PRIMARY KEY,
    log_sheet_id       BIGINT NOT NULL REFERENCES log_sheets(id)    ON DELETE CASCADE,
    asset_id           BIGINT NOT NULL REFERENCES asset_entries(id) ON DELETE RESTRICT,
    field_key          VARCHAR(255) NOT NULL,
    kind               VARCHAR(10)  NOT NULL,
    mime_type          VARCHAR(100) NOT NULL,
    size_bytes         BIGINT NOT NULL,
    sha256             VARCHAR(64),
    width              INTEGER,
    height             INTEGER,
    duration_ms        BIGINT,
    storage_key        VARCHAR(255) NOT NULL,
    uploaded_at        BIGINT NOT NULL,
    created_by_user_id BIGINT REFERENCES users(id) ON DELETE RESTRICT,
    CONSTRAINT uk_attachments_storage_key UNIQUE (storage_key),
    CONSTRAINT ck_attachments_kind CHECK (kind IN ('IMAGE','AUDIO','VIDEO'))
);
CREATE INDEX idx_attachments_log_sheet_id  ON attachments (log_sheet_id);
CREATE INDEX idx_attachments_asset_field   ON attachments (log_sheet_id, asset_id, field_key);
```

**The primary key is a `VARCHAR(36)` UUID, not a sequence** — and that is the whole design.
The tablet mints the id offline, stores the blob under it locally, and uploads later. A
server-generated id would mean the device could not name its own file until it had a network,
which is exactly the situation this system is built for.

**Files live on disk, not in the database.** `storage_key` is the path; the bytes are under
`app.attachments.storage-path`. `sha256` lets an upload be verified and a duplicate detected.

`idx_attachments_asset_field` is composite on exactly the render query: all media for one
asset's one field on one sheet.

**Orphans are inevitable** — an upload that arrives after its sheet is deleted, or a row that
fails after the file lands. `AttachmentSweepService` reconciles disk against table nightly;
see [jobs.md](jobs.md#orphan-attachment-sweep).

---

# 7. Operations

## `nfc_fault_reports`

```sql
CREATE TABLE nfc_fault_reports (
    id                  BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    log_sheet_id        BIGINT NOT NULL REFERENCES log_sheets(id)    ON DELETE CASCADE,
    asset_id            BIGINT NOT NULL REFERENCES asset_entries(id) ON DELETE RESTRICT,
    operational_unit_id BIGINT REFERENCES operational_units(id) ON DELETE RESTRICT,
    reported_by_user_id BIGINT REFERENCES users(id) ON DELETE RESTRICT,
    reported_by_name    VARCHAR(255),
    source              VARCHAR(20) NOT NULL,
    reason              VARCHAR(2000),
    status              VARCHAR(20) NOT NULL DEFAULT 'OPEN',
    created_at          BIGINT NOT NULL,
    synced_at           BIGINT,
    client_action_id    VARCHAR(255),
    local_id            VARCHAR(255),
    reviewed_by_user_id BIGINT REFERENCES users(id) ON DELETE RESTRICT,
    reviewed_at         BIGINT,
    CONSTRAINT uk_nfc_fault_reports_client_action_id UNIQUE (client_action_id),
    CONSTRAINT ck_nfc_fault_reports_status CHECK (status IN ('OPEN','REVIEWED'))
);
CREATE INDEX idx_nfc_fault_reports_asset_id     ON nfc_fault_reports (asset_id);
CREATE INDEX idx_nfc_fault_reports_log_sheet_id ON nfc_fault_reports (log_sheet_id);
CREATE INDEX idx_nfc_fault_reports_unit_id      ON nfc_fault_reports (operational_unit_id);

-- The review queue's ordering, both columns in the query's own order (V6). `created_at` is the
-- reporting clock and repeats freely — a phone syncing a backlog files several reports in the
-- same millisecond — so the queue breaks ties on the id, and an index on `created_at` alone
-- would leave the database re-sorting each group of ties, which is exactly the boundary a page
-- break can fall inside.
CREATE INDEX idx_nfc_fault_reports_created_at   ON nfc_fault_reports (created_at DESC, id DESC);
```

**A broken chip must not stop a round.** When a tag will not read, the operator files one of
these and is then allowed to enter the reading manually. The report is what turns "the
operator bypassed the scan" from a silent hole in the data into a maintenance ticket.

`client_action_id` is the same idempotency mechanism as the action log — these are created
offline and synced in a batch.

Only an admin may mark one `REVIEWED`. `reported_by_name` is kept alongside the id because a
report may arrive from a device whose user has since been deactivated.

**`reviewed_by_user_id` is nullable, and the page has to survive that.** SpEL throws on a null map
index rather than yielding null, so `userById[r.reviewedByUserId]` in `nfc-fault-reports.html` was
a 500 on the *whole* queue for one such row rather than one blank cell — the null check on the id
now comes first. See AGENTS.md's trap list.

**The browse page reads one page of this table, never all of it** (V6). It used to load every
report ever filed: one row is written per broken chip, nothing deletes them, and the page is read
most on the days that history is longest. `NfcFaultReportRepository.search` now does scope, status
and free text in one query — the free text matching the report's own reason and reporter *and*,
through a subquery, the asset's code and name, which is what a reviewer actually knows.

## `import_jobs` and `import_job_errors`

```sql
CREATE TABLE import_jobs (
    id                   BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    job_uuid             VARCHAR(36)   NOT NULL,
    entity_type          VARCHAR(64)   NOT NULL,
    status               VARCHAR(32)   NOT NULL,
    file_name            VARCHAR(512)  NOT NULL,
    file_path            VARCHAR(1024) NOT NULL,
    file_size            BIGINT  NOT NULL DEFAULT 0,
    total_rows           INTEGER NOT NULL DEFAULT 0,
    processed_rows       INTEGER NOT NULL DEFAULT 0,
    success_count        INTEGER NOT NULL DEFAULT 0,
    error_count          INTEGER NOT NULL DEFAULT 0,
    submitted_by_user_id BIGINT  NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    error_message        TEXT,
    created_at           BIGINT NOT NULL,
    started_at           BIGINT,
    completed_at         BIGINT,
    heartbeat_at         BIGINT,
    CONSTRAINT uk_import_jobs_uuid UNIQUE (job_uuid)
);
CREATE INDEX idx_import_jobs_status            ON import_jobs (status);
CREATE INDEX idx_import_jobs_user_created      ON import_jobs (submitted_by_user_id, created_at DESC);
CREATE INDEX ix_import_jobs_status_heartbeat   ON import_jobs (status, heartbeat_at);

CREATE TABLE import_job_errors (
    id         BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    job_id     BIGINT NOT NULL REFERENCES import_jobs(id) ON DELETE CASCADE,
    row_num    INTEGER NOT NULL,
    message_en VARCHAR(1024) NOT NULL
);
CREATE INDEX idx_import_job_errors_job_id ON import_job_errors (job_id);
```

`status` ∈ `PENDING`, `RUNNING`, `COMPLETED`, `FAILED`, `CANCELLED`.
`entity_type` ∈ `LOCATIONS`, `PLANT_SYSTEMS`, `MAIN_FUNCTIONS`, `SUB_FUNCTIONS`,
`ASSET_ENTRIES`, `USERS`, `OPERATIONAL_UNITS`, `UNIT_STAFF`.

`job_uuid` rather than the numeric id is what appears in URLs, so a job reference cannot be
guessed by counting.

**`idx_import_jobs_status` is what `assertNoActiveImport()` uses on every submission** —
imports are deliberately serialised system-wide. That guard also means a job stuck in
`PENDING`/`RUNNING` blocks all future imports **for every user**, which is what makes
`heartbeat_at` worth a column.

`heartbeat_at` (V2) is the last time the worker thread proved it was alive: written when the
job is marked `RUNNING` and refreshed on every progress tick (every 25 rows). It exists
because progress alone cannot distinguish a slow import from a dead one — a job that dies on
its first row never advances `processed_rows` either. `ImportJobWatchdog` fails jobs whose
heartbeat is older than `app.import.stale-timeout-minutes`, and
`ix_import_jobs_status_heartbeat` keeps that once-a-minute scan off the full job history.
Nullable, because rows written before that column existed have none; readers fall back to `started_at`.
See [jobs.md](jobs.md#excel-import-jobs).

`error_message` is `TEXT` and not `VARCHAR` because it holds whatever a stack trace produced.
The application truncates to 500 characters before writing — a stack-trace-length message
helps nobody in a table cell — so the column type is headroom, not an invitation.

## `audit_log`

```sql
CREATE TABLE audit_log (
    id             BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    entity_type    VARCHAR(100) NOT NULL,
    entity_id      VARCHAR(255),
    action         VARCHAR(20)  NOT NULL,
    actor_user_id  BIGINT,  -- deliberately NOT a foreign key; see below
    actor_username VARCHAR(255),
    source         VARCHAR(20),
    request_id     VARCHAR(64),
    changes        JSONB,
    recorded_at    BIGINT NOT NULL
);
CREATE INDEX idx_audit_log_entity      ON audit_log (entity_type, entity_id);
CREATE INDEX idx_audit_log_recorded_at ON audit_log (recorded_at DESC);
```

**Written automatically for every repository `save`/`delete`** by `RepositoryAuditAspect` —
you never call it. `changes` holds the field-level diff; `entity_type` is the *table* name so
the log reads in the same vocabulary as this document.

`actor_username` is denormalised alongside `actor_user_id` so the log stays readable after a
rename, and readable at all for a null actor (a background job).

**`actor_user_id` has no foreign key, and that is deliberate** (V3 drops the `RESTRICT` one
V1 created). Audit rows are written asynchronously, so one can still be queued when its actor's
account is deleted — `hasAppActivity` sees only rows already written — and the queued INSERT then
died on the foreign key with the row lost silently. A full suite run logged 42 such losses while
every test stayed green, because `AuditWriteService` catches the failure and warns.

`ON DELETE SET NULL` was tried first and does not fix it. A referential action fires when the
*parent* row is deleted and rewrites the children existing **at that moment**; these rows arrive
afterwards, naming an id that no longer resolves, so there is nothing for it to act on and the
INSERT fails exactly as under `RESTRICT`. It repairs only the already-written case — which is the
one case the delete guard already refuses.

Dropping the constraint is what closes it. `actor_user_id` is not a live reference but a statement
about the past — "this account did this, then" — which stays true after the account is gone. That
is the same reason `actor_username` sits denormalised beside it. Nothing dereferences the id:
`AuditLog.actorUserId` is a plain `Long`, not a `@ManyToOne`, no query joins through it, and the
audit screen renders the username. `ddl-auto=validate` checks tables, columns and types, not
foreign keys, so the boot-time check is unaffected.

Two consequences worth knowing. **The delete guard is untouched** — `UserService.hasAppActivity`
uses `existsByActorUserId`, an ordinary query that never needed a constraint behind it, so a user
with recorded activity is still refused. And **ids are not re-checked**: `users.id` is `GENERATED
BY DEFAULT`, so an explicit insert could in principle reuse a deleted id and make an old row
appear to name someone else. Read `actor_username`; it is the authoritative field.

`log_sheet_action_log` keeps its three `RESTRICT` keys (`fk_lsal_actor_user`, `fk_lsal_from_user`,
`fk_lsal_to_user`) — not because it is exempt, but because `LogSheetActionLogger` writes
**synchronously**, inside the caller's transaction. Its rows are always visible to the guard
before a delete is allowed, so no write can land after its actor is gone.

`request_id` is the MDC correlation id, which ties an audit row to the request that caused it
in the application log.

**This is the biggest table in the system by row count** — every import row writes one. See
[jobs.md](jobs.md#audit-writes) for the queue behind it and the failure mode it can cause,
and the audit retention purge for how it is trimmed.

## `api_keys`

```sql
CREATE TABLE api_keys (
    id                 BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    client_name        VARCHAR(255) NOT NULL,
    description        VARCHAR(1000),
    key_id             VARCHAR(64)  NOT NULL,
    secret_hash        VARCHAR(64)  NOT NULL,
    prefix             VARCHAR(32)  NOT NULL,
    active             BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at         BIGINT       NOT NULL,
    created_by_user_id BIGINT REFERENCES users(id),
    expires_at         BIGINT,
    revoked_at         BIGINT,
    revoked_by         BIGINT REFERENCES users(id),
    revoke_reason      VARCHAR(500),
    last_used_at       BIGINT,
    CONSTRAINT uk_api_keys_key_id UNIQUE (key_id)
);
CREATE UNIQUE INDEX ux_api_keys_client_name_live
    ON api_keys (LOWER(client_name)) WHERE revoked_at IS NULL;
CREATE INDEX idx_api_keys_revoked_at ON api_keys (revoked_at);
```

**One credential per third-party system, and it is not a user.** A service account would have
dragged in the whole user model — a role, unit assignments, a password policy, a login-attempt
lock, an `api_sessions` row — and every one of the three access layers in
[security.md](security.md#1-the-three-layers) assumes a human principal. The requirement is the
opposite of that, so a key is stored as its own thing. See
[security.md](security.md#7-the-fourth-authentication-surface--integration-api-keys).

**The key is shown once.** Presented format `lsk_<key_id>_<secret>`, where `key_id` is 16 hex
characters and `secret` is 256 bits of `SecureRandom` in base64url. Only `secret_hash` (SHA-256,
hex) is stored, so a lost key is re-issued and never recovered.

**Why the key has two halves.** `key_id` is public and indexed, so verifying a request is one
lookup on a unique index plus one hash comparison. Hashing the *whole* key instead would force
either a scan that hashes every row, or making the hash the primary key — and then no
administrator could be shown which key a usage row belongs to.

**SHA-256, not BCrypt, deliberately.** A slow KDF exists to make guessing a low-entropy password
expensive. There is no password here — the secret is 256 random bits — so brute force is not on
the table, and BCrypt's ~100 ms would be paid on every request of an integration that may poll
once a minute. Same reasoning as GitHub's and Stripe's key formats.

**Three independent ways a key stops working**, combined in one place (`ApiKey.isUsableAt`) so no
caller can check two of them and believe it has checked the key:

| State | Reversible? | Meaning |
|---|---|---|
| `active = false` | yes | paused — "stop this integration until their migration finishes" |
| `revoked_at` set | **no** | permanent — the key leaked; issue a new one |
| `expires_at` passed | n/a | the key aged out on its own |

**Revoke, never delete.** The row survives so past `api_key_usage` rows stay attributable, and so
one integration's revocation provably cannot touch another's — each key is an independent row
with no shared state between them.

`ux_api_keys_client_name_live` is **partial on non-revoked rows**: one live key per client, while
revoking and re-issuing for the same client — the normal rotation path — does not collide with
the retired row.

**"Live" here means only *not revoked*.** An expired key and a disabled key both still occupy
their client's slot, so both block re-issue until they are revoked. That is intentional — the
slot is about which row *represents* this client, not about which row currently works — but it
means the create path has to say which of the three it hit, because the next step differs:

| Blocking row | What the administrator does next |
|---|---|
| active and usable | nothing is wrong; the integration already has a working key |
| disabled | re-enable it, or revoke and issue a new one |
| expired | **revoke it, then issue a new one** — there is no extend |

`ApiKeyRepository.findLiveByClientName` returns the row rather than a boolean for exactly this
reason: a boolean could only ever produce one sentence, and the one it produced said "an active
key already exists" about a key that had expired weeks earlier.

`last_used_at` is throttled to one write a minute (`ApiKeyAuthenticator.LAST_USED_THROTTLE_MS`,
the same value and reasoning as `api_sessions.last_seen_at`) and written with a `@Modifying`
query rather than `save()`, so a polling integration does not produce an `audit_log` row per
request. `ApiKey` itself *is* audited — issuing and revoking a credential is exactly what that
table is for — with `secret_hash` in `AuditEntitySupport.MASKED_FIELDS`.

## `api_key_usage`

```sql
CREATE TABLE api_key_usage (
    id           BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    api_key_id   BIGINT REFERENCES api_keys(id),
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
    requested_at BIGINT       NOT NULL
);
CREATE INDEX idx_api_key_usage_requested_at  ON api_key_usage (requested_at);
CREATE INDEX idx_api_key_usage_key_requested ON api_key_usage (api_key_id, requested_at);
```

**One row per request that reached the integration chain, including the ones it refused.** The
refusals are the point, not a side effect: a run of `INVALID_KEY` from one address is the only
evidence anybody will get that somebody is guessing keys, and a `REVOKED_KEY` row is how you find
the integration nobody told about the rotation.

**`api_key_id` is nullable on purpose** — a request presenting an unknown key has no key to point
at, and those are exactly the rows an administrator wants to see. `key_id` and `client_name` are
denormalised so a row still reads correctly on its own and listing a day of traffic needs no join.

**`outcome` is finer-grained than `status_code`**, because every rejection answers 401 and the
code alone cannot tell "never sent a key" from "revoked last Tuesday and still polling":
`OK`, `MISSING_KEY`, `INVALID_KEY`, `DISABLED_KEY`, `REVOKED_KEY`, `EXPIRED_KEY`, `BAD_REQUEST`,
`NOT_FOUND`, `ERROR`. The **caller** is told only `unauthorized` for all five key failures — the
real reason lives here, where an administrator can read it and the caller cannot.

**Not `audit_log`, and not negotiable.** That table records *changes*; an integration only reads,
so every row would be a change record for a change that did not happen. And an integration polling
once a minute writes ~1,400 rows a day, which mixed into `audit_log` drowns the record of who
edited what — the one question that table exists to answer. `ApiKeyUsage` is therefore in
`AuditEntitySupport.EXCLUDED_TYPES`: it *is* an audit trail, and auditing it would write two rows
per request.

`query_string` records the filters a caller asked for. The key itself can never appear there — it
travels in a header, which is the other reason it is a header.

Written asynchronously on `auditExecutor`; trimmed by a scheduled purge driven by
`audit.retention.days`. See [jobs.md](jobs.md#integration-usage-purge).

## `app_settings`

```sql
CREATE TABLE app_settings (
    setting_key VARCHAR(100) PRIMARY KEY,
    value       VARCHAR(500) NOT NULL,
    updated_at  BIGINT
);
```

Runtime configuration an admin can change without a restart — attachment size and count
ceilings, audit retention days. Anything the *operator of the plant* should be able to set
lives here; anything the *operator of the server* sets lives in `application.properties`.

| Key | Default | Seeded in |
|---|---|---|
| `excel.export.max_rows` | `10000` | V1 |
| `audit.retention.days` | `90` | V1 |
| `auth.jwt.expiry_minutes` | `480` | V1 |
| `attachments.max_images_per_field` / `max_audios_per_field` / `max_videos_per_field` | `3` / `1` / `1` | V1 |
| `nfc.manual_entry_enabled` | `true` | **V3** — site-wide switch **above** the manual-tag-entry permission (an AND, never an OR). Seeded on so an upgrade changes nothing; the code's fallback for a missing or unreadable row is **off**, because a value nobody can read is not an authorisation. |
| `attachments.max_audio_seconds` / `max_video_seconds` | `120` / `120` | V1 |
| `attachments.image_annotation_enabled` | `true` | V3 |
| `nfc.strict_serial_match` | `true` | V3 |

**A missing row is not an error.** `AppSettingsService` falls back to the same default the seed
carries, so a database restored from an older dump keeps working. The seed exists so the plant's
current policy is readable *from the database* — `SELECT * FROM app_settings` answers "what are
these tablets running under" without anyone reading Java — and so a `pg_dump` carries an explicit
value rather than an absence.

The two boolean policies are also deliberately **fallback-ON**: an unreadable or missing value
leaves the annotation step and the strict scan check enabled. A garbled row must never be the
thing that quietly relaxes an integrity rule.

The PWA receives the attachment limits **and both policies** through `/api/bootstrap`, so a
device enforces the same ceiling the server does — an operator learns a file is too big before
recording it, not after — and no tablet decides its own scan rule.

---

## Changing the schema

1. **Write a new `V{n}__short_description.sql`.** Never edit an applied file.
2. **Update the affected section here**, in the same commit.
3. Update the entity, and add `@Column(name = "…")` explicitly — implicit naming has bitten
   this project before.
4. Boot the app. `ddl-auto=validate` is your check that entity and schema agree.
5. If the change affects the mobile contract, update the PWA's Dexie schema and
   `docs/` on that side too.

## Related

- **[hierarchy.md](hierarchy.md)** — how the five levels combine, and what must happen on update
- **[log-sheets.md](log-sheets.md)** — the lifecycle these tables serve
- **[jobs.md](jobs.md)** — the background work that reads and writes them
- **[reports.md](reports.md)** — the queries built on them
- **[security.md](security.md)** — how `roles` / `permissions` / `role_permissions` are actually enforced
- **[AGENTS.md](../AGENTS.md)** — traps found the hard way
