# AGENTS.md — backend-offline-first

Guidance for AI coding agents working in this repository. Prefer this file + `README.md` over inventing architecture. When they conflict, **trust the code**.

---

## What this project is

Spring Boot **4.1** / Java **25** backend for an industrial **offline-first round / log-sheet** system:

- **Web admin** (Thymeleaf + session form-login) — master data, templates, RBAC, reports, batch Excel import.
- **Mobile API** (`/api/**`, JWT) — inbox, claim/release, batch complete, NFC lookup, per-sheet **bundle**.
- **PostgreSQL** schema owned by Flyway (`V1__initial_schema.sql` only).
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
| `security/` | JWT, LDAP bind, `PermissionCodes`, user details |
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
- **Log sheets:** Generated from templates (manual/scheduled) **or created as custom/template-less sheets** (`CustomLogSheetService`: supervisor picks active assets in a supervised unit; assets may span multiple classes; `template_id = null`; multi-class field snapshot). Lifecycle statuses + assignment types (`SELF_CLAIMED` / `SUPERVISOR_ASSIGNED`). Late offline completes → `log_sheet_void_submissions`.
- **RBAC:** Permission code = `METHOD:path`. System roles: `ADMIN`, `HIGH_USER`, `SUPERVISOR`, `SENIOR_OPERATOR`, `OPERATOR`. Unit scope via `unit_supervisors` / `unit_operators` + `OperationalUnitScopeService`. Endpoint permission ≠ full access — check service rules (e.g. supervisor create-only templates; custom sheet unit + asset scope).
- **Mobile data:** Lightweight `GET /api/bootstrap` = unit context only. Plant/assets for a round come from **`GET /api/log-sheets/{id}/bundle`**. Bundle already supports multi-class entries/fields — custom sheets need no PWA change. Do not restore a full master-data delta bootstrap unless explicitly requested.
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
| `src/main/resources/db/migration/V1__*.sql` | Yes — **only** V1 |
| `templates/`, `static/` | Yes — Thymeleaf UI |
| Separate frontend SPA / mobile app in this repo | **No** |
| Hexagonal adapters / CQRS / Kafka | **No** |
| Incremental Flyway V2+ | **No** (folded into V1) |

---

## Conventions agents MUST follow

### 1. Business logic location
- New rules → `service/`.
- Controllers stay thin.
- Hierarchy mutations → `AssetHierarchyService` (do not reimplement cascade in controllers).

### 2. Flyway / schema
- Single baseline script: `src/main/resources/db/migration/V1__initial_schema.sql` (commented in English).
- `spring.jpa.hibernate.ddl-auto=validate` — schema comes from Flyway only.
- Editing an **already applied** V1 (even comments) → checksum mismatch. Fix with `flyway repair` or update `flyway_schema_history.checksum` for `version = '1'` only when DDL intent still matches.
- Prefer folding greenfield DDL into V1; for **already-migrated** environments use a new numbered migration (`V2__…`, …) instead of rewriting applied history silently.

### 2b. New endpoints → permissions (mandatory)

**One permission row per protected HTTP endpoint** (`code = METHOD:path`). Permissions are **manual**, not auto-generated from `@RequestMapping`.

Whenever you add an endpoint you **must**:

1. Add `PermissionCodes` constant.
2. Add `@PreAuthorize("hasAuthority('METHOD:/path')")` on the handler.
3. **Create/update the permission through a Flyway migration** — `INSERT` into `permissions`, plus `role_permissions` for any system role that should get it (beyond what V1 already grants to `ADMIN`).

| Environment | How to ship the permission |
|---|---|
| Fresh DB / still consolidating V1 | Add the `INSERT` into `V1__initial_schema.sql` (accept checksum repair if V1 was already applied locally). |
| DB that already ran V1 | Add a **new** Flyway script (`V2__add_….sql`, …). **Required** for shared/staging/production. |

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
- Every new endpoint: `@PreAuthorize("hasAuthority('METHOD:/path')")` **and** a Flyway-seeded `permissions` row (see §2b). Skipping the migration leaves the endpoint unreachable or inconsistently granted across environments.
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

- **`data_records`** + `RecordController` / `RecordWebController` — older inspection records. New rounds → log sheets.
- **`asset_classes.fields` JSONB** — denormalized for older mobile clients. **Source of truth = `field_definitions`**. Keep synced when fields change.
- **`GET /api/master-data`** — deprecated; prefer `/api/bootstrap`.
- Repo `findByUpdatedAtGreaterThanEqual` leftovers from older delta master sync — unused by current bootstrap.
- Synchronous per-page Excel import still exists; large files → **batch import**.

---

## Important files (start here)

| File | Why |
|---|---|
| `README.md` | Human-facing product/docs |
| `db/migration/V1__initial_schema.sql` | Full schema + RBAC seeds |
| `config/WebSecurityConfig.java` | Dual security chains |
| `security/PermissionCodes.java` | Authority catalog |
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
5. Inactive assets: NFC yes, generation/preview no.
6. One sub-function → one asset (DB + service + import).
7. User hard-delete blocked after app activity — prefer deactivate.
8. Import files live under `./data/imports` (runtime; often gitignored via `data/`) — TRUNCATE jobs does **not** delete disk files.
9. Permission matrix in Flyway + service-layer gates both matter; **never add an endpoint without a Flyway permission insert**.
10. Do not commit unless the user asks; do not push unless asked.

---

## Preferred change style

- Match existing naming, package layout, and test style.
- Small, focused diffs; no drive-by refactors or unsolicited markdown.
- After schema/uniqueness changes: update validators, translators, Excel import, and tests together.
- When unsure about product intent (e.g. deleting `data_records`), ask — do not silently remove legacy surfaces.
