# Backend Offline-First

Copyright (C) 2026 hadi_hnp

A **Spring Boot** backend for an industrial **round/log-sheet inspection** management system, built with an **offline-first** architecture. It coordinates between a web admin panel (Thymeleaf) and an offline-capable operator mobile app that periodically syncs data with the server.

---

## Documentation map

This README is the tour. When you need the detail, these are the references — each is kept
current with the code, and a change to behaviour is expected to update the matching file in
the same commit.

| Document | What it answers |
|---|---|
| **[docs/schema.md](docs/schema.md)** | Every table, column, index and constraint, with the reasoning. Describes the schema **as it is now** rather than as a replay of migrations. |
| **[docs/hierarchy.md](docs/hierarchy.md)** | Location → Plant System → Main Function → Sub Function → Asset, how access scope is derived from it, and **what must happen when you move something**. |
| **[docs/security.md](docs/security.md)** | The five system roles and exactly what each may do, how endpoint / scope / object checks combine, and the access rules that depend on a role's **code** rather than its permissions. |
| **[docs/log-sheets.md](docs/log-sheets.md)** | The core business object: how a sheet is created, its seven states, every transition, every endpoint, and the asset-status request workflow. |
| **[docs/jobs.md](docs/jobs.md)** | Every scheduler, startup runner and async pool — what it does, where it lives, how it is configured, and how it fails. |
| **[docs/reports.md](docs/reports.md)** | All seven report pages and the exact formula behind every number. |
| **[AGENTS.md](AGENTS.md)** | Conventions, and a numbered list of traps found the hard way. Read before changing anything. |
| **[CLAUDE.md](CLAUDE.md)** | Entry point for AI agents working in this repository. |

The PWA has the same arrangement — see its `README.md`, `AGENTS.md` and `docs/`.

---

## Table of Contents

- [Documentation map](#documentation-map)
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
- [Attachments (photo, voice note & video fields)](#attachments-photo-voice-note--video-fields)
  - [Orphan-file sweep](#orphan-file-sweep)
- [Groundwork for later features](#groundwork-for-later-features)
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

> **Roles are fully copyable.** Nothing in the application decides access from a role's *code*.
> Rules that are not about calling an endpoint — "sees the whole plant", "may complete a sheet
> they were not assigned", "may review a fault report" — are **capabilities**: `CAP:*` rows in
> `permissions` that a duplicated role inherits along with everything else. Before this they
> were `hasRole("ADMIN")`-style comparisons, so a copy of `ADMIN` carried all 123 permissions
> and was still refused. The five system roles behave exactly as they always did; what changed
> is that a copy of one now behaves the same way. See
> [docs/security.md](docs/security.md#3-capabilities--access-that-is-not-about-an-endpoint).
>
> **The five system roles are immutable** — name, description and permissions alike, and they
> cannot be deleted. They are the reference the seeds, the code and this document all describe,
> and editing one makes those three disagree silently. To customise, use **ساخت نقش مشابه**: the
> copy is an ordinary editable role, and it now genuinely behaves like the original.
>
> **The last active administrator cannot be deleted, deactivated, or stripped of the ADMIN
> role.** All three leave nobody able to administer users and roles, repairable only by editing
> the database by hand. Create a second administrator first, then the original is free. The rule
> follows the last *active admin*, not the account named `admin`, so renaming or retiring the
> bootstrap account is still allowed — but an inactive admin does not count as a fallback.

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
  - ✅ Log-sheet templates: **view only** (`GET:/log-sheet-templates`) — limited to supervised units
  - ❌ Log-sheet templates: **no create, edit or delete.** `POST:/log-sheet-templates` is *not* seeded for this role, and `LogSheetTemplateService.canEditOrDelete()` allows only `ADMIN` / `HIGH_USER` — so granting the endpoint by hand would still be refused by the service
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
  - Cannot create, edit or delete templates at all — enforced in `LogSheetTemplateService` even if the endpoint permission were granted by hand. (`assertCanManageUnit` does contain a per-unit rule for supervisors, but `canEditOrDelete()` rejects the role before it is reached; it exists so that relaxing the role rule later cannot silently skip the unit check.)
  - May create **custom log sheets** (`POST:/log-sheets/custom`) only for supervised units; selected assets must be **active** and within that unit’s hierarchy scope; assets may span multiple asset classes (multi-class field snapshot).
- **Typical use:** shift/line supervisor who runs daily rounds, assigns work, and creates one-off **custom** rounds for a subset of assets. Scheduled *templates* are defined for them by an `ADMIN` or `HIGH_USER`.

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
| `master-data` | Locations → assets, log-sheet templates | `ADMIN`, `HIGH_USER` (+ template **view only** for `SUPERVISOR` — its single `master-data` permission) |
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

### 1. Create the database and its user

> Everything in this section was executed against **PostgreSQL 18.4** and the results are what
> is written here — including the failures, which are shown so you can recognise them.

Two things connect to this database, and they need different rights:

| Component | When | Needs |
|---|---|---|
| **Flyway** | at every application startup (`spring.flyway.enabled=true`) | **DDL** — `CREATE TABLE`, `CREATE INDEX`, `ALTER TABLE` |
| **Spring Data JPA / Hibernate** | for every request | **DML** — `SELECT`, `INSERT`, `UPDATE`, `DELETE` |

They share **one** datasource. That single fact decides your setup: if Flyway runs inside the
application, the connecting user must be able to create tables. A DML-only user is only possible
if the migrations are run by somebody else, beforehand.

So pick one of the two setups below. **Option A** is right for almost everyone.

---

#### Option A — one role that owns the database (recommended)

Make the application user the **owner**. Every table Flyway creates is then owned by the same
role that will query it, so DML follows from DDL automatically and **no `GRANT` is ever needed
after adding a migration** — the most common cause of "it worked locally, it fails in staging".

Run as a superuser (`postgres`):

```sql
-- The role the application connects as. CREATE USER is simply CREATE ROLE ... LOGIN.
CREATE USER offline_app WITH PASSWORD 'StrongPassword';

-- The database, owned by that role from the start.
CREATE DATABASE offline_first_db OWNER offline_app;
```

Then connect **to the new database** and hand over the schema:

```sql
\c offline_first_db

-- Required on PostgreSQL 15 and later. The public schema is no longer writable by everyone,
-- so without this Flyway fails on its very first statement with:
--     SQL State : 42501
--     Message   : ERROR: permission denied for schema public
ALTER SCHEMA public OWNER TO offline_app;
```

That is the whole setup. Ownership already implies `USAGE`, `CREATE` and full rights on
everything the role creates, so no further `GRANT` is needed.

```
jdbc:postgresql://localhost:5432/offline_first_db
username: offline_app
password: StrongPassword
```

---

#### Option B — least privilege: migrations separately, DML-only at runtime

Use this when policy forbids a runtime user holding DDL rights. The trade is that **migrations
no longer run themselves** — you run them as the owner during deployment, and the application
starts with Flyway switched off.

**B1. Roles and database** (as superuser):

```sql
-- Owns the schema and runs migrations. No login: nothing connects as it directly.
CREATE ROLE offline_owner NOLOGIN;

-- What the application connects as at runtime. No DDL rights, ever.
CREATE USER offline_app WITH PASSWORD 'StrongPassword';

CREATE DATABASE offline_first_db OWNER offline_owner;
```

**B2. Schema ownership and the runtime grants** (connected to the new database):

```sql
\c offline_first_db

ALTER SCHEMA public OWNER TO offline_owner;

-- Connect + see inside the schema. USAGE alone allows no reading of any table.
GRANT CONNECT ON DATABASE offline_first_db TO offline_app;
GRANT USAGE   ON SCHEMA public            TO offline_app;

-- Rights on the tables that exist RIGHT NOW.
GRANT SELECT, INSERT, UPDATE, DELETE
  ON ALL TABLES IN SCHEMA public TO offline_app;

-- …and on tables created LATER, by future migrations. This is the line people leave out.
ALTER DEFAULT PRIVILEGES FOR ROLE offline_owner IN SCHEMA public
  GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO offline_app;
```

> **Why both.** `GRANT ... ON ALL TABLES` is a snapshot: it affects only the tables that exist
> at the moment you run it. Run it on an empty database and it grants **nothing**, and the table
> your next migration creates is unreadable. Demonstrated on 18.4:
>
> ```
> GRANT SELECT ON ALL TABLES IN SCHEMA public TO demo_reader;   -- empty database
> CREATE TABLE made_later(id int);
> SELECT * FROM made_later;   -->  ERROR: permission denied for table made_later
> ```
>
> `ALTER DEFAULT PRIVILEGES` is what covers future objects. `FOR ROLE offline_owner` matters:
> default privileges attach to the role that *creates* the object, so naming the owner is what
> makes it apply to the tables migrations create.

**B3. Sequences — not needed for this schema, but here is the rule.**

Every primary key here is `BIGINT GENERATED BY DEFAULT AS IDENTITY`. An identity column's
sequence is internally owned by its table and `INSERT` alone covers it — verified by inserting
with *no* sequence privileges at all:

```sql
REVOKE USAGE, SELECT, UPDATE ON ALL SEQUENCES IN SCHEMA public FROM offline_app;
INSERT INTO operational_units(code,name,created_at,updated_at) VALUES ('X','x',0,0);  -- works
```

Add the grants below only if a future migration introduces a `serial` column or code calls
`nextval()` directly. Harmless to include pre-emptively:

```sql
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO offline_app;
ALTER DEFAULT PRIVILEGES FOR ROLE offline_owner IN SCHEMA public
  GRANT USAGE, SELECT ON SEQUENCES TO offline_app;
```

(`UPDATE` on a sequence only allows `setval`, which nothing in this application does.)

**B4. Running migrations, then the app.** Migrate as the owner, then start with Flyway off:

```bash
# Deployment step — as a role that owns the schema.
SPRING_DATASOURCE_USERNAME=postgres SPRING_DATASOURCE_PASSWORD=… \
  java -jar backend-offline-first-0.0.1-SNAPSHOT.jar

# Runtime — DML only.
SPRING_DATASOURCE_USERNAME=offline_app SPRING_DATASOURCE_PASSWORD=StrongPassword \
SPRING_FLYWAY_ENABLED=false \
  java -jar backend-offline-first-0.0.1-SNAPSHOT.jar
```

> **`SPRING_FLYWAY_ENABLED=false` is mandatory here.** Leave it on and the DML-only user hits
> the same `42501` on startup, because Flyway checks and creates its own history table first.
>
> The cost of this option is a real one: schema and code can now drift. Nothing forces the
> migration step to have run before the new jar starts, and `ddl-auto=validate` will then fail
> at boot naming a missing column — which is the good outcome, but only at deploy time.

---

#### What goes wrong, and what it looks like

| Symptom | Cause |
|---|---|
| `SQL State 42501 — permission denied for schema public` at startup | The connecting role cannot create. Option A: you skipped `ALTER SCHEMA public OWNER`. Option B: you left Flyway enabled. |
| `permission denied for table <something>` at runtime, after a deployment | Option B without `ALTER DEFAULT PRIVILEGES` — the new migration's table was granted to nobody. |
| `permission denied for schema public` although you ran `GRANT ALL ON SCHEMA public` | The grant landed on a different database. `GRANT ... ON SCHEMA` is per-database; you must `\c` into it first. |
| Flyway reports "Schema is up to date" but tables are missing | You are connected to a different database than you migrated. Check the JDBC URL. |

#### Verify before the first run

```sql
\c offline_first_db offline_app
SELECT has_database_privilege('offline_app','offline_first_db','CONNECT') AS can_connect,
       has_schema_privilege  ('offline_app','public','USAGE')             AS can_use,
       has_schema_privilege  ('offline_app','public','CREATE')            AS can_create;
```

- **Option A** — all three `t`. If `can_create` is `f`, redo `ALTER SCHEMA public OWNER`.
- **Option B** — `can_create` is expected to be `f`; that is the point. Make sure
  `SPRING_FLYWAY_ENABLED=false` is set.

#### Notes

- **Do not pre-create any tables.** Flyway owns the schema entirely, and
  `spring.jpa.hibernate.ddl-auto=validate` means Hibernate never creates or alters anything — it
  only checks that every mapped column exists. An empty database is what the first startup wants.
- **A database name with a hyphen must be quoted** everywhere: `CREATE DATABASE "offline-first-db"`,
  and `\c "offline-first-db"`. The default name in `application.properties` uses underscores
  (`offline_first_db`), which needs no quoting — the simpler choice.
- **Docker, for local development.** The official image creates the database with the given user
  as its owner, which satisfies Option A with no extra steps:

  ```bash
  docker run --name offline-first-db -e POSTGRES_DB=offline_first_db \
    -e POSTGRES_USER=offline_app -e POSTGRES_PASSWORD=StrongPassword \
    -p 5432:5432 -d postgres:18
  ```

- For personal development, create an `application-local.properties` file (ignored by
  `.gitignore`) to override values, then run with the `local` profile.

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
| `app.log.max-file-size` | `APP_LOG_MAX_FILE_SIZE` | `100MB` — rotation is size **and** time; without the size cap the current day's file can grow until it fills the disk |
| **log format** | `SPRING_PROFILES_ACTIVE=json-logs` | *(unset)* → human-readable text. Set the profile to emit JSON for Filebeat/Logstash — same four files, same names, only the encoding changes. Composes with other profiles: `prod,json-logs`. See [Application logging](#application-logging-files-under-applogpath). |
| `app.audit.enabled` | `APP_AUDIT_ENABLED` | `true` |
| `app.audit.async.core-pool-size` | `APP_AUDIT_ASYNC_CORE_POOL_SIZE` | `2` |
| `app.audit.async.max-pool-size` | `APP_AUDIT_ASYNC_MAX_POOL_SIZE` | `4` |
| `app.audit.retention.batch-size` | `APP_AUDIT_RETENTION_BATCH_SIZE` | `5000` |
| `app.sync.batch-max-items` | `APP_SYNC_BATCH_MAX_ITEMS` | `500` |
| `app.attachments.storage-dir` | `APP_ATTACHMENTS_STORAGE_DIR` | `./data/attachments` — root for captured photos/voice notes; **back it up with the database** (see [Attachments](#attachments-photo--audio-fields)) |
| `app.attachments.max-file-size-bytes` | `APP_ATTACHMENTS_MAX_FILE_SIZE_BYTES` | `26214400` (25 MB) — outer ceiling; per-kind caps below are what normally apply |
| `app.attachments.max-image-bytes` | `APP_ATTACHMENTS_MAX_IMAGE_BYTES` | `5242880` (5 MB) |
| `app.attachments.max-audio-bytes` | `APP_ATTACHMENTS_MAX_AUDIO_BYTES` | `5242880` (5 MB) |
| `app.attachments.max-video-bytes` | `APP_ATTACHMENTS_MAX_VIDEO_BYTES` | `20971520` (20 MB) |
| `app.attachments.sweep.enabled` | `APP_ATTACHMENTS_SWEEP_ENABLED` | `true` — daily [orphan-file sweep](#orphan-file-sweep) |
| `app.attachments.sweep.grace-hours` | `APP_ATTACHMENTS_SWEEP_GRACE_HOURS` | `24` — safety rail; never lower to minutes |
| `app.attachments.sweep.cron` | `APP_ATTACHMENTS_SWEEP_CRON` | `0 0 2 * * *` — 02:00 daily |
| `app.attachments.sweep.zone` | `APP_ATTACHMENTS_SWEEP_ZONE` | `Asia/Tehran` |
| `app.template-guides.storage-dir` | `APP_TEMPLATE_GUIDES_STORAGE_DIR` | `./data/template-guides` — groundwork, nothing writes it yet |
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

### Production sizing (JVM heap, connection pool, HTTP threads)

**Nothing here is set in the repo, and that is deliberate** — Spring Boot's defaults are
workable for the target deployment. This section exists so the numbers are a *reasoned choice*
rather than an accident, and so whoever tunes production starts from a baseline instead of
guessing. Every knob below is commented out in `application.properties`; uncomment (or set the
environment variable) **on the production host only**. Local `mvnw spring-boot:run` should stay
on the defaults.

Assumed box: **~16 GB RAM, 2 CPU cores, ~50 concurrent users, Postgres frequently co-located.**

#### JVM heap — the one that is not a property

There is no `application.properties` key for heap; it is a command-line flag:

```bash
java -Xms512m -Xmx2g -jar backend-offline-first-0.0.1-SNAPSHOT.jar
```

| Situation | Suggested `-Xmx` |
|---|---|
| Postgres on the **same** host (the common case) | **2 GB** |
| Database on a **remote** host | up to **4 GB** |

**Do not hand most of the 16 GB to the JVM.** A large heap does not make this application
faster — it holds little long-lived state, and attachment bytes stream to and from disk rather
than being buffered in memory (see [Where the bytes live](#where-the-bytes-live-and-why-not-in-the-database)).
What the extra RAM *is* good for is Postgres's shared buffers and the OS page cache that makes
its reads fast; a 12 GB heap on a co-located box would starve exactly the cache the database
depends on. `-Xms512m` simply avoids a burst of early heap resizing at startup.

#### HikariCP connection pool

| Key | Boot default | Suggested | Why |
|---|---|---|---|
| `spring.datasource.hikari.maximum-pool-size` | `10` | `10` (up to `15`) | Already right for 2 cores. A pool bigger than the database can usefully run concurrently just moves the queue from the app into Postgres. Raise only if you observe waits on connection acquisition |
| `spring.datasource.hikari.minimum-idle` | = max | `5` | Keeps a warm floor without pinning the full pool |
| `spring.datasource.hikari.connection-timeout` | `30000` ms | `20000` ms | Fails fast and visibly under overload instead of letting every request thread pile up waiting |

Two things in **this** application also draw on that pool, which is easy to forget when sizing it:

- The background workers — audit writes (`app.audit.async.max-pool-size`, up to 4) and Excel
  batch import (`app.import.async.max-pool-size`, 1).
- The **mobile sync batch endpoint**, which runs synchronously in one transaction per request
  (see [Batch size limit](#batch-size-limit)) — so concurrently syncing devices count toward
  pool demand, not just people clicking around the panel.

#### Tomcat HTTP threads

| Key | Boot default | Suggested |
|---|---|---|
| `server.tomcat.threads.max` | `200` | `80` |
| `server.tomcat.threads.min-spare` | `10` | `10` |

At ~50 concurrent users the cap is never reached, so **this changes nothing in normal
operation**. Its value is as a bulkhead: under a burst, 80 threads queue the excess rather than
letting 200 threads contend for 2 cores and a 10-connection pool — which is the difference
between a system that is merely slow and one that stops responding.

#### If you change only one thing

Set `-Xmx`. It is the only setting here that is not already at a sensible default, and the only
one whose absence depends on how the service happens to be started.

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
# Heap is a command-line flag, not a property — see Production sizing above.
java -Xms512m -Xmx2g -jar backend-offline-first-0.0.1-SNAPSHOT.jar
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

## Attachments (photo, voice note & video fields)

An asset class field can have data type **`image`**, **`audio`** or **`video`** (chosen in
*Asset Classes → Fields*). The operator captures it against that field — on the tablet in the
mobile app, or by attaching a file on the web fill page — and it syncs like any other answer.

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

### Limits, and where they are set

Five ceilings are edited by an administrator in **Settings → پیوست‌ها** and apply **per field per
asset** — not per log sheet. Three photos of one pump is the useful case; three photos spread
across a 50-asset sheet would be no limit at all.

| Setting | Default | Allowed range |
|---|---|---|
| Max images per field | 3 | 1–10 |
| Max audio clips per field | 1 | 1–10 |
| Max videos per field | 1 | 1–10 |
| Max audio duration | 120 s | 5–600 s |
| Max video duration | 120 s | 5–600 s |

They are stored in `app_settings` and reach the mobile app through `GET /api/bootstrap`, which
the PWA already calls on every reconnect. So an administrator changes them once in the panel and
every tablet follows automatically — **the device never edits them**; the app's Settings screen
shows them read-only, and only to admins.

Both clients enforce them so an operator gets a clear message before wasting a capture, and the
server enforces them again on upload. That repetition is deliberate: a client is not a trust
boundary, and a stale tablet or a hand-rolled request must not be able to plant a thirtieth
photo. Counting is per *kind*, so a voice note never consumes a photo slot, and deleting an
attachment frees its slot — otherwise an operator who took a bad photo at the ceiling could
never replace it. An idempotent retry at the ceiling is not counted as a new file.

### Byte ceilings, and the video decision

Byte caps are **server properties, not admin settings** — an administrator reasons in "how many
photos", and a byte cap set by hand is likelier to be wrong than useful:

| Property | Default |
|---|---|
| `app.attachments.max-image-bytes` | 5 MB |
| `app.attachments.max-audio-bytes` | 5 MB |
| `app.attachments.max-video-bytes` | 20 MB |
| `app.attachments.max-file-size-bytes` | 25 MB (outer ceiling for any kind) |

Video is the one that needed a real decision, because unlike a photo it **cannot be cheaply
re-encoded on the device afterwards** — the constraints handed to `getUserMedia` and
`MediaRecorder` at capture time are the only lever there is. The app records at **480p
(854 px long edge), 700 kbps video + 24 kbps mono audio**, which is about 88 KB/s, so the
default two minutes lands near **10.5 MB**.

480p rather than 720p is deliberate: a leak, a flame, a vibrating coupling or a gauge sweeping
is entirely legible at that size, and 720p would roughly double the bytes for no diagnostic
gain on a network that also has to carry everything else.

A bitrate is a **hint, not a promise** — a high-motion scene (steam, spray, a swinging torch)
makes the encoder overshoot badly. So the app also enforces a hard byte ceiling *while
recording* and stops early at 15 MB, below the server's 20 MB. Stopping early keeps what was
captured; letting it run would mean the server refusing the whole clip after the operator had
already filmed it. When that happens the app says so rather than leaving a mysteriously short
video.

**Storage arithmetic worth doing before raising anything.** At the defaults, one asset carrying
three photos and a voice note is roughly 1.2 MB. A daily 50-asset sheet is ~60 MB, about
**22 GB a year**. Add one 120-second video per asset and the same sheet becomes ~530 MB a day,
**190 GB a year**. Video is the setting that changes the storage plan, which is why the
duration ceiling exists and why the default count is 1.

### Endpoints

| Method | Path | Notes |
|---|---|---|
| `POST` | `/api/attachments` | `multipart/form-data`: `id`, `logSheetId`, `assetId`, `fieldKey`, `file`, optional `width`/`height`/`durationMs` |
| `GET` | `/api/attachments/{id}` | Returns the bytes inline with the detected content type, `Cache-Control: max-age=30d, private` |
| `DELETE` | `/api/attachments/{id}` | Removes the row, then the file |
| `POST` | `/log-sheets/{id}/attachments` | **Web panel** upload from the fill page (session auth + CSRF) |
| `POST` | `/log-sheets/{id}/attachments/{attachmentId}/delete` | Web panel delete |
| `GET` | `/log-sheets/{id}/attachments/{attachmentId}` | Web panel view — see [Viewing attachments](#viewing-attachments-in-the-admin-panel) |

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

### Viewing attachments in the admin panel

A log sheet's detail page renders media as a row of **64 px tiles** — click one and it opens
full size in a lightbox (image, audio player or video player, whichever it is), with size and
duration under each tile. The same rendering appears on the voided-submission page, so a
supervisor comparing what a late operator sent against the authoritative entries sees both sets
side by side.

Small tiles rather than inline players is a deliberate correction: one pump entry can carry
three photos, a voice note and a video, and at full size a single asset pushed the readings —
the actual record — off the screen. The lightbox is one click away and closes on Escape or an
outside click; opening it clears the previous media so a voice note never keeps playing behind
a closed overlay.

The lightbox is a few lines of self-hosted CSS and JavaScript (`/js/attachments.js`), not a
library — see [Offline by construction](#offline-by-construction).

**The web fill page can attach files too.** Media fields there render a real upload control
(previously they rendered as a plain text box, which was simply broken). Each file uploads on
its own request and the returned id is kept in a hidden input the ordinary form submit carries —
the same split the mobile app uses, so one large file cannot fail a whole sheet's submission.
Images are re-encoded in the browser to the same 1600 px / WebP budget before upload, and clip
durations are measured client-side so an over-length file is refused before it is sent.

The bytes come from a **second route**, `GET /log-sheets/{id}/attachments/{attachmentId}`, not
from `GET /api/attachments/{id}`. This is not duplication — the two live on different security
chains. `/api/**` is stateless and JWT-only, so a browser holding a panel session gets `401`
from it and an `<img src="/api/attachments/…">` would simply show a broken image. The web route
is authenticated by that session instead.

Access is unchanged in substance: the sheet id is part of the path, the attachment is verified
to belong to that sheet, and the sheet's own visibility rule decides. Pairing your sheet id
with someone else's attachment id is refused. The route reuses the `GET:/log-sheets/{id}`
permission deliberately — anyone who may open a sheet may see the evidence attached to it — so
no new permission row or migration is involved.

A reference whose row is gone renders as «فایل پیوست در دسترس نیست» rather than a broken image
tag: a partially-restored sheet should look partially restored, not complete.

### Offline by construction

Neither the panel nor the app fetches anything from the internet at runtime. This was audited,
not assumed:

| Asset | Where it comes from |
|---|---|
| Bootstrap CSS/JS, Bootstrap Icons | **webjars** — Maven dependencies packaged inside the jar, served from `/webjars/**` |
| Vazirmatn font | `src/main/resources/static/fonts/`, referenced by a local `@font-face` |
| Attachment gallery + lightbox | `/js/attachments.js` and `/css/app.css` — written here, no library |
| Date picker | `/vendor/mds-bs-datetimepicker/` |
| PWA fonts, React, MUI | bundled into `dist/assets/` by Vite; the built CSS references only `/assets/*.woff2` |

There is no `<script src="https://…">`, no `@import` of a remote stylesheet, no `url(http…)` in
any CSS, and no `fetch()` to an absolute host anywhere in either build output. A plant network
with no route to the internet runs both surfaces fully, which is the point — a CDN reference
would not be a convenience here, it would be a latent outage.

To keep it that way: add front-end libraries as webjars (panel) or npm dependencies (PWA), never
as a script tag.

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
row is already gone and the sweep below reclaims the bytes later; the reverse order would leave a
row pointing at nothing, which every reader would then have to defend against.

### Orphan-file sweep

The row is the source of truth and the file is a satellite, so anything that removes a row
without going through the delete endpoint leaves bytes behind. Three ways that happens:

| Cause | How common |
|---|---|
| **A log sheet is deleted.** `attachments.log_sheet_id` is `ON DELETE CASCADE`, so the rows vanish inside the database and nothing ever tells the filesystem. | The usual one |
| **A crash between writing the file and committing the row.** `store()` writes bytes first; if the process dies in the gap, the file exists and the row never will. | Rare |
| **A database restore to an earlier point.** Rows go back in time; files do not. | Rare, but a big one when it happens |

None of these are bugs to prevent — they are the ordinary cost of keeping binaries out of the
database. The sweep is the other half of that trade.

**What it does.** Walks everything under `app.attachments.storage-dir`, converts each path to the
storage key a row would reference, asks the database in batches of 500 which of those keys are
still referenced, and deletes the rest. Then it removes any date directories left empty, so the
`YYYY/MM/DD` shards do not accumulate forever.

**The grace period is the whole safety design.** A file younger than
`app.attachments.sweep.grace-hours` (default **24**) is never deleted, even with no row at all.
`store()` writes the bytes *before* the transaction commits, so for a moment a perfectly good
upload looks exactly like an orphan; a sweep running in that window would destroy a photo an
operator had just taken and leave the row that follows pointing at nothing. Twenty-four hours is
enormously more than that window needs, and that is the point — the cost of waiting is some dead
bytes for a day, the cost of being wrong is lost evidence. **Do not lower this to minutes.**

**The reverse problem is reported, never repaired.** A row whose file is missing is counted and
shown on the Settings page in red, and nothing is deleted. Removing such rows automatically
would erase the only remaining record that something went missing — exactly when an
administrator most needs to see it. In practice it almost always means the storage directory was
not restored alongside the database.

**How to run it.** *Settings → پاکسازی فایل‌های بدون مرجع* shows four figures — files scanned,
orphans found, space reclaimable, and rows with a missing file — computed without deleting
anything, so you can see whether running it is worth the walk. The run happens in the background
with a cancel button, and the same job also runs automatically once a day.

| Property | Default | Notes |
|---|---|---|
| `app.attachments.sweep.enabled` | `true` | The scheduled pass. Manual runs work regardless. |
| `app.attachments.sweep.grace-hours` | `24` | Safety rail, not a tuning knob |
| `app.attachments.sweep.cron` | `0 0 2 * * *` (02:00 daily) | See below |
| `app.attachments.sweep.zone` | `Asia/Tehran` | Which clock the cron is read against |

**Why a cron and not an interval.** A fixed delay pins the sweep to whenever the server last
restarted — restart at 15:40 on a Tuesday and every future pass walks the whole storage root at
15:40, mid-shift. A cron keeps it at a quiet hour across restarts. The zone is explicit because
"midnight" is otherwise meaningless: a server running in UTC would sweep at 03:30 Tehran time.

| Schedule you want | `app.attachments.sweep.cron` |
|---|---|
| Every day at 02:00 (default) | `0 0 2 * * *` |
| Every 12 hours (02:00 and 14:00) | `0 0 2,14 * * *` |
| Every 6 hours | `0 0 0,6,12,18 * * *` |
| Weekly, Friday 03:00 | `0 0 3 * * FRI` |

Six fields: second, minute, hour, day-of-month, month, day-of-week.

It is safe to leave the scheduled pass on: with the grace period, a scheduled run can only ever
touch files that have been unreferenced for a full day.

**Not covered yet:** the template-guide directory (`app.template-guides.storage-dir`). Extend
`AttachmentSweepService` when guide uploads are built.

---

## Asset status: a request a supervisor approves

An asset carries a `status` of its own (`asset_entries.status`). It is **never written
directly**. Completing a log sheet *proposes* a change; a supervisor decides it.

### Why a request and not a direct write

A reading taken in the field is a claim, not a decision. An operator recording a pump as out of
service should not silently retag the asset for everyone who looks at it afterwards. So when a
sheet completes and a class field keyed **`status`** (case-insensitively — `status`, `Status`,
`STATUS`) holds a value that **differs** from what the asset currently shows, the system raises a
row in `asset_status_change_requests` with status **`PENDING`** («ثبت شده»). The asset itself does
not move.

A reading equal to the current status raises nothing — there is no change to decide on. A blank
reading raises nothing either: blank means "the operator did not record a state", not "the asset
has no state".

### The request records where it came from

| Column | Meaning |
|--------|---------|
| `requested_status` / `previous_status` | What is being asked for, and what the asset showed when it was asked |
| `source` | `LOG_SHEET` or `MANUAL` |
| `log_sheet_id` / `log_sheet_entry_id` / `field_key` | Which sheet, entry and field produced the reading |
| `requested_by_user_id` / `requested_at` | Who filed it and when |
| `decided_by_user_id` / `decided_at` / `decision_note` | Who decided, when, and why |
| `applied_old_status` | The value approval actually replaced — what an undo restores |

The queue page shows the **source sheet's current status** next to each request, so a proposal
from a sheet that has since been voided does not look the same as one from a sheet that stands.

### A supervisor can file one directly

Supervisors and admins can raise a request with no log sheet behind it, giving a reason. It is
still only a proposal — filing does not change the asset. The form works in two steps, and the
order is not cosmetic:

1. **Choose the asset.** The picker searches by code or name and is scoped through
   `AssetAccessService.findReportableAssets` — the *same* scope the save validates against, so
   the list can never offer something the save would then refuse. An admin sees everything; a
   unit-scoped user sees the assets of their operational units and everything below them,
   exactly as elsewhere in the panel.
2. **Then choose the status.** A status only means anything in the vocabulary of the asset's
   class, so the second field cannot be filled in until the first is answered. If the class
   declares options the form offers exactly those (with the asset's current value disabled —
   it cannot be "changed" to itself); if the status field is free text, a text box appears.

**A class with no `status` field cannot have a request at all.** Nothing would ever set such a
status back through a log sheet, and approving would invent a value the operators' own form
cannot express. The form says so and keeps submission disabled; `raiseManual` refuses it
server-side regardless, as does an off-list value when the field declares options.

### When the change is dated

**The history is dated to when the reading was taken, not when it was signed off.** A status
noted at 08:15 and approved at 16:40 belongs at 08:15 — otherwise every asset's history bunches
up around review times and stops lining up with the rounds that produced it. The log sheet
entry's device timestamp is captured on the request (`reading_recorded_at`) and becomes
`asset_status_history.changed_at` on approval; a manual request uses its own filing time, and a
request raised before that column existed falls back to when it was filed.

**Undoing is dated now**, because it is an administrative decision rather than an observation —
back-dating the correction too would hide when it actually happened. The asset row's own
`updated_at` also stays real time; only the history entry is back-dated, because that is the
thing being described.

### Deciding

| Transition | Effect on the asset |
|---|---|
| PENDING → **APPROVED** | Takes the requested status; the replaced value is stored on the request |
| PENDING → **REJECTED** | Nothing |
| APPROVED → **PENDING** or **REJECTED** | Goes back to exactly what that approval replaced |
| REJECTED → PENDING | Nothing (reopens the question) |

Every approval and undo writes an `asset_status_history` row carrying `request_id`, so the asset
timeline says **which decision** produced each change and what the value was before it.

### The only-latest rule

> A decision that would move the asset's status is allowed **only on that asset's newest
> request**.

Undoing an approval restores `applied_old_status`. That is sound only for the newest request:
undoing one in the middle would restore a value that later requests have already superseded,
silently rolling the asset back in time. The same applies to *approving* a stale pending
request — it would set the asset to a reading a newer round has already replaced.

**Rejecting a pending request is always allowed**, whatever its age, because it changes nothing
about the asset; otherwise stale proposals would clog the queue for ever.

The UI hides the controls it knows will be refused and shows a lock icon instead, and the service
refuses regardless — verified live against a hand-crafted POST, not just a hidden button.

### This is the only mechanism

Voiding or reopening the source log sheet deliberately does **not** touch the asset any more.
With two mechanisms the rule above could be violated by the sheet lifecycle itself, silently and
behind the supervisor's back. A request raised by a sheet that is later voided simply stays in
the queue showing that its sheet was voided, and the supervisor decides with that in front of
them. For the same reason the asset form's status field is **read-only** after creation; the
service ignores it even if a hand-crafted POST supplies one.

### Activation history — related, and deliberately separate

An asset also has an `active` flag deciding whether it takes part in log-sheet generation.
Changes to it are journalled in **`asset_activation_history`** — a different table:

| | `status` | `active` |
|---|---|---|
| Means | what state the equipment is in | whether the record takes part in generation |
| Changed by | an approved request only | the asset form |
| Approval needed | yes | no |

They meet only in the merged history view. The first row for an asset is its registration
(`CREATED`, `was_active` null), so the timeline starts where the record does.

### The history page — `/reports/asset-history`

One chronological timeline per asset merging status and activation changes: what changed, from
what to what, who, when, and **how** — a link to the driving log sheet, the request number, or a
registry change. A filter switches between همه / وضعیت عملیاتی / فعال‌سازی without a reload.

Reachable from the log-sheet detail page (per asset row), the asset registry list, and the
sidebar. The request queue lives at **`/asset-status-requests`**, under **داده‌های عملیاتی** in
the sidebar — it is day-to-day operational work, not a report.

| | |
|---|---|
| Permissions | `GET:/asset-status-requests`, `POST:/asset-status-requests`, `POST:/asset-status-requests/{id}/decide` — granted to `ADMIN`, `HIGH_USER`, `SUPERVISOR`; operators never see them. History uses `GET:/reports` |
| Asset scope | `AssetAccessService.findReportable` — responsibility **through a log sheet**, not location ownership |
| Enforced where | `AssetStatusRequestService.requireDecider()` re-checks the role inside the service, not only on the endpoint |

### Performance

Raising is per **sheet**, not per asset: one query for the entries, one snapshot resolution per
class, one `findAllById` for the assets. `idx_ascr_asset (asset_id, id DESC)` answers both the
asset timeline and the only-latest guard; `idx_ascr_pending` is partial on `status = 'PENDING'`
so the approval queue stays small however much history accumulates.

`AssetStatusIntegrationTest` (23 cases) pins the whole surface: raising, the four blank/equal/
truncation/multiselect rules, duplicate suppression on re-completion, every transition, the
exact restoration on undo, both directions of the only-latest guard, and that voiding a sheet
moves neither the asset nor the request.

## Groundwork for later features

Two things exist in the backend with **no client yet**. They are here so the schema is settled
before the rest is built, and so the decisions behind them are not re-derived from scratch.

### GPS location field type

A class field can have data type **`location`**. Its value is a coordinate:

```json
{ "pump_position": { "type": "location", "lat": 35.6892, "lng": 51.3890,
                     "accuracy": 12.5, "capturedAt": 1786105032313 } }
```

Working today: validation (a coordinate outside WGS-84 bounds is rejected, not stored — once
written it would be indistinguishable from a real place on every screen after it), canonical
normalisation on save through `retainKnownKeys`, blank-detection for the required check, and
display in the panel. `LocationValues` is the single parser, tolerant of a bare `{lat,lng}`
object or a `"lat,lng"` string, strict about the content.

**Both capture paths are built, and they differ on purpose:**

| | How it is answered | Why |
|---|---|---|
| **PWA** | «ثبت موقعیت فعلی» reads the device position (`navigator.geolocation`), with the browser's own permission prompt — the same shape as the camera and microphone fields | There *is* a device position to read, so a typed coordinate would be an unverifiable claim about where somebody stood |
| **Web panel** | Two numeric inputs, latitude and longitude | There is no device to read — this is a supervisor correcting a reading or entering one from a survey |

The PWA capture uses `enableHighAccuracy` with `maximumAge: 0`: a cached or network-derived fix
cannot tell one pump from the next, and the point is where the operator is standing *now*.
Accuracy travels with the reading because in a plant a phone fix can be tens of metres out — the
difference between "at the pump" and "at the next pump". GPS needs no network, so this works with
the radio off. A refused permission, a timeout and an unavailable fix are reported separately,
because only the first is something the operator can act on.

**On the web the two inputs deliberately share one form field name.** The browser submits
same-named controls in document order, so the server receives exactly `[lat, lng]` and pairs them
into the canonical object — both paths therefore store the identical shape. A pair that will not
parse (one box filled, an out-of-range number) is **dropped rather than stored**, so validation
reports the field as unanswered, which is true, instead of storing half a position that looks
real. Both boxes empty is simply an unanswered field and never blocks the sheet.

> The pairing happens **before validation**, not after. Validation judges the value it is given,
> and until the two strings are paired they are neither a coordinate nor an empty field — running
> it first rejected every location field on the sheet, including the empty ones.

**Why an object and not a `"35.6892,51.3890"` string.** A string makes every consumer re-parse
and re-guess: which number came first, what the decimal separator is, whether a third value is
altitude or accuracy. It also loses precision the moment anyone formats it. Named numeric keys
survive Jackson, `jsonb`, Excel export and any future map rendering with no parsing step.
`accuracy` is carried because a coordinate without one cannot be judged — in a plant, a phone fix
can be tens of metres out, which is the difference between "at the pump" and "at the next pump".

**Display is coordinates as text, deliberately with no map link.** The panel runs on networks with
no route to the internet, so a tile server or a maps link would fail precisely where it is used,
and a dead link is worse than numbers an operator can read out over the radio.

### Template guide documents

`log_sheet_template_guides` lets a log-sheet template carry reference documents — a procedure, a
wiring diagram, a safety note, a photo of the correct valve position — that operators open from
any log sheet generated by that template.

**Table, entity and repository only.** No endpoint, no upload, no UI. Two decisions are already
encoded in the schema and worth knowing before building on it:

- **It hangs off the template, not the log sheet.** A guide describes how to do the round, so it
  belongs to the recurring definition. Per-sheet copies would mean re-uploading the same PDF for
  every generated occurrence.
- **Guides resolve live, unlike `field_definitions_snapshot`.** Correcting a procedure *should*
  reach the sheets already in progress — that is what a procedure is for. This is the opposite of
  how field definitions behave, and it is intentional. If a requirement ever needs "the guide as
  it stood when this sheet was raised", freeze it deliberately rather than assuming the current
  behaviour was an oversight.

`active` is a soft switch rather than a delete, so a superseded revision can be hidden from
operators without destroying the record of what they were told to follow at the time.

Bytes would live under `app.template-guides.storage-dir` (default `./data/template-guides`),
kept separate from the attachment root so a handful of long-lived documents are not mixed in with
many short-lived per-sheet media files — easier to back up and easier to reason about.

**When you build the upload path, extend `AttachmentSweepService`** to cover that directory too;
the [orphan sweep](#orphan-file-sweep) only walks the attachment root today.

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
- **Voided offline submissions** (`/log-sheets/{id}/void-submissions/{voidId}`) — what a late operator actually sent, with a client-side search box and a **دارای داده / بدون داده** filter. "Has data" means at least one parameter carries a value, not merely that the asset produced rows: a card whose every parameter reads «ثبت نشده» belongs under *بدون داده*
- My Inbox (`/my-inbox`) — for supervisors and operators
- Reports (`ADMIN`, `HIGH_USER`, `SUPERVISOR`) — log-sheet and asset-inventory summaries; **parameter history** (`/reports/asset-parameters`) reads **submitted log sheets**
- Audit logs (change history) — `ADMIN` only
- **Batch Excel import** (`/batch-import`) — `ADMIN` and `HIGH_USER` (see below)
- **NFC fault reports** (`/nfc-fault-reports`) — operators report an unreadable or damaged tag; see [review status](#nfc-fault-report-review-status)

### NFC fault report review status

A fault report is raised `OPEN` and stays that way until someone confirms it has been dealt
with. **Only `ADMIN` can change it**, via `POST /nfc-fault-reports/{id}/review`:

| | |
|---|---|
| Statuses | `OPEN` → `REVIEWED`, and back again |
| Permission | `POST:/nfc-fault-reports/{id}/review`, category `admin` — so the blanket `HIGH_USER` grant (everything **except** `admin`) deliberately excludes it |
| Enforced where | `NfcFaultReportService.setReviewed` throws `AccessDeniedException` unless `SecurityUtils.isAdmin()` — the UI guard is a convenience, not the control |
| Recorded | `reviewed_by_user_id` and `reviewed_at`; the list shows the reviewer's name and date next to the badge |
| Reopening | Clears both fields, so a stale reviewer never lingers on a report that is open again |

Setting a report to a state it is already in is a no-op rather than an error, which keeps a
double-submitted form harmless.

Note that the **data-quality report** counts `status = OPEN` reports as its NFC-health queue, so
marking a report reviewed removes it from that queue — that is the point of the field.

Most master data list pages still support **synchronous Excel import** on the entity page (`GET .../import-template` and `POST .../import`), with import results (success/error counts) returned via `ImportResult`/`ImportError`. For large files, prefer the **batch import** page.

### Reading a log sheet's data on the panel

Two things make a filled sheet legible at a glance:

- **An unfilled parameter says so.** It used to render as its bare unit («°C» and nothing else),
  which reads exactly like a filled row whose value happened to be invisible — so "which
  readings are actually missing" was unanswerable on a 50-asset sheet. An empty row now shows
  **«ثبت نشده»**, hides the unit, and is dimmed. The flag is `FormFieldRow.isEmpty()`, not a
  comparison of the rendered text.
- **Media stays inside its row.** Photo, video and audio attachments render as fixed 56×56
  thumbnails in a wrapping gallery, with values allowed to break mid-token
  (`overflow-wrap: anywhere`), so a row with several attachments no longer pushes past the
  table edge. Clicking a thumbnail opens the existing lightbox.

A `multiselect` reading renders as `on، IDLE` — Java's list rendering (`[on, IDLE]`) is never
shown to an operator.

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

Eight read-only pages under `/reports/*`: management overview, compliance, exceptions, data
quality, workforce, action reasons, asset parameters, and asset history. One permission —
**`GET:/reports`** — covers all eight; how *much* a viewer sees is then decided by their unit
scope.

📖 **[docs/reports.md](docs/reports.md)** is the reference: every page, every KPI, and the
exact formula behind each number — including the two denominators (compliance and self-serve)
that are easy to assume wrongly, the two different counting-window rules, and the measured
performance at one year of scale.

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
5. Progress and `heartbeat_at` are updated every **25 rows**; the UI polls `GET /batch-import/jobs` every 2.5 s for live status.
6. On completion the uploaded file is **deleted from disk**; row errors (if any) stay in `import_job_errors`.

`GET /batch-import/jobs` returns `{busy, jobs}`. `jobs` is the caller's own recent jobs; `busy` is **system-wide** — the same check that gates submission. The page needs both because it cannot derive one from the other: the submit form must re-enable the moment *anyone's* import finishes, and it must stay disabled while another user's is running. This is also why the form no longer needs a page refresh between imports.

> **Note for anyone adding a button here.** The page's row actions go through `AppCsrf.postJson` (`static/js/csrf.js`). A plain `fetch(url, {method:'POST'})` is rejected by the CSRF filter, redirected by the access-denied handler, and comes back as HTML with status **200** — the click then fails silently with nothing in the console. See gotcha #69 in [AGENTS.md](AGENTS.md).
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

### Stop, abandon and delete

| Action | When it appears | Endpoint |
|---|---|---|
| **توقف (Stop)** | `PENDING` or `RUNNING` | `POST /batch-import/jobs/{jobUuid}/cancel` |
| **رها کردن (Abandon)** | `RUNNING` with no progress reported for **2 minutes** | `POST /batch-import/jobs/{jobUuid}/abandon` |
| **حذف (Delete)** | Terminal jobs only (`COMPLETED`, `FAILED`, `CANCELLED`) | `POST /batch-import/jobs/{jobUuid}/delete` |

- Stop on a `PENDING` job is immediate; on `RUNNING` jobs cancellation is **cooperative** (takes effect between row batches, like audit retention purge).
- **Abandon exists because cooperative cancellation cannot stop a thread that is already gone.** It writes `FAILED` directly and raises the cancel flag in case the worker is merely wedged. Rows already imported stay imported — the button ends the *job*, not its effects. Delete then works normally.
- Delete removes the DB row and any stored row errors; it does not affect master data already imported. It still refuses a live job, which is why Abandon is a separate action rather than a relaxation of that guard.

All three require `POST:/batch-import` (same as starting an import) — they reuse the parent authority, so no separate permission row exists.

### When a job's worker dies

A job stranded at `RUNNING` is worse than a failed one: the one-active-import rule is **system-wide**, so a single wedged row blocks the next import for *every* user. Three mechanisms now clear one, and none of them needs a restart:

| Mechanism | Covers | Timing |
|---|---|---|
| **`ImportJobWatchdog`** (`@Scheduled`, 60 s) | A worker that died while the application kept running | After `app.import.stale-timeout-minutes` (default **15**) with no progress tick |
| **رها کردن** button | The same, when nobody wants to wait | Immediately, after a confirmation |
| **`ImportJobRecoveryRunner`** (startup) | The application itself went away, taking the worker with it | Next boot |

The watchdog reads `import_jobs.heartbeat_at`, stamped when a job starts and refreshed on every 25-row progress tick. Progress alone is not enough to judge liveness: a job that dies on its first row never advances `processed_rows` either.

A worker that turns out to be alive after being written off cannot resurrect the row — `complete()` and `fail()` both no-op on a job that already reached a terminal status, so an import somebody wrote off and restarted cannot later claim it finished cleanly.

**On startup**, `ImportJobRecoveryRunner` additionally marks interrupted `RUNNING` jobs `FAILED` and **re-queues** `PENDING` jobs whose file still exists on disk (instead of failing them).

Log prefixes and log levels for Import are documented under [Audit Trail & Logging](#audit-trail--logging). Storage path override example:

```powershell
$env:APP_IMPORT_STORAGE_PATH = "C:\Users\Hadi\Desktop\Temp\appdata"
```

### Troubleshooting — reset import job tables

**Try the UI first.** A stuck job no longer needs a database intervention: press **رها کردن** on the row, or wait for the watchdog. The SQL below is for wanting a clean slate, not for unwedging a job.

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
- The pool uses **`CallerRunsPolicy`** (queue `app.audit.async.queue-capacity`, default 2,000). A bulk import is an unbounded producer — one task per saved row — against a bounded queue, and under the previous default abort policy a full queue threw `TaskRejectedException` *into the import thread*, killing a 9,942-row import with the message `ExecutorService in active state did not accept task`. With CallerRunsPolicy the producer performs the INSERT itself instead: the import slows to the rate audit can sustain rather than dying. This is not a tuning knob — see [docs/jobs.md](docs/jobs.md#audit-writes).
- A few types are excluded from the trail (`AuditEntitySupport.EXCLUDED_TYPES`): `AuditLog`, `LogSheetActionLog`, `LogSheetEntry`, `LogSheetVoidSubmission`, and — since the fix above — `ImportJob` / `ImportJobError`. The import ones are excluded for correctness, not tidiness: a job's own bookkeeping was 57% of the entire audit trail on live data, and the queue it filled was what then rejected the write of that job's final status. **Entities an import creates (assets, locations, …) are still fully audited.**
- `AuditRetentionService` supports **batch purging** of records older than the configured retention period (`app_settings.audit.retention.days`), with mid-run cancellation support; execution happens on a dedicated thread and progress is visible/controllable from the "Settings" panel.
- Audit history can be viewed from the "Audit Logs" page (`/audit-logs`).
- **Do not disable** this DB audit for production accountability — it is separate from application file logging (below).

### Application logging (files under `app.log.path`)

**Four files, because four different people ask four different questions.**

| File | Answers | Volume | Retention |
|---|---|---|---|
| `app.log` | "what happened around 14:32?" — the running narrative | high | 90 days |
| `business.log` | "what did the system **do**?" — imports, scheduler runs, sheet events | tens of lines a day | 90 days |
| `audit.log` | "**who** changed this row?" — one line per audited change | tens of thousands a day | 180 days |
| `error.log` | "what broke, and what caused it?" — errors only, root cause first | rare | 180 days |

> **`audit.log` is new, and splitting it out was the biggest single readability win.** Audit used to be written into `business.log` at **one line per changed field**. Measured on the live database that was **42,498 audit lines against roughly 40 real business events** — 9.8 MB against 708 KB of `app.log`. Anyone opening `business.log` to find out what the system had done was reading a file that was 99.8% something else. See [`AuditTrailLogger`](src/main/java/com/hnp/backendofflinefirst/logging/AuditTrailLogger.java).

#### The line format

```
2026-08-14T18:49:10.827+03:30 WARN  [http-nio-…-exec-5] [32fb8d26-740] admin@10.0.2.15 POST /batch-import/jobs/X/delete c.h.b.security.WebAccessDeniedHandler - Access denied on …
└─ when, ISO-8601 +03:30      │     └─ thread          └─ correlation │  └─ client IP  └─ what they asked for       └─ source
                              level                        id          user
```

- **Timestamps are ISO-8601 in Asia/Tehran with the offset.** Local time so an operator reads the same clock as the wall; the explicit offset keeps the value unambiguous once it reaches a log store, and lines it up with the database's epoch-millis columns.
- **`clientIp` is on every line** (`X-Forwarded-For` first entry when behind a proxy) — it is the field you want during a security question and it used to be captured but never printed.
- **`%thread` is not redundant with the correlation id**, which is the tempting assumption. `AsyncConfig.wrapWithMdc` copies the whole MDC onto async workers, so a line written by the import pool minutes after the upload still carries that request's correlation id, user and URI — `[import-1] [3e8f33d6-b2b] admin POST /batch-import … [IMPORT_DONE] rowsRead=3000`. Without the thread, nothing distinguishes it from a line on the HTTP thread. Scheduler lines have no correlation id at all, so there it is the only handle.

#### Text or JSON

Default is the human-readable layout above. For shipping to Filebeat → Logstash/Elasticsearch, activate the **`json-logs`** Spring profile:

```bash
SPRING_PROFILES_ACTIVE=json-logs
```

Same four files under the same names — only the encoding changes, so nothing downstream has to be reconfigured. Every MDC value becomes a real field (`correlationId`, `user`, `clientIp`, `method`, `uri`, `errorId`, `failedAt`), which is what makes filtering in Kibana work without grok patterns that break on multi-line stack traces and Persian message text. Profiles compose: `SPRING_PROFILES_ACTIVE=prod,json-logs`.

> JSON is selected by a Spring profile rather than a plain property because logback's own `<if condition="…">` needs the Janino dependency, its `condition` attribute is deprecated in logback 1.5, and nesting `<if>` inside an `<appender>` warns — together that printed ten logback complaints on every boot, which rather defeats a change about readability. `<springProfile>` is Spring Boot's own extension and is silent.

#### Reading `error.log`

Each error is one visually separated block, so there is never a question about which stack trace belongs to which failure:

```
──────────────────────────────────────────────────────────────────────────────
2026-08-14T18:45:29.086+03:30 ERROR errorId=409a101 [53961cc9-c2c] admin@10.0.2.15 POST /batch-import/jobs/AAA/delete thread=http-nio-…-exec-6
  failedAt=ImportJobService.delete
  c.h.b.service.importjob.ImportJobService - !!! [SVC] ImportJobService.delete | 4ms | errorId=409a101 | IllegalArgumentException | Import job not found.
java.lang.IllegalArgumentException: Import job not found.
	at …
```

| Element | Why |
|---|---|
| The rule + blank line | The old file had **16 message lines against 904 stack-frame lines**; the thing you were looking for was invisible between walls of `at org.springframework…`. |
| `errorId` | Ties this entry to the propagation lines the same failure leaves in `app.log`, and gives a person something to quote. Two requests failing in the same second cannot be told apart by timestamp. |
| `failedAt` | The innermost method that actually threw. |
| **Root cause first** (`%rEx`) | The real problem is the first line, instead of forty frames of proxy and filter wrapping above it. Depth-capped at 30. |

**One exception is logged once.** As a failure travels REPO → SVC → WEB it passes the logging advice at each layer; only the innermost writes the stack trace, and the outer layers add a one-line `propagating from …`. That rule is keyed on the **exception's identity** — it used to be a per-request boolean, which meant a *second, unrelated* error in the same request was mistaken for a propagation of the first: logged at WARN, without a trace, and therefore never written to `error.log` at all. The second failure simply vanished.

| Channel | Level (default) | What it contains |
|---|---|---|
| **WEB / API** (`LoggingAspect`) | **INFO** | Controller entry/exit (request boundary) |
| **SVC / REPO** (`LoggingAspect`) | **DEBUG** | Method entry/exit + arg/result serialization — quiet during Import/bulk |
| **Business** (`BusinessEventLogger` → `business.log`) | **INFO** | Import start/finish summaries, scheduler runs, important ops |
| **Audit** (`AuditTrailLogger` → `audit.log`) | **INFO** | One summary line per audited change: action, entity, id, actor, field names |
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
- **`RequestMdcFilter`** runs *ahead* of Spring Security and adds the correlation id, method, URI and client IP — deliberately early, so security's own log lines (a rejected JWT, a CSRF failure, a locked account) already carry a correlation id.
- **`UserMdcFilter`** adds the username, and is registered **inside** the Spring Security chain (right after the context is restored, and after the JWT filter on the API chain). That placement matters: a filter merely ordered *after* the chain only wraps what comes downstream, and the denials worth logging are raised *inside* it — which is why "Access denied" used to report `anonymous` for a user who was plainly logged in.
- `SecurityAuditLogger` records security-related events (login/logout, unauthorized access attempts).
- **Rotation is size *and* time**: `maxFileSize` (default 100 MB, `APP_LOG_MAX_FILE_SIZE`) plus daily rolling. Time alone caps only the archive — the *current* day's file could otherwise grow until it filled the disk the app also writes attachments to.

| Property | Env var | Default |
|---|---|---|
| `app.log.path` | `APP_LOG_PATH` | `ProdLog` |
| `app.log.max-file-size` | `APP_LOG_MAX_FILE_SIZE` | `100MB` |
| *(JSON output)* | `SPRING_PROFILES_ACTIVE=json-logs` | text |

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

### Shipping logs to Filebeat / Logstash

Logs are human-readable text by default. To emit JSON instead, start with the `json-logs` profile:

```bash
SPRING_PROFILES_ACTIVE=json-logs java -jar target/backend-offline-first-0.0.1-SNAPSHOT.jar
```

The same four files (`app.log`, `business.log`, `audit.log`, `error.log`) are written under `app.log.path` with the same names — only the encoding changes, so a Filebeat input pointed at that directory needs no path changes when you switch. Every MDC value becomes a real field (`correlationId`, `user`, `clientIp`, `method`, `uri`, `errorId`, `failedAt`, `thread_name`), and stack traces arrive as a single `stack_trace` string rather than as multi-line events Filebeat has to stitch back together. A minimal input:

```yaml
filebeat.inputs:
  - type: filestream
    paths: [ "/opt/app/ProdLog/*.log" ]
    parsers:
      - ndjson:
          target: ""
          overwrite_keys: true
```

Profiles compose, so a production profile keeps working: `SPRING_PROFILES_ACTIVE=prod,json-logs`.

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
