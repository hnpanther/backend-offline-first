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
- [Authentication & Authorization (RBAC)](#authentication--authorization-rbac)
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
- ✅ **Hierarchical master data management**: Operational Unit → Location → Plant System → Main Function → Sub Function → Asset.
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
- **Service** (`service/`): Core business logic (log-sheet lifecycle, import/export, operational unit scope resolution, etc.).
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
- `users` — application users (admin panel login and/or field operations).
- `operational_units` — hierarchical operational units (org structure).
- `unit_supervisors` / `unit_operators` — many-to-many links between units and supervising/operator users.

### RBAC
- `permissions` — one row per HTTP endpoint, code = `METHOD:path`.
- `roles` — system/custom roles.
- `role_permissions`, `user_roles` — many-to-many join tables.

### Settings
- `app_settings` — key/value application configuration (e.g. Excel export row limit, audit retention days).

### Master Data (hierarchical)
- `locations` → `plant_systems` → `main_functions` → `sub_functions`
- `asset_classes` + `field_definitions` (dynamic form schema per asset class)
- `asset_entries` — physical assets tagged with a unique NFC tag.

### Operational Data
- `data_records` — simple inspection records submitted from mobile (upserted via `local_id`).
- `log_sheet_templates` — templates for round log-sheet inspections (manual or scheduled).
- `log_sheets` + `log_sheet_entries` — generated log sheets and their entries.
- `log_sheet_action_log` — immutable audit trail of lifecycle actions with an idempotency key (`client_action_id`).
- `log_sheet_void_submissions` — late offline submissions that arrived after someone else already completed the sheet (voided but retained for the record).

### Audit
- `audit_log` — generic field-level change history for master/operational entities, stored as JSONB.

> **Key design note:** every primary key is an auto-incrementing `BIGINT IDENTITY`. Business/natural keys (`code`, `local_id`, `nfc_tag_id`, `client_action_id`, `username`) remain `VARCHAR` with unique constraints, preserving both client-side idempotency and simple database relationships.

---

## Authentication & Authorization (RBAC)

- Authentication is **session-based with form login** (`WebSecurityConfig`) using **BCrypt** password hashing.
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
- **Web panel:** dashboard, users, roles, settings, audit logs, operational units, all master data, log-sheet templates (full CRUD), log sheets, reports, records (if granted to custom roles; not in default supervisor/operator sets).
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
| `admin` | Users, roles, settings, audit logs | `ADMIN` only |
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

1. **`generateDueSheets`** — finds active `SCHEDULED` templates whose `next_run_at` is due and generates log sheets (with a max-backfill cap to prevent a data burst after a long server outage).
2. **`expireOverdueSheets`** — marks open log sheets (`PENDING`/`ASSIGNED`/`IN_PROGRESS`) that are past their `due_at` as `EXPIRED`; if a saved draft exists, it finalizes the draft instead of expiring it.

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
├── service/         # Business logic
├── ui/              # Response/view helpers (error localization, etc.)
├── util/            # Utilities (Jalali dates, Excel, reference labels, etc.)
└── web/             # Thymeleaf admin panel controllers

src/main/resources/
├── application.properties
├── logback-spring.xml
├── db/migration/    # Flyway scripts (V1__initial_schema.sql)
├── static/          # Panel CSS/JS/fonts
└── templates/       # Thymeleaf views (users, roles, assets, log sheets, etc.)

src/test/java/com/hnp/backendofflinefirst/
├── controller/, domain/, security/, service/, ui/, util/
├── integration/     # End-to-end integration tests (ApiIntegrationTest)
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

Default connection settings (`application.properties`):

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

| Key | Description | Default |
|---|---|---|
| `spring.datasource.url` | PostgreSQL connection URL | `jdbc:postgresql://localhost:5432/offline_first_db` |
| `spring.datasource.username` / `password` | Database credentials | `postgres` / `postgres` |
| `spring.jpa.hibernate.ddl-auto` | Schema sync mode (`validate` only; schema is built by Flyway) | `validate` |
| `spring.flyway.locations` | Migration scripts location | `classpath:db/migration` |
| `server.port` | Server port | `8081` |
| `app.scheduler.log-sheet-gen-ms` | Log-sheet generation job interval (ms) | `60000` |
| `app.scheduler.log-sheet-expiry-ms` | Log-sheet expiry job interval (ms) | `60000` |
| `app.scheduler.log-sheet-max-backfill` | Max missed occurrences to back-fill per template after an outage | `500` |
| `app.log.path` | Log file output path | `ProdLog` |
| `app.audit.enabled` | Enable/disable audit trail writes | `true` |
| `app.audit.async.core-pool-size` / `max-pool-size` | Async audit write thread pool size | `2` / `4` |
| `app.audit.retention.batch-size` | Rows purged per batch during retention cleanup | `5000` |

---

## Mobile API (Offline Sync)

All endpoints below require an authenticated session (Spring Security) and are protected via `@PreAuthorize`. Paths under `/api/**` are exempt from CSRF.

| Method | Path | Description |
|---|---|---|
| `POST` | `/api/auth/login` | Log in and receive the user's roles/permissions |
| `GET`  | `/api/health` | Service health check (no auth required) |
| `GET`  | `/api/master-data?since={ts}` | Fetch master data (full or delta based on `updated_at`) |
| `POST` | `/api/records/batch` | Submit a batch of inspection records (upserted via `local_id`) |
| `GET`  | `/api/log-sheets/inbox` | Fetch the inbox: assigned log sheets + the unit's available pool |
| `POST` | `/api/log-sheets/{id}/claim` | Claim a log sheet from the pool |
| `POST` | `/api/log-sheets/{id}/release` | Release a log sheet back to the pool |
| `POST` | `/api/log-sheets/batch` | Submit a batch of completed log sheets (offline sync) |
| `GET`  | `/api/asset-entries/nfc/{nfcTagId}` | Look up an asset by its NFC tag |

### Delta Sync

`GET /api/master-data` accepts an optional `since` (timestamp) parameter; when provided, only records changed since that time (`updated_at`) are returned — drastically reducing the payload size on subsequent syncs.

### Idempotency

- `data_records.local_id` and `log_sheets.local_id` are client-side unique keys; resubmitting the same record (e.g., due to a dropped connection mid-sync) results in an upsert, not a duplicate.
- `log_sheet_action_log.client_action_id` serves the same purpose for lifecycle actions (claim/release/complete, etc.) performed offline.

---

## Web Admin Panel

The `web/*WebController.java` controllers serve the following Thymeleaf pages (each guarded by its own `GET:/{path}` permission):

- Dashboard (`/`)
- Users, roles, settings (admin section)
- Operational units (with supervisor/operator Excel import/export)
- Master data: locations, plant systems, main/sub functions, asset classes and field definitions, asset entries
- Log-sheet templates (including a scoped asset preview; edit/delete for `ADMIN` / `HIGH_USER` only)
- Log sheets, web-based log-sheet completion (`/log-sheets/{id}/fill`) — `SENIOR_OPERATOR` and above
- My Inbox (`/my-inbox`) — for supervisors and operators
- Reports (`ADMIN`, `HIGH_USER`, `SUPERVISOR`)
- Audit logs (change history) — `ADMIN` only

Most master data list pages support **Excel import with a downloadable template** (`GET .../import-template` and `POST .../import`), with import results (success/error counts) returned via `ImportResult`/`ImportError`.

---

## Audit Trail & Logging

### Audit Trail (entity changes)
- `RepositoryAuditAspect` automatically (via AOP) intercepts repository save/delete operations and records field-level changes in the `audit_log` table (JSONB).
- Audit writes are **asynchronous** (`AsyncConfig` + `AuditWriteService`) to avoid adding latency to the main request path.
- `AuditRetentionService` supports **batch purging** of records older than the configured retention period (`app_settings.audit.retention.days`), with mid-run cancellation support; execution happens on a dedicated thread and progress is visible/controllable from the "Settings" panel.
- Audit history can be viewed from the "Audit Logs" page (`/audit-logs`).

### Logging
- `LoggingAspect` logs entry/exit and errors for key methods.
- `BusinessEventLogger` writes important business events (log-sheet generation/completion/expiry, imports, scheduler runs, audit) to a dedicated `com.hnp.backendofflinefirst.business` logger (configured in `logback-spring.xml`, output path from `app.log.path`).
- `LogSanitizer` strips/masks sensitive information (e.g., passwords) before it's written to logs.
- `RequestMdcFilter` adds a request ID to the MDC so logs for a single HTTP request can be traced and correlated.
- `SecurityAuditLogger` records security-related events (login/logout, unauthorized access attempts).

---

## Testing

The project has extensive test coverage:

- **Unit tests**: `service/*Test.java` — business logic (log-sheet lifecycle, assignment, operational unit scope, Excel import, etc.)
- **Security tests**: `security/EndpointSecurityTest.java` — verifies endpoint permissions.
- **Integration tests**: `integration/ApiIntegrationTest.java` using **Testcontainers PostgreSQL** (`support/AbstractPostgresIntegrationTest.java`) to run tests against a real, isolated database.
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

Production environment settings (database, port, etc.) can be overridden via environment variables or an `application-prod.properties` file (kept out of Git).

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
