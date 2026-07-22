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
  - [Authentication & Authorization (RBAC)](#authentication--authorization-rbac)
  - [Active Directory (LDAP) authentication](#active-directory-ldap-authentication)
  - [Default system roles (5)](#default-system-roles-5)
  - [Permission categories at a glance](#permission-categories-at-a-glance)
  - [Extra service-layer rules](#extra-service-layer-rules-beyond-endpoint-permissions)
- [Log-Sheet Lifecycle](#log-sheet-lifecycle)
- [Project Structure](#project-structure)
- [Prerequisites](#prerequisites)
- [Getting Started](#getting-started)
- [Configuration (application.properties)](#configuration-applicationproperties)
- [Mobile API (Offline Sync)](#mobile-api-offline-sync)
- [Web Admin Panel](#web-admin-panel)
- [Batch Excel Import (async)](#batch-excel-import-async)
- [Audit Trail & Logging](#audit-trail--logging)
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
- ✅ **Log-sheet templates** with manual or scheduled generation based on a recurrence interval (hourly/daily/weekly/monthly).
- ✅ **Automatic scheduler** that generates due log sheets and expires ones whose completion window has passed.
- ✅ **Work assignment model**: shared unit pool, claim/release by operators, assign/reassign by supervisors, supervisor takeover.
- ✅ **Unit-scoped RBAC** for supervisor/operator roles, restricting visibility and actions to their own operational units.
- ✅ **Fine-grained per-endpoint permission system** (`METHOD:path`) with 5 default system roles: `ADMIN`, `HIGH_USER`, `SUPERVISOR`, `SENIOR_OPERATOR`, `OPERATOR`.
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

The initial schema lives in `src/main/resources/db/migration/V1__initial_schema.sql` (Flyway) and includes the following table groups:

### Users & Organization
- `users` — application users (admin panel login and/or field operations). Each user has an `auth_type`: `LOCAL` (BCrypt only), `ACTIVE_DIRECTORY` (LDAP bind at login), or `HYBRID` (local password first, then AD). Roles and permissions always come from the application database — AD is used for password verification only.
- `operational_units` — hierarchical operational units (org structure).
- `unit_supervisors` / `unit_operators` — many-to-many links between units and supervising/operator users.

### RBAC
- `permissions` — one row per HTTP endpoint, code = `METHOD:path`.
- `roles` — system/custom roles.
- `role_permissions`, `user_roles` — many-to-many join tables.

### Settings
- `app_settings` — key/value application configuration (e.g. Excel export row limit, audit retention days).

### Master Data (hierarchical)
- `locations` — physical/logical plant areas (tree via `parent_id`).
- `plant_systems` — engineering systems (tree via `parent_id`; root systems also carry `location_id`).
- `main_functions` — functional groupings (tree via `parent_id`; roots attach to a **system** or **location**).
- `sub_functions` — granular equipment/function groups (tree via `parent_id`; roots attach to a **main function**, **system**, or **location**). Each sub-function has a physical **tag** used for NFC fallback.
- `asset_classes` + `field_definitions` — dynamic form schema per asset class.
- `asset_entries` — physical assets; linked to exactly one `sub_function_id` (placement ancestry is read from that sub-function's denormalized fields). Unique on `asset_code` / `nfc_tag_id`; Flyway **V3** adds `ux_asset_entries_asset_code_lower` on `LOWER(asset_code)` for case-insensitive uniqueness and faster IgnoreCase lookups.

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

**Excel import parent priority** (first non-empty column wins):

| Sheet | Columns (header row) |
|---|---|
| locations | `code`, `name`, `parentCode`, `unitCode` |
| plant-systems | `code`, `name`, `parentSystemCode`, `locationCode` |
| main-functions | `code`, `name`, `parentMainFunctionCode`, `systemCode`, `locationCode` |
| sub-functions | `code`, `name`, `tag`, `parentSubFunctionCode`, `mainFunctionCode`, `systemCode`, `locationCode` |

**Tests:** `AssetHierarchyServiceTest` (unit) and `AssetHierarchyCascadeIntegrationTest` (PostgreSQL + Flyway) cover nesting, cascade, scope walks, cycle validation, FK delete guards, and asset sync touches.

> **Upgrading an existing database** created before nested main/sub functions: add nullable `parent_id` columns + FKs/indexes on `main_functions` and `sub_functions` (see `V1__initial_schema.sql`), or reset Flyway on a fresh database.

### Operational Data
- `data_records` — simple inspection records submitted from mobile (upserted via `local_id`).
- `log_sheet_templates` — templates for round log-sheet inspections (manual or scheduled).
- `log_sheets` + `log_sheet_entries` — generated log sheets and their entries.
- `log_sheet_action_log` — immutable audit trail of lifecycle actions with an idempotency key (`client_action_id`).
- `log_sheet_void_submissions` — late offline submissions that arrived after someone else already completed the sheet (voided but retained for the record).

### Audit
- `audit_log` — generic field-level change history for master/operational entities, stored as JSONB.

### Batch import jobs
- `import_jobs` — async Excel import job metadata (status, progress, file path on disk).
- `import_job_errors` — row-level errors per job (up to `app.import.max-stored-errors` rows).

> **Key design note:** every primary key is an auto-incrementing `BIGINT IDENTITY`. Business/natural keys (`code`, `local_id`, `nfc_tag_id`, `client_action_id`, `username`) remain `VARCHAR` with unique constraints, preserving both client-side idempotency and simple database relationships.

---

## Authentication & Authorization (RBAC)

- Authentication supports **local BCrypt**, **Active Directory (LDAP bind)**, or **hybrid** per user (`users.auth_type`). See [Active Directory (LDAP) authentication](#active-directory-ldap-authentication).
- Authentication is **session-based with form login** (`WebSecurityConfig`) for the web panel; mobile API uses **JWT** (`POST /api/auth/login`).
- Permissions are defined as **one authority per endpoint**: `PermissionCodes.code(method, path)`, e.g. `GET:/locations` or `POST:/log-sheets/{id}/complete`.
- Permission checks are enforced on controllers with `@PreAuthorize("hasAuthority('...')")`; `@EnableMethodSecurity` enables this mechanism.
- Permissions are grouped into categories: `general`, `admin`, `organization`, `master-data`, `operational`, `reports`, `api` (see `V1__initial_schema.sql`).
- **Unit-scoped access control** is additionally enforced in the service layer via `OperationalUnitScopeService` (supervisor/operator ↔ operational-unit assignments in `unit_supervisors` / `unit_operators`).
- Users with unit-scoped roles (`SUPERVISOR`, `SENIOR_OPERATOR`, `OPERATOR`) are redirected to **My Inbox** (`/my-inbox`) after login; `ADMIN` and `HIGH_USER` land on the dashboard.
- Mobile REST APIs (`/api/**`) are exempt from CSRF; authentication/access errors are returned as JSON via `ApiAuthenticationEntryPoint` / `ApiAccessDeniedHandler`.

### Default system roles (5)

| Code | Persian name | Scope | Summary |
|---|---|---|---|
| `ADMIN` | مدیر سیستم | Global | Full access to every endpoint and every operational unit |
| `HIGH_USER` | کاربر ارشد | Unit-aware for templates | Everything except the `admin` category; may edit/delete log-sheet templates only within units they supervise |
| `SUPERVISOR` | سرپرست | Own units (+ sub-units) | Log-sheet supervision and mobile/web field work; may **create** templates but **not** edit or delete them |
| `SENIOR_OPERATOR` | اپراتور ارشد | Own units (+ sub-units) | Like `OPERATOR`, plus web-based log-sheet completion |
| `OPERATOR` | اپراتور | Own units (+ sub-units) | Claim/release and complete assigned work (mobile app; no web fill form) |

> Custom roles can be created in the panel, but the five roles above are **system roles** and cannot be deleted.

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
  - ✅ `operational` — log sheets (full lifecycle), my inbox, reports, records list/detail
  - ✅ `api` — master-data sync, log-sheet batch/inbox/claim/release/assign/reassign, NFC lookup, legacy records batch
  - ❌ `admin` — users, roles, settings, audit retention UI, audit log viewer
  - ✅ Batch Excel import UI (`GET:/batch-import`, `POST:/batch-import`, `GET:/batch-import/jobs`) — granted explicitly (category is `admin`, but `HIGH_USER` receives these endpoints)
- **Service-layer rules (log-sheet templates):**
  - Sees only templates whose `operational_unit_id` is in a unit they **supervise** (including sub-units).
  - May create, edit, and delete templates within that supervised scope.
- **Log sheets:** not filtered by unit assignment in the same way as operators; list visibility follows `LogSheetAccessService` (non–unit-scoped for `HIGH_USER`).
- **Typical use:** plant/department lead who manages master data and templates for their area but not global user administration.

### `SUPERVISOR` — سرپرست

- **Web panel permissions:**
  - ✅ Log sheets: list, detail, manual generate from template, claim, release, assign, reassign, extend deadline, takeover, web fill, web complete
  - ✅ My inbox (`GET:/my-inbox`)
  - ✅ Reports (`GET:/reports`)
  - ✅ Log-sheet templates: **list + create only** (`GET:/log-sheet-templates`, `POST:/log-sheet-templates`)
  - ❌ Log-sheet templates: **no edit or delete** (`POST:/log-sheet-templates/{id}`, `POST:/log-sheet-templates/{id}/delete`)
  - ❌ Dashboard (`GET:/`), users, roles, settings, audit logs
  - ❌ Master data CRUD (locations, assets, asset classes, etc.) — not in default permission set
  - ❌ Operational units management
  - ❌ Records pages (legacy inspection records)
- **Mobile API:**
  - ✅ `GET /api/master-data`, `GET /api/log-sheets/inbox`, `POST /api/log-sheets/batch`
  - ✅ Claim, release, assign, reassign on log sheets
  - ✅ `GET /api/operational-units/{unitId}/operators`, `GET /api/asset-entries/nfc/{nfcTagId}`
- **Service-layer rules:**
  - Log sheets and template lists are limited to units they **supervise** (and descendant units).
  - Supervisor-only actions (assign, reassign, release of `SUPERVISOR_ASSIGNED` sheets, takeover, extend) require `OperationalUnitScopeService.isSupervisorOf(user, unit)`.
  - May create templates only for supervised units; cannot edit/delete existing templates (enforced in `LogSheetTemplateService` even if permissions were customized).
- **Typical use:** shift/line supervisor who runs daily rounds, assigns work, and defines new templates but leaves template maintenance to senior staff.

### `SENIOR_OPERATOR` — اپراتور ارشد

- **Permissions:** `OPERATOR` set **plus** web completion:
  - ✅ `GET:/log-sheets/{id}/fill`, `POST:/log-sheets/{id}/complete`
- **Also has:** log-sheet list/detail, claim, release, my inbox, mobile API (master data, inbox, batch sync, claim/release, NFC).
- **Does not have:** generate, assign, reassign, extend, takeover, reports, templates, master data, dashboard.
- **Operational scope:** log sheets in units where the user is assigned as **operator** (or supervisor, if both links exist), including sub-units.
- **Typical use:** experienced operator who may complete inspections in the **web UI** as well as the mobile app.

### `OPERATOR` — اپراتور

- **Web panel permissions:**
  - ✅ Log sheets: list, detail, claim, release
  - ✅ My inbox
  - ❌ Web fill/complete (`/log-sheets/{id}/fill`) — mobile completion only
  - ❌ Supervisor actions (generate, assign, reassign, extend, takeover)
  - ❌ Templates, master data, reports, dashboard, admin pages
- **Mobile API:**
  - ✅ `GET /api/master-data`, `GET /api/log-sheets/inbox`, `POST /api/log-sheets/batch`
  - ✅ Claim, release
  - ✅ NFC asset lookup
  - ❌ Assign / reassign (supervisor-only)
- **Operational scope:** units where the user is assigned as **operator** (plus sub-units).
- **Typical use:** field operator performing NFC-based round inspections on a phone/tablet.

### Permission categories at a glance

| Category | Examples | Default roles with access |
|---|---|---|
| `general` | Dashboard `GET:/` | `ADMIN`, `HIGH_USER` |
| `admin` | Users, roles, settings, audit logs, **batch Excel import** | `ADMIN` (+ batch import for `HIGH_USER`) |
| `organization` | Operational units, staff import | `ADMIN`, `HIGH_USER` |
| `master-data` | Locations → assets, log-sheet templates | `ADMIN`, `HIGH_USER` (+ template **view/create** for `SUPERVISOR`) |
| `operational` | Log sheets, my inbox, records | Role-specific (see above) |
| `reports` | `GET:/reports` | `ADMIN`, `HIGH_USER`, `SUPERVISOR` |
| `api` | `/api/master-data`, log-sheet sync, NFC | All field roles; exact endpoints per role |

### Extra service-layer rules (beyond endpoint permissions)

| Area | Rule |
|---|---|
| Log-sheet list/detail | `OPERATOR` / `SENIOR_OPERATOR` / `SUPERVISOR`: filtered to accessible units; `ADMIN` / `HIGH_USER`: global |
| Log-sheet assign / reassign / takeover / extend | Caller must be supervisor of the sheet's unit (or `ADMIN`) |
| Log-sheet template list | `ADMIN`: all units; `HIGH_USER` / `SUPERVISOR`: supervised units only |
| Log-sheet template edit/delete | `ADMIN` / `HIGH_USER` only, within supervised units for `HIGH_USER` |
| Log-sheet template create | `ADMIN`, `HIGH_USER`, `SUPERVISOR` — unit must be in supervisor scope (except `ADMIN`) |
| Web completion | `SENIOR_OPERATOR`, `SUPERVISOR`, `HIGH_USER`, `ADMIN` (not plain `OPERATOR`) |

The canonical permission matrix is defined in `src/main/resources/db/migration/V1__initial_schema.sql` (`permissions` + `role_permissions` inserts). Custom roles can be composed in the **Roles** page by toggling individual endpoint permissions.

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

A log sheet is a unit of work generated from a template — manually or on schedule — and progresses through the following states (`LogSheetStatus`):

```
PENDING  ──►  ASSIGNED  ──►  IN_PROGRESS  ──►  SUBMITTED  (terminal)
   │              │               │
   └──────────────┴───────────────┴────────► EXPIRED   (terminal, if past due)
                                              CANCELLED (terminal, manual cancel)
```

- **PENDING**: generated, sitting in the unit pool, no assignee.
- **ASSIGNED**: a supervisor assigned it to an operator (in their inbox), not yet started.
- **IN_PROGRESS**: an operator claimed/started it.
- **SUBMITTED / EXPIRED / CANCELLED**: terminal, irreversible states.

### Assignment Type (`AssignmentType`)
- `SELF_CLAIMED` — an operator picked it up themselves; only that operator may return it to the pool.
- `SUPERVISOR_ASSIGNED` — a supervisor pushed it to an operator's inbox; only a supervisor of that unit may release or reassign it.

### Scheduler (`LogSheetScheduler`)
Two independent periodic jobs (intervals configurable via `application.properties`):

1. **`generateDueSheets`** — finds active `SCHEDULED` templates whose `next_run_at` is due and generates log sheets. Catch-up after an outage is controlled by `app.scheduler.log-sheet-max-backfill` (**per template**) — see below.
2. **`expireOverdueSheets`** — marks open log sheets (`PENDING`/`ASSIGNED`/`IN_PROGRESS`) that are past their `due_at` as `EXPIRED`; if a saved draft exists, it finalizes the draft instead of expiring it.

### Scheduler catch-up / max backfill

Config: `app.scheduler.log-sheet-max-backfill` / env `APP_SCHEDULER_LOG_SHEET_MAX_BACKFILL` (default `500`).

Applied **per template** each time that template is due — not as a global limit across all templates.

How the scheduler decides whether more than one occurrence is “due”: it walks recurrence boundaries from `next_run_at`. If the **next** boundary after the current one is also already `<= now`, there is a multi-item backlog. It does **not** use a wall-clock grace like “only a few minutes late”.

| Value | Behavior |
|---|---|
| **`0`** | **One due → create it. Multiple due → create none.** See examples below. |
| **`N > 0`** | Create up to **N** missed occurrences **oldest-first**, then skip any remainder and jump `next_run_at` to the next future boundary. |
| Default `500` | Same as `N > 0` with a large safety cap. |

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
├── db/migration/    # Flyway scripts (V1 schema, V2…, V3 asset_code lower unique index)
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

Flyway automatically builds the database schema on startup (`spring.flyway.enabled=true`).

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
| `app.auth.jwt.secret` | `APP_AUTH_JWT_SECRET` | `dev-only-change-me-use-long-random-secret-key!!` |
| `app.auth.ldap.enabled` | `APP_AUTH_LDAP_ENABLED` | `true` |
| `app.auth.ldap.url` | `APP_AUTH_LDAP_URL` | `ldaps://dc.site.hnp:636` |
| `app.auth.ldap.domain` | `APP_AUTH_LDAP_DOMAIN` | `site.hnp` |
| `app.auth.ldap.timeout-ms` | `APP_AUTH_LDAP_TIMEOUT_MS` | `5000` |
| `app.auth.ldap.trust-self-signed` | `APP_AUTH_LDAP_TRUST_SELF_SIGNED` | `true` |
| `app.scheduler.log-sheet-gen-ms` | `APP_SCHEDULER_LOG_SHEET_GEN_MS` | `60000` |
| `app.scheduler.log-sheet-expiry-ms` | `APP_SCHEDULER_LOG_SHEET_EXPIRY_MS` | `60000` |
| `app.scheduler.log-sheet-max-backfill` | `APP_SCHEDULER_LOG_SHEET_MAX_BACKFILL` | `500` (per template; `0` = skip backlog & resume from next future run — see Scheduler catch-up) |
| `app.log.path` | `APP_LOG_PATH` | `ProdLog` |
| `app.audit.enabled` | `APP_AUDIT_ENABLED` | `true` |
| `app.audit.async.core-pool-size` | `APP_AUDIT_ASYNC_CORE_POOL_SIZE` | `2` |
| `app.audit.async.max-pool-size` | `APP_AUDIT_ASYNC_MAX_POOL_SIZE` | `4` |
| `app.audit.retention.batch-size` | `APP_AUDIT_RETENTION_BATCH_SIZE` | `5000` |
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
| `POST` | `/api/auth/login` | Log in and receive the user's roles/permissions |
| `GET`  | `/api/health` | Service health check (no auth required) |
| `GET`  | `/api/bootstrap` | **Preferred** — full mobile bootstrap payload (master data + settings); replaces legacy `/api/master-data` |
| `GET`  | `/api/master-data?since={ts}` | Legacy master-data sync (delegates to bootstrap); prefer `/api/bootstrap` for new clients |
| `POST` | `/api/records/batch` | Submit a batch of inspection records (upserted via `local_id`) |
| `GET`  | `/api/log-sheets/inbox` | Fetch the inbox: assigned log sheets + the unit's available pool |
| `POST` | `/api/log-sheets/{id}/claim` | Claim a log sheet from the pool |
| `POST` | `/api/log-sheets/{id}/release` | Release a log sheet back to the pool |
| `POST` | `/api/log-sheets/batch` | Submit a batch of completed log sheets (offline sync) |
| `GET`  | `/api/log-sheets/{id}/bundle` | Full offline bundle for one log sheet (entries + scoped hierarchy context) |
| `GET`  | `/api/asset-entries/nfc/{nfcTagId}` | Look up an asset by its NFC tag |

### Delta Sync

`GET /api/bootstrap` (and legacy `GET /api/master-data`) accept an optional `since` (timestamp) parameter; when provided, only records changed since that time (`updated_at`) are returned — drastically reducing the payload size on subsequent syncs.

### Idempotency

- `data_records.local_id` and `log_sheets.local_id` are client-side unique keys; resubmitting the same record (e.g., due to a dropped connection mid-sync) results in an upsert, not a duplicate.
- `log_sheet_action_log.client_action_id` serves the same purpose for lifecycle actions (claim/release/complete, etc.) performed offline.

---

## Web Admin Panel

The `web/*WebController.java` controllers serve the following Thymeleaf pages (each guarded by its own `GET:/{path}` permission):

- Dashboard (`/`)
- Users, roles, settings (admin section)
- Operational units (with supervisor/operator Excel import/export)
- Master data: locations, plant systems, main/sub functions (each supports **nested parents** in the panel and Excel), asset classes and field definitions, asset entries
- Log-sheet templates (including a scoped asset preview; edit/delete for `ADMIN` / `HIGH_USER` only)
- Log sheets, web-based log-sheet completion (`/log-sheets/{id}/fill`) — `SENIOR_OPERATOR` and above
- My Inbox (`/my-inbox`) — for supervisors and operators
- Reports (`ADMIN`, `HIGH_USER`, `SUPERVISOR`)
- Audit logs (change history) — `ADMIN` only
- **Batch Excel import** (`/batch-import`) — `ADMIN` and `HIGH_USER` (see below)

Most master data list pages still support **synchronous Excel import** on the entity page (`GET .../import-template` and `POST .../import`), with import results (success/error counts) returned via `ImportResult`/`ImportError`. For large files, prefer the **batch import** page.

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

The project has extensive test coverage:

- **Unit tests**: `service/*Test.java` — business logic (log-sheet lifecycle, assignment, operational unit scope, Excel import, **`AssetHierarchyService`** placement trees, etc.)
- **Security tests**: `security/EndpointSecurityTest.java` — verifies endpoint permissions.
- **Integration tests**:
  - `integration/ApiIntegrationTest.java` — REST API flows
  - `integration/AssetHierarchyCascadeIntegrationTest.java` — end-to-end hierarchy cascade, scope, FK constraints, and asset sync touches against **Testcontainers PostgreSQL**
  - `integration/MobileBundleApiIntegrationTest.java` — bootstrap/bundle APIs
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
