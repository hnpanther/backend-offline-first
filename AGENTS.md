# AGENTS.md — backend-offline-first

Guidance for AI coding agents working in this repository. Prefer this file + `README.md` over inventing architecture. When they conflict, **trust the code**.

---

## What this project is

Spring Boot **4.1** / Java **25** backend for an industrial **offline-first round / log-sheet** system:

- **Web admin** (Thymeleaf + session form-login) — master data, templates, RBAC, reports, batch Excel import.
- **Mobile API** (`/api/**`, JWT) — inbox, claim/release, batch complete, NFC lookup, per-sheet **bundle**.
- **PostgreSQL** schema owned by Flyway — a single `V1__initial_schema.sql`; every migration so far has been folded back into it while the app is still greenfield (see §2 for the fold recipe).
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
- **Asset ↔ SubFunction:** one **ACTIVE** asset per sub-function, unlimited **inactive** ones (`ux_asset_entries_active_sub_function`, a *partial* unique index, + `MasterDataUniquenessValidator`). Inactive assets remain NFC-findable but are **excluded from log-sheet preview/generation**. See gotcha #29 for the equipment-replacement contract.
- **Log sheets:** Generated from templates (manual/scheduled) **or created as custom/template-less sheets** via `CustomLogSheetService` + web `POST:/log-sheets/custom` (asset search `GET:/log-sheets/options/assets`). Supervisor picks **active** assets in a supervised unit; assets may span multiple classes; `template_id = null`; name → `template_name`; multi-class `field_definitions_snapshot` via `LogSheetFieldDefinitionsService.captureSnapshot(Collection)`. UI requires due date; service validates future `dueAt` when set. Created `PENDING` / `MANUAL`, then same claim/assign/complete/expire lifecycle. **`VOIDED`** soft-invalidates a `SUBMITTED` sheet (admin or unit supervisor: `POST:/log-sheets/{id}/void` / `unvoid`); excluded from parameter reports; restorable only to `SUBMITTED`. Reopen submitted → draft with new due: `POST:/log-sheets/{id}/reopen` (admin or unit supervisor). Optional web-only `notes` on the sheet (fill/complete). Late offline completes → `log_sheet_void_submissions`.
- **Mobile sessions:** `/api/**` JWTs are stateful — every token carries a `jti` backed by an `api_sessions` row (`ApiSessionService`). `JwtAuthenticationFilter` rejects a token whose row is missing, revoked, or expired, so signature validity alone is **not** enough. Login registers device/UA/IP and **supersedes** the user's other live sessions (one device per user). Admin page `/api-sessions` lists and revokes; token lifetime stays in `app_settings['auth.jwt.expiry_minutes']`. Revocation only bites when the device is online — that is by design for offline-first.
- **Web sessions:** the panel enforces **one concurrent session per user** via Spring's concurrent session control (`maximumSessions(1)` + in-memory `SessionRegistryImpl` + `HttpSessionEventPublisher` in `WebSecurityConfig`); a new login expires the old browser → `/login?expired`. Idle timeout: `server.servlet.session.timeout=60m`. `AppUserDetails.equals/hashCode` (by username) is **required** for the limit — do not remove it. `WebSessionMetadataStore` (in-memory) keeps IP/UA/login time; admin page `/web-sessions` (`WebSessionService`) lists/expires sessions addressed by a SHA-256 digest key — never expose raw `JSESSIONID`s. No DB table on purpose: sessions are non-persistent across restarts.
- **RBAC:** Permission code = `METHOD:path` (one DB row per **authority**; export/options/draft/bulk paths often reuse a parent authority). System roles: `ADMIN`, `HIGH_USER`, `SUPERVISOR`, `SENIOR_OPERATOR`, `OPERATOR`. Unit scope via `unit_supervisors` / `unit_operators` + `OperationalUnitScopeService`. Endpoint permission ≠ full access — check service rules (e.g. supervisor create-only templates; custom sheet unit + asset scope). Custom-create permissions are Flyway-seeded for `ADMIN` / `HIGH_USER` / `SUPERVISOR`.
- **Mobile data:** Lightweight `GET /api/bootstrap` = unit context only. Plant/assets for a round come from **`GET /api/log-sheets/{id}/bundle`**. Bundle already supports multi-class entries/fields and null `templateId` — custom sheets need no PWA change. Do not restore a full master-data delta bootstrap unless explicitly requested.
- **Batch import:** `/batch-import` → disk under `app.import.storage-path` (default `./data/imports`) + `import_jobs`. One active job system-wide; max **10 000** rows; sequential async pool.
- **Mobile sync batch cap:** `POST /api/log-sheets/batch` runs synchronously in one DB transaction per request (not async/queued like batch import) — it rejects over `app.sync.batch-max-items` (default **500**) with a `400` before touching the DB. Checked in `LogSheetService.submitBatch`, first line. If you add another sync-batch endpoint, apply the same guard rather than assuming client-side chunking.

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
| `src/main/resources/db/migration/V1__*.sql` | Yes — the baseline + every feature since (`api_sessions`, ops/monitoring permissions, log-sheet cancel, `nfc_fault_reports`, `asset_entries.status`, user contact-field uniqueness, the custom-log-sheet unit picker permission, `nfc_serial`, `log_sheet_action_log.comment`), all folded in while still greenfield — see §2 |
| `templates/`, `static/` | Yes — Thymeleaf UI |
| Separate frontend SPA / mobile app in this repo | **No** |
| Hexagonal adapters / CQRS / Kafka | **No** |
| Flyway V2+ | **No** — folded into V1 and deleted. Prefer editing V1 + the fold recipe in §2 while pre-production |

---

## Conventions agents MUST follow

### 1. Business logic location
- New rules → `service/`.
- Controllers stay thin.
- Hierarchy mutations → `AssetHierarchyService` (do not reimplement cascade in controllers).

### 2. Flyway / schema
> ⚠️ **`V1__initial_schema.sql` is the ONE migration file. Adding a `V2__…` is a decision, not a default.**
> The earlier "V1 is frozen" rule was reversed by the user: V2/V3/V4 were folded back into V1 on
> 2026-08-05 and deleted. While the app is pre-production the preferred move is still to edit V1
> directly and realign the dev DB (recipe below), so the schema reads as one coherent document
> instead of a pile of ALTERs. Ask before introducing a new numbered migration.

- Baseline script: `src/main/resources/db/migration/V1__initial_schema.sql` (commented in English) — currently the **only** migration file. Everything shipped so far has been folded into it: the original `V2__api_session_registry.sql`/`V3__web_session_permissions.sql`/`V4__ops_monitoring_permissions.sql`, then `V2__nfc_fault_reports.sql`/`V3__asset_status_field.sql`/`V4__user_contact_field_uniqueness.sql`, and most recently `V2__custom_log_sheet_unit_picker_permission.sql`/`V3__asset_nfc_serial.sql`/`V4__log_sheet_action_comment.sql`. See gotcha #22 for the permission-grant mechanics that make or break a fold. Keep this line in sync — it's the first thing an agent reads before assuming another migration exists.
- `spring.jpa.hibernate.ddl-auto=validate` — schema comes from Flyway only. A successful boot is therefore also proof that every entity column exists in the folded script.
- **Recipe for editing the already-applied V1** (what was actually done for the 2026-08-05 fold, all four steps required):
  1. Fold the DDL into the right `CREATE TABLE` / index / seed block — never leave a trailing `ALTER TABLE`.
  2. Delete the now-redundant files **and run `./mvnw clean`**. `target/classes/db/migration/` keeps stale copies of deleted files, and Flyway runs what is on the classpath — this is what makes a fold "fail" with a phantom duplicate-key error from a migration whose source file is already gone.
  3. In the dev DB: `DELETE FROM flyway_schema_history WHERE version IN (…)` for the folded versions.
  4. Boot once — Flyway reports `Applied to database` vs `Resolved locally` for V1 — then `UPDATE flyway_schema_history SET checksum = <resolved locally> WHERE version = '1'`. Take the number from Flyway's own output rather than recomputing it. Boot again to confirm `Successfully validated 1 migration`.
  <br>The physical schema is untouched by all of this: a fold only rewrites bookkeeping, because the folded statements were already applied.
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

### 7b. Master-data forms with large tables
- ~600 operational units and thousands of assets: form pickers must be **searchable remote** components (`remote-select.js` for one value, `remote-multi-select.js` for many), never a full table dump into a `<select>`. See gotcha #32.

### 8. Scheduling / templates
- Preserve `log_sheet_templates.next_run_at` on non-schedule edits.
- A template's assets come from **either** its scope+class (`SCOPE`) **or** a frozen `log_sheet_template_assets` list (`EXPLICIT`) — see gotcha #28 before touching generation, and never re-resolve an EXPLICIT template from its scope.
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

- **`POST /log-sheets/{id}/admin-reopen`** — web bookmark alias for `POST /log-sheets/{id}/reopen`; authority `POST:/log-sheets/{id}/reopen` only.
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

28. **A log-sheet template has TWO asset-selection modes and they must not be blurred together** (`log_sheet_templates.asset_selection_mode`, `SCOPE` | `EXPLICIT`, default `SCOPE`). `SCOPE` is the historical behaviour: every generation re-runs the hierarchy walk ∩ `class_id`, so an asset added to that scope later **automatically joins** the next sheet. `EXPLICIT` is the scheduled counterpart of a custom log sheet: the asset set is **frozen** in `log_sheet_template_assets` and must reproduce identically on every run — the *only* way an asset leaves it is being deactivated (`active = false`), and because the membership row survives, re-activating brings it back with no re-edit. Do not "helpfully" re-resolve an EXPLICIT template from its scope, and do not prune membership rows when an asset goes inactive — both would silently destroy the guarantee the mode exists for. An EXPLICIT template stores `scope_type`/`scope_id`/`class_id` as **NULL** (`class_id` had to be made nullable), so anything reading those three must tolerate null: `buildScopeSummary` returns null (a scope string would misrepresent a hand-picked set — same as `CustomLogSheetService`, which has always written null there), and display goes through `ReferenceLabelService.templateAssetSourceLabel(mode, ...)` rather than `templateScopeDisplayLabel`, which would render a bare «—». Its assets **may span several classes**, so the field-definition snapshot uses the multi-class `captureSnapshot(Set<Long>)` overload, never the single-class one. `resolveExplicitAssets` preserves the author's chosen order (map-by-id over the saved id list, not the query's return order). Access control mirrors gotcha #27 exactly: `LogSheetTemplateService.validateExplicitAssets` confines a unit-scoped supervisor to `findVisibleActiveByIdInAndUnitIds` while plant-wide roles get `findActiveByIdIn`, and `LogSheetTemplateWebController.assetOptions` reuses the same `allowUnrestrictedScope` guard — the ids arrive as plain form fields, so a UI-only check would be bypassable. `fk_lsta_asset` is `RESTRICT`, so `MasterDataDeleteService.assertDeletableAssetEntry` needs its own explicit guard (with an `ErrorTranslator` entry) or the user gets a raw constraint violation instead of a Persian message. In the form, EXPLICIT mode **disables** the scope/class inputs rather than just hiding them, so the browser neither enforces their `required` nor submits stale values. **No PWA change is needed** for any of this — a null `scope_summary` and multi-class entries are the exact shape custom log sheets have always produced, and the PWA already normalizes `scopeSummary ?? ''` and resolves field definitions per-entry class. Tests: `ExplicitTemplateAssetIntegrationTest` (frozen vs dynamic side-by-side, deactivate/reactivate, multi-class, delete guard), plus the `explicit*` cases in `LogSheetGenerationServiceTest` and `LogSheetTemplateServiceTest`.
29. **A sub-function is a SLOT, not a piece of equipment: one ACTIVE asset, unlimited INACTIVE ones.** `ux_asset_entries_sub_function_id` was replaced by the **partial** index `ux_asset_entries_active_sub_function ON asset_entries (sub_function_id) WHERE active`, because replacing a broken pump means deactivating it (kept for history, still attached to the same sub-function) and attaching its successor to that same slot. So: never assume `findFirstBySubFunctionId` returns *the* asset — use `findFirstBySubFunctionIdAndActiveTrue` for occupancy questions, and treat `findBySubFunctionId` as a genuine list. `MasterDataUniquenessValidator.validateAssetSubFunction` now takes the candidate's `active` flag and returns early when it is false; the Excel counterpart `validateAssetSubFunctionForImport` does the same **and** skips the in-file duplicate registration, so one sheet may legitimately carry several retired assets on one slot (this forced reading the `active` column *before* the sub-function check in `ExcelImportService`). The non-obvious half is NFC: an asset created without an explicit tag **inherits** the sub-function's `tag` (fallback `code`), and `nfc_tag_id` is globally unique — so a retired asset holding onto that inherited value would collide with its own replacement and make the swap impossible. `AssetEntryService.applyNfcInheritance` (which replaced `resolveNfcFromSubFunction`) therefore **clears** the tag when the asset is inactive **and** the tag equals the sub-function's tag or code, while keeping any other tag — that one is physically on that specific equipment. Do not "simplify" this into "clear the tag whenever deactivating" (it would destroy real per-asset tags) or into "only compare against `code`" (the inherit path prefers `tag`, so the tag case is the common one). Re-activating a retired asset while a successor is active is correctly rejected. Tests: `SchemaConstraintsIntegrationTest` (`replacingAnAssetOnASubFunctionHandsOverTheInheritedNfcTag`, `manyInactiveAssetsMayShareOneSubFunction`, `reactivatingARetiredAssetIsBlockedWhileASuccessorIsActive`) and the `deactivating*` cases in `AssetEntryServiceTest`.

30. **Log-sheet templates are ADMIN/HIGH_USER-only for writes; SUPERVISOR is read-only.** `POST:/log-sheet-templates` was removed from the SUPERVISOR grant list in V1, **and** `LogSheetTemplateService.assertCanManageUnit` now calls `canEditOrDelete()` (ADMIN || HIGH_USER) before the unit check — the permission set is user-editable, so the endpoint grant must never be the only gate. A supervisor still *sees* the templates of every unit they belong to (`visibleUnitIds()` → `getSupervisorScopeUnitIds`, which is a Set with downward closure, so multi-unit membership already works). In `log-sheet-templates.html` the add button **and the add modal itself** are wrapped in `th:if="${canEditTemplates}"` — gating only the button leaves a working form in the DOM. Side effect worth knowing: `canUnrestrictScope()` is `!isUnitScopedOnly()` which is exactly `ADMIN || HIGH_USER`, i.e. the same set as `canEditOrDelete()`. That makes the unit-confinement branches in `applyScopeRestrictionPolicy` and `validateExplicitAssets` (gotchas #27/#28) unreachable for writers today; they are kept deliberately as defence-in-depth so relaxing the role rule cannot silently reopen the escalation path — don't delete them as "dead code".

31. **`status` and the secondary title (`name_fa` / `asset_name_fa`) are reserved schema-only columns on all five hierarchy tables** (`locations`, `plant_systems`, `main_functions`, `sub_functions`, `asset_entries`) — the same treatment `asset_entries.status` already had (gotcha #24), extended on request. Nullable, unconstrained (no CHECK, no enum), with no entity field, DTO, API, or UI wiring. Safe under `ddl-auto=validate`, which only checks that entity-mapped columns exist in the schema, never that every schema column is mapped. `status` is **not** the same concept as `active` (which only gates log-sheet preview/generation). When wiring one up, add the field with an explicit `@Column(name = "...")` — no new migration required.

32. **The location form's operational-unit picker must stay searchable.** There are ~600 operational units in the real dataset, so the plain `<select multiple>` was replaced with `remote-multi-select.js` backed by `GET:/locations/options/operational-units` (guarded by `GET:/locations`). That component is now shared with the EXPLICIT template asset picker, so its user-visible noun is parameterised via `data-item-noun` — hardcoding a noun in the counter text breaks the other caller. Preselected values are declared as `<span class="remote-multi-preselected">` markup fed by `MasterDataOptionsService.operationalUnitOptionsByIds`, keeping the server the source of truth for the saved selection; the component then owns the chips and rebuilds the hidden `unitIds` inputs from them on every change. Never re-introduce a full `findAllByOrderByIdDesc()` dump into a form select for this table.
33. **Removed legacy surfaces — do not resurrect them.** Four things were deleted after being verified dead end-to-end (declaration count vs caller count, plus a live-DB check that the columns were never written): the nine `findByUpdatedAtGreaterThanEqual` repository methods (leftovers of a delta master-data sync that never shipped, zero callers), `log_sheets.local_id` + `uk_log_sheets_local_id` + `LogSheetRepository.findByLocalId` (never written — `log_sheets` rows are server-created, so a client key is meaningless; the **DTO** `localId` stays, it is echoed back in `LogSheetSubmitResult` purely so the PWA can correlate batch results), `log_sheets.sync_error` (never set server-side), and the deprecated `GET /api/master-data` endpoint with its `GET:/api/master-data` permission (a thin delegate to the same `BootstrapService` as `/api/bootstrap`, which the PWA already uses exclusively). Tests that used `/api/master-data` merely as "some protected endpoint" were repointed at `/api/bootstrap`. The remaining two items from that issue were retired later, once the earlier reasons for keeping them had lapsed: **`asset_classes.fields`** (the PWA fallback could never fire — `fields` was only ever a projection of `field_definitions`, written by a sync hook in `AssetClassWebController`, and the bundle always ships the authoritative rows; the Dexie upgrade that migrated from it disappeared when the PWA schema was collapsed) and the whole **`data_records` stack** — table, `RecordController`/`RecordService`/`RecordWebController`, both templates, the Excel export, the `GET /reports` record widgets, the asset delete-guard clause, three permissions, and the PWA's records table + sync drain. What made it safe to delete rather than a product decision: the PWA's only creator of a `DataRecord` (`DataEntryForm`) was already gone, so no new rows could ever appear, and the live dev DB held **0** rows. `GET /reports` now shows log-sheet and asset widgets only.

34. **A voided offline submission has its own detail page** — `GET /log-sheets/{id}/void-submissions/{voidId}` → `LogSheetVoidSubmissionViewService`. It renders the JSONB `payload` snapshot written by `LogSheetService.entriesToPayload`, deliberately **not** `log_sheet_entries`: those hold the authoritative state that superseded the submission, so reading them would show the wrong data and defeat the page. Field labels resolve per asset class (falling back to the sheet-wide snapshot when the asset row is gone), and the payload's own `assetName` is the fallback so a deleted asset stays readable. The path id is validated against `submission.logSheetId` — without that filter any voidId would be readable from any sheet URL. It reuses `GET:/log-sheets/{id}` rather than adding a permission, and applies `requireVisibleLogSheet` first.
35. **Master-data Excel sheets: the Persian name sits right after `name`, and locations no longer carry unit codes.** Every import layout gained an optional `nameFa` (`assetNameFa` for assets) as the column immediately after the name, which **shifted the index of every column after it** — `ExcelImportService` reads by position and the header row is never validated, so an off-by-one here is silent (e.g. an asset's `active` flag read from the class-name cell). The Persian column was placed there rather than appended precisely so that an out-of-date spreadsheet fails **loudly** on the next lookup ("Parent location not found: …") instead of quietly writing a unit code into a display field. Users must re-download the template after this change. Location import also dropped its `unitCodes` column entirely: an imported location starts unowned and units are attached from the location form (the *export* still emits `unitCodes`, which is fine — export and import are no longer symmetrical for that one column). Current layouts live in the `// Columns:` comment above each importer and in the `String[] cols` of each controller's `/import-template`; keep the three in sync. Tests: `ExcelImportFormatIntegrationTest` (builds real .xlsx files) and `PersianNameWebFormIntegrationTest` (the update handlers copy fields one by one, so a missing `setNameFa` would drop the value on every edit while create still looked fine).

36. **Never filter a log-sheet list in Java after fetching a whole unit's history.** `LogSheetAccessService.findTeamOpenForSupervisor` used to call `findByOperationalUnitIdIn` and then filter to ASSIGNED/IN_PROGRESS in a stream. Measured at a realistic 3-year history for one unit (22k rows, 10 actually open): **63 ms → 1.4 ms**, 815 → 5 shared buffers, seq scan → index scan. Worse than the timing, every one of those rows became a managed Hibernate entity including the large `notes` and `field_definitions_snapshot` columns. It now uses `LogSheetRepository.findOpenInUnitsAssignedToOthers` (status set + `assigneeUserId <> supervisor`, pushed into SQL) backed by the composite `idx_log_sheets_unit_status` — the two single-column indexes cannot serve the combined predicate as cheaply. The query is `ORDER BY id DESC` on purpose: the PWA renders `teamOpen` in server order without sorting, and switching from a seq scan to an index scan would otherwise have reshuffled the list unpredictably. `findByOperationalUnitIdIn` was deleted from `LogSheetRepository` so the pattern cannot come back by accident (the identically-named method on `LogSheetTemplateRepository` is unrelated and still used). Tests: `TeamOpenInboxQueryIntegrationTest`.
37. **Supervision cascades DOWN the unit tree; operation does NOT.** This is the single rule behind every unit-scoped access decision, and it was previously wrong in both directions. Supervising unit A means supervising A *and every descendant* (B, C, and their children) — a supervisor is responsible for the whole branch. Operating unit A means unit A **and nothing else**: operators of A and operators of B are different teams that merely share a manager, so an operator of A must never see, claim, or be assigned B's work. What was broken: `getOperatorScopeUnitIds` and `getAccessibleUnitIds` both ran `expandDownward` over the operator's units, which leaked every sub-unit's claimable pool, sheet visibility (`canView`), master-data scope (`AssetAccessService`) and assignment eligibility to a parent unit's operators; meanwhile the custom-log-sheet unit picker (`LogSheetWebController.customCreatableUnits`) used the **un**expanded `getSupervisedUnitIds`, so a supervisor of A was only offered A even though `CustomLogSheetService.createCustom` already accepted the whole branch — the UI was narrower than the rule it enforced. Now: `getAccessibleUnitIds` = supervisor scope (expanded) ∪ operated units (**not** expanded), `isOperatorOf` checks the direct set, and `getOperatorScopeUnitIds` is gone so nothing can call the expanding version by mistake. `OperationalUnitScopeService` is the **only** place allowed to walk the unit hierarchy — no other class calls `getParentId()` on a unit, and it must stay that way. Tests: `OperationalUnitScopeServiceTest` (the A/B/C/D fixture with the rule spelled out) and `UnitHierarchyScopeIntegrationTest` (same scenario end-to-end against PostgreSQL, including the claimable pool).
38. **A parent-unit supervisor may DO sub-unit work, but only that sub-unit's own operators may be assigned it.** This falls out of gotcha #37 and is the intended product rule, so do not "tidy" either half away. `LogSheetAssignmentService.requireSupervisorAndTarget` checks the actor with `isSupervisorOf` (expanded, so a supervisor of A passes for a sheet in B) and the target with `isOperatorOf` (**direct**, so an operator of A is rejected for B's work — the eligible people are B's own operators). `canOperateUnit` deliberately includes supervisors, which is what lets a supervisor of A take over and complete a sheet in B or D personally. The web assignment dropdown is already correct because `unitOperators(unitId)` reads `findByUnitId` on the sheet's own unit. Tests: `UnitHierarchyScopeIntegrationTest` (`assigningSubUnitWorkIsAllowedOnlyToThatSubUnitsOwnOperators`, `parentUnitSupervisorMayTakeOverSubUnitWorkFromItsOperator`, `takeoverDeeperInTheBranchWorksTooButNotInAnUnrelatedUnit`).

39. **The operational-unit parent picker is searchable, and the unit tree is cycle-guarded.** The parent `<select>` used to render `${units}` — which is only the **current page** of the paged list, so with hundreds of units most possible parents were simply never offered. It is now a `remote-select` backed by `GET:/operational-units/options/parents` (guarded by `GET:/operational-units`, `excludeId` drops the unit being edited). Separately, `OperationalUnitService.update` only blocked the *direct* self-parent, so A→B→A was constructible. That matters now that the tree drives access control: a cycle would make two units each other's descendant and hand every supervisor on the loop the other's scope, besides making ancestor walks non-terminating. `requireNoParentCycle` walks the proposed parent's ancestors with a hop limit derived from the row count, so even a pre-existing loop cannot spin. Persian message via `ErrorTranslator`. Tests: the `update*Cycle*` cases in `OperationalUnitServiceTest`.
40. **The Excel-import modal's on-page description text and the importer's actual column layout are two independent things that can drift apart.** The `nameFa` column added in gotcha #35 and the `unitCodes` column dropped from locations were both applied to `ExcelImportService` and the `String[] cols` template generator, but the human-readable description text inside each page's import modal (`templates/{locations,plant-systems,main-functions,sub-functions,asset-entries}.html`) was never updated — it kept listing `unitCode` for locations (removed) and omitted `nameFa` everywhere (added). Nothing type-checks this: the description is static Persian prose next to a file input, unrelated to the parser that actually reads the uploaded file, so a mismatch here doesn't fail a test or even show up in behavior — a user just gets misled into building a spreadsheet with the wrong columns. Whenever a master-data Excel layout changes, grep the corresponding template for the `ورود از اکسل` modal body and update its column list and notes in the same change, not just `ExcelImportService`/`cols`.
41. **The custom-log-sheet unit picker is searchable and scoped like every other operational-unit picker, and its permission is seeded directly in V1.** `LogSheetWebController.customCreatableUnits()` used to render every accessible unit into the modal's `<select>` server-side; with ~600 units that's both slow and unusable, so it's now a `remote-select` backed by `GET /log-sheets/options/units` (new `GET:/log-sheets/options/units` permission, seeded in V1 — ADMIN/HIGH_USER via the blanket grants, SUPERVISOR via its explicit IN-list; see gotcha #22). Scoping reuses `OperationalUnitScopeService.getSupervisorScopeUnitIds` (gotcha #37's expanded set), matching the `customCreatableUnitIds()` fix from gotcha #37 itself. Separately, `OptionEndpointScopeIntegrationTest` was missing `@Transactional`: it and its sibling `UnitHierarchyScopeIntegrationTest` both rely on `WithAppUser` injecting a **fixed** `id=1L` principal, so any `UnitSupervisor` link one test method creates for that id survives into the next test method in the same class when there's no rollback — `anOperatorWithNoSupervisedUnitsGetsAnEmptyCustomUnitPicker` failed with 2 leaked units instead of 0 until `@Transactional` was added to the class, mirroring what `UnitHierarchyScopeIntegrationTest` already did for the same reason. Any new integration test class that uses `WithAppUser` and creates supervisor/operator links **must** be `@Transactional` unless it deliberately avoids linking anything to the fixed test-user id. Tests: `OptionEndpointScopeIntegrationTest` (`adminSeesEveryUnitInTheCustomLogSheetUnitPicker`, `supervisorCustomUnitPickerIncludesTheWholeSupervisedBranchButNothingElse`, `anOperatorWithNoSupervisedUnitsGetsAnEmptyCustomUnitPicker`, `customUnitPickerRequiresThePermission`).
42. **`asset_entries.nfc_serial` is the physical chip UID and must never be folded into the `nfc_tag_id` inheritance logic.** The two are deliberately separate columns: `nfc_tag_id` is the *logical* lookup key that inherits from the sub-function's tag (fallback: its code) when blank and is *released* on deactivation so the replacement equipment can claim it (gotcha #41 / `applyNfcInheritance`); `nfc_serial` is the hardware serial burned into the chip, e.g. `00:aa:34:9f:12:cd`. It is normalized (trim → null) in `AssetEntryService.normalize` but pointedly **not** passed through `applyNfcInheritance` — inheriting it would make every asset on a tagged sub-function claim the same physical chip and collide on `ux_asset_entries_nfc_serial_lower`; releasing it on deactivation would be equally wrong, since a retired asset keeps the chip it was fitted with. Optional, but unique when supplied — a plain (non-partial) case-insensitive unique index, exactly like `nfc_tag_id`, relying on Postgres treating NULLs as distinct. It is snapshotted onto `log_sheet_entries.nfc_serial` at generation time in **both** creation sites (`LogSheetGenerationService` and `CustomLogSheetService` — they are near-duplicate loops, so a change to one almost always belongs in the other) and carried to the PWA in `LogSheetEntryDto`, which is the only channel by which an offline device can learn it. It is **server-authoritative**: `mergeMobileEntryUpdates` copies known fields one by one and never reads `nfcSerial` off the request, so adding it to the DTO did not open a client write path — `aClientCannotOverwriteTheStoredNfcSerialOnSubmit` pins that. On the PWA it rides alongside `nfcTagId` everywhere (types, `mapServerEntryToLocal`, upload payload) and is indexed in **Dexie v11**; that version is a pure additive index with no `.upgrade()` callback, which is safe precisely because no data is reshaped. Excel gained the column directly after `nfcTagId`, shifting the three columns after it — see gotcha #35 for why mid-layout insertion is the deliberate choice, and #40 for remembering to update the modal prose at the same time. Tests: `AssetEntryServiceTest`, `MasterDataUniquenessValidatorTest`, `ExcelImportFormatIntegrationTest`, `CustomLogSheetIntegrationTest`, `MobileBundleApiIntegrationTest`, `mergeLogSheetBundle.test.ts`.
43. **`log_sheet_action_log.comment` is optional by contract, and the actions that take it use a two-overload logger so the other call sites stay untouched.** EXTEND / CANCEL / VOID / UNVOID / ADMIN_REOPEN accept a free-text reason from the web modal; every other action passes none. `LogSheetActionLogger.record(...)` therefore has a **9-arg** overload (with comment) and an **8-arg** one delegating with `null` — that is why adding the feature touched five methods instead of nineteen call sites, and why `anActionWithNoCommentParameterRecordsNoComment` exists to prove the overload never leaks a default. **Watch out when mocking**: `LogSheetAssignmentServiceTest` mocks `actionLogger`, so the 8-arg overload is *not* executed for those five actions and existing `verify(actionLogger).record(…8 args…)` assertions had to move to the 9-arg form — a mock does not run the delegating body. This bit twice (once for CANCEL/VOID, again for UNVOID when it was wired later); expect it for any further action you convert. Blank is normalized to `null` (never `""`) so the history template can just test `h.comment != null`; over-limit is **rejected, not truncated** (a truncated reason reads as a complete one), and `normalizeComment` runs **before** `require(sheetId)`/permission checks so a bad comment cannot leave a half-applied action — which is also why an over-limit test must not stub the repository or scope service or Mockito reports `UnnecessaryStubbing`. Note `adminReopen` is a legacy path alias delegating to `reopen`, so its signature had to grow the parameter too or the comment would be silently dropped for anyone still posting to `/admin-reopen`. UI-wise all five actions moved from `confirm()` to Bootstrap modals; the extend and reopen modals need `data-bs-focus="false"` or Bootstrap's focus trap fights the Persian date-picker popover (same reason as the custom log-sheet modal), and `persian-datetime-picker.js` already re-inits on `shown.bs.modal`, so a picker inside a modal needs no extra wiring. Each modal repeats its trigger's `th:if` + `sec:authorize` per gotcha #30. The comment character counter lives in its own IIFE in `log-sheet-detail.js` because the pre-existing `DOMContentLoaded` handler bails out early (`if (!searchInput) return;`) — appending inside it would silently never run. Tests: the comment cases in `LogSheetAssignmentServiceTest` and `LogSheetVoidAndNotesIntegrationTest`.
44. **The chip serial has two write paths and one read path, and only the read path is allowed to be strict.** Write path A is the admin asset form / Excel import (gotcha #42). Write path B is new: `POST /api/asset-entries/{id}/nfc-serial` — the PWA's admin-only NFC inspect page sends the UID it just read off a physical tag so an admin can bind chip → asset in the field instead of typing a UID by hand. That endpoint carries a **method-level** `@PreAuthorize` that overrides `AssetEntryController`'s class-level one, because the class-level permission is the read/list permission and a device that may *look up* an asset by tag must not thereby be able to *rewrite* which chip that asset answers to (`EndpointSecurityTest.nfcLookupPermissionDoesNotAllowWritingTheChipBinding` pins exactly that). It goes through `AssetEntryService.updateNfcSerial`, which touches only `nfc_serial` + `updated_at` and reuses `MasterDataUniquenessValidator` so the case-insensitive uniqueness rule is identical on both write paths; blank clears the binding. Its permission row sits in V1 **before** the blanket grants, so ADMIN/HIGH_USER are covered automatically and it must **not** appear in the SUPERVISOR/OPERATOR/SENIOR_OPERATOR `IN (...)` lists (gotcha #22). The read path is the PWA setting `nfcStrictSerialMatch` (§ PWA below) — **default off**; the serial is a *second* factor there, never a replacement for Record 1.

45. **PWA `nfcStrictSerialMatch` is opt-in AND-matching, and every non-scan entry path deliberately bypasses it.** `services/nfc/matchLogSheetEntry.ts` is the single matcher for the log-sheet fill page. Off (the default, and the behaviour that predates the setting) it resolves the NDEF Record 1 payload to a tag id and finds the entry by `nfcTagId` — byte-for-byte the old `findLogSheetEntryByNfcTag`. On, the entry's stored `nfcSerial` must *also* equal the chip UID (`NDEFReadingEvent.serialNumber`), and an asset with **no** stored serial is **rejected** (`serialMissing`), not waved through — falling back to Record 1 there would make the setting meaningless exactly where it matters. `LogSheetFillPage.handleTagId` passes `strictSerial: source === 'nfc' && …`, so manual tag entry and the NFC-fault manual-entry fallback are untouched by design: neither has a hardware serial to offer, and gating them would strand any operator whose tag is physically broken. Serials are compared case-insensitively with `:`/`-`/space stripped, because the reader emits `04:33:26:…` lower case while an admin typing into the asset form may use any of those forms. `enrichEntriesWithNfc` now backfills `nfcSerial` from the local asset table alongside `nfcTagId` (the flag it returns was renamed `nfcBackfilled` accordingly) so a bundle captured before the serial was recorded can still verify; the backfilled value rides along in `toBatchPayload` exactly as `nfcTagId` already did, which is inert server-side since the submit path never reads `nfcSerial` off the request (gotcha #42). The toggle lives in Settings → NFC and is disabled for non-admins. Tests: `services/nfc/matchLogSheetEntry.test.ts` (6 default-mode cases exist specifically to prove the old behaviour did not shift).

46. **Reporting scope is deliberately wider than registry scope, and the two must not be merged.** `AssetAccessService` now answers two different questions with two different method families. `findVisible*` = "which assets sit in locations this unit owns" (`location_units` → location tree → systems → main functions → sub-functions, via `AssetUnitScopeSql.SCOPED_SUBFUNCTIONS_CTE`) — correct for master-data lists, Excel exports and the asset registry. `findReportable*` = "which assets is this user responsible for" — that CTE (`REPORTABLE_ASSETS_CTE`) **unions** the ownership arm with every asset appearing on a log sheet whose `log_sheets.operational_unit_id` is in the caller's accessible units. The bug this fixes: log-sheet authority travels through `operational_unit_id` alone and gotcha #27 makes a template with `restrict_scope_to_unit = false` *deliberately* put out-of-unit assets on a unit's sheet, so filtering the asset-parameter report by location ownership denied supervisors the readings of work they had just been required to perform. It was total, not partial, in any environment where `location_units` is unpopulated — the live dev DB had **0 of 180** locations mapped, so the CTE's `loc_roots` was empty and *every* unit-scoped user saw zero readings for every asset, including their own unit's sheets. Because `visibleUnitIds()` already returns the downward-expanded supervisor scope (gotcha #57), a parent-unit supervisor picks up their child units' sheets with no extra work. Use `UNION`, never `UNION ALL` — an asset matching both arms must appear once, and the paging count query must reuse the same CTE or the count and the rows disagree. Only the asset-parameters report and its asset picker were switched over; the inventory report keeps ownership semantics on purpose. Tests: `AssetReportingScopeIntegrationTest` (6 cases, including the unmapped-`location_units` reproduction and the no-duplicate check).

47. **The management report suite is read-only, unit-scoped, and computed two different ways on purpose.** Six pages under `/reports/*` (`overview`, `compliance`, `exceptions`, `data-quality`, `workforce`, `actions`) all sit behind the existing `GET:/reports` authority and go through `ManagementReportService`, which takes `AssetAccessService.visibleUnitIds()` — `null` = unrestricted admin, empty = no rows — so a parent-unit supervisor picks up their children's numbers via the downward expansion that scope already carries (gotcha #57). **Counting windows key on `createdAt`**, so a sheet belongs to the period it was *raised* in: window on completion instead and a backlog cleared today flatters today while hollowing out the month it was actually owed. Two things are deliberately *not* SQL. **Lateness percentiles** are computed in Java over the raw samples, and only overshoot counts — early completions are dropped rather than averaged in as negative lateness, which would let punctual work cancel out overdue work. **Out-of-range detection** cannot be SQL at all: severity compares a value inside `form_data` against that field's `validation` JSON, so it is evaluated in Java over at most `OUT_OF_RANGE_SHEET_SCAN_LIMIT` (500) recent submitted sheets, reading definitions from the sheet's own `field_definitions_snapshot` — the ranges in force **when the reading was taken**, so re-tuning a range never rewrites history — and filtering the snapshot by the entry's `classId` so a key shared between two classes is not judged against the wrong ranges. Watch the enum names in the JPQL: `LogSheetEntrySource` is `WEB` / `PWA_NFC` / `PWA_MANUAL`, and a wrong constant fails at **context startup** with `SemanticException: Could not interpret path expression`, not at query time. All derived rates guard their denominators (`ManagementReportRowsTest` pins which denominator each one uses — `complianceRate` counts cancelled work against the unit but excludes work still open).

48. **`log_sheet_entries.max_severity` / `breached_fields` are denormalised, and the only safe place to compute them is the write.** Severity is not a SQL predicate — the value lives in `form_data` (jsonb) and its thresholds live in *another row's* `validation` json — so "which assets are out of range" used to mean opening every candidate sheet and evaluating in Java, capped at 500 sheets. Fine for a person opening a page, useless for a job polling every few minutes. `EntrySeverityEvaluator.apply(entry, fieldDefs)` now stamps the verdict at save time and the exception report is one indexed query (`idx_log_sheet_entries_breaches`, partial on WARNING/DANGER). **Every path that mutates `form_data` must call it immediately after `setFormData(...)`** — today exactly two: `LogSheetService.mergeMobileEntryUpdates` (mobile batch) and `applyWebEntryValues` (web draft save *and* web completion, which share it). Call it on **every** write, not only when the value changed: a resubmit, a correction and a clear all have to leave the flag agreeing with what is stored. A path that skips it leaves a stale flag, which is worse than no flag because it reads as authoritative — `EntrySeverityPersistenceIntegrationTest`'s correction/downgrade/clear cases are the tripwire. Three encoding decisions matter: `NULL` means *never evaluated or no values* and is deliberately distinct from `'OK'` (evaluated and clean), so pre-existing rows are invisible rather than falsely clean; `breached_fields` lists danger keys before warning keys so a reader needs only one column; and definitions are resolved through `LogSheetFieldDefinitionsService`, which prefers the sheet's `field_definitions_snapshot`, so re-tuning a range never re-judges history. The class filter in the evaluator is not optional — a multi-class sheet snapshots every class's definitions and two classes may share a key like `pressure` with different ranges. `EntrySeverityBackfillRunner` stamps pre-existing rows once at startup; it is idempotent (selects only `max_severity IS NULL`) and self-disabling, so leave it in place.

49. **Report cost at the target load is measured, not assumed — and two shapes were fixed because measuring found them.** Benchmarked on a synthetic year (10 sheets/day x 50 assets x 365 = 3,650 sheets / 164,250 entries): compliance 0.8 ms, operator throughput 0.4 ms, exceptions 7 ms, breach counts 12 ms, manual-vs-scanned 77 ms, last-reading-per-asset 64 ms. Anything reading `log_sheets` stays cheap forever (~3,650 rows/year). The exception report does **not** degrade because `idx_log_sheet_entries_breaches` is *partial* — it indexes only WARNING/DANGER rows, which are a small minority, so it stays small however big the table gets; that is what makes it safe for an external system to poll. The two full-scan aggregates (`entrySourceSplitByUnit`, `lastSubmittedReadingPerAsset`) grow linearly with entry count — ~0.35 s at 5 years, ~0.7 s at 10 — and the fix when they matter is a nightly rollup table, **not** more indexes, since they aggregate over every row in the window. Two bugs the measurement exposed: `overview()` used to derive its danger/warning figures by fetching `outOfRangeReadings(...)` and counting the list, which was silently capped by the page limit, so any period busier than the cap under-reported — it now counts in SQL via `countBreachesBySeverity`; and `openNfcFaults()` called `findAll()` and filtered in Java, now `findOpenForUnits(unitIds)`. Known, documented limit: `assetsWithoutRecentReadings` inspects `limit * 4` assets, which is every asset at hundreds but a sample at tens of thousands.

50. **`RoleService.duplicateRole` copies permissions only, and never the `systemRole` flag.** It delegates to `createRole` so the duplicate-code check and `systemRole = false` live in exactly one place. Copying the flag would create a second undeletable role; copying user assignments would grant the new role to everyone who held the original, which is the opposite of what an administrator building a narrower variant wants — `duplicateDoesNotCopyUserAssignments` pins that with `verifyNoInteractions(userRoleRepository)`. A blank description inherits the source's (blank means "not supplied", not "deliberately empty"). The web form is **one shared modal** whose `th:action` is a placeholder (`/roles/0/duplicate`) retargeted by JS to the clicked row — one modal instead of N in the DOM, and Thymeleaf still injects the CSRF hidden field because the element is a `th:action` form. Note the web chain **has CSRF enabled** (only the `/api/**` chain disables it), so any curl-based test of a web form must pull `_csrf` from the rendered page first.

---

## Preferred change style

- Match existing naming, package layout, and test style.
- Small, focused diffs; no drive-by refactors or unsolicited markdown.
- After schema/uniqueness changes: update validators, translators, Excel import, and tests together.
- When unsure about product intent, ask — do not silently remove a surface that might still be someone's workflow.

---

## Photo & audio fields (built)

Class fields can hold photos and voice notes captured on the tablet. This section records the
decisions behind the shipped implementation — read it before touching anything under
`AttachmentService`, `AttachmentStorageService`, or the PWA's `attachmentSync`.

### The decision that shapes everything else: no base64 in `form_data`

Base64 inflates by 33% and — much worse — puts the bytes inside the `jsonb` column. Every read
of the log sheet, every bundle sync, every backup and every report would then carry megabytes
of binary. At the project's own target load a daily sheet is tens of GB a year: fine on a
filesystem, not fine inside `log_sheet_entries.form_data`.

So **the JSON holds references and the bytes live on disk**:

```json
{ "pump_photo": { "type": "attachment", "ids": ["a7f3…", "b2c1…"] } }
```

`AttachmentReferences` parses this on the server and `attachmentIdsOf` on the client. Both are
**tolerant on shape** (a bare array or a single string id is accepted, for data written by an
older client) and **strict on content** (blank and `"null"` entries are dropped — they would be
references that can never resolve). `AttachmentReferences.extract` additionally requires
reference *shape* before reporting a field as holding ids, so `{"pressure": 42}` is never
mistaken for an attachment field.

### Server side

| Piece | Where |
|-------|-------|
| Table | `attachments` in `V1__initial_schema.sql` — `id` VARCHAR(36) **minted by the client**, `log_sheet_id` (CASCADE), `asset_id` (RESTRICT), `field_key`, `kind` (IMAGE/AUDIO/VIDEO), `mime_type`, `size_bytes`, `sha256`, `width`/`height`/`duration_ms`, `storage_key` UNIQUE, `uploaded_at`, `created_by_user_id` |
| Bytes | Filesystem under `app.attachments.storage-dir` (default `./data/attachments`), date-sharded `2026/08/07/<uuid>.<ext>`. **Not** a DB blob — blobs wreck backup and replication. `storage_key` is the indirection that lets S3/MinIO replace the filesystem later without a schema change. |
| Size cap | `app.attachments.max-file-size-bytes` (default 10 MB) |
| Endpoints | `POST /api/attachments` (multipart), `GET /api/attachments/{id}`, `DELETE /api/attachments/{id}` — each with its own permission, granted to SUPERVISOR / OPERATOR / SENIOR_OPERATOR |
| Field types | `image` and `audio` in the asset-class field form; `video` is plumbed end-to-end server-side but deliberately not offered in the UI yet |

Three rules in there are security, not tidiness:

- **Access is decided by the owning log sheet, never by knowing an id.** Every method resolves
  the sheet through `LogSheetAccessService.requireVisibleLogSheet` — *including the idempotent
  re-upload path*, which returns an existing row before any other lookup and would otherwise be
  the one unguarded door. A UUID in a URL is not a capability.
- **The declared content type is ignored.** `detectMimeType` reads magic bytes; a client can
  label an executable `image/webp`, and the download route would later serve it back under that
  type. Unrecognised bytes are refused outright, never treated as "unknown but probably fine".
  The one genuinely ambiguous case is Matroska — `MediaRecorder` emits audio and video WebM with
  an identical EBML header — so `resolveWebmType` decides from the *field's* kind, which is
  server-side data rather than client input.
- **Storage keys are generated, never accepted.** `resolveWithinRoot` re-checks that a resolved
  path is still under the root before every read or delete.

The kind a field accepts comes from the **sheet's own frozen `field_definitions_snapshot`**, not
from the request and not from the live class schema. That is what stops a photo being attached
to a numeric field, and it means the answer matches the form the operator actually saw.

### Client-side compression is mandatory, not an optimisation

A tablet camera produces 8–12 MP files. `src/utils/mediaCapture.ts` compresses **before** writing
to IndexedDB, and the original is discarded:

- **Photos** — canvas → `image/webp` at quality 0.8, capped at 1600 px on the long edge
  (`MAX_IMAGE_DIMENSION`). 8 MB → 200–400 KB, still ample to read a gauge face. JPEG fallback for
  anything that cannot encode WebP; a failed encode is an error rather than a silent
  pass-through of the original, which would defeat the point.
- **Audio** — `MediaRecorder` with `audio/webm;codecs=opus`, mono, 24 kbps, hard-capped at 120 s
  (`MAX_AUDIO_DURATION_MS`). The microphone track is stopped on **every** exit path — a live
  track leaves the browser's recording indicator on, which users reasonably read as the app
  spying on them.

### PWA storage

Dexie `version(2)` adds
`attachments: 'id, logSheetLocalId, logSheetServerId, assetId, fieldKey, syncStatus, createdAt'`,
holding **`Blob`s, not base64**. Previews use `URL.createObjectURL` and every URL is revoked on
change and on unmount — leaking them pins whole blobs in memory on a tablet that stays on one
screen for a shift.

The row outlives the blob. `purgeSyncedAttachmentBlobs` drops the bytes of anything safely on the
server for more than 7 days while keeping the metadata, so the field still shows the attachment
and opening it re-fetches. `deleteSyncedAttachmentsForLogSheet` retires rows when the cleanup
pass ages out a local sheet — *only* the synced ones; a pending row carries its own
`logSheetServerId` and must still be delivered.

### Sync: a separate queue, deliberately

**Attachments do not ride inside `POST /api/log-sheets/batch`.** That payload stays small and
atomic; a 400 KB photo inside it means every dropped connection retries the whole shift's
readings.

1. The sheet submits with attachment **ids only** — unchanged code path.
2. On `SUBMITTED`/`DUPLICATE`, `bindAttachmentsToServerSheet` stamps the new server id onto that
   sheet's attachment rows. Until this lands, `getPendingAttachments` skips them: the server keys
   an attachment to a log sheet, so uploading earlier is impossible.
3. `syncPendingAttachments` uploads them **one at a time**. Sequential is deliberate — on a weak
   field link three concurrent uploads are slower than one, far likelier to time out, and would
   hold three blobs in memory at once.
4. The whole pass sits in its own try/catch: a photo that will not upload must never fail a
   log-sheet submission that already succeeded.

**Failure classification is the part worth understanding.** `ApiError` with status 0 means the
transport died — the row is left *completely untouched* (not marked failed) and the pass stops
rather than hammering a dead link, because marking it failed would make a tunnel look like a
rejection. A 4xx other than 401/408 is permanent: the server examined the request and refused it,
and identical bytes get an identical refusal. 401 stays retryable — an expired session is not a
bad payload. 5xx stays retryable but records why it did not go.

**Voiding a sheet does nothing to its attachments.** Void is a soft, reversible status change,
and `requireVisibleLogSheet` checks the operational unit rather than the status — so a voided
sheet still serves downloads and still accepts a queued upload that arrives late. Both are
deliberate: destroying evidence on a reversible action would make the un-void meaningless, and
refusing the late upload would strand the file while leaving a dangling reference in `form_data`.

**A permanently refused upload is parked on the client**, not retried forever — see
`permanentFailure` in the PWA's `storage/attachments.ts`. If you add a new 4xx rejection reason
here, be aware it becomes terminal on the device until someone hits retry.

**Idempotency:** the client-minted UUID is the unique key. Re-uploading it returns 200 with the
existing row and does **not** rewrite the bytes — the first upload won, and a differing retry is a
client bug rather than a correction.

### Storage pressure

`src/utils/storageQuota.ts` calls `navigator.storage.persist()` at startup (a refusal is normal —
browsers grant it to installed PWAs, not casual tabs) and checks `estimate()` **before** opening
the camera. Refusing up front tells the operator to sync while they can still act on it; a failed
IndexedDB write after the shot is taken loses the shot. A browser that reports nothing is treated
as "not low" — refusing a capture because we could not measure the disk would be the worse
failure.

### Easy things to forget

- **HTTPS** — `getUserMedia` requires it. The existing mkcert/nginx setup already provides it.
- **`capture="environment"`** on the file input is what makes Android open the camera rather than
  the gallery.
- A media field cannot use react-hook-form's `required`: its value is an object once the control
  renders, and every object is truthy. `buildValidationRules` counts ids instead.
