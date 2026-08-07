# Backend Offline-First

Copyright (C) 2026 hadi_hnp

A **Spring Boot** backend for an industrial **round/log-sheet inspection** management system, built with an **offline-first** architecture. It coordinates between a web admin panel (Thymeleaf) and an offline-capable operator mobile app that periodically syncs data with the server.

---

## Table of Contents

- [Overview](#overview)
- [Key Features](#key-features)
- [Tech Stack](#tech-stack)
- [Architecture](#architecture)
- [Data Model & Database](#data-model--database)
  - [Asset Placement Hierarchy](#asset-placement-hierarchy)
  - [Flyway notes](#flyway-notes)
- [Authentication & Authorization (RBAC)](#authentication--authorization-rbac)
  - [Adding a new endpoint (required)](#adding-a-new-endpoint-required--do-not-skip)
  - [Login-attempt throttle](#login-attempt-throttle)
  - [Default system roles (5)](#default-system-roles-5)
  - [Permission categories at a glance](#permission-categories-at-a-glance)
  - [Extra service-layer rules](#extra-service-layer-rules-beyond-endpoint-permissions)
- [Active Directory (LDAP) authentication](#active-directory-ldap-authentication)
- [Log-Sheet Lifecycle](#log-sheet-lifecycle)
  - [Custom (template-less) log sheets](#custom-template-less-log-sheets)
  - [Replacing an asset on a sub-function](#replacing-an-asset-on-a-sub-function)
  - [Asset selection modes (dynamic vs frozen)](#asset-selection-modes-dynamic-vs-frozen)
  - [User-submitted date validation](#user-submitted-date-validation)
- [Project Structure](#project-structure)
- [Prerequisites](#prerequisites)
- [Getting Started](#getting-started)
- [Configuration (application.properties)](#configuration-applicationproperties)
- [Mobile API (Offline Sync)](#mobile-api-offline-sync)
  - [API Documentation (OpenAPI / Swagger — admin only)](#api-documentation-openapi--swagger--admin-only)
- [Attachments (photo & audio fields)](#attachments-photo--audio-fields)
- [Web Admin Panel](#web-admin-panel)
  - [Favicon / app icon](#favicon--app-icon)
- [Reports](#reports)
  - [Report performance at scale](#report-performance-at-scale)
- [Batch Excel Import (async)](#batch-excel-import-async)
- [Audit Trail & Logging](#audit-trail--logging)
- [Operations Monitoring (Actuator)](#operations-monitoring-actuator)
- [Testing](#testing)
- [Build & Deploy](#build--deploy)
- [Default User](#default-user)
- [License](#license)

---

## Overview

This project implements a periodic industrial inspection ("round") system where:

- **Operators** use a mobile app (likely NFC-based) in operational environments that may lack a stable internet connection, filling out inspection forms (Log Sheets).
- Data is stored **offline** and synced with the server in **batches** as soon as connectivity is available.
- **Supervisors and administrators** manage master data, organizational structure, log-sheet templates, roles/permissions, and reports through a **web panel** (Thymeleaf + Bootstrap).
- The server is the **authoritative source of truth** for log-sheet lifecycle state, ensuring data conflicts are handled correctly in offline/multi-user scenarios.

---

## Key Features

- ✅ **Offline-first architecture** with idempotent keys (`local_id`, `client_action_id`) to prevent duplicate data on sync.
- ✅ **Hierarchical master data management** with nested trees at every placement level: Operational Unit → Location (tree) → Plant System (tree) → Main Function (tree) → Sub Function (tree) → Asset. Each node has exactly one **direct** parent; full ancestry is **denormalized** onto downstream rows and **cascaded** on save (including `AssetEntry.updatedAt` for mobile sync).
- ✅ **Dynamic asset classes** with configurable form fields (JSON-schema-like) — `AssetClass` + `FieldDefinition`.
- ✅ **NFC-based asset lookup** (`GET /api/asset-entries/nfc/{nfcTagId}`).
- ✅ **One asset per sub-function** (DB unique index + create/update/import validation); inactive assets stay findable by NFC but are skipped in new log-sheet generation/preview.
- ✅ **Log-sheet templates** with manual or scheduled generation based on a recurrence interval (hourly/daily/weekly/monthly).
- ✅ **Custom (template-less) log sheets** — supervisors hand-pick active assets in a supervised unit (multi-class allowed); no template, no scheduler.
- ✅ **Frozen-list templates** (`asset_selection_mode = EXPLICIT`) — the scheduled counterpart of a custom log sheet: a hand-picked, multi-class asset set that stays **identical on every generation**; an asset leaves it only by being deactivated.
- ✅ **Automatic scheduler** that generates due log sheets and expires ones whose completion window has passed.
- ✅ **Work assignment model**: shared unit pool, claim/release by operators, assign/reassign by supervisors, supervisor takeover.
- ✅ **Unit-scoped RBAC** for supervisor/operator roles, restricting visibility and actions to their own operational units.
- ✅ **Fine-grained RBAC** with authorities `METHOD:path` (one DB row per authority; some URLs reuse a parent authority — export, options, draft, bulk delete) and 5 default system roles: `ADMIN`, `HIGH_USER`, `SUPERVISOR`, `SENIOR_OPERATOR`, `OPERATOR`.
- ✅ **Full audit trail** (field-level entity change history) with async writes and configurable retention/cleanup (manual or background).
- ✅ **Business event logging** separated from system logs (`business.log`).
- ✅ **Excel import/export** for master data, users, and assets (Apache POI).
- ✅ **Async batch Excel import** — central UI (`/batch-import`) for large files: background processing, live progress, per-row error reporting, cancel/delete jobs.
- ✅ **Asset and record reporting**.
- ✅ **Localized (Farsi) error messages** for API responses.

---

## Tech Stack

| Layer | Technology |
|---|---|
| Language & Platform | Java 25, Spring Boot 4.1.0 |
| Web/API | Spring Web MVC, Spring Security (Form Login + Session, `@PreAuthorize`) |
| View Layer | Thymeleaf + Bootstrap 5.3.3 + Bootstrap Icons |
| Database | PostgreSQL |
| ORM | Spring Data JPA / Hibernate |
| Migrations | Flyway |
| Excel Import/Export | Apache POI (poi-ooxml) |
| AOP | AspectJ (logging and automatic repository auditing) |
| Serialization | Jackson |
| Testing | JUnit 5, Spring Boot Test, Testcontainers (PostgreSQL), Spring Security Test |
| Coverage | JaCoCo |
| Build Tool | Maven (Maven Wrapper included) |
| Password Hashing | BCrypt (spring-security-crypto) |

---

## Architecture

The project follows a layered, modular design under the `com.hnp.backendofflinefirst` package:

```
Controller (REST /api/**)   ─┐
Web Controller (Thymeleaf) ──┼──► Service ──► Repository (Spring Data JPA) ──► PostgreSQL
                              │
Security (Spring Security) ──┘
Aspect (AOP: Logging/Audit) ──── attached to Service/Repository for automatic tracing
Scheduler (@Scheduled) ────────  drives log-sheet lifecycle (generation/expiry)
```

- **Controller** (`controller/`): REST APIs for the mobile app under `/api/**`, independent of session-based UI.
- **Web** (`web/`): Thymeleaf controllers for the admin panel (server-rendered HTML views).
- **Service** (`service/`): Core business logic (log-sheet lifecycle, import/export, operational unit scope resolution, **`AssetHierarchyService`** for master-data placement trees, etc.).
- **Entity/Repository**: Database model (JPA entities) and Spring Data repositories.
- **Security**: Custom `UserDetailsService`, permission management (`PermissionCodes`), current-user access utilities.
- **Aspect**: Automatic method logging (`LoggingAspect`) and automatic repository change auditing (`RepositoryAuditAspect`).
- **Audit**: Entity-level audit trail infrastructure (`AuditEntitySupport`, `AuditFieldChange`).
- **Logging**: Business event logging (`BusinessEventLogger`), sensitive data scrubbing (`LogSanitizer`), request tracing via MDC.
- **UI/Util**: View helpers (Jalali date conversion, reference labels, localized error messages, etc.).

---

## Data Model & Database

Schema is managed by **numbered Flyway scripts** under `src/main/resources/db/migration/`. Flyway runs them in version order on startup (`spring.flyway.enabled=true`).

| Script (example) | Typical role |
|---|---|
| `V1__initial_schema.sql` | Baseline tables, indexes, RBAC seeds (`permissions`, `role_permissions`, system roles) |
| `V2__api_session_registry.sql` | Example: new table + admin permissions for a feature (`api_sessions`) |
| `V3__web_session_permissions.sql` | Example: permission-only migration (no new table; web session admin UI) |

**Pattern:** ship **new** DDL or permission seeds in the **next** `V{n}__….sql` — do not edit scripts that already ran in shared environments (checksum mismatch). Older one-off changes were folded into V1 where possible; the list above is illustrative, not a promise that the repo will always stop at three files.

SQL is heavily commented in English (tables, FKs, indexes). See [Flyway notes](#flyway-notes).

### Users & Organization
- `users` — application users (admin panel login and/or field operations). Each user has an `auth_type`: `LOCAL` (BCrypt only), `ACTIVE_DIRECTORY` (LDAP bind at login), or `HYBRID` (local password first, then AD). Roles and permissions always come from the application database — AD is used for password verification only. Optional contact fields: `national_code`, `phone_number`, `nfc_tag_id` (person NFC) — each must be either blank or **unique** (`ux_users_national_code`, `ux_users_phone_number`, `ux_users_nfc_tag_id_lower` — the NFC one is case-insensitive, matching `asset_entries.nfc_tag_id`; standard Postgres unique indexes already permit any number of blank/NULL rows, so no partial-index trick is needed). Enforced both at the DB level and pre-checked in `UserService.applyContactFields()` (mirrors the existing username-duplicate check) so admins get a friendly Persian message instead of a raw constraint error. Prefer `active=false` over hard-delete: FKs and service rules block deleting users that already appear in log sheets, audits, or import jobs.
- `operational_units` — hierarchical operational units (org structure); `code` is **case-insensitive unique**.
- `unit_supervisors` / `unit_operators` — many-to-many links between units and supervising/operator users.
- `location_units` — many-to-many links between **locations and the operational units responsible for them**. A location can be owned by several units (same shape as the staff links above); there is no `locations.unit_id` column. This join table is the root of every unit-scope walk: `unit → location_units → location tree → systems → main/sub functions → assets`. A location with no row here belongs to no unit and is invisible to unit-scoped roles.

### RBAC
- `permissions` — one row per **authority** (`METHOD:path`), e.g. `GET:/locations`. This is the unit stored in the DB and checked by `@PreAuthorize`; it is **not** always one row per physical controller mapping (see below).
- `roles` — system/custom roles.
- `role_permissions`, `user_roles` — many-to-many join tables.

### Settings
- `app_settings` — key/value application configuration (e.g. Excel export row limit, audit retention days, JWT expiry minutes).

### Mobile API sessions
- `api_sessions` — one row per issued mobile-API JWT, keyed by the token's `jti`. Rows survive revocation/expiry so the admin panel can show login history. See [Mobile API sessions](#mobile-api-sessions-stateful-jwt).

### Master Data (hierarchical)
- `locations` — physical/logical plant areas (tree via `parent_id`); `code` case-insensitive unique. Responsible operational units live in `location_units` (several per location) — edit them from the searchable multi-select on the location form. The Excel **export** still emits a comma-separated `unitCodes` column for reporting, but the **import** no longer reads it: an imported location starts unowned.
- `plant_systems` — engineering systems (tree via `parent_id`; root systems also carry `location_id`); `code` case-insensitive unique.
- `main_functions` — functional groupings (tree via `parent_id`; roots attach to a **system** or **location**); `code` case-insensitive unique.
- `sub_functions` — granular equipment/function groups (tree via `parent_id`; roots attach to a **main function**, **system**, or **location**). Each sub-function has a physical **tag** used for NFC fallback when an asset’s NFC is blank. Both `code` and `tag` are **case-insensitive unique**.
- `asset_classes` + `field_definitions` — dynamic form schema per asset class. `field_definitions` is the **only** source of truth (the old denormalized `asset_classes.fields` JSONB was removed — a second copy of the same schema only drifts). Field keys are unique **per class** (case-insensitive). Asset-class `name` is case-insensitive unique.
- `asset_entries` — physical assets; each row **must** reference exactly one `sub_function_id`. At most **one ACTIVE** asset may occupy a sub-function (`ux_asset_entries_active_sub_function`, a **partial** unique index on `WHERE active`); any number of **inactive** assets may share it — see [Replacing an asset on a sub-function](#replacing-an-asset-on-a-sub-function). Placement ancestry is read from the sub-function’s denormalized fields. Also unique (case-insensitive): `asset_code`, `nfc_tag_id` when present, and `nfc_serial` when present (`ux_asset_entries_nfc_serial_lower`) — see [NFC tag id vs NFC serial](#nfc-tag-id-vs-nfc-serial). `active` (default `true`) excludes inactive assets from **log-sheet template preview / generation** only; NFC lookup and sync can still find them. `status` (`VARCHAR(30)`, nullable) is a separate free-form field for the asset's real-world operational state (e.g. ON / OFF / IDLE / MAINTENANCE) — **not** the same concept as `active`, which only gates log-sheet generation. Schema-only for now: no entity field, DTO, API, or UI reads or writes it yet; deliberately left unconstrained (no CHECK, no enum) since the exact set of states isn't finalized. Add the `AssetEntry.status` field (with `@Column(name = "status")`) plus DTO/mapper/UI wiring when this is actually put to use — the column is safe to read/write directly via SQL or a future migration without needing another schema change first.

> **Placement rule:** every main/sub function row stores one direct parent axis (`parent_id` *or* `system_id` / `location_id` / `main_function_id` for roots). `system_id` and `location_id` on main/sub functions are **denormalized** copies of the full ancestor chain so assets, log-sheet scope walks, and mobile bundles stay fast without recursive joins.

### Asset Placement Hierarchy

Master-data placement is owned by **`AssetHierarchyService`** (`service/AssetHierarchyService.java`). It is the single place that:

1. Applies the chosen **direct parent** and fills denormalized ancestry (`apply*Parent` / `apply*Ancestry`).
2. **Cascades** ancestry changes to descendant nodes on save (and bumps linked **`asset_entries.updated_at`** when sub-function placement changes).
3. Resolves **log-sheet template scope** to the set of sub-function IDs beneath a location, system, main function, or sub-function tree walk (`subFunctionIdsInScope`).

```
Location (parent_id tree)
  ├─ PlantSystem (parent_id tree; root → location_id)
  │    ├─ MainFunction (parent_id tree; root → system or location)
  │    │    └─ SubFunction (parent_id tree; root → mainFunction / system / location)
  │    │         └─ AssetEntry (sub_function_id only)
  │    └─ SubFunction (direct under system)
  ├─ MainFunction (direct under location)
  └─ SubFunction (direct under location)
```

| Entity | Direct parent options | Denormalized fields | Cascade on ancestry change |
|---|---|---|---|
| **Location** | `parent_id` → another location | — (no downstream denorm) | No — downstream rows keep the same `location_id`; scope walks read the tree at query time |
| **PlantSystem** | `parent_id` → another system **or** root `location_id` | `location_id` on child systems | Yes → root main functions, direct sub-functions, nested sub-function trees |
| **MainFunction** | `parent_id` → another MF **or** root `system_id` / `location_id` | `system_id`, `location_id` | Yes → child main functions, direct sub-functions, nested sub-function trees |
| **SubFunction** | `parent_id` → another SF **or** root `main_function_id` / `system_id` / `location_id` | `main_function_id`, `system_id`, `location_id` | Yes → child sub-functions + **asset** `updated_at` touch |

**Prior-state reads:** updates that mutate an entity in memory before save use persisted-ancestry projections (`*Ancestry` + `findPersistedAncestryById` with `FlushMode.COMMIT`) so cascade detection still sees the pre-change database values.

**Web panel:** list pages show parent as **type + label** (e.g. `سیستم: SYS-01 - برق`) via `ReferenceLabelService`. Edit forms for **plant systems**, **main functions**, and **sub-functions** show a cascade maintenance warning when ancestry may propagate.

### Hierarchy cascade — operational guidance (large trees)

Cascade after reparenting a **System / Main Function / Sub Function** walks descendants entity-by-entity in **one transaction** (updates denormalized ancestry; touches `asset_entries.updated_at` for mobile sync). That is correct for normal trees, but costly when a branch has thousands of sub-functions/assets (locks, memory, HTTP timeout risk, audit volume).

| Situation | Recommendation |
|---|---|
| Daily operations / small edits | Fine — no special handling |
| Move a **large** System / MF / SF after a huge asset import | Treat as **Maintenance**: off-peak, one change at a time, wait for the request to finish |
| Location reparent | Does **not** cascade denormalized fields onto systems/functions (scope walks still use the location tree) |

Bulk/async cascade is **not** implemented yet; prefer operational discipline over large mid-shift reorganizations until/unless that is built.

**Excel import columns** (hierarchy sheets: first non-empty parent column wins):

| Sheet | Columns (header row) |
|---|---|
| locations | `code`, `name`, `nameFa`, `parentCode` |
| plant-systems | `code`, `name`, `nameFa`, `parentSystemCode`, `locationCode` |
| main-functions | `code`, `name`, `nameFa`, `parentMainFunctionCode`, `systemCode`, `locationCode` |
| sub-functions | `code`, `name`, `nameFa`, `tag`, `parentSubFunctionCode`, `mainFunctionCode`, `systemCode`, `locationCode` |
| asset-entries | `assetCode`, `assetName`, `assetNameFa`, `nfcTagId`, `nfcSerial`, `subFunctionCode`, `className`, `active` (`true`/`false`; blank → active). Empty NFC → sub-function tag/code. Each `subFunctionCode` may be used by **at most one ACTIVE** asset row (file + DB). |

> **`nameFa` is optional everywhere** — a secondary Persian title, shown in the list and both forms, never used for lookups.
>
> ⚠️ **Re-download the templates.** `nameFa` was inserted directly **after `name`**, so every column after it shifted by one; the importer reads by position and does not validate the header row. The placement is deliberate: an out-of-date sheet then fails loudly on the next code lookup instead of silently writing the wrong value into a field.
>
> ⚠️ **The locations sheet no longer has a unit column.** An imported location starts with **no** operational units; attach them from the multi-select on the location form. The *export* still includes `unitCodes` for reporting, so export and import are intentionally not symmetrical for that one column.
>
> ⚠️ **The assets sheet gained `nfcSerial` directly after `nfcTagId`**, shifting `subFunctionCode`/`className`/`active` by one — re-download the template. It holds the **physical NFC chip serial/UID** (e.g. `00:aa:34:9f:12:cd`): optional, but **unique when supplied**, and unlike `nfcTagId` it is *never* inherited from the sub-function and never released when the asset is deactivated. See [NFC tag id vs NFC serial](#nfc-tag-id-vs-nfc-serial).
| users | `username`, `fullName`, `nationalCode`, `phoneNumber`, `nfcTag`, `password`, `authType`, `active`, `roleCodes` |

**Tests:** `AssetHierarchyServiceTest` (unit) and `AssetHierarchyCascadeIntegrationTest` (PostgreSQL + Flyway) cover nesting, cascade, scope walks, cycle validation, FK delete guards, and asset sync touches. Schema uniqueness (including one asset per sub-function) is covered in `SchemaConstraintsIntegrationTest`.

### Operational Data
> **Reserved, schema-only columns.** `locations`, `plant_systems`, `main_functions`, `sub_functions` and
> `asset_entries` each carry a nullable `status VARCHAR(30)` (real-world operational state, e.g. ON / OFF /
> IDLE / MAINTENANCE — **not** the same concept as `active`, which only gates log-sheet generation) and a
> nullable secondary title (`name_fa`, or `asset_name_fa` on `asset_entries`) for a Persian/second name.
> Both are deliberately DB-only for now: no entity field, DTO, API, or UI reads or writes them, and they are
> left unconstrained (no CHECK, no enum) until their exact use is decided. This is safe because
> `ddl-auto=validate` only checks that entity-mapped columns exist, never the reverse. To put one to use, add
> the field with an explicit `@Column(name = "...")` plus DTO/mapper/UI wiring — no new migration needed.

- `log_sheet_templates` — templates for round log-sheet inspections (manual or scheduled); `name` case-insensitive unique.
  - `operational_unit_id` is the **owning unit**. It is copied onto every generated `log_sheets` row and is the *only* thing that decides which unit can see and fill the resulting work.
  - `restrict_scope_to_unit` (default `TRUE`) is a **scope-picking rule, not an access rule**. When on, the scope must sit under a location owned by that unit and the pickers only offer that unit's hierarchy. When off, the scope may point anywhere in the plant — used when a unit is deliberately made responsible for assets outside its own locations. Access is unaffected either way: the assigned unit still reaches the work through `log_sheets.operational_unit_id`, and no other unit gains visibility of those assets.
  - **Only plant-wide roles (`ADMIN` / `HIGH_USER`) may turn the restriction off.** For a unit-scoped supervisor it is forced back on server-side (`LogSheetTemplateService.applyScopeRestrictionPolicy`), because otherwise they could scope a template at another unit's assets and read those readings back through sheets generated into their own unit.
  - `asset_selection_mode` (`SCOPE` | `EXPLICIT`, default `SCOPE`) decides **where the assets come from** — see [Asset selection modes](#asset-selection-modes-dynamic-vs-frozen) below. In `EXPLICIT` mode `scope_type`, `scope_id`, and `class_id` are all **null** and the assets live in `log_sheet_template_assets` instead.
- `log_sheet_template_assets` — the **frozen asset list** of an `EXPLICIT` template. Composite PK `(template_id, asset_id)`; `template_id` cascades on template delete, `asset_id` is `RESTRICT` so an asset that is part of a frozen list cannot be hard-deleted (guarded with a readable message in `MasterDataDeleteService.assertDeletableAssetEntry`). Empty for `SCOPE` templates.
- `log_sheets` + `log_sheet_entries` — generated log sheets and their entries. Sheets may come from a template (`template_id` set) or be **custom** (`template_id` null, hand-picked multi-class assets via `CustomLogSheetService`).
- `log_sheet_action_log` — immutable audit trail of lifecycle actions with an idempotency key (`client_action_id`) and an **optional** `comment` (`VARCHAR(1000)`) holding the actor's own explanation — see [Why an action was taken](#why-an-action-was-taken-optional-comments).
- `log_sheet_void_submissions` — late offline submissions that arrived after someone else already completed the sheet (voided but retained for the record).

### Audit
- `audit_log` — generic field-level change history for master/operational entities, stored as JSONB.

### Batch import jobs
- `import_jobs` — async Excel import job metadata (status, progress, file path on disk under `app.import.storage-path`, default `./data/imports`).
- `import_job_errors` — row-level errors per job (up to `app.import.max-stored-errors` rows).

> **Key design note:** every primary key is an auto-incrementing `BIGINT IDENTITY`. Business/natural keys (`code`, `local_id`, `nfc_tag_id`, `client_action_id`, `username`) remain `VARCHAR`. Most master-data codes/tags/names use **case-insensitive unique indexes** on `LOWER(...)`. Asset ↔ **active** sub-function occupancy is enforced both in the service/import layer and with the partial index `ux_asset_entries_active_sub_function`.

### Flyway notes

| Situation | What to do |
|---|---|
| New / empty database | Start the app; Flyway applies all pending scripts in `db/migration/` in order. |
| Edit a script **after** it was already applied (even comments-only) | Flyway checksum changes → startup **checksum mismatch**. Prefer `flyway repair`, or update `flyway_schema_history.checksum` for that version only when DDL intent still matches. |
| **New HTTP endpoint / permission** | Always add a DB permission via **Flyway** (see [Adding a new endpoint](#adding-a-new-endpoint-required--do-not-skip)). Never rely on manual UI-only permission creation for production rollout. |
| Need a clean schema replay | Use a fresh database (or drop/recreate) rather than rewriting history on production data. |

---

## Authentication & Authorization (RBAC)

- Authentication supports **local BCrypt**, **Active Directory (LDAP bind)**, or **hybrid** per user (`users.auth_type`). See [Active Directory (LDAP) authentication](#active-directory-ldap-authentication).
- Authentication is **session-based with form login** (`WebSecurityConfig`) for the web panel; mobile API uses **JWT** (`POST /api/auth/login`).
- Permissions are defined as **one authority per protected capability**: `PermissionCodes.code(method, path)`, e.g. `GET:/locations` or `POST:/log-sheets/{id}/complete`.
- Permission checks are enforced on controllers with `@PreAuthorize("hasAuthority('...')")`; `@EnableMethodSecurity` enables this mechanism.
- **Controller mappings vs DB rows:** many handlers reuse a parent authority — e.g. `GET …/export` and `GET …/options/…` use the same `GET:/…` list permission; `POST …/delete-bulk` uses `POST:/…/{id}/delete`; `POST /log-sheets/{id}/draft` uses `POST:/log-sheets/{id}/complete`; batch-import job cancel/errors reuse `GET` or `POST:/batch-import`. Only **new** authorities need a new `permissions` row.
- Permissions are grouped into categories: `general`, `admin`, `organization`, `master-data`, `operational`, `reports`, `api` (seeded in Flyway; baseline in `V1__initial_schema.sql`, later scripts may add more).

#### Adding a new endpoint (required — do not skip)

Every new **authority** that `@PreAuthorize` checks and that is not already seeded **must** get a matching `permissions` row. Permissions are **not** auto-discovered from controllers; they are created **manually** and applied **only through Flyway**.

Checklist when you introduce a **new** authority (new `METHOD:path` string):

1. Add a constant in `PermissionCodes` (`METHOD:path`).
2. Guard the handler(s) with `@PreAuthorize("hasAuthority('METHOD:/path')")`.
3. **Ship a Flyway migration** that `INSERT`s into `permissions` (code, name, category, http_method, endpoint_path) and, when needed, `role_permissions` for roles that should receive it (`ADMIN` is usually all permissions from the V1 cross-join; other roles need explicit grants).
4. Update security/role docs or tests if the matrix changes.

If the new URL can fairly reuse an existing authority (export/options/bulk-delete pattern), **do not** add a duplicate permission — reuse the parent code in `@PreAuthorize`.

Do **not** insert permissions only via the admin UI, ad-hoc SQL outside Flyway, or application startup code. For **already-migrated** databases add a new numbered script (e.g. `V5__add_custom_sheet_permissions.sql`); fold into V1 only for greenfield when explicitly consolidating.

- **Unit-scoped access control** is additionally enforced in the service layer via `OperationalUnitScopeService` (supervisor/operator ↔ operational-unit assignments in `unit_supervisors` / `unit_operators`).
- Users with unit-scoped roles (`SUPERVISOR`, `SENIOR_OPERATOR`, `OPERATOR`) are redirected to **My Inbox** (`/my-inbox`) after login; `ADMIN` and `HIGH_USER` land on the dashboard.
- Mobile REST APIs (`/api/**`) are exempt from CSRF; authentication/access errors are returned as JSON via `ApiAuthenticationEntryPoint` / `ApiAccessDeniedHandler`.

### Login-attempt throttle

`AppAuthenticationProvider` is the single funnel both `POST /api/auth/login` and the web `/login` form go through (all three `auth_type`s — LOCAL/ACTIVE_DIRECTORY/HYBRID) — both chains share one explicit, no-parent `AuthenticationManager` bean (`WebSecurityConfig`) so a failed attempt is checked exactly once regardless of entry point. `LoginAttemptService` (in-memory, per-instance — same non-persistent pattern as `SessionRegistryImpl`/`WebSessionMetadataStore`) tracks failed attempts per **normalized, lower-cased username** and locks it out for a configurable window after too many failures:

| Property | Environment variable | Default |
|---|---|---|
| `app.auth.login-attempt.max-attempts` | `APP_AUTH_LOGIN_ATTEMPT_MAX_ATTEMPTS` | `5` |
| `app.auth.login-attempt.lock-minutes` | `APP_AUTH_LOGIN_ATTEMPT_LOCK_MINUTES` | `15` |

The lock is checked **before** any password verification — including before the LDAP bind for `ACTIVE_DIRECTORY`/`HYBRID` users. This matters beyond ordinary brute-force protection: without it, an attacker who only knows a real employee's **username** could repeatedly submit wrong passwords through this app to trip Active Directory's own account-lockout policy against that employee — a denial-of-service that doesn't require guessing the password at all. Once locked, the username is rejected (`LockedException` → Persian message via `ErrorTranslator`) even if the correct password is supplied, until the window elapses; a successful login resets the counter.

`POST /api/auth/login` always returns the specific translated message in its JSON body. The web `/login` page shows the specific lockout message too (`LoginController` reads `WebAttributes.AUTHENTICATION_EXCEPTION` from the session) — but **only** for lockout; ordinary bad-credentials/disabled-account failures still show the generic "نام کاربری یا رمز عبور نادرست است." to avoid revealing account state to a caller who may not even own the username.

Resets on app restart (in-memory) and doesn't distinguish between LOCAL/AD failures — any 5 wrong attempts in the window lock the username regardless of auth type.

**Admin page `/login-attempts`** (sidebar → «تلاش‌های ورود ناموفق», `ADMIN` only, `LoginAttemptWebController`) lists:
- **Locked users** — failure count, last attempt time, remaining lock time, and a manual **unlock** button (`POST /login-attempts/{username}/unlock`).
- **Near-lockout users** — anyone with recent failures below the threshold, informational only (no action).

Lock state is always recomputed from the clock at read time rather than cached, so the unlock button is safe to click at any time — including after the lock already expired naturally in the time between page load and click; either way it's a plain, idempotent removal from the in-memory tracker, and the username goes back to a clean state (no lingering partial count).

### Mobile API sessions (stateful JWT)

Mobile tokens are signed JWTs **and** are tracked server-side in `api_sessions`, so a valid signature alone is not enough to authenticate a request.

| Behaviour | How it works |
|---|---|
| Session record | `POST /api/auth/login` mints a token carrying a unique `jti` and inserts a row with username, optional `deviceLabel` (from the login payload), `User-Agent`, and client IP (`X-Forwarded-For` first entry when present). |
| **One device per user** | Registering a new session revokes every other live session of that user with reason `SUPERSEDED`. A second tablet login logs the first one out. |
| Revocation | `JwtAuthenticationFilter` accepts a token only while its `jti` row has `revoked_at IS NULL` and `expires_at` in the future, so an admin revoke takes effect on the device's **next** request. |
| Token lifetime | Admin-only, from **Settings → JWT expiry minutes** (`app_settings['auth.jwt.expiry_minutes']`, default 480, range 5–10080). Existing tokens keep the lifetime they were issued with. |
| Activity tracking | `last_seen_at` is refreshed at most once per minute per session (`ApiSessionService.LAST_SEEN_THROTTLE_MS`) so syncing does not cause a write per request. |

Admin page **`/api-sessions`** (sidebar → «نشست‌های اپ موبایل», `ADMIN` only) lists sessions with device, IP, login/expiry/last-activity times and status, filters active vs. all, searches by username/device/IP, and offers per-session revoke plus "revoke every session of this user".

Endpoints: `GET:/api-sessions`, `POST:/api-sessions/{id}/revoke`, `POST:/api-sessions/revoke-user/{userId}` (seeded for `ADMIN` in V2).

**Offline caveat:** revocation is only observed once the device reaches the server. An offline tablet keeps working from its local cache and finds out at the next sync (HTTP 401) — expected for an offline-first client, so the PWA must treat a 401 during sync as "log in again". Tokens issued before this feature carry no `jti` and are rejected, meaning every mobile client must re-login once after the upgrade.

### Web panel sessions (concurrency + admin control)

The browser panel gets the same control surface as mobile, built on Spring Security's standard **concurrent session control** rather than a database table:

| Behaviour | How it works |
|---|---|
| **One browser per user** | `maximumSessions(1)` on the web filter chain: a new form login expires the user's previous session (same "supersede" semantics as `api_sessions`). The old browser is redirected to `/login?expired` with an explanatory notice on its next request. |
| Idle timeout | `server.servlet.session.timeout=60m` (env-overridable via `SERVER_SERVLET_SESSION_TIMEOUT`). Only the web panel is affected — mobile is JWT-based. |
| Session registry | Spring's in-memory `SessionRegistryImpl` + `HttpSessionEventPublisher`. Deliberately **not** persisted: HTTP sessions are already non-persistent across restarts (`server.servlet.session.persistent=false`), so the registry matches the session store's lifetime. `AppUserDetails` implements `equals`/`hashCode` by username — required for the per-user session limit to work. |
| Login metadata | `WebSessionMetadataStore` (in-memory) records IP (`X-Forwarded-For` first entry when present), `User-Agent`, and login time from the form-login success handler; entries are dropped on session destruction. |

Admin page **`/web-sessions`** (sidebar → «نشست‌های وب», `ADMIN` only) lists live sessions with user, browser, IP, login and last-activity times, and expires individual sessions (`WebSessionService`). Rows are addressed by a **SHA-256 digest** of the session id — raw `JSESSIONID` values never reach the page, so the list cannot be used to hijack a session. The admin's own row shows a "current session" badge instead of an expire button.

Endpoints: `GET:/web-sessions`, `POST:/web-sessions/{key}/expire` (seeded for `ADMIN` in V3).

**Restart caveat:** the registry and metadata live in memory, so after an application restart the page starts empty; users simply log in again (sessions were invalidated by the restart anyway).

### Default system roles (5)

| Code | Persian name | Scope | Summary |
|---|---|---|---|
| `ADMIN` | مدیر سیستم | Global | Full access to every endpoint and every operational unit |
| `HIGH_USER` | کاربر ارشد | Unit-aware for templates | Everything except the `admin` category; may edit/delete log-sheet templates only within units they supervise |
| `SUPERVISOR` | سرپرست | Own units (+ sub-units) | Log-sheet supervision and mobile/web field work; templates are **read-only** — may view those of their own units but never create, edit, or delete |
| `SENIOR_OPERATOR` | اپراتور ارشد | Own units **only** | Like `OPERATOR`, plus web-based log-sheet completion |
| `OPERATOR` | اپراتور | Own units **only** | Claim/release and complete assigned work (mobile app; no web fill form) |

> Custom roles can be created in the panel, but the five roles above are **system roles** and cannot be deleted.

#### Building a custom role from an existing one

Ticking dozens of permission boxes from scratch is how access bugs get made, and the usual need
is a *variant* — "the same as `SUPERVISOR` but without template editing". The roles page has a
**ساخت نقش مشابه** action (`bi-files` icon) on every row: give the copy a new code and name and
it is created carrying **every permission of the source role**, ready to edit.

| Behaviour | Rule |
|---|---|
| Permissions | copied in full from the source |
| `system_role` flag | **never** copied — a copy of a system role is an ordinary, deletable role. Inheriting the flag would quietly create a second undeletable role |
| Description | inherited from the source when left blank; an explicit value wins |
| Users | **not** copied. Duplicating a role is about the shape of the access; silently granting it to everyone who held the original is the opposite of what someone building a narrower variant wants |
| Code | must be unique — rejected with a Persian message otherwise |

Endpoint: `POST /roles/{id}/duplicate`, guarded by the existing `POST:/roles` authority (if you
may create a role, you may create one by copying).

> **Unit hierarchy rule.** Supervision cascades **down**, operation does **not**.
> Supervising unit A also covers B, C and everything beneath them — a supervisor is responsible for the
> whole branch, so their log-sheet lists, claimable pool, team view and custom-log-sheet unit picker all
> span it. Operating unit A covers **only** A: operators of A and operators of B are separate teams that
> share a manager, so an operator of A never sees, claims, or is assigned B's work. Both rules live in
> `OperationalUnitScopeService` — `getSupervisorScopeUnitIds` expands downward, `isOperatorOf` does not,
> and `getAccessibleUnitIds` is the union of the two.
>
> A supervisor of A may therefore **take over and complete** work sitting in B or D personally, and may
> **assign** it — but only to that sub-unit's *own* operators: an operator of A is not eligible for B's work.
> The unit tree is cycle-guarded (`A → B → A` is rejected) because access control depends on it.

### `ADMIN` — مدیر سیستم

- **Permissions:** all seeded permissions (every category).
- **Web panel:** dashboard, users, roles, settings, audit logs, **batch Excel import**, operational units, all master data, log-sheet templates (full CRUD), log sheets, reports, records (if granted to custom roles; not in default supervisor/operator sets).
- **Operational scope:** no unit filter — sees and manages all units.
- **Typical use:** system administrator, initial bootstrap user (`admin` / `admin123`).

### `HIGH_USER` — کاربر ارشد (سرپرست ارشد)

- **Permissions:** every permission **except** the `admin` category:
  - ✅ `general` — dashboard (`GET:/`)
  - ✅ `organization` — operational units (+ Excel import/export, staff import)
  - ✅ `master-data` — locations, plant systems, main/sub functions, asset classes & fields, asset entries (+ Excel), **log-sheet templates (list, create, edit, delete)**
  - ✅ `operational` — log sheets (full lifecycle), my inbox, reports
  - ✅ `api` — `GET /api/bootstrap` (unit context), log-sheet inbox/bundle/batch, claim/release/assign/reassign, NFC lookup, legacy records batch
  - ❌ `admin` — users, roles, settings, audit retention UI, audit log viewer
  - ✅ Batch Excel import UI (`GET:/batch-import`, `POST:/batch-import`, `GET:/batch-import/jobs`) — granted explicitly (category is `admin`, but `HIGH_USER` receives these endpoints)
- **Service-layer rules (log-sheet templates):**
  - Sees only templates whose `operational_unit_id` is in a unit they **supervise** (including sub-units).
  - May create, edit, and delete templates within that supervised scope.
- **Log sheets:** not filtered by unit assignment in the same way as operators; list visibility follows `LogSheetAccessService` (non–unit-scoped for `HIGH_USER`).
- **Typical use:** plant/department lead who manages master data and templates for their area but not global user administration.

### `SUPERVISOR` — سرپرست

- **Web panel permissions:**
  - ✅ Log sheets: list, detail, manual generate from template, **custom (template-less) create with hand-picked assets**, claim, release, assign, reassign, extend deadline, takeover, web fill, web complete
  - ✅ My inbox (`GET:/my-inbox`)
  - ✅ Reports (`GET:/reports`)
  - ✅ Log-sheet templates: **list + create only** (`GET:/log-sheet-templates`, `POST:/log-sheet-templates`)
  - ❌ Log-sheet templates: **no edit or delete** (`POST:/log-sheet-templates/{id}`, `POST:/log-sheet-templates/{id}/delete`)
  - ❌ Dashboard (`GET:/`), users, roles, settings, audit logs
  - ❌ Master data CRUD (locations, assets, asset classes, etc.) — not in default permission set
  - ❌ Operational units management
- **Mobile API:**
  - ✅ `GET /api/bootstrap`, `GET /api/log-sheets/inbox`, `GET /api/log-sheets/{id}/bundle`, `POST /api/log-sheets/batch`
  - ✅ Claim, release, assign, reassign on log sheets
  - ✅ `GET /api/operational-units/{unitId}/operators`, `GET /api/asset-entries/nfc/{nfcTagId}`
- **Service-layer rules:**
  - Log sheets and template lists are limited to units they **supervise** (and descendant units).
  - Supervisor-only actions (assign, reassign, release of `SUPERVISOR_ASSIGNED` sheets, takeover, extend) require `OperationalUnitScopeService.isSupervisorOf(user, unit)`.
  - May create templates only for supervised units; cannot edit/delete existing templates (enforced in `LogSheetTemplateService` even if permissions were customized).
  - May create **custom log sheets** (`POST:/log-sheets/custom`) only for supervised units; selected assets must be **active** and within that unit’s hierarchy scope; assets may span multiple asset classes (multi-class field snapshot).
- **Typical use:** shift/line supervisor who runs daily rounds, assigns work, defines new templates, and occasionally creates one-off custom rounds for a subset of assets.

### `SENIOR_OPERATOR` — اپراتور ارشد

- **Permissions:** `OPERATOR` set **plus** web completion:
  - ✅ `GET:/log-sheets/{id}/fill`, `POST:/log-sheets/{id}/complete`
- **Also has:** log-sheet list/detail, claim, release, my inbox, mobile API (bootstrap, inbox, per-sheet bundle, batch sync, claim/release, NFC).
- **Does not have:** generate, custom create, assign, reassign, extend, takeover, reports, templates, master data, dashboard.
- **Operational scope:** log sheets in units where the user is assigned as **operator** — that unit only, **never its sub-units**. (If the same user also has a supervisor link, that link brings its own branch; see the note below.)
- **Typical use:** experienced operator who may complete inspections in the **web UI** as well as the mobile app.

### `OPERATOR` — اپراتور

- **Web panel permissions:**
  - ✅ Log sheets: list, detail, claim, release
  - ✅ My inbox
  - ❌ Web fill/complete (`/log-sheets/{id}/fill`) — mobile completion only
  - ❌ Supervisor actions (generate, assign, reassign, extend, takeover)
  - ❌ Templates, master data, reports, dashboard, admin pages
- **Mobile API:**
  - ✅ `GET /api/bootstrap`, `GET /api/log-sheets/inbox`, `GET /api/log-sheets/{id}/bundle`, `POST /api/log-sheets/batch`
  - ✅ Claim, release
  - ✅ NFC asset lookup
  - ❌ Assign / reassign (supervisor-only)
- **Operational scope:** units where the user is assigned as **operator** — that unit only, **not** its sub-units.
- **Typical use:** field operator performing NFC-based round inspections on a phone/tablet.

### Permission categories at a glance

| Category | Examples | Default roles with access |
|---|---|---|
| `general` | Dashboard `GET:/` | `ADMIN`, `HIGH_USER` |
| `admin` | Users, roles, settings, audit logs, **batch Excel import** | `ADMIN` (+ batch import for `HIGH_USER`) |
| `organization` | Operational units, staff import | `ADMIN`, `HIGH_USER` |
| `master-data` | Locations → assets, log-sheet templates | `ADMIN`, `HIGH_USER` (+ template **view/create** for `SUPERVISOR`) |
| `operational` | Log sheets, my inbox | Role-specific (see above) |
| `reports` | `GET:/reports` | `ADMIN`, `HIGH_USER`, `SUPERVISOR` |
| `api` | `GET /api/bootstrap`, log-sheet inbox/bundle/batch, NFC | All field roles; exact endpoints per role |

### Extra service-layer rules (beyond endpoint permissions)

| Area | Rule |
|---|---|
| Log-sheet list/detail | `OPERATOR` / `SENIOR_OPERATOR` / `SUPERVISOR`: filtered to accessible units; `ADMIN` / `HIGH_USER`: global |
| Log-sheet assign / reassign / takeover / extend | Caller must be supervisor of the sheet's unit (or `ADMIN`) |
| Log-sheet template list | `ADMIN`: all units; `HIGH_USER` / `SUPERVISOR`: supervised units only |
| Log-sheet template create/edit/delete | `ADMIN` / `HIGH_USER` only, within supervised units for `HIGH_USER`. A `SUPERVISOR` is rejected by `LogSheetTemplateService.assertCanManageUnit` even if granted the endpoint permission. |
| Log-sheet template list | Every unit the user belongs to (a user may belong to several) — `visibleUnitIds()` |
| Custom log sheet create | `POST:/log-sheets/custom` — unit-scoped callers only for units they **supervise**; every selected asset must be **active** and inside that unit’s hierarchy; assets may span **multiple** asset classes |
| Void / unvoid submitted sheet | Admin or supervisor of the sheet's unit (`VOIDED` ↔ `SUBMITTED`); readings drop out of / return to parameter reports |
| Reopen submitted sheet | Admin or supervisor of the sheet's unit — new future deadline; returns to editable open status |
| Web completion | `SENIOR_OPERATOR`, `SUPERVISOR`, `HIGH_USER`, `ADMIN` (not plain `OPERATOR`) |

The canonical permission matrix is defined in Flyway (`permissions` + `role_permissions` inserts — baseline in `V1__initial_schema.sql`, plus any later `V*__*.sql` permission seeds). Custom roles can be composed in the **Roles** page by toggling individual endpoint permissions.

---

## Active Directory (LDAP) authentication

Users must **exist in the application database** before they can log in. Active Directory is used only to verify the password at login; roles, permissions, and unit assignments are always resolved from PostgreSQL.

### Per-user `auth_type`

| Value | Login behaviour |
|---|---|
| `LOCAL` | BCrypt password hash stored in `users.password_hash` |
| `ACTIVE_DIRECTORY` | LDAP simple bind as `username@domain` with the password entered at login |
| `HYBRID` | Try local BCrypt first; if that fails, try AD bind |

### Username format

- The user signs in with the **short username only** (e.g. `a.saljooghi`).
- The database stores the same short username in `users.username`.
- The application appends `@domain` only for the LDAP bind (e.g. `a.saljooghi@site.hnp`).
- No separate LDAP service account is required — the user's own credentials are used for bind.

### `url` vs `domain` (two different settings)

| Property | Purpose | Example |
|---|---|---|
| `app.auth.ldap.url` | **Where** to connect (LDAP server host, protocol, port) | `ldaps://dc.site.hnp:636` |
| `app.auth.ldap.domain` | **UPN suffix** appended to username for bind | `site.hnp` → bind as `user@site.hnp` |

These are independent: `url` is the server address (like `LDAP://dc.site.hnp:636` in PowerShell); `domain` is the account suffix (like `a.saljooghi@site.hnp` in PowerShell). If bind works in PowerShell with `@site.hnp` but fails in the app, check that `app.auth.ldap.domain` matches the UPN suffix, not a different DNS name such as `site.local`.

### Self-signed LDAPS certificates

When the Domain Controller uses a **self-signed** TLS certificate:

- Set `app.auth.ldap.trust-self-signed=true`.
- You do **not** need to import the certificate with Java `keytool` — the application skips TLS certificate validation for LDAP connections.
- Still required: correct `url`, correct `domain`, `app.auth.ldap.enabled=true`, and the user must exist in DB with `auth_type` `ACTIVE_DIRECTORY` or `HYBRID`.

For production on a trusted internal network this is acceptable. A more secure alternative is to import the AD CA into the JVM truststore and leave `trust-self-signed=false`.

### Example configuration

```properties
app.auth.ldap.enabled=true
app.auth.ldap.url=ldaps://dc.site.hnp:636
app.auth.ldap.domain=site.hnp
app.auth.ldap.trust-self-signed=true
app.auth.ldap.timeout-ms=5000
```

### Verify AD bind from Windows (PowerShell)

```powershell
$ldap = New-Object System.DirectoryServices.DirectoryEntry(
  "LDAP://dc.site.hnp:636",
  "a.saljooghi@site.hnp",
  "YourPassword",
  [System.DirectoryServices.AuthenticationTypes]::SecureSocketsLayer
)
$ldap.RefreshCache()
```

If this succeeds but the app fails, compare `url` and `domain` with the values above. Enable debug logging temporarily:

```properties
logging.level.com.hnp.backendofflinefirst.security.LdapAuthenticationService=DEBUG
```

Failed binds are also logged at WARN with the principal and server URL.

---

## Log-Sheet Lifecycle

A log sheet is a unit of work generated from a template — manually or on schedule — **or created as a custom (template-less) sheet with a hand-picked asset set**, and progresses through the following states (`LogSheetStatus`):

```
PENDING  ──►  ASSIGNED  ──►  IN_PROGRESS  ──►  SUBMITTED  (terminal)
   │              │               │
   └──────────────┴───────────────┴────────► EXPIRED   (terminal, if past due)
                                              CANCELLED (terminal, manual cancel)
```

- **PENDING**: generated, sitting in the unit pool, no assignee.
- **ASSIGNED**: a supervisor assigned it to an operator (in their inbox), not yet started.
- **IN_PROGRESS**: an operator claimed/started it.
- **SUBMITTED**: completed; included in parameter reports.
- **VOIDED**: soft-invalidated after submit (data kept); **excluded** from parameter reports; restorable only to `SUBMITTED`.
- **EXPIRED / CANCELLED**: other terminal states (`CANCELLED` reserved; not used for post-submit void).

### Void / restore / reopen (web)

| Action | From → To | Who | Endpoint |
|---|---|---|---|
| Void | `SUBMITTED` → `VOIDED` | System admin or supervisor of the sheet's unit | `POST:/log-sheets/{id}/void` |
| Unvoid | `VOIDED` → `SUBMITTED` | same | `POST:/log-sheets/{id}/unvoid` |
| Reopen | `SUBMITTED` → `IN_PROGRESS`/`PENDING` + new `dueAt` (future/2-year window enforced) | same | `POST:/log-sheets/{id}/reopen` (web bookmark alias: `POST /log-sheets/{id}/admin-reopen`, same authority) |

Void preserves entry `formData` and completion timestamps. Reopen clears completion timestamps so the sheet can be edited again (voided sheets must be unvoided first). **PWA:** no change required for void/notes; inbox never lists terminal sheets; reports already filter `SUBMITTED`.

### Sheet notes (web only)

Optional `log_sheets.notes` (max 4000 chars) editable on the web fill form (draft/complete). Shown on the detail page. Not part of the mobile bundle / PWA UI.

### Custom (template-less) log sheets

One-off rounds for a **selected subset of assets** in an operational unit, without creating/using a `log_sheet_templates` row.

| Aspect | Behaviour |
|---|---|
| Service | `CustomLogSheetService.createCustom` |
| Web UI | Log sheets list (`/log-sheets`) → green **«لاگ‌شیت سفارشی»** modal (`POST:/log-sheets/custom`) |
| Unit picker | Typeahead search `GET:/log-sheets/options/units?q=&limit=` (not a full unit dump); scoped the same as the hierarchy rule below — supervisors get their supervised branch, ADMIN/HIGH_USER get every unit |
| Asset picker | Typeahead search `GET:/log-sheets/options/assets?unitId=&q=&limit=` (debounced, capped; not a full unit dump) |
| Permissions | `POST:/log-sheets/custom`, `GET:/log-sheets/options/units`, `GET:/log-sheets/options/assets` — seeded for `ADMIN`, `HIGH_USER`, `SUPERVISOR` (all three seeded in `V1__initial_schema.sql`) |
| Unit scope | Unit-scoped supervisors may only create for units they supervise; assets must be **active** and visible in that unit |
| Template | `template_id = null`; display name stored in `template_name`; `scope_summary` usually null |
| Classes | Selected assets **may span multiple asset classes**; `field_definitions_snapshot` captures fields for **all** those classes |
| Due date | UI marks **مهلت تکمیل** as required; service still rejects a due date outside the future/2-year window when one is supplied — see [User-submitted date validation](#user-submitted-date-validation) |
| Lifecycle | Created as `PENDING` + `origin = MANUAL`, then same claim / assign / complete / expire flow as template-generated sheets |
| Mobile / PWA | No client change required — `GET /api/log-sheets/{id}/bundle` already returns multi-class entries + field definitions; null `templateId` is fine |

Unlike template generation, there is **no hierarchy scope walk** and **no class filter from a template**: the asset set is exactly what the supervisor selected.

### Replacing an asset on a sub-function

A sub-function is a **slot in the plant**, not a piece of equipment. When a pump breaks it is
deactivated and its replacement is attached to the very same sub-function, so the slot keeps its
history and operators keep scanning the same tag.

| Rule | Where |
|---|---|
| At most **one ACTIVE** asset per sub-function | `ux_asset_entries_active_sub_function` (partial unique index, `WHERE active`) + `MasterDataUniquenessValidator.validateAssetSubFunction(id, subFunctionId, active)` |
| **Any number of INACTIVE** assets may share one sub-function | the index is partial, and the validator returns early for an inactive candidate |
| Deactivating **releases** an NFC tag inherited from the sub-function | `AssetEntryService.applyNfcInheritance` |
| A tag the asset owns itself is **kept** on deactivation | same method — it is physically on that equipment |
| Re-activating a retired asset is **rejected** while a successor is active | same validator, now with `active = true` |

The NFC release matters because an asset created without an explicit tag inherits the
sub-function's `tag` (fallback: its `code`), and `nfc_tag_id` is globally unique. If the retired
asset kept that value, its replacement — which inherits the identical value — would collide on
`ux_asset_entries_nfc_tag_id_lower` and could not be created at all. So on deactivation the tag is
cleared **only when it equals the sub-function's tag or code**; anything else is a tag belonging to
that specific piece of equipment and stays with it.

Excel import follows the same rule: only active rows compete for a sub-function, so one sheet may
carry several retired assets that all sat on the same slot over time
(`validateAssetSubFunctionForImport(..., active, ...)`).

### Why an action was taken (optional comments)

The action history has always recorded *what* happened and *who* did it. The five supervisor-driven
lifecycle actions can now also record *why*, because "cancelled" alone doesn't tell a later reader
whether the unit was down for maintenance or someone mis-clicked:

| Action | Endpoint | Modal |
|---|---|---|
| تمدید مهلت / باز کردن مجدد (منقضی یا لغو‌شده) | `POST /log-sheets/{id}/extend` | `#extendModal` (new deadline **required** + comment optional) |
| لغو کار | `POST /log-sheets/{id}/cancel` | `#cancelModal` |
| ابطال | `POST /log-sheets/{id}/void` | `#voidModal` |
| لغو ابطال | `POST /log-sheets/{id}/unvoid` | `#unvoidModal` |
| باز کردن مجدد (تکمیل‌شده) | `POST /log-sheets/{id}/reopen` | `#reopenModal` (new deadline **required** + comment optional) |

The remaining actions (GENERATE, CLAIM, RELEASE, ASSIGN, REASSIGN, TAKEOVER, COMPLETE, SUBMIT,
EXPIRE, SUPERSEDE) take no comment — they are either automatic or already fully described by their
from/to fields.

**The comment is always optional.** Blank input is normalized to `null` and the action completes
exactly as before — nothing about the lifecycle depends on it. An over-long comment (> 1000 chars)
is **rejected**, not truncated, so a half-sentence can never masquerade as a complete reason; the
textarea carries a matching `maxlength`, so the server check is a backstop rather than the primary
gate. Validation runs *before* the sheet is loaded or permissions are checked, so a bad comment
cannot leave a partially-applied action behind.

Storage is one nullable `log_sheet_action_log.comment` column (V4) and the field is
**action-agnostic** — `LogSheetActionLogger.record(...)` has a 9-arg overload taking the comment and
an 8-arg one that passes `null`, so the ~14 comment-less action call sites are untouched and wiring a
further action needs no migration. Comments render in the history timeline under the action they
belong to.

All five moved from a browser `confirm()` (and, for extend/reopen, a cramped inline date field) to
Bootstrap modals that state the consequence, offer the comment box with a live character counter, and
keep the original button colour/icon.

**Tests:** the comment cases in `LogSheetAssignmentServiceTest` (recorded for each of the five,
trimmed, blank → null, over-limit rejected with no side effect, exactly-at-limit accepted) and
`LogSheetVoidAndNotesIntegrationTest` (persisted through real PostgreSQL, including a
void → unvoid → reopen chain where each row keeps its own explanation; comment-less actions still
write `null`).

### NFC tag id vs NFC serial

An asset carries **two** distinct NFC values. They look similar and are stored side by side, so the
difference is worth being explicit about — conflating them breaks equipment replacement.

| | `nfc_tag_id` | `nfc_serial` |
|---|---|---|
| What it is | the **logical** tag identifier used for lookup | the **hardware** serial/UID burned into the physical chip, e.g. `00:aa:34:9f:12:cd` |
| Belongs to | the *position* (sub-function) the asset occupies | the *piece of plastic* stuck on that equipment |
| Inherited from the sub-function when blank | **Yes** — sub-function `tag`, falling back to its `code` | **Never** — a blank serial stays blank |
| Released when the asset is deactivated | **Yes**, if it was inherited (so the successor can take it) | **No** — the retired asset keeps the chip it was fitted with |
| Required | optional (auto-filled by inheritance) | optional, and stays empty if not supplied |
| Unique | yes, case-insensitive (`ux_asset_entries_nfc_tag_id_lower`) | yes when supplied, case-insensitive (`ux_asset_entries_nfc_serial_lower`); NULLs are distinct in Postgres, so any number of assets may have none |
| Set from | form, Excel import, or inheritance | form or Excel import only |

Both are snapshotted onto `log_sheet_entries` at generation time and both travel to the PWA in the
log-sheet bundle (`LogSheetEntryDto.nfcSerial`) — an offline device has no other way to learn the
chip UID. The serial is **server-authoritative**: the mobile submit merge copies known fields one by
one and never reads `nfcSerial` off the request, so a client echoing a different value back cannot
rewrite the stored snapshot. On the PWA it is stored on `AssetEntry` and `LogSheetEntryData` and
**indexed** since Dexie **v11** (`assetEntries: 'id, nfcTagId, nfcSerial, classId, subFunctionId'`),
so a future "scan a chip, resolve its asset offline" lookup needs no further schema change. Nothing
reads it in the UI yet — it is carried, stored, and indexed ahead of that use.

**Tests:** `AssetEntryServiceTest` (no inheritance, trimming, duplicate rejection),
`MasterDataUniquenessValidatorTest` (web + import uniqueness, separate namespace from the tag),
`ExcelImportFormatIntegrationTest` (column position, in-file and DB duplicates, blank stays blank),
`CustomLogSheetIntegrationTest` (entry snapshot), `MobileBundleApiIntegrationTest` (reaches the PWA;
a client cannot overwrite it), and `mergeLogSheetBundle.test.ts` (server-authoritative on the PWA).

### Asset selection modes (dynamic vs frozen)

A `log_sheet_templates` row decides where its assets come from via `asset_selection_mode`. Both modes share
everything else — scheduling, completion window, unit ownership, lifecycle.

| | `SCOPE` (default, «پویا») | `EXPLICIT` («ثابت») |
|---|---|---|
| Asset set | **Re-resolved on every generation**: hierarchy scope walk ∩ asset class | **Frozen** rows in `log_sheet_template_assets` |
| Required fields | `scope_type`, `scope_id`, `class_id` | none of them (all stored `NULL`) |
| Asset added to the scope later | **Joins** the next generated sheet automatically | **Never** joins — the list only changes when the template is edited |
| Asset deactivated (`active = false`) | Excluded from the next generation | Excluded from the next generation; the membership row **stays**, so re-activating brings it back without re-editing |
| Asset classes | Exactly one (`class_id`) | **May span several** — snapshot covers every class present |
| `log_sheets.scope_summary` | `"<scopeType>:<scopeId>"` | `null` (a scope string would misrepresent a hand-picked set) |
| Scheduling | Supported | Supported — this is the point: a **scheduled** custom round with a stable asset list |

`EXPLICIT` is the scheduled counterpart of a [custom log sheet](#custom-template-less-log-sheets): the same
hand-picked, possibly multi-class asset set, but recurring. Resolution lives in
`LogSheetGenerationService.resolveExplicitAssets` — it reads the saved ids, filters to active assets, and
**preserves the order the author chose**.

- **Web UI:** the template form's «روش انتخاب دارایی‌ها» selector. Choosing «انتخاب دستی دارایی‌ها (ثابت)» hides
  *and disables* the scope/class fields (so they are omitted from the POST entirely) and shows a searchable
  multi-picker backed by `GET:/log-sheet-templates/options/assets`.
- **Access control:** the asset picker honours the same `restrict_scope_to_unit` guard as the scope pickers. A
  unit-scoped supervisor is confined to their own unit's assets — enforced server-side in
  `LogSheetTemplateService.validateExplicitAssets`, not just in the UI — for the same privilege-escalation
  reason as the scope restriction. Only `ADMIN` / `HIGH_USER` may freeze assets from outside the owning unit.
- **Validation:** at least one asset, every id must resolve to an **active** asset the author is allowed to
  see; duplicates are dropped, order preserved. Editing replaces the whole list.
- **Mobile / PWA:** no client change required — see [Custom (template-less) log sheets](#custom-template-less-log-sheets); the bundle
  already carries multi-class entries, and a null `scope_summary` is the same case custom sheets have always produced.

### Assignment Type (`AssignmentType`)
- `SELF_CLAIMED` — an operator picked it up themselves; only that operator may return it to the pool.
- `SUPERVISOR_ASSIGNED` — a supervisor pushed it to an operator's inbox; only a supervisor of that unit may release or reassign it.

### Scheduler (`LogSheetScheduler`)
Two independent periodic jobs (intervals configurable via `application.properties`):

1. **`generateDueSheets`** — finds active `SCHEDULED` templates whose `next_run_at` is due and generates log sheets. Catch-up after an outage is controlled by `app.scheduler.log-sheet-max-backfill` (**per template**) — see below.
2. **`expireOverdueSheets`** — marks open log sheets (`PENDING`/`ASSIGNED`/`IN_PROGRESS`) that are past their `due_at` as `EXPIRED`; if a saved draft exists, it finalizes the draft instead of expiring it.

### Scheduler catch-up / max backfill

Config: `app.scheduler.log-sheet-max-backfill` / env `APP_SCHEDULER_LOG_SHEET_MAX_BACKFILL`.

**Effective default in this repo:** `0` (set in `application.properties`). That means: if several scheduled occurrences were missed, **do not** generate a backlog — advance `next_run_at` and create at most the **current** due sheet when the scheduler runs. Set to `N > 0` to generate up to `N` oldest missed occurrences per template per tick.

`LogSheetScheduler` also declares `@Value("${app.scheduler.log-sheet-max-backfill:500}")`; that `500` is only a fallback when the property is **missing** from the environment. Because `application.properties` defines the key, deployments use **`0`** unless you override the env var.

Applied **per template** each time that template is due — not as a global limit across all templates.

How the scheduler decides whether more than one occurrence is “due”: it walks recurrence boundaries from `next_run_at`. If the **next** boundary after the current one is also already `<= now`, there is a multi-item backlog. It does **not** use a wall-clock grace like “only a few minutes late”.

| Value | Behavior |
|---|---|
| **`0`** | **One due → create it. Multiple due → create none.** See examples below. |
| **`N > 0`** | Create up to **N** missed occurrences **oldest-first**, then skip any remainder and jump `next_run_at` to the next future boundary. |
| Property absent (code fallback `500`) | Same as `N > 0` with a large cap — **not** the default when `application.properties` is used (`0` there). |

#### `0` — single overdue occurrence (still create)

Template every hour. `next_run_at = 20:26`. App off from `20:23`, back on at `20:29`.

- Due list: only `20:26` (next slot `21:26` is still in the future).
- Result: sheet for **`20:26` is created**; `next_run_at` becomes **`21:26`**.

#### `0` — multiple overdue occurrences (create none)

Template every hour starting `07:30`. `08:30` already ran (`next_run_at = 09:30`). App off around `09:00`, back on at `11:00`.

- Due list: `09:30` and `10:30` (next future slot `11:30`).
- Result: **nothing is created**; `next_run_at` jumps to **`11:30`**.

Another long-outage case: `next_run_at` still yesterday `07:55`, app back today `10:53` (2 minutes before today’s `10:55` slot).

- Many past slots are due → **create nothing**.
- Park `next_run_at` on **today `10:55`**.
- When the clock reaches `10:55`, that single live tick is created; then `next_run_at = 11:55`.

So `0` does **not** mean “never generate again”. It means: skip a **multi-item** backlog; keep generating normal single due ticks (including ones only minutes away once their time arrives).

> **Note:** Sheets are not created early. A future slot is only *scheduled* via `next_run_at`. Actual creation happens on the next scheduler pass at/after that time (`app.scheduler.log-sheet-gen-ms`, default 60s).

#### `N > 0` — oldest-first cap (example `3`)

Template every hour. Ten occurrences were missed (`next_run_at` points at the oldest). `APP_SCHEDULER_LOG_SHEET_MAX_BACKFILL=3`.

- Creates the **3 oldest** missed sheets (then typically `EXPIRED` if their completion window has already passed).
- Does **not** create the remaining 7, and does **not** prefer “the latest” ones.
- Skips the rest and sets `next_run_at` to the first boundary still in the future.

Example: missed `01:00` … `10:00`, now `10:30`, `N=3` → creates `01:00`, `02:00`, `03:00`; jumps cursor to `11:00`.

### Template schedule cursor (`next_run_at`)

`log_sheet_templates.next_run_at` is the **scheduler cursor**: the next occurrence time at which a sheet should be generated from that template. It is **not** a display-only field and must not be treated as free-form metadata.

| When | What happens to `next_run_at` |
|---|---|
| **Create** a `SCHEDULED` + `schedule_active` template | Seeded by `LogSheetTemplateService.computeInitialNextRun`: `schedule_start_at` if still in the future, otherwise the next recurrence boundary at/after now |
| **Scheduler** finishes a due run (`LogSheetGenerationService.runScheduled`) | Advanced to the next recurrence boundary after the generated occurrence(s); `last_run_at` is also updated |
| **Edit** only non-schedule fields (name, description, scope, class, unit, active flag, completion window) | **Preserved** — rename / scope changes must not move the cursor or skip missed runs |
| **Edit** schedule definition (`generation_mode`, `schedule_active`, `recurrence_unit`, `recurrence_every`, `schedule_start_at`) | **Re-seeded** from `schedule_start_at` / now via `computeInitialNextRun` |
| Switch to `MANUAL`, or turn `schedule_active` off / leave recurrence incomplete | Cleared to `null` (no live cursor) |

> **Why this matters:** previously every template update recomputed `next_run_at`, so even renaming a template could jump the cursor forward and skip backfill. Current behavior keeps the cursor unless the schedule definition itself changes (`LogSheetTemplateService.update`).

### User-submitted date validation

Every user-submitted deadline/schedule date is validated server-side via `DateUtils.requireFutureWithinYears(epochMs, now, label)`: it must be **strictly after the current server time** and **at most 2 years (`DateUtils.MAX_FUTURE_YEARS`) ahead** of it. `null` is a no-op — whether the field is required at all is each caller's own concern. This closes off both a "past/garbage date silently accepted" gap and an unbounded-future value that would otherwise sit in the DB as epoch millis (e.g. a stray negative or absurdly large number) indefinitely.

Enforced at every point a human supplies one of these dates:

| Call site | Field | Service |
|---|---|---|
| Create log sheet template | Schedule start (`scheduleStart`) | `LogSheetTemplateService.create` |
| Edit log sheet template | Schedule start — **only re-validated when the submitted value actually changes**; an existing template's original start naturally drifts into the past as its recurring schedule runs, so re-checking an untouched value would incorrectly block an unrelated edit (e.g. a rename) | `LogSheetTemplateService.update` |
| Create custom log sheet | Due date (`dueAt`) | `CustomLogSheetService.createCustom` |
| Extend a log sheet | New due date (`dueAt`) | `LogSheetAssignmentService.extend` |
| Reopen a submitted log sheet with a new deadline | New due date (`dueAt`) | `LogSheetAssignmentService.reopenSubmittedWithExtend` |

Not covered (deliberately): `LogSheetGenerationService` computing `due_at` for a system-generated occurrence (not user input), and `ReportWebController`'s report date-range filters (historical query bounds, not a future deadline). The mobile REST API (`LogSheetController`) does not expose template creation, custom-sheet creation, extend, or reopen — those are web-panel-only operations, so no additional call site exists there.

English exception messages (`"<label> must be in the future."` / `"<label> must be within 2 years from now."`) are translated to Persian by `ErrorTranslator` and surfaced as the flash `errorMessage` on the originating form.

### Device vs. Server Time Separation
- `action_at` (real/device time when the offline action occurred) is separated from `recorded_at` (server persist time) so the true event order is preserved even offline.
- `client_action_id` is the idempotency key ensuring replayed offline actions during sync don't duplicate rows in `log_sheet_action_log`.
- **`log_sheet_void_submissions`**: if a late offline submission arrives after someone else (e.g., a supervisor via takeover) already completed the sheet, it's stored as void — it never overwrites the authoritative completed record.

---

## Project Structure

```
src/main/java/com/hnp/backendofflinefirst/
├── aspect/          # AOP: method logging + automatic repository auditing
├── audit/           # Entity-level audit infrastructure
├── config/          # Spring configuration (Security, Async, CORS, Jackson, admin bootstrap)
├── controller/      # Mobile REST API (/api/**)
├── domain/          # Domain enums (statuses, action types, etc.)
├── dto/             # Request/response models
├── entity/          # JPA entities
├── logging/         # Business event logging, sanitizer, MDC filter
├── repository/      # Spring Data JPA repositories
├── security/        # UserDetailsService, auth handlers, permission codes
├── service/         # Business logic (+ importjob/ for async batch Excel import)
├── ui/              # Response/view helpers (error localization, etc.)
├── util/            # Utilities (Jalali dates, Excel, reference labels, etc.)
└── web/             # Thymeleaf admin panel controllers

src/main/resources/
├── application.properties
├── logback-spring.xml
├── db/migration/    # Flyway numbered scripts (V1 baseline + V2/V3/V4… as needed)
├── static/          # Panel CSS/JS/fonts
└── templates/       # Thymeleaf views (users, roles, assets, log sheets, etc.)

src/test/java/com/hnp/backendofflinefirst/
├── controller/, domain/, security/, service/, ui/, util/
├── integration/     # ApiIntegrationTest, AssetHierarchyCascadeIntegrationTest, MobileBundleApiIntegrationTest
└── support/         # Testcontainers base class + custom security context support
```

---

## Prerequisites

- **JDK 25**
- **PostgreSQL** (compatible with the `org.postgresql:postgresql` driver)
- **Maven** (or use the bundled `mvnw`/`mvnw.cmd` wrapper)
- **Docker** (optional, required for Testcontainers-based tests)

---

## Getting Started

### 1. Create the database

```sql
CREATE DATABASE offline_first_db;
```

Default connection settings (overridable via environment variables — see table below):

```
jdbc:postgresql://localhost:5432/offline_first_db
username: postgres
password: postgres
```

> For personal development, create an `application-local.properties` file (ignored by `.gitignore`) to override values, then run with the `local` profile.

### 2. Run migrations and start the app

Flyway automatically applies all scripts in `src/main/resources/db/migration/` on startup (`spring.flyway.enabled=true`). Do not edit already-applied migrations in shared environments; add a new `V{n}__….sql` instead (see [Flyway notes](#flyway-notes)).

Using the Maven Wrapper (Windows):

```bash
mvnw.cmd spring-boot:run
```

Or Linux/macOS:

```bash
./mvnw spring-boot:run
```

The server starts on port **8081** by default:

```
http://localhost:8081
```

### 3. Log in to the panel

Open `http://localhost:8081/login` in your browser and sign in with the default user (see [Default User](#default-user)).

---

## Configuration (application.properties)

All values below can be set in `application.properties` or overridden with **environment variables**. If an environment variable is not set, the default in the third column applies.

| Property | Environment variable | Default |
|---|---|---|
| `spring.datasource.url` | `SPRING_DATASOURCE_URL` | `jdbc:postgresql://localhost:5432/offline_first_db` |
| `spring.datasource.username` | `SPRING_DATASOURCE_USERNAME` | `postgres` |
| `spring.datasource.password` | `SPRING_DATASOURCE_PASSWORD` | `postgres` |
| `server.port` | `SERVER_PORT` | `8081` |
| `app.cors.allowed-origins` | `APP_CORS_ALLOWED_ORIGINS` | `*` (comma-separated list to restrict, e.g. `https://pwa.example.com,http://localhost:5173`) |
| `app.auth.jwt.secret` | `APP_AUTH_JWT_SECRET` | `dev-only-change-me-use-long-random-secret-key!!` |
| `app.auth.login-attempt.max-attempts` | `APP_AUTH_LOGIN_ATTEMPT_MAX_ATTEMPTS` | `5` |
| `app.auth.login-attempt.lock-minutes` | `APP_AUTH_LOGIN_ATTEMPT_LOCK_MINUTES` | `15` |
| `app.auth.ldap.enabled` | `APP_AUTH_LDAP_ENABLED` | `true` |
| `app.auth.ldap.url` | `APP_AUTH_LDAP_URL` | `ldaps://dc.site.hnp:636` |
| `app.auth.ldap.domain` | `APP_AUTH_LDAP_DOMAIN` | `site.hnp` |
| `app.auth.ldap.timeout-ms` | `APP_AUTH_LDAP_TIMEOUT_MS` | `5000` |
| `app.auth.ldap.trust-self-signed` | `APP_AUTH_LDAP_TRUST_SELF_SIGNED` | `true` |
| `app.scheduler.log-sheet-gen-ms` | `APP_SCHEDULER_LOG_SHEET_GEN_MS` | `60000` |
| `app.scheduler.log-sheet-expiry-ms` | `APP_SCHEDULER_LOG_SHEET_EXPIRY_MS` | `60000` |
| `app.scheduler.log-sheet-max-backfill` | `APP_SCHEDULER_LOG_SHEET_MAX_BACKFILL` | **`0`** in `application.properties` (per template; `0` = skip multi-occurrence backlog, still create single due tick — see [Scheduler catch-up](#scheduler-catch-up--max-backfill)). `@Value` fallback in code is `500` if the property is absent. |
| `app.log.path` | `APP_LOG_PATH` | `ProdLog` |
| `app.audit.enabled` | `APP_AUDIT_ENABLED` | `true` |
| `app.audit.async.core-pool-size` | `APP_AUDIT_ASYNC_CORE_POOL_SIZE` | `2` |
| `app.audit.async.max-pool-size` | `APP_AUDIT_ASYNC_MAX_POOL_SIZE` | `4` |
| `app.audit.retention.batch-size` | `APP_AUDIT_RETENTION_BATCH_SIZE` | `5000` |
| `app.sync.batch-max-items` | `APP_SYNC_BATCH_MAX_ITEMS` | `500` |
| `app.attachments.storage-dir` | `APP_ATTACHMENTS_STORAGE_DIR` | `./data/attachments` — root for captured photos/voice notes; **back it up with the database** (see [Attachments](#attachments-photo--audio-fields)) |
| `app.attachments.max-file-size-bytes` | `APP_ATTACHMENTS_MAX_FILE_SIZE_BYTES` | `10485760` (10 MB) |
| `app.import.storage-path` | `APP_IMPORT_STORAGE_PATH` | `./data/imports` |
| `app.import.max-stored-errors` | `APP_IMPORT_MAX_STORED_ERRORS` | `500` |
| `app.import.max-rows` | `APP_IMPORT_MAX_ROWS` | `10000` |
| `app.import.async.core-pool-size` | `APP_IMPORT_ASYNC_CORE_POOL_SIZE` | `1` |
| `app.import.async.max-pool-size` | `APP_IMPORT_ASYNC_MAX_POOL_SIZE` | `1` |
| `spring.servlet.multipart.max-file-size` | `SPRING_SERVLET_MULTIPART_MAX_FILE_SIZE` | `50MB` |
| `spring.servlet.multipart.max-request-size` | `SPRING_SERVLET_MULTIPART_MAX_REQUEST_SIZE` | `50MB` |

### Other fixed settings

| Key | Description | Default |
|---|---|---|
| `spring.jpa.hibernate.ddl-auto` | Schema sync mode (`validate` only; schema is built by Flyway) | `validate` |
| `spring.flyway.locations` | Migration scripts location | `classpath:db/migration` |

### Example: production via environment variables (Linux)

```bash
export SPRING_DATASOURCE_URL=jdbc:postgresql://db-host:5432/offline_first_db
export SPRING_DATASOURCE_USERNAME=app_user
export SPRING_DATASOURCE_PASSWORD=secret
export SERVER_PORT=8081
export APP_AUTH_JWT_SECRET=your-long-random-production-secret
export APP_AUTH_LDAP_ENABLED=true
export APP_AUTH_LDAP_URL=ldaps://dc.site.hnp:636
export APP_AUTH_LDAP_DOMAIN=site.hnp
export APP_AUTH_LDAP_TRUST_SELF_SIGNED=true
java -jar backend-offline-first-0.0.1-SNAPSHOT.jar
```

### Example: Windows (PowerShell, current session)

```powershell
$env:SPRING_DATASOURCE_URL = "jdbc:postgresql://localhost:5432/offline_first_db"
$env:APP_AUTH_LDAP_DOMAIN = "site.hnp"
$env:APP_AUTH_LDAP_TRUST_SELF_SIGNED = "true"
$env:APP_IMPORT_STORAGE_PATH = "C:\Users\Hadi\Desktop\Temp\appdata"
.\mvnw.cmd spring-boot:run
```

> Spring Boot also accepts relaxed env names (e.g. `SPRING_DATASOURCE_URL` maps to `spring.datasource.url` automatically if you omit the `${...}` placeholders and rely on external configuration only).

---

## Mobile API (Offline Sync)

All endpoints below require an authenticated session (Spring Security) and are protected via `@PreAuthorize`. Paths under `/api/**` are exempt from CSRF.

| Method | Path | Description |
|---|---|---|
| `POST` | `/api/auth/login` | Log in and receive the user's roles/permissions. Optional `deviceLabel` in the body names the device in the admin session list; the login **supersedes** any other active session of that user |
| `GET`  | `/api/health` | Service health check (no auth required) |
| `GET`  | `/api/bootstrap` | **Preferred** — lightweight session context (see [Mobile bootstrap](#mobile-bootstrap-not-a-full-catalog)) |
| `GET`  | `/api/log-sheets/inbox` | Fetch the inbox: assigned log sheets + the unit's available pool |
| `POST` | `/api/log-sheets/{id}/claim` | Claim a log sheet from the pool |
| `POST` | `/api/log-sheets/{id}/release` | Release a log sheet back to the pool |
| `POST` | `/api/log-sheets/batch` | Submit a batch of completed log sheets (offline sync) |
| `GET`  | `/api/log-sheets/{id}/bundle` | Full offline bundle for one log sheet (entries + scoped hierarchy context) |
| `GET`  | `/api/asset-entries/nfc/{nfcTagId}` | Look up an asset by its NFC tag |
| `POST` | `/api/attachments` | Upload one captured photo/voice note (see [Attachments](#attachments-photo--audio-fields)) |
| `GET`  | `/api/attachments/{id}` | Download an attachment’s bytes |
| `DELETE` | `/api/attachments/{id}` | Delete an attachment and its file |

### Mobile bootstrap (not a full catalog)

`GET /api/bootstrap` returns a **small** JSON payload (`BootstrapResponse`):

- `serverTime`, `userId`
- `operationalUnits`, `accessibleUnitIds`, `supervisorScopeUnitIds`, `primaryUnitId`

It does **not** download the plant hierarchy, asset registry, or field definitions. Those come **per log sheet** from `GET /api/log-sheets/{id}/bundle` (entries + scoped context). NFC lookup uses `GET /api/asset-entries/nfc/{nfcTagId}`.

> **Removed:** the deprecated `GET /api/master-data` endpoint, its `GET:/api/master-data` permission, and the unused
> `findByUpdatedAtGreaterThanEqual` repository methods (all leftovers of a delta master-data sync that never shipped)
> have been deleted. `/api/bootstrap` is the only session-context endpoint.

### Idempotency

- Log sheets carry a `localId` in the **batch DTO only** (echoed back in `LogSheetSubmitResult` so the client can correlate results). It is deliberately *not* persisted: `log_sheets` is server-owned and its rows are never created by a client, so the column and its unique constraint were dropped. Log-sheet submit idempotency comes from the sheet's own id + state machine, not from a client key.
- `log_sheet_action_log.client_action_id` serves the same purpose for lifecycle actions (claim/release/complete, etc.) performed offline.

### Batch size limit

`POST /api/log-sheets/batch` processes the whole array **synchronously in one DB transaction per request** (unlike batch Excel import, which is async and queued) — an unbounded array could tie up a DB connection/thread for an unreasonable time. It is capped at `app.sync.batch-max-items` (default **500**, env `APP_SYNC_BATCH_MAX_ITEMS`); an over-limit request is rejected outright (`400`, before any DB work) with a Persian message telling the client to split into smaller batches and sync sequentially. 500 comfortably covers even a heavy offline backlog for a single device (tens to low hundreds of sheets), while keeping one request's transaction bounded.

### API Documentation (OpenAPI / Swagger — admin only)

The `/api/**` endpoints above are also documented live via `springdoc-openapi`, generated automatically from the `@RestController` classes in `controller/` — any new endpoint you add shows up with no extra work (no manual registration, unlike permissions). It's enabled in **every environment, including production**, but the docs themselves are gated behind the `GET:/v3/api-docs/**` permission (seeded in `V1__initial_schema.sql`, `ADMIN` only) — same pattern as [Actuator](#operations-monitoring-actuator).

- Swagger UI: `http://localhost:8081/swagger-ui.html`
- Raw OpenAPI spec: `http://localhost:8081/v3/api-docs`

Both require an authenticated **web panel** session (log in at `/login`) with the `ADMIN` role — an anonymous or non-admin request redirects to `/login`, same as any other admin-only page. Only `/api/**` is scanned (`springdoc.paths-to-match`) — the Thymeleaf admin panel (`web/`) is server-rendered HTML, not a machine-consumed API, and is intentionally excluded. Use the **Authorize** button in Swagger UI with a token from `POST /api/auth/login` to try endpoints that require it.

---

## Attachments (photo & audio fields)

An asset class field can have data type **`image`** or **`audio`** (chosen in *Asset Classes →
Fields*). The operator then captures a photo or records a voice note against that field on the
tablet, and it syncs like any other answer. `video` exists throughout the backend
(`AttachmentKind.VIDEO`, MP4/WebM detection, `.mp4`/`.webm` extensions) but is deliberately not
offered in the field-type dropdown yet, so nothing can create a video field by accident.

### Where the bytes live, and why not in the database

`log_sheet_entries.form_data` stores **references, never the media**:

```json
{ "pump_photo": { "type": "attachment", "ids": ["a7f3…", "b2c1…"] } }
```

Base64 in `form_data` was rejected on purpose. It inflates by a third, and it would put binaries
inside a `jsonb` column that every log-sheet read, every mobile bundle and every database backup
then has to carry. At this project's own target load — a daily sheet of ~50 assets — photos come
to tens of GB a year: perfectly fine on a disk, ruinous inside the database.

The bytes go to the filesystem instead, under a configurable root:

```properties
app.attachments.storage-dir=${APP_ATTACHMENTS_STORAGE_DIR:./data/attachments}
app.attachments.max-file-size-bytes=${APP_ATTACHMENTS_MAX_FILE_SIZE_BYTES:10485760}
```

Files are date-sharded as `2026/08/07/<uuid>.<ext>` so no single directory ever accumulates a
year of uploads. **Back this directory up alongside the database** — the two are only meaningful
together: a row without its file cannot be shown, and a file without its row is unreachable.

The `attachments` table records `id` (a UUID minted by the client), `log_sheet_id`, `asset_id`,
`field_key`, `kind`, `mime_type`, `size_bytes`, `sha256`, `width`/`height`/`duration_ms`,
`storage_key`, `uploaded_at` and `created_by_user_id`. `storage_key` is the indirection that
would let object storage (S3/MinIO) replace the filesystem later without a schema change.

### Endpoints

| Method | Path | Notes |
|---|---|---|
| `POST` | `/api/attachments` | `multipart/form-data`: `id`, `logSheetId`, `assetId`, `fieldKey`, `file`, optional `width`/`height`/`durationMs` |
| `GET` | `/api/attachments/{id}` | Returns the bytes inline with the detected content type, `Cache-Control: max-age=30d, private` |
| `DELETE` | `/api/attachments/{id}` | Removes the row, then the file |

Each has its own permission, granted to `SUPERVISOR`, `OPERATOR` and `SENIOR_OPERATOR` (and to
`ADMIN`/`HIGH_USER` through the blanket grants). `GET /api/log-sheets/{id}/bundle` embeds
attachment **metadata** so a device that re-downloads a sheet knows what exists and can fetch
each file on demand — the bundle itself never carries bytes.

### What the server refuses, and why

- **Access is decided by the owning log sheet, not by knowing an id.** Every route resolves the
  sheet through the same unit-scope rule as the rest of the app, so a user outside the owning
  unit gets `403` even with a valid UUID. This includes the idempotent re-upload path, which
  returns an existing row before any other lookup.
- **The declared content type is ignored.** The real type is read from the file's magic bytes.
  A client can label an executable `image/webp`; only the leading bytes tell the truth, and
  serving it back later under the claimed type would be the exploit. Bytes matching nothing
  known are refused outright.
- **The field must actually accept that kind of media**, according to the sheet's own frozen
  `field_definitions_snapshot` — not the request, and not the live class schema. A photo cannot
  be attached to a numeric field, and audio cannot be attached to an image field.
- **Files over `app.attachments.max-file-size-bytes` are rejected**, as are empty ones.

### Idempotency and retries

The attachment id is minted by the client, which makes an upload safely repeatable: a tablet on
a weak link that never saw the response re-sends the same id and gets `200` with the existing
row rather than a second copy. The stored bytes are **not** rewritten on a retry — the first
upload won, and differing bytes under the same id mean a client bug, not a correction.

Attachments deliberately do **not** ride inside `POST /api/log-sheets/batch`. That submission
has to stay small and atomic; one photo inside it would mean every dropped connection retried
the whole shift's readings. Instead the sheet submits with ids only, and each file uploads
separately afterwards — so a dropped connection costs exactly one file. See the PWA's
`services/sync/attachmentSync.ts` for the client half.

### Voiding a sheet

Voiding is a **soft, reversible** status change — the readings are preserved and excluded from
parameter reports until someone un-voids the sheet. Attachments follow the same logic and are
left completely alone: files stay on disk, rows stay in the table, and both upload and download
keep working. Destroying the evidence on void would make the un-void meaningless, and access
control here is about the operational unit rather than the sheet's status.

That also covers the realistic race — a sheet syncs, a supervisor voids it, and only then does
the tablet get signal for the queued photo. That upload is accepted. Refusing it would strand
the file forever and leave the entry's `form_data` holding a reference that never resolves.

### Deleting

Deleting an attachment removes the row first and then the file. If the file delete fails, the
row is already gone and a sweep can reclaim the bytes later; the reverse order would leave a row
pointing at nothing, which every reader would then have to defend against. Deleting a log sheet
cascades to its attachment rows — the files it leaves behind are orphans that a future sweep job
can reclaim by comparing the directory against `storage_key`.

---

## Web Admin Panel

The `web/*WebController.java` controllers serve the following Thymeleaf pages (list routes use `GET:/{path}`; related export/options/import handlers often reuse the same authority — see [RBAC](#authentication--authorization-rbac)):

- Dashboard (`/`)
- Users, roles, settings (admin section)
- Operational units (with supervisor/operator Excel import/export)
- Master data: locations, plant systems, main/sub functions (each supports **nested parents** in the panel and Excel), asset classes and field definitions, asset entries
- Log-sheet templates (including a scoped asset preview; edit/delete for `ADMIN` / `HIGH_USER` only)
- Log sheets, web-based log-sheet completion (`/log-sheets/{id}/fill`) — `SENIOR_OPERATOR` and above
- **Custom log sheets** from the log-sheets list (supervisor+): pick unit + assets (multi-class OK); see [Custom (template-less) log sheets](#custom-template-less-log-sheets)
- My Inbox (`/my-inbox`) — for supervisors and operators
- Reports (`ADMIN`, `HIGH_USER`, `SUPERVISOR`) — log-sheet and asset-inventory summaries; **parameter history** (`/reports/asset-parameters`) reads **submitted log sheets**
- Audit logs (change history) — `ADMIN` only
- **Batch Excel import** (`/batch-import`) — `ADMIN` and `HIGH_USER` (see below)

Most master data list pages still support **synchronous Excel import** on the entity page (`GET .../import-template` and `POST .../import`), with import results (success/error counts) returned via `ImportResult`/`ImportError`. For large files, prefer the **batch import** page.

### Favicon / app icon

The panel's favicon is **the PWA's app icon**, so both halves of the product carry
the same mark.

| Item | Where |
|------|-------|
| Served file | `src/main/resources/static/favicon.png` (180×180) |
| Referenced from | `templates/fragments/layout.html` and `templates/login.html` (`<link rel="icon">`) |
| Public without login | yes — `/favicon.png` is in `WebSecurityConfig`'s `permitAll` list, otherwise the login page would be redirected while fetching it and show no icon |
| Source of truth | `public/icons/icon.svg` **in the PWA repo** — not here |

**To change it**, edit the SVG in the PWA repo and run its generator; that script
rasterises every PNG the PWA needs *and* writes this `favicon.png`:

```bash
npm run icons
```

Run it from the PWA repo (`../../FrontEnd/offline-first-pwa` by default; set
`BACKEND_STATIC_DIR` if this repo lives elsewhere), then rebuild the backend so
the new file is packaged into the jar:

```bash
./mvnw.cmd clean package
```

Do not hand-edit `favicon.png` — the next `npm run icons` overwrites it. See the
PWA README's **App Icon** section for the full picture, including why Android
needs a separately generated maskable variant.

---

## Reports

Seven report pages under `/reports/*`. This chapter is the reference for **what each number
means and exactly how it is calculated** — the formulas are not obvious from the screens, and
two of them (compliance, self-serve) have denominators that are easy to assume wrongly.

### Who can see them, and how much

| | |
|---|---|
| Permission | **`GET:/reports`** — a single authority covering all seven pages |
| Granted by default to | `ADMIN`, `HIGH_USER`, `SUPERVISOR` |
| Not granted to | `OPERATOR`, `SENIOR_OPERATOR` — they do not even see the sidebar section |
| Mutating endpoints | none; every report is read-only |

Once inside, **how much** a user sees is a separate rule from **whether** they get in:

| Viewer | Scope |
|--------|-------|
| `ADMIN` / `HIGH_USER` | Unrestricted — the whole plant (`visibleUnitIds()` returns `null`) |
| `SUPERVISOR` | Their supervised units **plus every descendant unit**, plus any unit they personally operate |

That is the same `AssetAccessService.visibleUnitIds()` used for log sheets, so a supervisor of
unit 1 sees units 2 and 3 in every report automatically. Because the permission is a database
row, an administrator can revoke it from `SUPERVISOR` on the roles page without a code change.

> **No per-report granularity yet.** Anyone with `GET:/reports` gets all seven. To restrict a
> single page (say, keep *Workforce* admin-only) you would add a dedicated permission row and
> a matching `@PreAuthorize`.

### The counting window

Every page has a "بازه (روز)" selector. Two different rules apply, deliberately:

| Report | Window applies to | Why |
|--------|-------------------|-----|
| Compliance, Workforce, Overview | `log_sheets.created_at` — when the sheet was **raised** | A sheet belongs to the period it was *owed* in. Window on completion instead and a backlog cleared today inflates today while hollowing out the month the work was actually due. |
| Exceptions, Data quality | `COALESCE(completed_at, submitted_at)` — when the reading was **taken** | These are about readings, and a reading's date is when it was recorded. |

The selector is clamped to 1–365 days server-side, so a hand-edited query string cannot turn a
report into a full-table scan.

---

### 1. داشبورد مدیریتی — `/reports/overview`

Six KPI cards plus a 12-month trend.

| Card | Formula |
|------|---------|
| نرخ تحقق به‌موقع | `onTime / (submitted + expired)` |
| قرائت در محدوده خطر | count of entries with `max_severity = 'DANGER'` (warning count shown beneath) |
| باز و گذشته از مهلت | open sheets (`PENDING`/`ASSIGNED`/`IN_PROGRESS`) whose `due_at < now`. **Not** windowed — it is a live figure |
| خرابی NFC باز | sum of unresolved `nfc_fault_reports` per asset |
| ابطال‌شده | `VOIDED` in window (expired shown beneath) |
| ثبت دستی | `PWA_MANUAL entries / all submitted entries` |

The trend table is 12 calendar months ending with the current one; each row's bar is
`onTime / submitted` for that month alone.

### 2. نرخ تحقق و تأخیر — `/reports/compliance`

Grouped by operational unit and, separately, by template.

| Column | Meaning |
|--------|---------|
| کل | every sheet raised in the window, whatever its state |
| ارسال‌شده | `status = SUBMITTED` |
| به‌موقع | submitted **and** `due_at IS NOT NULL` **and** completion `<= due_at` |
| با تأخیر | submitted **and** had a deadline **and** completion `> due_at` |
| منقضی / لغو / ابطال | `EXPIRED` / `CANCELLED` / `VOIDED` |
| باز | still `PENDING` / `ASSIGNED` / `IN_PROGRESS` |
| **نرخ تحقق** | **`onTime / (submitted + expired + cancelled)`** |

Three things worth knowing about that rate:

- **Open work is excluded from the denominator.** It has not had its chance yet; counting it
  would punish a unit for work that is not due.
- **Cancelled work *is* in the denominator.** Cancelling is a failure to perform. Leave it out
  and a unit could cancel everything it could not finish and score 100%.
- **A sheet with no `due_at` is neither on-time nor late.** It cannot be judged against a
  deadline it never had, so it is absent from both columns (though still counted in کل).

**میانه تأخیر / صدک ۹۰** are computed over the *overshoot only* — sheets finished early are
dropped rather than entered as negative lateness, which would let punctual work cancel out
overdue work and hide the problem. Percentile = nearest-rank on the sorted samples.

### 3. تخطی از بازه مجاز — `/reports/exceptions`

Every submitted reading that breached its warning or danger range, danger first, newest first.

Reads the stored `log_sheet_entries.max_severity` / `breached_fields`, which are computed **at
write time** against the ranges captured in that sheet's `field_definitions_snapshot` — the
thresholds in force when the reading was taken, so re-tuning a range never rewrites history.
One row per offending field, so an entry breaching two parameters appears twice.

`?dangerOnly=true` restricts to DANGER. Page cap: 1000 rows (display only — the query itself is
indexed, see [performance](#report-performance-at-scale)).

### 4. کیفیت داده — `/reports/data-quality`

Three sections, three different questions about whether the data can be trusted.

| Section | Formula / rule |
|---------|----------------|
| نسبت ثبت دستی | `PWA_MANUAL / all submitted entries` per unit. A null `entry_source` predates the field and counts as **scanned**, not manual — treating unknown as manual would invent a problem out of old rows. Bar turns amber ≥10%, red ≥30% |
| سلامت تگ‌های NFC | assets with `status = OPEN` fault reports, **oldest first** — it is a maintenance queue, not a leaderboard |
| دارایی‌های بدون قرائت | assets with no submitted reading since the window start; "هرگز" means no reading has ever been recorded. Uses the **reporting** scope, so an asset reached only through a log sheet still counts as yours to watch |

### 5. نیروی انسانی و بار کاری — `/reports/workforce`

| Column | Formula |
|--------|---------|
| تکمیل‌شده | submitted sheets where `completed_by_user_id = this user` |
| با تأخیر / نرخ تأخیر | of those, how many missed `due_at`; rate = `late / submitted` |
| میانگین زمان رسیدگی | `avg(completion − COALESCE(claimed_at, assigned_at, created_at))` |
| به ازای هر اپراتور | `totalSheets / distinct operators assigned to the unit` |
| پیک‌آپ / انتساب | sheets with a `claimed_at` / with an `assigned_at` |
| **خودگردانی** | **`claimed / (claimed + assigned)`** — measured against *routed* work only; sheets nobody has picked up yet say nothing about how work reaches people |

> Intended as a coaching and load-balancing tool. A low number can mean heavier work, not worse
> performance — the report cannot tell the difference.

### 6. دلایل اقدامات — `/reports/actions`

Every EXTEND / CANCEL / VOID / UNVOID / ADMIN_REOPEN that carries a written explanation, newest
first. Filters on `comment IS NOT NULL` rather than on action type, so it stays correct if
another action is given a reason later. **This is the only place in the system where the *why*
behind a deadline change or an invalidation is recorded.**

### 7. پارامترهای دارایی — `/reports/asset-parameters`

Per-asset reading history and trend chart. Its asset picker uses the **reporting scope**
(ownership *or* responsibility through a log sheet), not the registry scope — see
[RBAC](#authentication--authorization-rbac).

---

### Report performance at scale

Measured on a synthetic year at the stated target load — **10 log sheets/day × 50 assets ×
365 days = 3,650 sheets and 164,250 entries** — on the same PostgreSQL the app uses:

| Query | Time | Plan |
|-------|------|------|
| Compliance by unit | **0.8 ms** | seq scan of `log_sheets` (3.6k rows — trivial) |
| Operator throughput | **0.4 ms** | seq scan of `log_sheets` |
| Out-of-range exceptions | **7 ms** | **index scan** on `idx_log_sheet_entries_breaches` |
| Breach counts (overview) | **12 ms** | index scan + aggregate |
| Manual-vs-scanned split | **77 ms** | parallel seq scan of `log_sheet_entries` |
| Last reading per asset | **64 ms** | seq scan of `log_sheet_entries` |

**Verdict: yes, comfortably.** The whole overview page is well under a second at one year.

How each family behaves as data grows:

- **Anything reading `log_sheets` stays cheap indefinitely.** That table grows ~3,650 rows/year;
  even a decade is ~36k rows, which Postgres scans in milliseconds.
- **The exception report does not degrade**, because `idx_log_sheet_entries_breaches` is a
  *partial* index covering only WARNING/DANGER rows. Breaches are a small minority, so the index
  stays small no matter how large the table becomes. This is the one report an external system
  might poll frequently, and it is the one built to be polled.
- **The two full-scan aggregates grow linearly** with entry count: ~70 ms at 1 year, so roughly
  0.35 s at 5 years and 0.7 s at 10. Acceptable for a page someone opens; the point to revisit
  them is when they cross ~1 s, and the fix then is a nightly rollup table rather than more
  indexes (they aggregate over *all* rows in the window, so no index can avoid the read).

Two things that were deliberately built to avoid trouble:

- The overview's breach figures are **counted in SQL**, not by fetching rows and counting them
  in Java. Counting fetched rows silently stopped growing once a period exceeded the page cap.
- Open NFC fault reports are **filtered in SQL**. Loading the whole table and filtering in Java
  is fine with a handful of rows and linear in the whole table forever after.

One known limit, stated rather than hidden: **دارایی‌های بدون قرائت** examines a bounded slice of
assets (4× the display limit) rather than the entire registry. With a few hundred assets that
is every asset; with tens of thousands it is a sample. Revisit alongside the rollup table above.

---

## Batch Excel Import (async)

Central UI at **`/batch-import`** (sidebar: «ورود دسته‌ای اکسل») for uploading large `.xlsx` files without blocking the browser. Each upload becomes a background **job** tracked in `import_jobs`.

### Safety limits (initial / large loads)

Import is optimized for **operational safety**, not for a single giant file:

| Rule | Default | Config |
|---|---|---|
| **Max data rows per file** (header excluded) | **10,000** | `app.import.max-rows` / `APP_IMPORT_MAX_ROWS` |
| **One active import at a time** (system-wide) | Enforced | Rejects submit while any job is `PENDING` or `RUNNING` |
| **Async worker pool** | `core=1`, `max=1` | `app.import.async.*` — keeps processing sequential |

**Practical guidance for first-time master-data load (e.g. ~100k assets):**

1. Split Excel files into chunks of **at most 10,000 data rows**.
2. Upload them **one after another** — wait until the current job finishes before starting the next.
3. Do **not** run parallel imports (the UI disables submit while a job is active; the API rejects concurrent submits).
4. Watch job progress, server CPU/memory, and free disk under `app.log.path` / `app.import.storage-path`.

A **20,000-row** file is **rejected** before processing starts (same limit applies to synchronous page imports such as `POST /asset-entries/import`). Splitting into two 10k files does not reduce total database work, but keeps each job lighter and safer.

> Row-by-row lookups (duplicate code, sub-function, class, NFC, save) are still O(rows). The 10k cap is a **safety limit**, not a full import performance rewrite.
### Supported entity types

Only types the current user may import (per existing `POST:.../import` permissions) appear in the dropdown:

| Entity | Template download |
|---|---|
| Locations | `/locations/import-template` |
| Plant systems | `/plant-systems/import-template` |
| Main functions | `/main-functions/import-template` |
| Sub functions | `/sub-functions/import-template` |
| Asset entries | `/asset-entries/import-template` |
| Users | `/users/import-template` |
| Operational units | `/operational-units/import-template` |
| Unit staff (supervisors/operators) | `/operational-units/import-staff-template` |

### How it works

1. User selects entity type and uploads `.xlsx` (max **50 MB** by default; also subject to the **10,000-row** safety limit above).
2. File is stored on disk under `app.import.storage-path` (see env `APP_IMPORT_STORAGE_PATH`). Row count is checked **before** the job is queued; over-limit files are rejected and the stored file is deleted.
3. A `PENDING` row is inserted in `import_jobs` (with `total_rows` already set); processing starts **after the DB transaction commits** (so the async worker can see the job).
4. `ImportJobRunner` reads the file row-by-row via `ExcelImportService` (same logic as synchronous import).
5. Progress is updated every **25 rows**; the UI polls `GET /batch-import/jobs` for live status.
6. On completion the uploaded file is **deleted from disk**; row errors (if any) stay in `import_job_errors`.
### Row-level behaviour

- Each data row is validated and saved **individually**.
- Validation errors (missing code, parent not found, duplicate code where pre-checked, etc.) are recorded and the import **continues** with the next row.
- Final job status is `COMPLETED` with counts `موفق: X — خطا: Y` (not `FAILED`), unless an unexpected exception aborts the whole job.
- Up to **500** row errors per job are persisted (`app.import.max-stored-errors`); view them via the **خطاها** button.

### Job statuses

| Status | Meaning |
|---|---|
| `PENDING` | Queued, not started yet |
| `RUNNING` | Processing rows |
| `COMPLETED` | Finished (may include per-row errors) |
| `FAILED` | Aborted by an unexpected error or server restart (while `RUNNING`) |
| `CANCELLED` | Stopped by user |

### Cancel and delete

| Action | When | Endpoint |
|---|---|---|
| **توقف (Cancel)** | `PENDING` or `RUNNING` | `POST /batch-import/jobs/{jobUuid}/cancel` |
| **حذف (Delete)** | Terminal jobs only (`COMPLETED`, `FAILED`, `CANCELLED`) | `POST /batch-import/jobs/{jobUuid}/delete` |

- Cancel on a `PENDING` job is immediate; on `RUNNING` jobs cancellation is **cooperative** (takes effect between row batches, like audit retention purge).
- Delete removes the DB row and any stored row errors; it does not affect master data already imported.

Both actions require `POST:/batch-import` (same as starting an import).

### Restart recovery

On application startup, `ImportJobRecoveryRunner`:

- Marks interrupted `RUNNING` jobs as `FAILED`.
- **Re-queues** `PENDING` jobs whose file still exists on disk (instead of failing them).

Log prefixes and log levels for Import are documented under [Audit Trail & Logging](#audit-trail--logging). Storage path override example:

```powershell
$env:APP_IMPORT_STORAGE_PATH = "C:\Users\Hadi\Desktop\Temp\appdata"
```

### Troubleshooting — reset import job tables

If jobs are stuck or you need a clean slate, run this in PostgreSQL (does **not** delete already-imported master data; only job tracking rows):

```sql
TRUNCATE TABLE import_job_errors, import_jobs RESTART IDENTITY;
```

`RESTART IDENTITY` resets auto-increment IDs to 1. Uploaded files on disk under `app.import.storage-path` are **not** removed by this query — delete that folder manually if needed.

---

## Audit Trail & Logging

### Audit Trail (entity changes)
- `RepositoryAuditAspect` automatically (via AOP) intercepts repository save/delete operations and records field-level changes in the `audit_log` table (JSONB).
- For **UPDATE**, previous field values are captured as an **independent snapshot** (committed DB state / Hibernate loaded-state fallback) so managed-entity mutations and auto-flush do not produce empty diffs.
- Both `save` and `saveAndFlush` are covered (`saveAndFlush` does not go through the `save` proxy via self-invocation).
- Audit writes are **asynchronous** (`AsyncConfig` + `AuditWriteService`) to avoid adding latency to the main request path.
- `AuditRetentionService` supports **batch purging** of records older than the configured retention period (`app_settings.audit.retention.days`), with mid-run cancellation support; execution happens on a dedicated thread and progress is visible/controllable from the "Settings" panel.
- Audit history can be viewed from the "Audit Logs" page (`/audit-logs`).
- **Do not disable** this DB audit for production accountability — it is separate from application file logging (below).

### Application logging (files under `app.log.path`)

| Channel | Level (default) | What it contains |
|---|---|---|
| **WEB / API** (`LoggingAspect`) | **INFO** | Controller entry/exit (request boundary) |
| **SVC / REPO** (`LoggingAspect`) | **DEBUG** | Method entry/exit + arg/result serialization — quiet during Import/bulk |
| **Business** (`BusinessEventLogger` → `business.log`) | **INFO** | Import start/finish summaries, scheduler runs, important ops |
| **Explicit `log.info`** in services (e.g. `[IMPORT]`, `[IMPORT_JOB]`) | **INFO** | Job lifecycle and import totals |
| **Errors** | **WARN / ERROR** | Failures (always) |
| **Hibernate SQL** | **WARN** | SQL trace off by default (enable DEBUG only when diagnosing) |

**Why SVC is DEBUG:** Import and other bulk paths call many services per row. Logging every entry/exit at INFO (with Jackson serialization of entities) can produce tens of thousands of lines per file, fill async log queues, and slow the import thread. Serialization runs **only when** the corresponding level is enabled (`isInfoEnabled` / `isDebugEnabled`).

**To temporarily see service method traces** (very verbose during Import):

```properties
logging.level.com.hnp.backendofflinefirst.service=DEBUG
```

Other logging notes:

- `LogSanitizer` strips/masks sensitive information (e.g., passwords) before it's written to logs.
- `RequestMdcFilter` adds a request ID to the MDC so logs for a single HTTP request can be traced and correlated.
- `SecurityAuditLogger` records security-related events (login/logout, unauthorized access attempts).
- Rolling files: `app.log` (≈2GB total cap), `business.log`, `error.log` under `app.log.path` (see `logback-spring.xml`).

### Import troubleshooting log prefixes

```
[IMPORT_STORAGE] configured storagePath=... resolvedDir=...
[IMPORT_STORAGE] store start / store done ...
[IMPORT_JOB] submitted ... scheduling async run after commit ...
[IMPORT_JOB] run start jobId=... filePath=... exists=true
[IMPORT] ExcelImportService.<entity> finished ... success=... errors=...
```

Enable storage path override (Windows example):

```powershell
$env:APP_IMPORT_STORAGE_PATH = "C:\Users\Hadi\Desktop\Temp\appdata"
```

---

## Operations Monitoring (Actuator)

`spring-boot-starter-actuator` is enabled for basic production observability. It replaces reliance on the old `GET /api/health`, which only ever returns a hardcoded `"ok"` and never actually checks whether the database is reachable — that endpoint is unchanged and still used by mobile clients for a lightweight connectivity check (no DB round-trip, since it's polled by offline devices).

Only `health` and `metrics` are exposed (`management.endpoints.web.exposure.include=health,metrics`). Endpoints that can leak configuration or secrets — `env`, `beans`, `heapdump`, `configprops`, etc. — are **not** exposed.

| Endpoint | Access | Notes |
|---|---|---|
| `GET /actuator/health/liveness` | Public (no login) | For process watchdogs / restart automation — reports status only. |
| `GET /actuator/health/readiness` | Public (no login) | For load balancers deciding whether to route traffic — reports status only. |
| `GET /actuator/health` | `GET:/actuator/**` permission (`ADMIN` by default) | Runs the real DB connectivity check and reports `UP`/`DOWN`. `show-details` stays at the safe default (`never`), so no internal detail (DB host, component names, …) leaks even to an authenticated caller. |
| `GET /actuator/metrics`, `GET /actuator/metrics/{name}` | `GET:/actuator/**` permission (`ADMIN` by default) | Runtime counters/gauges — `hikaricp.connections.active`, `jvm.memory.used`, `http.server.requests`, etc. |

The `GET:/actuator/**` permission is seeded in `V1__initial_schema.sql` and granted only to `ADMIN`, following the same pattern as the mobile/web session-admin pages (see [Adding a new endpoint](#adding-a-new-endpoint-required--do-not-skip)). `/actuator/health/liveness` and `/actuator/health/readiness` are permitted without authentication in `WebSecurityConfig` — everything else under `/actuator/**` requires it.

---

## Testing

The project has extensive test coverage:

- **Unit tests**: `service/*Test.java` — business logic (log-sheet lifecycle, assignment, operational unit scope, Excel import, **`AssetHierarchyService`** placement trees, uniqueness validators, etc.)
- **Security tests**: `security/EndpointSecurityTest.java` — verifies endpoint permissions.
- **Integration tests**:
  - `integration/ApiIntegrationTest.java` — REST API flows
  - `integration/AssetHierarchyCascadeIntegrationTest.java` — end-to-end hierarchy cascade, scope, FK constraints, and asset sync touches against **Testcontainers PostgreSQL**
  - `integration/SchemaConstraintsIntegrationTest.java` — case-insensitive uniqueness, one asset per sub-function, user contact fields, asset `active` behaviour
  - `integration/MobileBundleApiIntegrationTest.java` — bootstrap/bundle APIs
  - `integration/ApiSessionIntegrationTest.java` — JWT session registry: login registers a device, a second login supersedes the first, revocation blocks the next call, admin page rendering/search
  - `integration/WebSessionConcurrencyIntegrationTest.java` — web session control: a second form login expires the first browser, admin `/web-sessions` page lists and expires sessions
- **`support/WithAppUser`**: a custom annotation to simulate an authenticated user with a given role/permission in tests.

Run all tests (requires Docker for Testcontainers):

```bash
mvnw.cmd test
```

Code coverage report (JaCoCo) is generated after running tests at:

```
target/site/jacoco/index.html
```

> `application-test.properties` configures a separate test profile with the same Flyway schema (no manual database setup needed — handled by Testcontainers).

---

## Build & Deploy

Build the executable JAR:

```bash
mvnw.cmd clean package
```

Run the generated jar:

```bash
java -jar target/backend-offline-first-0.0.1-SNAPSHOT.jar
```

Production environment settings can be overridden via the environment variables in the [Configuration](#configuration-applicationproperties) table, or via an `application-prod.properties` file (kept out of Git).

---

## Default User

On first startup, if no user with the `ADMIN` role exists, `AdminBootstrapRunner` automatically creates one:

```
username: admin
password: admin123
```

⚠️ **This is a default password — change it immediately after your first login from the "Users" page.**

---

## License

Copyright (C) 2026 **hadi_hnp**

This project is free software: you can redistribute it and/or modify it under the terms of the [GNU General Public License v3.0 or later](https://www.gnu.org/licenses/gpl-3.0.html) (GPL-3.0-or-later).

See the [LICENSE](LICENSE) file for the full license text.
