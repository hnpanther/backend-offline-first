# AGENTS.md — backend-offline-first

Guidance for AI coding agents working in this repository. Prefer this file + `README.md` over inventing architecture. When they conflict, **trust the code**.

---

## What this project is

Spring Boot **4.1** / Java **25** backend for an industrial **offline-first round / log-sheet** system:

- **Web admin** (Thymeleaf + session form-login) — master data, templates, RBAC, reports, batch Excel import.
- **Mobile API** (`/api/**`, JWT) — inbox, claim/release, batch complete, NFC lookup, per-sheet **bundle**.
- **PostgreSQL** schema owned by Flyway — currently a single `V1__initial_schema.sql` (every migration so far has been folded back into it while the app is still greenfield in testing; see §2).
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
- **Mobile sync batch cap:** `POST /api/log-sheets/batch` / `POST /api/records/batch` run synchronously in one DB transaction per request (not async/queued like batch import) — both reject over `app.sync.batch-max-items` (default **500**) with a `400` before touching the DB. Checked in `LogSheetService.submitBatch` / `RecordService.submitBatch`, first line. If you add another sync-batch endpoint, apply the same guard rather than assuming client-side chunking.

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
| `src/main/resources/db/migration/V1__*.sql` | Yes — the **only** migration file right now (baseline + every feature since, including `api_sessions`, ops/monitoring permissions, log-sheet cancel, `nfc_fault_reports`, `asset_entries.status`, user contact-field uniqueness — all folded in while still greenfield, see §2) |
| `templates/`, `static/` | Yes — Thymeleaf UI |
| Separate frontend SPA / mobile app in this repo | **No** |
| Hexagonal adapters / CQRS / Kafka | **No** |
| Flyway V2+ | **No** (add one when new DDL is needed — see §2 for when folding back into V1 is/isn't safe) |

---

## Conventions agents MUST follow

### 1. Business logic location
- New rules → `service/`.
- Controllers stay thin.
- Hierarchy mutations → `AssetHierarchyService` (do not reimplement cascade in controllers).

### 2. Flyway / schema
- Baseline script: `src/main/resources/db/migration/V1__initial_schema.sql` (commented in English) — currently the **only** migration file. Everything shipped so far (the original `V2__api_session_registry.sql`/`V3__web_session_permissions.sql`/`V4__ops_monitoring_permissions.sql`, then later `V2__nfc_fault_reports.sql`, `V3__asset_status_field.sql`, `V4__user_contact_field_uniqueness.sql`) has been folded back into V1 while the app is still greenfield in local testing (see gotcha #22 for the mechanics and hazards of doing this). Keep this line in sync when adding a new migration — it's the first thing an agent reads before assuming one exists. **Once this ships to a real/shared environment, folding stops being safe — see the "Merging..." note below.**
- `spring.jpa.hibernate.ddl-auto=validate` — schema comes from Flyway only.
- Editing an **already applied** script (even comments) → checksum mismatch. Fix with `flyway repair` or update `flyway_schema_history.checksum` for that version only when DDL intent still matches.
- New DDL goes in a **new numbered migration** (`V5__…`, …) — do not rewrite applied history silently. Fold into V1 only when the user explicitly asks and the environment is greenfield.
- Merging two already-applied local migrations into one (e.g. renumbering) is only safe when nothing has shipped to a shared DB yet (uncommitted / solo local dev). Locally: delete the `flyway_schema_history` rows for the old versions **and** the rows their `INSERT`s created (permissions/role_permissions, etc.), then let Flyway reapply the merged file fresh so its checksum is computed correctly — don't hand-edit checksums. Also delete the stale copies of the old files under `target/classes/db/migration/` (a `compile` alone won't remove files that no longer exist in `src/`), or Flyway will fail with "Found more than one migration with version N".

### 2b. New endpoints → permissions (mandatory)

**One `permissions` row per authority** (`code = METHOD:path`). Authorities are **manual**, not auto-generated from `@RequestMapping`.

Several handlers intentionally **reuse** an existing authority (no extra DB row): e.g. `GET …/export` and `GET …/options/…` → parent list `GET:/…`; `POST …/delete-bulk` → `POST:/…/{id}/delete`; `POST /log-sheets/{id}/draft` → `POST:/log-sheets/{id}/complete`; batch-import cancel/errors → `GET` or `POST:/batch-import`. The number of `@PreAuthorize` mappings can exceed the number of seeded permissions.

Whenever you add a handler that checks a **new** authority string:

1. Add `PermissionCodes` constant.
2. Add `@PreAuthorize("hasAuthority('METHOD:/path')")` on the handler(s).
3. **Create/update the permission through a Flyway migration** — `INSERT` into `permissions`, plus `role_permissions` for any system role that should get it (beyond what V1 already grants to `ADMIN`).

| Environment | How to ship the permission |
|---|---|
| Any DB that already ran the earlier scripts | Add a **new** Flyway script (`V3__add_….sql`, …). **Required** for shared/staging/production. |
| Greenfield, and the user explicitly asks to consolidate | Add the `INSERT` into `V1__initial_schema.sql` (accept checksum repair if V1 was already applied locally). |

**Forbidden:** creating the permission only in the Roles UI, only with ad-hoc SQL, or only in Java bootstrap — other environments will miss it and `@PreAuthorize` will deny everyone.

### 3. Uniqueness
- Use `MasterDataUniquenessValidator` for web + Excel (codes, tags, names, NFC, field keys, one-asset-per-SF).
- Import: `*ForImport` + `FileUniqueness` (in-file + DB).
- When updating managed entities that participate in unique indexes: **validate on a detached candidate first**, then copy onto the managed entity (see `AssetEntryService.update`) — avoids Hibernate auto-flush mid-check. Same precaution applies to any throwing validation on a managed entity under Open Session In View, not just uniqueness — e.g. `AssetClassWebController.updateField` computes/validates `FieldValidationSupport.build(...)` (throws on warning/danger `min > max`) **before** mutating the fetched `FieldDefinition`, so a rejected edit leaves the existing row completely untouched rather than partially dirtied.

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
- `LoginAttemptService` (in-memory, per-username) throttles both `/api/auth/login` and web `/login` — checked in `AppAuthenticationProvider.additionalAuthenticationChecks` **before** `verifyPassword()`, so a locked username never reaches the LDAP bind. This exists specifically to stop an attacker from using this app's login to trip Active Directory's own account-lockout policy against a real employee (no password guess needed — just their username). Don't move the lock check after password verification.
- Admin page `/login-attempts` (`LoginAttemptWebController.java`, `ADMIN` only) lists currently-locked usernames and ones approaching the threshold, with a manual "unlock" button (`LoginAttemptService.unlock`). Lock state is always recomputed from the clock at read time (never cached), so unlocking is a plain, always-safe map removal — including when clicked after the lock already expired naturally.

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

### 10. API documentation (OpenAPI — admin only)
- `springdoc-openapi` (`OpenApiConfig.java`) documents `/api/**` only, generated automatically from `@RestController` classes — a new mobile endpoint needs **no manual step** to appear (unlike permissions, which do).
- Enabled in every environment, including production (`springdoc.api-docs.enabled` / `springdoc.swagger-ui.enabled=true` in `application.properties`). Access is gated behind `GET:/v3/api-docs/**` (`PermissionCodes.GET_API_DOCS`, seeded in `V1__initial_schema.sql` — folded in from the original `V4__ops_monitoring_permissions.sql`, see §2 — `ADMIN` only) via `WebSecurityConfig` — same pattern as Actuator (§ above). Do not make the spec/UI `permitAll()`.
- Do not widen `springdoc.paths-to-match` to include the Thymeleaf `web/` panel; it isn't a machine-consumed API.

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
| `db/migration/V1__initial_schema.sql` | Full schema + RBAC seeds — the only migration file (see §2) |
| `config/WebSecurityConfig.java` | Dual security chains |
| `security/PermissionCodes.java` | Authority catalog |
| `security/JwtService.java`, `JwtAuthenticationFilter.java`, `service/ApiSessionService.java` | Stateful JWT: `jti`, registry check, one device per user |
| `service/WebSessionService.java`, `security/WebSessionMetadataStore.java` | Web session admin view: digest keys, in-memory metadata |
| `security/AppAuthenticationProvider.java`, `security/LoginAttemptService.java`, `web/LoginAttemptWebController.java` | LOCAL/AD/HYBRID auth + per-username login-attempt throttle (AD lockout DoS protection) + admin unlock page |
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
16. A `permitAll()` route for a URL with **no registered MVC handler** (e.g. a conditionally-disabled feature) still redirects an anonymous caller to `/login` instead of 404ing — Spring Security re-authorizes the internal `/error` forward that `DispatcherServlet` triggers on 404, and `/error` isn't in the permit list. This is identical to hitting any other dead URL while unauthenticated; it does **not** mean the `permitAll()` rule failed. Don't "fix" it by adding `/error` to permitAll or chasing it as a bug.
17. `HttpSecurity.authenticationProvider(provider)` (used on the web chain) is **not** the same as passing an explicit `AuthenticationManager` — it lets `HttpSecurity`'s internal builder silently attach the Boot-auto-configured *global* `AuthenticationManager` as a **parent**, and that global manager also resolves to the very same `@Component`-registered provider. Net effect: on a failed login, `additionalAuthenticationChecks()` runs **twice** (once locally, once via parent fallback after the first throws) — e.g. `LoginAttemptService.recordFailure` double-counting every wrong password from the web form only, not from `/api/auth/login`. Fix/pattern: build one explicit no-parent `AuthenticationManager` bean (`new ProviderManager(provider)`) and wire it into **every** chain via `.authenticationManager(...)`, never `.authenticationProvider(...)`, so both entry points share the exact same single-invocation instance (see `WebSecurityConfig`).
18. A raw JS array-of-arrays literal (`[[...]]`) — or anything else starting with two adjacent `[`/`(` — inside a Thymeleaf-processed `<script>` block gets misread as Thymeleaf's own **inline expression** syntax (`[[${...}]]` / `[(${...})]`) and fails template parsing with a cryptic `attoparser.ParseException: Could not parse as expression`. This breaks the **whole page** (500, and in a real browser often surfaces as a stuck/looping `net::ERR_INCOMPLETE_CHUNKED_ENCODING` load, not an obvious error). Add `th:inline="none"` to any `<script>` tag whose JS wasn't written with Thymeleaf inlining in mind (see `field-definitions.html`). Also: editing a `.html` template while the app is already running via `spring-boot:run` does **not** reliably hot-reload it — restart to pick up template changes before trusting a browser test.
19. User-submitted deadline/schedule dates go through `DateUtils.requireFutureWithinYears(epochMs, now, label)` (must be strictly future, at most `MAX_FUTURE_YEARS` ahead) — see [README § User-submitted date validation](README.md#user-submitted-date-validation) for the full call-site list. On `LogSheetTemplateService.update`, only re-validate `scheduleStartAt` when it **actually changed** from the persisted entity (`!Objects.equals(existing, form)`) — a live template's original start naturally drifts into the past as its schedule keeps running, so unconditionally re-checking it would block unrelated edits (e.g. a rename) with a spurious "must be in the future" error. When adding a new user-facing date field, reuse this helper rather than a bespoke `<= now` check, and add it to the README table.
20. **Reassigning a partially-completed log sheet leaks the previous operator's readings into the new operator's view, silently.** Nothing in the reassignment path touches `log_sheet_entries` — `LogSheetAssignmentService.reopenSubmittedWithExtend` (preserves entry form data by design, only resets sheet-level timestamps) and `assign`/`reassign` (bulk `UPDATE LogSheet SET ...` JPQL, `LogSheetRepository.assignIfPending`/`reassignIfStillOpen` — cannot touch another table) never modify entries. `FormDataValidationSupport.validateFilledEntry` explicitly allows a completely blank entry to pass validation, so a 3-of-10-filled submission is accepted as a normal `SUBMITTED` outcome — this isn't a bug, partial submission is intentional. The mobile bundle endpoint (`LogSheetBundleService.buildFullBundle` → `LogSheetEntryRepository.findByLogSheetId`, no assignee/requester filter) always returns each entry's *current* `formData` regardless of who's asking. On the PWA, a device that has never seen the sheet locally before (`applyLogSheetBundle` in `logSheetSync.ts`, `existing` branch is falsy) copies `bundle.entries` straight into the new local record — `mergeEntriesPreservingFormData`/`mapServerEntryToLocal` in `mergeLogSheetBundle.ts` always fall back to `serverForm` when there's no matching local entry, since `preserveLocal` only matters when local data exists. Net effect: submit 3-of-10 → supervisor reopens (`reopenSubmittedWithExtend`) → reassign to a different operator → the new operator sees the first 3 assets pre-filled from the DB and, if they don't touch those rows, ends up submitting the first operator's readings under their own `completedByUserId`/`operatorName`. **Partial progress**: `log_sheet_entries.filled_by_user_id` / `entry_source` (added directly in `V1__initial_schema.sql`, originally shipped as a standalone `V2__nfc_fault_reports.sql` before being folded back in, see §2) now record who actually filled each individual entry and how — the display gap is closed (`log-sheet-detail.html`'s entries table shows both columns). The underlying formData leak itself (new assignee's device silently inherits the previous operator's readings) is **still unchanged** — that part is still flagged for a future decision (e.g. resetting or flagging entries on reassignment). What **is** fixed: `mergeMobileEntryUpdates` / `applyWebEntryValues` no longer blindly re-stamp `entry_source`/`filled_by_user_id` on every submit just because an entry "has data" — a mobile submit always resends *every* entry currently on the device (including ones the submitter never opened), so unconditional stamping meant a second operator's resubmit of an untouched, already-filled asset silently reassigned it to themselves and, since their own device never locally knew it had been filled manually, downgraded a fault-report-driven `PWA_MANUAL` entry to `PWA_NFC`. Reproduced live on log sheet #8 (operator1 fault-reported + manually filled 2 assets and submitted; supervisor reopened, took over, reassigned to operator2; operator2 filled their own 2 assets and resubmitted — one of operator1's untouched assets flipped from `PWA_MANUAL`/operator1 to `PWA_NFC`/operator2). Fix: both methods now compare the incoming `formData` against the entry's *currently stored* `formData` (`Objects.equals`) and only re-stamp attribution when it actually changed — an unchanged resend leaves the original author's `entry_source`/`filled_by_user_id` untouched. Tests: `submitPreservesOriginalAttributionWhenResubmittingUnchangedEntry`, `submitReattributesEntryWhenDataActuallyChanges`, `webDraftSavePreservesOriginalAttributionWhenValuesUnchanged` in `LogSheetServiceTest`.
22. **A `role_permissions` blanket grant (`SELECT ... FROM roles r CROSS JOIN permissions p WHERE r.code = 'ADMIN'`, no code filter) only covers permission rows that exist in the table *at the moment that INSERT statement itself runs*.** It is not a live rule re-evaluated later — it's a one-time snapshot. This is harmless when the blanket grant and the new permission rows are both in the *same* migration file (the new rows exist by the time the blanket-grant statement executes further down the same script) — the case exercised earlier when V1-V5 were consolidated into a single `V1__initial_schema.sql`. It silently fails when a *later*, separate migration (e.g. the original standalone `V2__nfc_fault_reports.sql`, before it got folded back into V1 — see §2) adds new permission rows and assumes V1's already-applied ADMIN/HIGH_USER blanket grants will "automatically" cover them — V1's grant statement executed and finished long before V2 ever ran, so ADMIN ends up **without** the new permissions despite `SecurityUtils.isAdmin()` being true, and no test catches it: `EndpointSecurityTest`'s `@WithAppUser(authorities = "...")` injects the authority string directly into the mock principal, bypassing the real `roles`/`role_permissions` join entirely, and Mockito-based service tests don't touch the DB at all. This was only caught by a live app boot + real admin login + real page hit — the standard verification step, not an edge case. Fix: **every separate migration file** after V1 must **explicitly** `INSERT INTO role_permissions` for ADMIN (and HIGH_USER, for non-admin-category rows) for its own new permissions — this stops being necessary once folded back into V1 itself, since the blanket grant then naturally runs after the new permission rows in the same script (verified: after folding the NFC-fault-report permissions into V1, `ADMIN`'s `role_permissions` row count exactly equals the total `permissions` row count, with **zero** duplicate-key errors from also keeping old explicit per-role grants — those must be **dropped**, not kept, once folded, since the blanket would try to insert the same `(role_id, permission_id)` pair a second time and violate the composite PK). Roles without a blanket grant (`SUPERVISOR`/`OPERATOR`/`SENIOR_OPERATOR`, which use explicit `IN (...)` lists) still need their own explicit grants regardless of fold state.
23. `RecurrenceUnit` includes `MINUTE` (added for sub-hourly template schedules, e.g. every 20 minutes) alongside `HOUR`/`DAY`/`WEEK`/`MONTH` — plain `VARCHAR(20)` column, no Flyway migration needed for new enum values. `LogSheetTemplateService.scheduleOverlapRisk(form)` is a **non-blocking, informational-only** check: when the completion window is longer than the recurrence interval, the previous occurrence(s) are still open when the next one generates, so open sheets for the same assets can stack up. It deliberately never blocks create/update (product decision) — the web controller surfaces it as a `warningMessage` flash attribute (new alert block in `fragments/layout.html`, alongside the existing `successMessage`/`errorMessage`) shown *together with* the success message, not instead of it. `recurrenceIntervalMinutes` approximates MONTH as 30 days on purpose — this only feeds a heads-up, not validation, so exact calendar math isn't needed. The scheduler's own poll interval (`app.scheduler.log-sheet-gen-ms`, default 60s) is unrelated to this check and still has ample margin at 20-minute granularity; nothing enforces a minimum `recurrenceEvery` for `MINUTE`, so a very small value (e.g. 1) is accepted without warning about poll-interval margin specifically.
24. **`asset_entries.status`** (`VARCHAR(30)`, nullable — added directly in `V1__initial_schema.sql`, originally shipped as a standalone `V3__asset_status_field.sql` before being folded back in, see §2) is a schema-only field for the asset's real-world operational state (ON / OFF / IDLE / MAINTENANCE, etc.) — explicitly requested as DB-only for now, with no entity field, DTO, API, or UI wiring yet. This is safe: `spring.jpa.hibernate.ddl-auto=validate` only checks that entity-mapped columns exist in the schema, not that every schema column has a mapped entity field, so an unmapped extra column never breaks startup. Deliberately left without a CHECK constraint or backing Java enum, unlike `NfcFaultReportStatus` — the exact set of states hasn't been decided, and constraining it now would just mean loosening it again later (see `NfcFaultReportStatus`'s own migration comment for the same reasoning pattern). Not the same concept as the existing `active` boolean, which only gates log-sheet template preview/generation, not real-world operational state. When this is actually wired up: add `AssetEntry.status` with `@Column(name = "status")`, then DTO/mapper/UI as needed — no new migration required for that follow-up.
25. **`users.national_code` / `phone_number` / `nfc_tag_id` are now unique-or-blank** (`ux_users_national_code`, `ux_users_phone_number`, `ux_users_nfc_tag_id_lower` — added directly in `V1__initial_schema.sql`, originally shipped as a standalone `V4__user_contact_field_uniqueness.sql` before being folded back in, see §2) — previously these three optional contact fields had zero uniqueness enforcement, so two users could silently share the same national code, phone number, or NFC badge tag. Plain (non-partial) Postgres unique indexes were used, matching the existing `ux_asset_entries_nfc_tag_id_lower` pattern exactly — standard unique indexes already permit unlimited `NULL` rows (NULL is never equal to NULL), so no `WHERE x IS NOT NULL` partial-index trick is needed. `nfc_tag_id` is case-insensitive (`LOWER(...)`, same reasoning as the asset one — it's the same kind of value); `national_code`/`phone_number` are plain since they're digit strings. App-level pre-checks were added in `UserService.applyContactFields()` (mirrors the existing `existsByUsername` duplicate check, using `user.getId()` for self-exclusion on update — works for both create and update since `user` is either a fresh unsaved entity with `id == null` or the already-`findById`-fetched managed entity) so admins get a friendly Persian message (`ErrorTranslator`: `"Duplicate national code:"` / `"Duplicate phone number:"` / reuses the existing `"Duplicate NFC tag:"`) instead of a raw constraint violation — the DB constraint itself is still the source of truth and has a matching `ErrorTranslator.constraintSpecificMessage` entry as a backstop for races. Tests: `UserServiceTest` (`createRejectsDuplicateNationalCode`, `createRejectsDuplicatePhoneNumber`, `createRejectsDuplicateNfcTagCaseInsensitive`, `updateAllowsKeepingOwnContactFieldValues`, `updateRejectsContactFieldOwnedByAnotherUser`).
26. **A location is owned by MANY operational units (`location_units`), not one.** There is no `locations.unit_id` column — it was replaced by a composite-PK join table with the same shape as `unit_supervisors`/`unit_operators`. Everything that resolves unit scope starts there: `AssetUnitScopeSql.SCOPED_SUBFUNCTIONS_CTE` (its `loc_roots` CTE selects from `location_units`) and `LocationRepository.findIdsByUnitIdIn` (JPQL over `LocationUnit`). When adding a scope query go through one of those two — never re-derive ownership from `Location`, which no longer carries it. Write paths must rewrite the links wholesale via `AssetHierarchyService.saveLocation(loc, unitIds)` / `replaceLocationUnits` (delete-then-insert, de-duplicated: the composite PK rejects a repeated pair coming from a multi-select). The unit delete-guard moved from `LocationRepository.existsByUnitId` to `LocationUnitRepository.existsByUnitId`. Display reads use `unitIdsByLocationId`, which deliberately returns an entry for **every** requested id (empty list when unowned) so Thymeleaf never has to null-check a lookup — see gotcha #18 for why one bad template expression takes the whole page down. Excel import/export carry a comma-separated `unitCodes` cell (the importer also accepts the Persian comma).
27. **`log_sheet_templates.restrict_scope_to_unit` is a scope-PICKING rule, never an access rule.** With it off, the scope may point anywhere in the plant so a unit can be made responsible for assets outside its own locations, and the `scopeBelongsToOperationalUnit` validation is skipped. Access is completely unaffected: a log sheet is reachable only through `log_sheets.operational_unit_id` (`LogSheetAccessService.canView` / `findAvailablePool` / `findTeamOpenForSupervisor`), and `LogSheetBundleService` returns the sheet's entries **without** re-filtering them by asset unit scope — which is precisely what makes out-of-unit assets fillable. Do not "fix" that by adding an asset-scope filter to the bundle; it would break this feature. The privilege-escalation guard is that only non-unit-scoped roles may clear the flag: `LogSheetTemplateService.applyScopeRestrictionPolicy` forces it back to `true` for a unit-scoped supervisor (the flag arrives as a plain form field, so a UI-only check would be bypassable), and `LogSheetTemplateWebController.allowUnrestrictedScope` mirrors that on the option endpoints so the pickers cannot be coaxed into listing another unit's hierarchy. Reports and master-data lists stay location-unit scoped (`AssetAccessService`) and are intentionally *not* widened by this flag. Tests: `LogSheetTemplateServiceTest` (`createAllowsScopeOutsideUnitWhenRestrictionIsOff`, `unitScopedSupervisorCannotDisableTheScopeRestriction`, `adminMayUnrestrictScopeButUnitScopedUserMayNot`) and `AssetUnitScopeQueryIntegrationTest.locationSharedByTwoUnitsIsVisibleToBoth`.

---

## Preferred change style

- Match existing naming, package layout, and test style.
- Small, focused diffs; no drive-by refactors or unsolicited markdown.
- After schema/uniqueness changes: update validators, translators, Excel import, and tests together.
- When unsure about product intent (e.g. deleting `data_records`), ask — do not silently remove legacy surfaces.
