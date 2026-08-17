# Security, Roles and Permissions

Who may do what, where each rule is enforced, and the places where the answer depends on a
role's **code** rather than on its permissions.

> **Authorities are `METHOD:path` strings.** `GET:/locations`,
> `POST:/log-sheets/{id}/complete` — including the literal `{id}` braces. The permission *is*
> the route; there is no mapping layer in between. See [schema.md](schema.md#roles-permissions-role_permissions-user_roles).

---

# 1. The three layers

An authority is necessary but rarely sufficient. Every request passes up to three independent
checks, and they answer different questions:

| Layer | Question | Where |
|---|---|---|
| **Endpoint** | May this role call this route at all? | `@PreAuthorize("hasAuthority('…')")` on the handler |
| **Scope** | May this user act on *this unit*? | `OperationalUnitScopeService`, `AssetAccessService`, `LogSheetAccessService` |
| **Object** | May this user see *this row*? | `requireVisibleLogSheet(id)` and friends |

**Holding the endpoint permission proves nothing about the second and third.** A supervisor
with `GET:/log-sheets/{id}` still cannot open a sheet belonging to a unit they do not
supervise: `LogSheetAccessService.requireVisibleLogSheet` throws `AccessDeniedException` first.

Several service-layer rules are checked in the **service**, not only on the controller,
precisely because a service is reachable from more than one route — see
`AssetStatusRequestService.requireDecider()` for the pattern and its reasoning.

---

# 2. The five system roles

Counts below are the seeded grants, verified against a live database.

| Role | Persian | Scope | admin | api | general | master-data | operational | organization | reports |
|---|---|---|---|---|---|---|---|---|---|
| `ADMIN` | مدیر سیستم | plant-wide | **32** | 15 | 1 | 42 | 24 | 8 | 1 |
| `HIGH_USER` | کاربر ارشد | plant-wide | 3 | 15 | 1 | 42 | 24 | 8 | 1 |
| `SUPERVISOR` | سرپرست | own units + sub-units | — | 14 | — | **1** | 24 | — | 1 |
| `SENIOR_OPERATOR` | اپراتور ارشد | own units only | — | 11 | — | — | 8 | — | — |
| `OPERATOR` | اپراتور | own units only | — | 11 | — | — | 6 | — | — |

`HIGH_USER`'s three `admin` permissions are exactly the batch-import trio
(`GET:/batch-import`, `GET:/batch-import/jobs`, `POST:/batch-import`) — the category is
`admin`, but the capability is not.

`SUPERVISOR`'s single `master-data` permission is **`GET:/log-sheet-templates`** — read only.
See [§5 F2](#f2--supervisors-cannot-create-templates-documentation-was-wrong).

## The two scope axes, and how they combine

Access has **two independent dimensions**, and confusing them is the usual source of "why can
this person see that?":

| Axis | Question | Decided by | Cascades? |
|---|---|---|---|
| **Capability** | What kind of actor are you? | `CAP:*` in `role_permissions` | n/a |
| **Unit scope** | Which units do you own? | `unit_supervisors` / `unit_operators` | supervision yes, operation no |

`CAP:SCOPE_PLANT_WIDE` **overrides** the second axis entirely: holders get `null` from
`visibleUnitIds()`, which every scoped query treats as "no filter". Everyone else is confined to
`getAccessibleUnitIds()`.

### Two different scopes for two different questions

| Scope | Used for | Reaches |
|---|---|---|
| **Registry** (`SCOPED_SUBFUNCTIONS_CTE`) | master-data lists, asset pickers | assets in locations the unit **owns** |
| **Reporting** (`REPORTABLE_ASSETS_CTE`) | reports | the above **∪** assets on log sheets the unit is responsible for |

Reporting is deliberately wider. Responsibility arrives through the log sheet: a template with
`restrict_scope_to_unit = false` deliberately puts other units' assets on a unit's round, and a
report that hid those readings would hide work the user had just been required to perform.

### ⚠️ Everything below the unit hangs off `location_units`

The registry walk is `unit → location_units → location tree → systems → main/sub functions →
assets`. **A location with no `location_units` row belongs to no unit and is invisible to every
unit-scoped user.**

This is the single most common misconfiguration, because the **Excel location import does not
set it** — an imported location starts unowned, by design (export still emits `unitCodes`, so
export and import are deliberately asymmetric there). Import 180 locations and attach one, and
every unit-scoped user sees the assets under that one location and nothing else.

It is not a code fault and nothing errors; the lists are simply empty. Diagnose it with:

```sql
-- Locations owned by nobody: invisible to every unit-scoped user.
SELECT count(*) FROM locations l
 WHERE NOT EXISTS (SELECT 1 FROM location_units lu WHERE lu.location_id = l.id);

-- Assets each unit can actually reach, before expansion.
SELECT ou.code, count(DISTINCT a.id)
  FROM operational_units ou
  LEFT JOIN location_units lu ON lu.unit_id = ou.id
  LEFT JOIN locations l ON l.id = lu.location_id
  LEFT JOIN sub_functions sf ON sf.location_id = l.id
  LEFT JOIN asset_entries a ON a.sub_function_id = sf.id
 GROUP BY ou.code ORDER BY ou.code;
```

Note the walk **recurses through the location tree**, so owning a parent location covers its
children — attaching the top of a branch is usually enough, and is the intended way to configure
it. Log-sheet visibility is a separate matter: it keys off `log_sheets.operational_unit_id` and
does **not** involve locations at all, so a unit with no location mapping can still hold and
complete rounds.

### The denormalisation this depends on

The CTE finds systems and functions by their **denormalised** `location_id` / `system_id` /
`main_function_id` rather than walking every tree, which is what keeps it fast.
`AssetHierarchyService` cascades those columns on every save. If they were ever stale, assets
would silently drop out of scope, so it is worth checking after any bulk data work:

```sql
SELECT 'systems',       count(*) FROM plant_systems  WHERE location_id IS NULL
UNION ALL SELECT 'main functions', count(*) FROM main_functions WHERE location_id IS NULL AND system_id IS NULL
UNION ALL SELECT 'sub functions',  count(*) FROM sub_functions
   WHERE location_id IS NULL AND system_id IS NULL AND main_function_id IS NULL;
-- all three should be 0
```

## Unit hierarchy: supervision cascades down, operation does not

> Supervising unit A also covers B, C and everything beneath them — a supervisor is
> responsible for the whole branch. Operating unit A covers **only** A: operators of A and
> operators of B are separate teams that share a manager.

`OperationalUnitScopeService.getSupervisorScopeUnitIds` expands downward, `isOperatorOf` does
not, and `getAccessibleUnitIds` is the union. The unit tree is cycle-guarded because access
control depends on it — and `expandDownward` terminates on a cycle anyway, since it only adds
ids it has not already seen.

`getAssignedUnitIds` (the raw, unexpanded union of both link tables) exists but is deliberately
**not used by any access decision**: expanding *it* would grant an operator of A everything
beneath A, which is the one thing this rule exists to prevent. If you find yourself reaching for
it, you almost certainly want `getAccessibleUnitIds`.

Empty scope is handled explicitly everywhere: `null` means unrestricted, an **empty set** means
no access and short-circuits before SQL — `IN ()` is a syntax error, and a query built from an
empty list would otherwise be unfiltered, i.e. fail open. Covered by
`CapabilityScopeIntegrationTest.aScopedUserWithNoUnitAtAllSeesNothingAndDoesNotFail`.

## OPERATOR — the full list

Everything an operator can reach, as seeded:

```
web:  GET:/log-sheets            GET:/log-sheets/{id}       GET:/my-inbox
      GET:/nfc-fault-reports     POST:/log-sheets/{id}/claim
      POST:/log-sheets/{id}/release

api:  GET:/api/bootstrap                     GET:/api/log-sheets/inbox
      GET:/api/log-sheets/{id}/bundle        POST:/api/log-sheets/batch
      POST:/api/log-sheets/{id}/claim        POST:/api/log-sheets/{id}/release
      GET:/api/asset-entries/nfc/{nfcTagId}  POST:/api/nfc-fault-reports/batch
      POST:/api/attachments   GET:/api/attachments/{id}   DELETE:/api/attachments/{id}
```

No web fill/complete — mobile only. `SENIOR_OPERATOR` adds exactly
`GET:/log-sheets/{id}/fill` and `POST:/log-sheets/{id}/complete` to this.

`SUPERVISOR` adds, over `OPERATOR`, on the API side:
`POST:/api/log-sheets/{id}/assign`, `POST:/api/log-sheets/{id}/reassign`,
`GET:/api/operational-units/{unitId}/operators`. The only `api` permission `SUPERVISOR` lacks
that `ADMIN` has is `POST:/api/asset-entries/{id}/nfc-serial` — writing a chip binding is not
an everyday field action.

---

## A site switch above a permission

`nfc.manual_entry_enabled` is the one place where a **setting** narrows a **permission**, and the
direction is the whole design: it restricts, never grants.

| | |
|---|---|
| Permission | `GET:/log-sheets/{id}/fill` — held by SUPERVISOR and SENIOR_OPERATOR |
| Setting | `nfc.manual_entry_enabled` in `app_settings`, edited on the Settings page |
| Effective rule | **both** — `isManualTagEntryAllowed(session, policy)` in the PWA |

With the switch off nobody types a tag id, however privileged; the asset is scanned, or opened
through an NFC fault report. With it on, the permission decides exactly as before.

> **Never let this become an OR.** The device-side switch it replaces did precisely that — it
> *granted* manual entry to every caller, so anyone who could reach a tablet's Settings screen
> could let a whole shift type tags instead of walking to the equipment. It is carried on
> `/api/bootstrap` (`mobilePolicy.nfcManualEntryEnabled`) rather than decided on the device for
> the same reason.

Enforcement is on the device, like `nfc.strict_serial_match`: the server cannot tell a typed tag
from a fault-report fallback, since both arrive as `manualEntry: true`. Tightening it therefore
takes effect on each tablet's next bootstrap.

---

# 3. Capabilities — access that is not about an endpoint

**Roles are fully copyable. Nothing in the application decides access from a role's code.**

Some rules are not "may this role call this route?" but "may this person see the whole plant?"
or "may they complete a sheet they were not assigned?". Those are **capabilities**: permission
rows with a `CAP:` code, `category = 'capability'`, and no method or path.

## Why they exist

They used to be role-code comparisons — `isAdmin()`, `hasRole("HIGH_USER")`, and
`isUnitScopedOnly()` defined as `!ADMIN && !HIGH_USER`. That made roles **un-copyable**:

> The "ساخت نقش مشابه" button copies a role's *permissions* and gives the copy a *new code*.
> Every rule written against the original's code stopped recognising it. A duplicate of `ADMIN`
> held all 123 permissions and still could not view another user's import job, complete an
> unassigned sheet, or look outside its own units — and there was no way to fix that from the
> Roles page.

The failure was safe (a copy was always *more* restricted) but invisible, and produced reports
of the form "this role has the permission and still gets access denied".

Capabilities live in the `permissions` table **on purpose**: role duplication already copies
`role_permissions`, so a copy inherits them by construction rather than through another
mechanism that would have to be taught to copy itself. They also ride the mobile login response
automatically, because it is built from the full authority set — which is what let the PWA drop
its own role checks with no API change.

## The eleven capabilities

| Capability | Means | ADMIN | HIGH_USER | SUPERVISOR | SENIOR_OP | OPERATOR |
|---|---|:-:|:-:|:-:|:-:|:-:|
| `CAP:SCOPE_PLANT_WIDE` | Sees every unit | ✅ | ✅ | | | |
| `CAP:TEMPLATE_MANAGE` | May write templates | ✅ | ✅ | | | |
| `CAP:TEMPLATE_MANAGE_ANY_UNIT` | …in units it does not supervise | ✅ | | | | |
| `CAP:TEMPLATE_VIEW_ANY_UNIT` | Template list unfiltered | ✅ | | | | |
| `CAP:TEMPLATE_VIEW_SUPERVISED` | Template list = supervised units | ✅ | ✅ | ✅ | | |
| `CAP:ASSET_STATUS_DECIDE` | Approves status changes | ✅ | ✅ | ✅ | | |
| `CAP:LOGSHEET_COMPLETE_WEB_ANY` | Completes any sheet in the browser | ✅ | | | | |
| `CAP:LOGSHEET_COMPLETE_WEB_SELF` | Completes *own* sheet in the browser | ✅ | | | ✅ | |
| `CAP:SUPERVISE_ANY_UNIT` | Supervisor powers across units | ✅ | | | | |
| `CAP:IMPORT_JOB_VIEW_ALL` | Sees others' import jobs | ✅ | | | | |
| `CAP:NFC_FAULT_REVIEW` | Marks a fault report reviewed | ✅ | | | | |

Note the asymmetry that survived the migration: `HIGH_USER` is plant-wide for *sight* but still
confined to the units it supervises when it *writes* a template.

Everything else a supervisor does flows from `isSupervisorOf` — a **scope** check against real
unit assignments in `unit_supervisors`, not a capability and not a role code. That distinction
is intentional: capabilities say what kind of actor you are, scope says which units you own.

## The rules that keep this working

**1. Phrase capabilities positively.** Absence must mean *restricted*. `isUnitScopedOnly()` is
the single place the negation is written:

```java
public static boolean isUnitScopedOnly() {
    return !hasCapability(Capabilities.SCOPE_PLANT_WIDE);
}
```

A capability named `CAP:UNIT_SCOPED` would invert this and hand every custom role the whole
plant. A role built by ticking every endpoint box is still unit-scoped, and there is a test for
exactly that.

**2. System roles are immutable.** All five — name, description and permissions alike.
`RoleService.assertNotSystemRole` refuses every edit, and `deleteRole` already refused deletion.

They are the reference the migrations, `SystemRoleCapabilities` and this document all describe;
editing one makes those three disagree silently, and the drift is invisible until something is
denied that the manual says is allowed. The urgency came from capabilities specifically: once
they became data rather than compiled-in role checks, unticking one from `ADMIN` would leave
nobody able to see the plant, with no way back through the page that did it. Drawing the line
at capabilities alone would have been a strange half-rule, so the whole role is closed.

**Customising is fully supported through copying.** "ساخت نقش مشابه" produces an ordinary,
editable, deletable role carrying every permission of the original — and because access is
decided from permissions, the copy genuinely behaves like the original. That path did not work
before; it does now, which is what makes closing these roles reasonable rather than merely
restrictive.

**2b. The last active administrator is protected.** Deleting them, deactivating them, or taking
the `ADMIN` role away all reach the same dead end — nobody can administer users or roles, and
the only repair is editing the database by hand. `UserService` refuses all three
(`isLastActiveAdministrator`, `assertNotOrphaningAdministration`), and the users page greys out
the delete button rather than offering one that always fails.

The rule is about the last **active admin**, not an account named `admin`: renaming the bootstrap
account, or creating a second administrator and retiring the first, is reasonable and stays
allowed. An *inactive* second admin does not count — an account nobody can log into is not a
fallback.

**3. The seed and the Java map must agree.** `SystemRoleCapabilities` duplicates the grant
matrix for two things that cannot read the database — protecting system roles, and building
test principals. `CapabilitySeedIntegrationTest` compares it against a live schema and fails on
drift, which is the only thing that makes having it twice tolerable.

**4. A guard test stops the old style coming back.** `NoRoleCodeAuthorizationTest` scans the
source and fails the build on `isAdmin(`, `hasRole(`, or a hard-coded system-role name outside
three allow-listed files. It has been verified to fail when a violation is introduced.

## Adding a capability

1. Constant in `Capabilities` + add to `ALL`.
2. Row and grants in a new `V{n}` migration (`category = 'capability'`, null method/path).
3. Entry in `SystemRoleCapabilities`.
4. Read it with `SecurityUtils.hasCapability(...)`.
5. Persian label in `PermissionCategoryLabels` is not needed — the row's `name` column carries it.

> **Not RBAC:** `ExcelImportService` matches the strings `"SUPERVISOR"` and `"OPERATOR"`, but
> that is parsing the `role` column of the **unit-staff import sheet** into a `StaffRole` — a
> unit membership, not an application role. Same words, different concept, which is why that
> file is allow-listed in the guard test. `AdminBootstrapRunner` looks up the ADMIN role by code
> to create the first user: provisioning, not authorization.

## Client-side gates (the PWA)

The mobile app has the same story and was migrated with it. It never enforces anything — the
server is authoritative — but hiding a control the server would allow misleads the operator just
as much as showing one it would refuse.

| PWA check (was) | Now | Identical grant set |
|---|---|---|
| `isAdminRole` → dashboard totals, settings switch, settings route | `hasPlantWideScope` | `CAP:SCOPE_PLANT_WIDE` |
| `isAdminRole` → NFC inspector route + nav item | `canManageNfcSerial` | `POST:/api/asset-entries/{id}/nfc-serial` |
| `isSupervisorRole` → assign/reassign, team work | `canAssignWork` | `POST:/api/log-sheets/{id}/assign` |
| `canEnterTagManually` role branch | dropped | `GET:/log-sheets/{id}/fill` already covered it |

Every replacement has a grant set **identical** to the role test it replaced, so no seeded role
changed behaviour. `AdminRoute` became `PermissionRoute`, which takes a predicate rather than
assuming "admin".

---

# 4. Adding an endpoint (mandatory)

Permissions are **not** auto-discovered from controllers. For every **new** authority string:

1. Add a constant in `PermissionCodes`.
2. Guard the handler with `@PreAuthorize("hasAuthority('METHOD:/path')")`.
3. **Ship a Flyway migration** inserting the `permissions` row, plus `role_permissions` for
   every role that should receive it. V1's blanket grant to `ADMIN` was a one-time snapshot and
   does **not** cover rows a later migration inserts.
4. Add the scope/object check in the service if the resource belongs to a unit.

Reuse an existing authority when the URL is a variant of an existing capability — export,
options, draft, bulk-delete. `GET …/export` reuses the list authority; `POST …/delete-bulk`
reuses `POST:…/{id}/delete`; batch-import cancel/abandon/delete all reuse `POST:/batch-import`.
The number of `@PreAuthorize` mappings therefore exceeds the number of seeded permissions,
which is expected.

**Forbidden:** creating a permission only in the Roles UI, only with ad-hoc SQL, or only in
Java bootstrap code. Other environments will miss it and `@PreAuthorize` will deny everyone.

---

# 5. Findings from the access review

State as reviewed. Re-run the queries in [§6](#6-how-to-re-audit) after any change.

## F1 — Master-data lists have no unit filter

**Status: open. Not currently exploitable; latent.**

All six master-data list/export handlers read the repository directly:

```
Location    locationRepository::findAll        PlantSystem   plantSystemRepository::findAll
MainFunction mainFunctionRepository::findAll   SubFunction   subFunctionRepository::findAll
AssetEntry  assetEntryRepository::findAll      AssetClass    assetClassRepository::findAll
```

None of them reference `AssetAccessService` or any scope service. Their `/export` siblings are
likewise unfiltered.

This is safe **today only because** those permissions are granted exclusively to `ADMIN` and
`HIGH_USER`, both plant-wide. But the Roles page lets an administrator tick any permission onto
any role. The moment `GET:/asset-entries` — or its export — is granted to a unit-scoped custom
role, that user receives the entire plant's asset register, and no code path prevents it.

A scoped alternative already exists and is used by the reports:
`AssetAccessService.findVisibleAssets`.

**Remediation plan**
1. Swap `AssetEntryWebController` list/export to `assetAccessService.findVisibleAssets(q, pageable)`.
   Behaviour is unchanged for `ADMIN`/`HIGH_USER` because `visibleUnitIds()` returns `null` for
   them.
2. For the five hierarchy controllers, add per-level visible-id queries. **Mind the null trap**
   (gotcha #58 in [AGENTS.md](../AGENTS.md)): `null` means *unrestricted* and needs its own
   branch, or admins get an empty list.
3. Integration test: a unit-scoped user holding `GET:/asset-entries` must not see assets outside
   their units.
4. Minimum viable alternative if the code is not changed: warn on the Roles page that
   `master-data` permissions carry no unit filter.

## F2 — Supervisors cannot create templates (documentation was wrong)

**Status: fixed in the docs. Behaviour unchanged and correct.**

The README claimed `SUPERVISOR` had `GET:` **and** `POST:/log-sheet-templates` and "may create
templates for supervised units". Neither is true:

- the seed grants `SUPERVISOR` only `GET:/log-sheet-templates`;
- `LogSheetTemplateService.canEditOrDelete()` returns `isAdmin() || hasRole("HIGH_USER")`, so
  even a granted endpoint would be refused.

Defence in depth working as intended; the documentation was simply ahead of the code. If
supervisors *should* be able to create templates, both the seed and `canEditOrDelete()` must
change — `assertCanManageUnit` already contains the per-unit rule that would then apply.

## F3 — NFC lookup is plant-wide

**Status: open. A design decision, not a defect.**

`GET /api/asset-entries/nfc/{nfcTagId}` returns any asset for any tag with no unit check, and
`OPERATOR` holds it. Operationally defensible — the operator is physically standing at the
asset — but if tags are sequential or guessable it permits enumeration of assets outside the
caller's units.

**If you decide to restrict it:** filter in `AssetEntryService.findByNfcTag` via
`AssetAccessService`, and return **404, not 403**, for out-of-scope hits. A 403 confirms the tag
exists, which is the thing being protected.

## F4 — Custom roles may be granted admin-category permissions

**Status: accepted risk / governance.**

Nothing prevents an administrator from ticking `POST:/users` or `POST:/roles` onto a custom
role. That is the administrator's prerogative and they are already trusted, but there is no
guard and no warning.

Note this is now *more* consequential than when it was first written: a copy of `ADMIN` used to
be a paper tiger, holding every permission while the code refused to treat it as an admin.
Since [§3](#3-capabilities--access-that-is-not-about-an-endpoint) it really is an admin. That is
the intended fix — but it means duplicating `ADMIN` now hands out genuine plant-wide power, and
should be done as deliberately as creating an admin user.


## F5 — Role-code authorization

**Status: fixed.** Every access decision now reads a capability or an endpoint permission; a
duplicated role behaves exactly like its original. See
[§3](#3-capabilities--access-that-is-not-about-an-endpoint) for the model, the eleven
capabilities, and the four rules that keep it working. Covered by
`NoRoleCodeAuthorizationTest`, `CapabilitySeedIntegrationTest` and
`DuplicatedRoleCapabilityIntegrationTest`, plus `src/types/auth.test.ts` in the PWA.

## F6 — Locations left unattached to any operational unit

**Status: configuration, not code. Check your data.**

Every unit-scoped user reaches assets through `location_units`, and the Excel location import
deliberately does not populate it. On the development database this showed as **1 row for 180
locations**: three of four operational units resolved to **zero** assets, and only the unit
owning that single location saw anything (47 of 87 assets).

Nothing is broken — the walk, the recursion and the unit hierarchy all behave correctly, and a
supervisor of the parent unit correctly inherited the child unit's 47 assets. But a unit-scoped
user with no location mapping sees empty master-data lists and near-empty reports, which reads
like a permissions bug and is not one.

**Fix by attaching the top of each branch** on the location form's unit multi-select; the walk
recurses through child locations from there. Use the queries in
[§2](#-everything-below-the-unit-hangs-off-location_units) to find unattached locations.

---

# 6. How to re-audit

Four checks. All of them are cheap and should be repeated after touching permissions.

**Every handler is guarded** — should list only `/login`, `/api/auth/login`, `/api/health`:

```bash
grep -rn "@PreAuthorize" -L src/main/java/com/hnp/backendofflinefirst/web src/main/java/com/hnp/backendofflinefirst/controller
```

**Code and database agree** — both lists should be empty apart from the two `WebSecurityConfig`
entries (`GET:/actuator/**`, `GET:/v3/api-docs/**`, which are gated there rather than by
`@PreAuthorize`):

```bash
grep -rhoE "hasAuthority\('[^']+'\)" src/main/java --include="*.java" | sed "s/hasAuthority('//; s/')//" | sort -u > /tmp/code.txt
grep -hoE "'[A-Z]+:/[^']*'" src/main/resources/db/migration/*.sql | tr -d "'" | sort -u > /tmp/db.txt
comm -23 /tmp/code.txt /tmp/db.txt   # checked in code, never seeded -> endpoint unreachable
comm -13 /tmp/code.txt /tmp/db.txt   # seeded, never checked -> dead permission
```

**No role-code check has crept back in** — this is `NoRoleCodeAuthorizationTest`, but the same
question by hand (comment lines aside, the result should be empty):

```bash
grep -rnE "isAdmin\(|hasRole\(" src/main/java --include=*.java
```

**Capabilities in the database match `SystemRoleCapabilities`** — `CapabilitySeedIntegrationTest`
asserts this, and it is worth eyeballing after editing roles:

```sql
SELECT r.code, p.code
  FROM roles r
  JOIN role_permissions rp ON rp.role_id = r.id
  JOIN permissions p ON p.id = rp.permission_id
 WHERE p.category = 'capability'
 ORDER BY r.code, p.code;
-- expected totals: ADMIN 11, HIGH_USER 4, SUPERVISOR 2, SENIOR_OPERATOR 1, OPERATOR 0
```

**No unexpected admin-category grant:**

```sql
SELECT r.code, p.code FROM roles r
  JOIN role_permissions rp ON rp.role_id = r.id
  JOIN permissions p ON p.id = rp.permission_id
 WHERE p.category = 'admin' AND r.code <> 'ADMIN'
 ORDER BY r.code, p.code;
-- expected: HIGH_USER + the three batch-import rows, nothing else
```

**Unit scope actually resolves to something** — the check that catches the misconfiguration in
[F6](#f6--locations-left-unattached-to-any-operational-unit), which looks exactly like a
permissions bug:

```sql
-- Any unit with 0 here shows empty master-data lists to its staff.
SELECT ou.code, count(DISTINCT a.id) AS assets_in_registry_scope
  FROM operational_units ou
  LEFT JOIN location_units lu ON lu.unit_id = ou.id
  LEFT JOIN locations l ON l.id = lu.location_id
  LEFT JOIN sub_functions sf ON sf.location_id = l.id
  LEFT JOIN asset_entries a ON a.sub_function_id = sf.id
 GROUP BY ou.code ORDER BY ou.code;
```

**Grant totals per role** — compare against the table in [§2](#2-the-five-system-roles):

```sql
SELECT r.code, p.category, count(*)
  FROM roles r
  JOIN role_permissions rp ON rp.role_id = r.id
  JOIN permissions p ON p.id = rp.permission_id
 GROUP BY 1, 2 ORDER BY 1, 2;
```

---

# 7. Other security surfaces

| Surface | State |
|---|---|
| **CSRF** | Enabled on the web chain; disabled **only** for `/api/**` (JWT, stateless). A `fetch()` POST without the token is silently swallowed — see gotcha #69 and use `AppCsrf.postJson`. |
| **Actuator** | `/actuator/health/liveness` and `/readiness` are public probes; everything else needs `GET:/actuator/**` (ADMIN). |
| **OpenAPI / Swagger** | Enabled in every environment but gated behind `GET:/v3/api-docs/**` (ADMIN). Never make it `permitAll()`. |
| **Mobile sessions** | JWTs are stateful — every token carries a `jti` backed by an `api_sessions` row. A valid signature alone does not authenticate. One device per user; a new login supersedes the others. |
| **Web sessions** | One browser per user (`maximumSessions(1)`). `/web-sessions` addresses rows by a SHA-256 digest, so raw `JSESSIONID`s never reach the page. |
| **Login throttle** | `LoginAttemptService` checks **before** password verification, including before the LDAP bind — this stops an attacker using this app to trip Active Directory's lockout against a real employee. |
| **Log files** | Not web-served. `LogSanitizer` masks password/token/secret. Note it does **not** currently mask `nationalCode` or `phoneNumber`. |
| **Bootstrap admin** | `admin` / `admin123`, created only when no ADMIN user exists. Change it before the system leaves your desk. |

---

## Related

- **[hierarchy.md](hierarchy.md)** — how unit scope is derived from the location tree
- **[log-sheets.md](log-sheets.md)** — the lifecycle these permissions gate
- **[schema.md](schema.md)** — `roles`, `permissions`, `role_permissions`, `user_roles`
- **[../AGENTS.md](../AGENTS.md)** — §2b and §5, plus the numbered traps
- **[../README.md](../README.md)** — the role descriptions in prose
