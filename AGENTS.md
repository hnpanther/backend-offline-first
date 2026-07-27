# AGENTS.md — backend-offline-first

Guidance for AI coding agents working in this repository. Prefer this file + `README.md` over inventing architecture. When they conflict, **trust the code**.

---

## What this project is

Spring Boot **4.1** / Java **25** backend for an industrial **offline-first round / log-sheet** system:

- **Web admin** (Thymeleaf + session form-login) — master data, templates, RBAC, reports, batch Excel import.
- **Mobile API** (`/api/**`, JWT) — inbox, claim/release, batch complete, NFC lookup, per-sheet **bundle**.
- **PostgreSQL** schema owned by Flyway (`V1__initial_schema.sql` baseline + `V2__api_session_registry.sql` + `V3__web_session_permissions.sql`).
- Server is **authoritative** for log-sheet lifecycle; clients use idempotency keys (`local_id`, `client_action_id`).

Default URL: `http://localhost:8081`. Default bootstrap admin: `admin` / `admin123` (change immediately).

---

## Architecture (layered modular monolith)

```
HTTP
 ├── controller/   REST /api/**  (JWT, CSRF off)     ──┐
 └── web/          Thymeleaf panel (session login)  ──┼──► service/ ──► repository/ ──► PostgreSQL
                                                      │
config/WebSecurityConfig  (dual filter chains) ───────┘

aspect/     LoggingAspect + RepositoryAuditAspect (audit → async AuditWriteService)
scheduler/  LogSheetScheduler → generation + expiry
```

**Not** hexagonal / clean-architecture ports. Keep layers:

| Layer | Role |
|---|---|
| `controller/`, `web/` | Thin: auth annotations, bind params, call services, map UX |
| `service/` | Business rules, uniqueness, hierarchy, lifecycle, unit scope |
| `repository/` | Spring Data JPA (+ ancestry projections, native scope SQL) |
| `entity/` | JPA model; timestamps = **epoch millis `Long`** |
| `domain/` | Enums / validation helpers (not entities) |
| `dto/` | API and transfer objects |
| `ui/` | Persian mapping, flash helpers (`ErrorTranslator`, `FaMessages`, …) |
| `security/` | JWT (+ `jti` session registry check), LDAP bind, `PermissionCodes`, user details |
| `config/` | Security, async pools, LDAP, admin/import recovery runners |
| `aspect/`, `audit/`, `logging/` | Cross-cutting logging + field-level audit |
| `util/`, `mapper/` | Excel, Jalali, NFC, labels, small mappers |
| `service/importjob/` | Async batch Excel jobs |

**Request pattern:** `@PreAuthorize` on controller → service (extra unit/supervisor rules) → repository. Do **not** put business or scope rules only in controllers or templates.

---

## Domain map (short)

- **Hierarchy:** Location → PlantSystem → MainFunction → SubFunction → **AssetEntry**  
  Owned by `AssetHierarchyService` (direct parent + denormalized ancestry + cascade + template scope walks).
- **Asset ↔ SubFunction:** **1:1** (`ux_asset_entries_sub_function_id` + `MasterDataUniquenessValidator`). Inactive assets (`active=false`) remain NFC-findable but are **excluded from log-sheet preview/generation**.
- **Log sheets:** Generated from templates (manual/scheduled) **or created as custom/template-less sheets** via `CustomLogSheetService` + web `POST:/log-sheets/custom` (asset search `GET:/log-sheets/options/assets`). Supervisor picks **active** assets in a supervised unit; assets may span multiple classes; `template_id = null`; name → `template_name`; multi-class `field_definitions_snapshot` via `LogSheetFieldDefinitionsService.captureSnapshot(Collection)`. UI requires due date; service validates future `dueAt` when set. Created `PENDING` / `MANUAL`, then same claim/assign/complete/expire lifecycle. **`VOIDED`** soft-invalidates a `SUBMITTED` sheet (admin or unit supervisor: `POST:/log-sheets/{id}/void` / `unvoid`); excluded from parameter reports; restorable only to `SUBMITTED`. Reopen submitted → draft with new due: `POST:/log-sheets/{id}/reopen` (admin or unit supervisor). Optional web-only `notes` on the sheet (fill/complete). Late offline completes → `log_sheet_void_submissions`.
- **Mobile sessions:** `/api/**` JWTs are stateful — every token carries a `jti` backed by an `api_sessions` row (`ApiSessionService`). `JwtAuthenticationFilter` rejects a token whose row is missing, revoked, or expired, so signature validity alone is **not** enough. Login registers device/UA/IP and **supersedes** the user's other live sessions (one device per user). Admin page `/api-sessions` lists and revokes; token lifetime stays in `app_settings['auth.jwt.expiry_minutes']`. Revocation only bites when the device is online — that is by design for offline-first.
- **Web sessions:** the panel enforces **one concurrent session per user** via Spring's concurrent session control (`maximumSessions(1)` + in-memory `SessionRegistryImpl` + `HttpSessionEventPublisher` in `WebSecurityConfig`); a new login expires the old browser → `/login?expired`. Idle timeout: `server.servlet.session.timeout=60m`. `AppUserDetails.equals/hashCode` (by username) is **required** for the limit — do not remove it. `WebSessionMetadataStore` (in-memory) keeps IP/UA/login time; admin page `/web-sessions` (`WebSessionService`) lists/expires sessions addressed by a SHA-256 digest key — never expose raw `JSESSIONID`s. No DB table on purpose: sessions are non-persistent across restarts.
- **RBAC:** Permission code = `METHOD:path` (one DB row per **authority**; export/options/draft/bulk paths often reuse a parent authority). System roles: `ADMIN`, `HIGH_USER`, `SUPERVISOR`, `SENIOR_OPERATOR`, `OPERATOR`. Unit scope via `unit_supervisors` / `unit_operators` + `OperationalUnitScopeService`. Endpoint permission ≠ full access — check service rules (e.g. supervisor create-only templates; custom sheet unit + asset scope). Custom-create permissions are Flyway-seeded for `ADMIN` / `HIGH_USER` / `SUPERVISOR`.
- **Mobile data:** Lightweight `GET /api/bootstrap` = unit context only. Plant/assets for a round come from **`GET /api/log-sheets/{id}/bundle`**. Bundle already supports multi-class entries/fields and null `templateId` — custom sheets need no PWA change. Do not restore a full master-data delta bootstrap unless explicitly requested.
- **Batch import:** `/batch-import` → disk under `app.import.storage-path` (default `./data/imports`) + `import_jobs`. One active job system-wide; max **10 000** rows; sequential async pool.

---

## Package checklist (what exists)

| Path | Present |
|---|---|
| `controller/` (+ `advice/`) | Yes — mobile API |
| `web/` (+ `advice/`) | Yes — admin panel |
| `service/` (+ `importjob/`) | Yes |
| `entity/`, `repository/`, `domain/`, `dto/` | Yes |
| `security/`, `config/`, `aspect/`, `audit/`, `logging/` | Yes |
| `ui/`, `util/`, `mapper/` | Yes |
| `src/main/resources/db/migration/V1__*.sql` | Yes — baseline |
| `src/main/resources/db/migration/V2__api_session_registry.sql` | Yes — `api_sessions` + admin permissions |
| `src/main/resources/db/migration/V3__web_session_permissions.sql` | Yes — web-session admin permissions |
| `templates/`, `static/` | Yes — Thymeleaf UI |
| Separate frontend SPA / mobile app in this repo | **No** |
| Hexagonal adapters / CQRS / Kafka | **No** |
| Flyway V4+ | **No** (add one when new DDL is needed) |

---

## Conventions agents MUST follow

### 1. Business logic location
- New rules → `service/`.
- Controllers stay thin.
- Hierarchy mutations → `AssetHierarchyService` (do not reimplement cascade in controllers).

### 2. Flyway / schema
- Baseline script: `src/main/resources/db/migration/V1__initial_schema.sql` (commented in English), plus `V2__api_session_registry.sql` and `V3__web_session_permissions.sql`.
- `spring.jpa.hibernate.ddl-auto=validate` — schema comes from Flyway only.
- Editing an **already applied** script (even comments) → checksum mismatch. Fix with `flyway repair` or update `flyway_schema_history.checksum` for that version only when DDL intent still matches.
- New DDL goes in a **new numbered migration** (`V4__…`, …) — do not rewrite applied history silently. Fold into V1 only when the user explicitly asks and the environment is greenfield.

### 2b. New endpoints → permissions (mandatory)

**One `permissions` row per authority** (`code = METHOD:path`). Authorities are **manual**, not auto-generated from `@RequestMapping`.

Several handlers intentionally **reuse** an existing authority (no extra DB row): e.g. `GET …/export` and `GET …/options/…` → parent list `GET:/…`; `POST …/delete-bulk` → `POST:/…/{id}/delete`; `POST /log-sheets/{id}/draft` → `POST:/log-sheets/{id}/complete`; batch-import cancel/errors → `GET` or `POST:/batch-import`. The number of `@PreAuthorize` mappings can exceed the number of seeded permissions.

Whenever you add a handler that checks a **new** authority string:

1. Add `PermissionCodes` constant.
2. Add `@PreAuthorize("hasAuthority('METHOD:/path')")` on the handler(s).
3. **Create/update the permission through a Flyway migration** — `INSERT` into `permissions`, plus `role_permissions` for any system role that should get it (beyond what V1 already grants to `ADMIN`).

| Environment | How to ship the permission |
|---|---|
| Any DB that already ran the earlier scripts | Add a **new** Flyway script (`V4__add_….sql`, …). **Required** for shared/staging/production. |
| Greenfield, and the user explicitly asks to consolidate | Add the `INSERT` into `V1__initial_schema.sql` (accept checksum repair if V1 was already applied locally). |

**Forbidden:** creating the permission only in the Roles UI, only with ad-hoc SQL, or only in Java bootstrap — other environments will miss it and `@PreAuthorize` will deny everyone.

### 3. Uniqueness
- Use `MasterDataUniquenessValidator` for web + Excel (codes, tags, names, NFC, field keys, one-asset-per-SF).
- Import: `*ForImport` + `FileUniqueness` (in-file + DB).
- When updating managed entities that participate in unique indexes: **validate on a detached candidate first**, then copy onto the managed entity (see `AssetEntryService.update`) — avoids Hibernate auto-flush mid-check.

### 4. i18n / errors
| Layer | Language |
|---|---|
| Services, validators, import row messages | **English** |
| Web flash / API JSON | **Persian** via `ErrorTranslator`, `FaMessages`, `ImportDisplay`, `ApiResponseSupport` |

Always add translator mappings for new English messages (or users see raw English / generics). Map new unique constraint names in `ErrorTranslator.dataIntegrityViolation` when adding indexes.

### 5. Security
- Every **new authority** in `@PreAuthorize`: matching Flyway-seeded `permissions` row (see §2b). Reuse an existing authority when the URL is a variant of an existing capability (export/options/draft). Skipping the migration leaves the endpoint unreachable or inconsistently granted across environments.
- Dual chains in `WebSecurityConfig`: `/api/**` JWT; web session.
- Auth types: `LOCAL` | `ACTIVE_DIRECTORY` | `HYBRID` — AD verifies password only; roles stay in DB.
- Never commit secrets (JWT, LDAP, DB passwords). Use env / gitignored `application-local.properties`.

### 6. Tests
- Unit: `*Test.java` next to concern (Mockito, no DB).
- Integration: `*IntegrationTest` extends `support/AbstractPostgresIntegrationTest` (Testcontainers PostgreSQL + Flyway). **Docker required**.
- Security helpers: `@WithAppUser` in `support/`.
- Prefer extending existing IT fixtures carefully (respect one-asset-per-SF uniqueness).

### 7. UI patterns (admin)
- List pages: text search `q` + pagination (`WebListSupport`) — **do not** add cascading multi-select filters on lists unless asked.
- Form parent pickers: AJAX typeahead (`MasterDataOptionsService` / options endpoints).
- Prefer existing Bootstrap + Thymeleaf patterns over new design systems.

### 8. Scheduling / templates
- Preserve `log_sheet_templates.next_run_at` on non-schedule edits.
- Property `app.scheduler.log-sheet-max-backfill` defaults to **`0`** in `application.properties` (skip multi-occurrence backlog; single due tick still generates). Do not “fix” docs by changing behaviour silently.

### 9. Audit / logging
- Do not disable repository audit for production convenience.
- Cover both `save` and `saveAndFlush` if touching audit aspects.
- SVC/REPO logs are **DEBUG** by design (Import would explode at INFO).

---

## Intentionally legacy / do not expand casually

- **`data_records`** + `RecordController` / `RecordWebController` — older per-asset inspection sync (not log-sheet rounds). New rounds → `log_sheets` / `log_sheet_entries`.
  - **Table:** see README [Operational Data](#operational-data) (`local_id`, `form_data` JSONB, `asset_type_id` = legacy class id, sync columns, …).
  - **API:** `POST /api/records/batch` → `POST:/api/records/batch`.
  - **Web:** `GET /records`, `GET /records/{id}`, `GET /records/export` (export → `GET:/records`).
  - **Reports:** `ReportWebController` `GET /reports` aggregates legacy record counts; `GET /reports/asset-parameters` uses log-sheet readings, not `data_records`.
- **`asset_classes.fields` JSONB** — denormalized for older mobile clients. **Source of truth = `field_definitions`**. Keep synced when fields change (`AssetClassWebController`).
- **`GET /api/master-data`** (`MasterDataController`, `@deprecated`) — same `BootstrapResponse` as `/api/bootstrap`; optional `since` **ignored**. Separate permission **`GET:/api/master-data`** vs **`GET:/api/bootstrap`** (both may appear on field roles in V1).
- **`POST /log-sheets/{id}/admin-reopen`** — web bookmark alias for `POST /log-sheets/{id}/reopen`; authority `POST:/log-sheets/{id}/reopen` only.
- Repo **`findByUpdatedAtGreaterThanEqual`** on master-data repositories — unused; leftovers from removed delta bootstrap.
- Synchronous per-page Excel import still exists; large files → **batch import**.

---

## Important files (start here)

| File | Why |
|---|---|
| `README.md` | Human-facing product/docs |
| `db/migration/V1__initial_schema.sql` | Full schema + RBAC seeds |
| `db/migration/V2__api_session_registry.sql` | `api_sessions` + admin session permissions |
| `config/WebSecurityConfig.java` | Dual security chains |
| `security/PermissionCodes.java` | Authority catalog |
| `security/JwtService.java`, `JwtAuthenticationFilter.java`, `service/ApiSessionService.java` | Stateful JWT: `jti`, registry check, one device per user |
| `service/WebSessionService.java`, `security/WebSessionMetadataStore.java` | Web session admin view: digest keys, in-memory metadata |
| `service/AssetHierarchyService.java` | Placement / cascade / scope |
| `service/LogSheet*.java`, `CustomLogSheetService.java` | Lifecycle, assignment, generation, custom create, bundle, template |
| `service/MasterDataUniquenessValidator.java` | Shared uniqueness |
| `service/OperationalUnitScopeService.java` | Unit RBAC |
| `service/importjob/*` | Async Excel |
| `ui/ErrorTranslator.java` | Persian mapping |
| `application.properties` | Ports, JWT, LDAP, scheduler, import |
| `support/AbstractPostgresIntegrationTest.java` | IT base |

---

## Commands

```bat
mvnw.cmd spring-boot:run
mvnw.cmd spring-boot:run -Dspring-boot.run.profiles=local
mvnw.cmd test
mvnw.cmd clean package
```

JaCoCo after tests: `target/site/jacoco/index.html`.

---

## Gotchas checklist

1. Unique-constraint updates → validate detached candidate before dirtying managed entity.
2. Hierarchy cascade uses persisted ancestry (`FlushMode.COMMIT`) — pass prior IDs when entity already mutated in memory.
3. Large reparent = expensive single TX — treat as maintenance.
4. Bootstrap ≠ full master catalog; use log-sheet bundles for assets/fields.
5. Inactive assets: NFC yes, generation/preview **and custom-sheet selection** no.
6. One sub-function → one asset (DB + service + import).
7. User hard-delete blocked after app activity — prefer deactivate.
8. Import files live under `./data/imports` (runtime; often gitignored via `data/`) — TRUNCATE jobs does **not** delete disk files.
9. Permission matrix in Flyway + service-layer gates both matter; **never add a new authority without a Flyway permission insert** (reuse parent authorities for export/options/draft when appropriate).
10. Custom sheets: no template scope walk — asset set is explicit; do not assume one `classId` per sheet when reading snapshot/bundle.
11. `VOIDED` ≠ `CANCELLED` and ≠ `log_sheet_void_submissions` (late superseded sync). Parameter reports filter `status = SUBMITTED` only — voiding excludes readings automatically.
12. A signed JWT is no longer sufficient — its `jti` must map to a live `api_sessions` row. Issuing a token outside `AuthApiController` without calling `ApiSessionService.register` produces a token the filter rejects.
13. Never bind a nullable `String` into a JPQL `LOWER(...)` comparison: PostgreSQL infers `bytea` and fails with `function lower(bytea) does not exist`. Write a separate termless query (see `ApiSessionRepository.findActive`).
14. Field keys must not contain `.`, `[`, or `]` (`MasterDataUniquenessValidator`) — the PWA uses them verbatim as react-hook-form names, where those characters mean nested paths.
15. Do not commit unless the user asks; do not push unless asked.

---

## Preferred change style

- Match existing naming, package layout, and test style.
- Small, focused diffs; no drive-by refactors or unsolicited markdown.
- After schema/uniqueness changes: update validators, translators, Excel import, and tests together.
- When unsure about product intent (e.g. deleting `data_records`), ask — do not silently remove legacy surfaces.
