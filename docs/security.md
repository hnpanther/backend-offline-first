# Security, Roles and Permissions

Who may do what, where each rule is enforced, and the places where the answer depends on a
role's **code** rather than on its permissions.

> **Authorities are `METHOD:path` strings.** `GET:/locations`,
> `POST:/log-sheets/{id}/complete` — including the literal `{id}` braces. The permission *is*
> the route; there is no mapping layer in between. See [schema.md](schema.md#roles-permissions-role_permissions-user_roles).

---

# 1. The three layers

Before any of them: **where the request's authorities come from.** A web request carries the
principal built at form login, in its session. An API request carries a bearer token that says
only *who* — `sub`, `uid`, `jti` — and `ApiTokenAuthenticator` reads the roles
and permissions out of the database on every single request.

That is deliberate and fairly recent. The token used to carry `roles` and `perms` claims, and the
filter turned them straight into authorities, which meant anybody holding the signing key chose
their own permissions — and the key ships as a working default. It also meant a permission change
did nothing until the token expired. §8 has the whole account.

An authority is then necessary but rarely sufficient. Every request passes up to three
independent checks, and they answer different questions:

| Layer | Question | Where |
|---|---|---|
| **Endpoint** | May this role call this route at all? | `@PreAuthorize("hasAuthority('…')")` on the handler |
| **Scope** | May this user act on *this unit*? | `OperationalUnitScopeService`, `AssetAccessService`, `LogSheetAccessService` |
| **Object** | May this user see *this row*? | `requireVisibleLogSheet(id)` and friends |

**Holding the endpoint permission proves nothing about the second and third.** A supervisor
with `GET:/log-sheets/{id}` still cannot open a sheet belonging to a unit they do not
supervise: `LogSheetAccessService.requireVisibleLogSheet` throws `AccessDeniedException` first.

**The object layer has exactly one exception, and it is written once.** `canView` reads *unit
scope **or** being that row's current assignee*:

```java
if (!SecurityUtils.isUnitScopedOnly()) return true;
if (isOwnWork(sheet)) return true;                       // assignee_user_id == me
return unitScopeService.canAccessUnit(me, sheet.getOperationalUnitId());
```

It exists because one action from an operator's point of view — "deliver the work I did" — was
being judged by two different rules. `LogSheetService.submitOne` asks only whether the caller is
the assignee; everything else applied unit scope. So moving somebody between operational units
while they were offline produced a round that arrived complete and silently lost half of itself:
readings accepted, photographs 403, NFC fault reports refused, the bundle unrefreshable, and the
sheet's own page in the panel denied — reachable from «کارتابل من», which has never had a unit
filter, and then denied on the click. Nothing warned either side.

Three things bound it:

- **`assignee_user_id` is server data**, never a client parameter. Nobody can assert their way in.
- **It is one row and one person.** `release`, `reassign` and `takeover` revoke it the instant
  ownership moves; a former assignee is refused again immediately.
- **It is not applied to the list queries.** `visibleUnitIdsOrNull` is untouched, so a sheet in a
  unit you no longer belong to still does not appear in that unit's listing.

`NfcFaultReportService` delegates to `canView` rather than calling `canAccessUnit` itself — it
used to be the one place with a hand-copied version of the rule, which is precisely how the two
drifted. Regression: `AttachmentApiIntegrationTest` § *The assignee's own work, after they leave
the unit*, which keeps the cross-unit refusals green beside it.

Several service-layer rules are checked in the **service**, not only on the controller,
precisely because a service is reachable from more than one route — see
`AssetStatusRequestService.requireDecider()` for the pattern and its reasoning.

---

# 2. The five system roles

Counts below are the seeded grants, **re-measured on a V8 database** by grouping
`role_permissions` by role and category — not carried forward by hand:

| Role | Persian | Scope | admin | api | capability | general | log-sheet | master-data | operational | organization | reports |
|---|---|---|---|---|---|---|---|---|---|---|---|
| `ADMIN` | مدیر سیستم | plant-wide | **36** | 16 | 11 | 1 | **2** | 43 | 24 | 8 | 1 |
| `HIGH_USER` | کاربر ارشد | plant-wide | 3 | 16 | 4 | 1 | **1** | 43 | 24 | 8 | 1 |
| `SUPERVISOR` | سرپرست | own units + sub-units | — | 15 | 2 | **1** | **1** | **1** | 24 | — | 1 |
| `SENIOR_OPERATOR` | اپراتور ارشد | own units only | — | 12 | 1 | — | — | — | 8 | — | — |
| `OPERATOR` | اپراتور | own units only | — | 12 | — | — | — | — | 6 | — | — |

> **Two columns are new here, and one number is the point of this release.** `capability` and
> `log-sheet` are categories V3 and V4 introduced; the table predated both and simply did not
> show them, so four of its other cells had drifted as well. The `log-sheet` category holds
> exactly two permissions — `/approve` and `/unapprove` — and **ADMIN is the only role with
> both**: V8 revoked the withdrawal from everyone else. `SUPERVISOR`'s 1 is `approve`.

`HIGH_USER`'s three `admin` permissions are exactly the batch-import trio
(`GET:/batch-import`, `GET:/batch-import/jobs`, `POST:/batch-import`) — the category is
`admin`, but the capability is not.

**The `general` column is a single permission, `GET:/`, and it is the dashboard.** **V16 grants
it to `SUPERVISOR` as well** — see [§2a](#2a--the-landing-dashboard-v16) for what that changes
and why it is a migration rather than a code change. Two of the five system roles still do not
hold it, which keeps "where does this user land" a question with a different answer per role
rather than a constant. `SecurityUtils.homePath()` is the one place that answers it — the
dashboard when the user may open it, otherwise their inbox — and the login redirect, the navbar
brand and every breadcrumb's «خانه» step all use it.

It asks for the **authority**, not for the role or the scope. The login redirect used to ask
`isUnitScopedOnly()`, which is a near-miss: it is a question about which *rows* a user sees,
not about which *pages* they may open. A custom plant-wide role without `GET:/` was therefore
redirected to the dashboard and refused by it — an access-denied message immediately after a
correct login. This is the same failure F5 describes, in a place nobody thinks of as an
access rule.

`SUPERVISOR`'s single `master-data` permission is **`GET:/log-sheet-templates`** — read only.
See [§5 F2](#f2--supervisors-cannot-create-templates-documentation-was-wrong).

**The 43rd `master-data` permission is `POST:/asset-classes/{classId}/fields/{fieldId}/state`**
(V7) — switching a class parameter off, and back on. It is deliberately **its own** permission
rather than part of «ویرایش فیلد کلاس»: disabling a parameter stops it being collected on every
future round, which is a decision of a different kind from relabelling one, and an endpoint
with no row in `permissions` is an endpoint the Roles page cannot offer and an auditor cannot
see.

It is granted *derived from the existing `…/fields/{fieldId}/delete` grant* rather than to a
list of role codes — whoever may already remove a parameter outright may take the safe route.
That is what makes a **duplicated role** inherit it: a copy of a role has a new `code`, so a
hard-coded list would leave the copy able to delete a parameter and unable to disable one.
Same rule as V4's progress permission; see [schema.md](schema.md) and
[§3](#3-capabilities--access-that-is-not-about-an-endpoint).

The matching **hard delete is now refused** whenever a reading, a superseded reading or an
asset's own band still names the key — so the destructive path is narrower than it was, not
wider. The guard is in `FieldDefinitionRepository.isFieldKeyReferenced`, scoped to the class
and its sub-classes.

## 2a — The landing dashboard (V16)

`GET:/` guarded an **inventory counter**: seven plant-wide `count(*)` calls — locations, asset
classes, assets, sub-functions, log sheets, users, units. Every one of them described a plant
rather than anybody's work, and none of them had a scope, which is the real reason the page
could only ever be an administrator's: showing a supervisor those totals tells them the size of
a plant they are not responsible for.

It is now a **periodic management dashboard broken down by operational unit**, narrowed by the
same `visibleUnitIds()` as every report ([reports.md §0](reports.md) has every formula). So the
page finally has a reader besides the administrator, and V16 grants the authority accordingly:

```sql
INSERT INTO role_permissions (role_id, permission_id)
SELECT rp.role_id, dashboard.id
FROM role_permissions rp
JOIN permissions reports ON reports.id = rp.permission_id AND reports.code = 'GET:/reports'
CROSS JOIN permissions dashboard
WHERE dashboard.code = 'GET:/'
ON CONFLICT (role_id, permission_id) DO NOTHING;
```

**Derived from `GET:/reports`, not from a list of role codes** — the rule of §3 and the shape V4,
V8 and V9 all use. `GET:/reports` is already the authority that means «this role may read
aggregate numbers about work it is responsible for», and the dashboard is that question asked
about one period. A code list would leave a `SUPERVISOR_COPY` able to open all eight reports and
refused by the landing page, which is exactly the breakage capabilities were introduced to end.
`BranchSchemaAndSeedRegressionTest.whoeverMayReadAReportMayOpenTheLandingDashboard` asserts the
two grant sets are *equal*, so they cannot drift apart later either.

### What else the grant changes, and it is intended

`SecurityUtils.homePath()` asks for `GET:/` and nothing else. So for a supervisor, V16 also
moves:

| | Before V16 | After |
|---|---|---|
| Login redirect | `/my-inbox` | `/` |
| Navbar brand, every breadcrumb's «خانه» | `/my-inbox` | `/` |
| Sidebar «داشبورد» entry | hidden | shown |

That follows from the one grant rather than from a second rule, and keeping it that way is the
point: the near-miss `homePath()` was written to remove was a rule about *scope* standing in for
a rule about *pages* — §2 above tells that story, and
[F5](#f5--role-code-authorization) is the same failure in its usual clothing. An
exception for supervisors would put that drift straight back. An administrator who disagrees
revokes `GET:/` from `SUPERVISOR` on the Roles page, and the login redirect follows the
revocation with no code change.

`SENIOR_OPERATOR` and `OPERATOR` hold neither authority and are untouched — they still land on
«کارتابل من», which is where their work is.

### Three layers still, not one

Holding `GET:/` gets a reader onto the page; it decides nothing about what the page shows them
(§1). The rows come from `visibleUnitIds()`, whose three answers are not interchangeable —
`null` unrestricted, **empty set** no access, non-empty exactly those units. `DashboardService`
passes `null` through to the queries' `:unitIds IS NULL OR …` branch and short-circuits the
empty set before any SQL runs. Reading `null` as "no units" would show the person with the most
access the least data, with no error anywhere; letting the empty set reach SQL as `IN ()` fails
open. [hierarchy.md §5](hierarchy.md#5-access-scope--the-part-that-must-be-right) is the
reference, and `DashboardIntegrationTest` pins both directions with a scoped principal.

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

## A permission granted to whoever already holds another

V4 needed `POST:/api/log-sheets/progress` to reach exactly the roles that may already deliver a
round from a tablet. It does not list the five system role codes — it derives the grant:

```sql
INSERT INTO role_permissions (role_id, permission_id)
SELECT rp.role_id, progress.id
FROM role_permissions rp
JOIN permissions batch ON batch.id = rp.permission_id AND batch.code = 'POST:/api/log-sheets/batch'
CROSS JOIN permissions progress
WHERE progress.code = 'POST:/api/log-sheets/progress'
ON CONFLICT (role_id, permission_id) DO NOTHING;
```

**Because an administrator's duplicated role has a new code and the same permissions.** A
hard-coded list would leave that copy able to submit a round and unable to report progress — the
exact class of breakage capabilities were introduced to end (§3). Verified live: a
`SUPERVISOR_COPY` role on the development database picked the grant up with everything else.

**V25 reuses it for `POST:/api/sync/backlog`** — a tablet's report of how many rounds it holds
whose readings the server has received in no form. Same derivation from
`POST:/api/log-sheets/batch`; six roles on the development database after the upgrade. The
endpoint writes three columns of the caller's **own** `api_sessions` row, chosen by the `jti` of
the verified token and the authenticated user id — never by anything in the body — and refuses a
count outside 0–100000.

Worth reusing when a new permission is "whoever already has X should have this too". It does not
replace §2b — the `permissions` row itself is still an explicit `INSERT`, and V1's blanket
`CROSS JOIN` still does not cover rows a later migration adds, which is why ADMIN and HIGH_USER
are reached through the same rule rather than assumed.

**The same file reuses it** for approval, three sections further down. `POST:/log-sheets/{id}/approve` and `.../unapprove` are granted to
whoever may already **void** a completed round — the same supervisory judgement:

```sql
INSERT INTO role_permissions (role_id, permission_id)
SELECT rp.role_id, p.id
FROM role_permissions rp
JOIN permissions v ON v.id = rp.permission_id AND v.code = 'POST:/log-sheets/{id}/void'
CROSS JOIN permissions p
WHERE p.code IN ('POST:/log-sheets/{id}/approve', 'POST:/log-sheets/{id}/unapprove')
ON CONFLICT (role_id, permission_id) DO NOTHING;
```

They are **two permissions, not one**, and separate from `void`, so a site that wants a distinct
reviewer — somebody who may accept a round but not reject one, or vice versa — can say so by
building a role rather than by changing code. That separation is what made the next change a
migration rather than a redesign.

## And one taken away again: withdrawing an approval (V8)

The derived grant above gave `/unapprove` to ADMIN, HIGH_USER and SUPERVISOR. **V8 revokes it
from every role but ADMIN.** `/approve` is untouched: a supervisor still reviews and accepts
their own unit's rounds, which is the point of the review step. Only the way back closes.

```sql
DELETE FROM role_permissions rp
      USING permissions p, roles admin
      WHERE rp.permission_id = p.id
        AND p.code = 'POST:/log-sheets/{id}/unapprove'
        AND admin.code = 'ADMIN'
        AND rp.role_id <> admin.id;
```

**Revoked from "everything that is not ADMIN", not from a list of role codes** — the mirror of
§3's rule and for the same reason: a duplicated `SUPERVISOR` has a new code and the same grants,
so `WHERE r.code IN (…)` would leave the copy holding exactly what this removes.

**It also removes grants somebody made by hand.** `role_permissions` records that a role *has* a
permission, never *why*, so a deliberate grant and an inherited one are indistinguishable here.
This is a change of default; an administrator who wants it back grants it in the Roles page.

### Granting it back takes two things, not one

This is the part that will otherwise be reported as a bug. `LogSheetAssignmentService.unapprove`
calls `requireSupervisorOrAdmin` **underneath** the authority check (§1's layer 3), so a role
holding the permission and nothing else is still refused — with «شما سرپرست این واحد
نیستید», a message about a relationship rather than a permission.

| | What to grant |
|---|---|
| The endpoint | `POST:/log-sheets/{id}/unapprove` |
| **and** the reach | `CAP:SUPERVISE_ANY_UNIT`, **or** a real supervisor relationship with the unit (`unit_supervisors`) |

Both are grantable from the Roles page — capabilities are ordinary permission rows in the
`capability` category (§3). `BranchSchemaAndSeedRegressionTest.onlyAdminMayWithdrawAnApprovalByDefault`
pins the default, and `LogSheetApprovalIntegrationTest.grantingTheWithdrawalOnwardIsTwoGrantsNotOne`
pins the second half.

### What it costs, which is a decision rather than a side effect

`void`, `reopen` and `extend` all refuse an approved round
([log-sheets.md](log-sheets.md#approval--the-review-step-after-completion)). So a supervisor who
approves one by mistake cannot invalidate it, reopen it for a correction, or extend it — the
round is frozen until an administrator withdraws the approval. That is the intent, and it is why
the approve modal now says so **before** the button is pressed rather than after; it used to
promise the opposite («با «لغو تأیید» قابل برگشت است»), to the one reader for whom it had stopped
being true.

A **self-withdrawal window** — the approver may undo their own approval for N minutes — was
considered and declined: it answers the real complaint, and it puts a time-dependent branch into
an authority check, which is the one kind of rule that cannot be read off the Roles page. Revisit
it with a count from the action log, not an impression.

## And five granted to whoever may edit a template (V9)

The round's own questions are edited on their own page, so they are their own permissions —
`GET`/`POST:/log-sheet-templates/{id}/fields` and the three `{fieldId}` ones, category
`master-data`. All five are granted **derived from `POST:/log-sheet-templates/{id}`**, the
template-edit grant, by the rule above: a duplicated role has a new code and the same
permissions, and a code list would leave the copy able to rename a template and unable to edit
its questions.

`SUPERVISOR` holds templates read-only and therefore receives none of them, which is the existing
split (§5 F2). The write endpoints also go through `LogSheetTemplateService.assertCanManageUnit`,
so the authority alone is not enough for a unit-scoped role — the same two-layer shape §3 and
AGENTS.md #161 describe.

**Saving the answers needs no new permission.** `POST /log-sheets/{id}/sheet-form/draft` reuses
`POST:/log-sheets/{id}/complete`: saving part of a sheet is part of filling it, so whoever may
fill it may do this and nobody else. A new authority would have to be granted everywhere the old
one already is, for no new decision. The same reuse covers round-level **media**:
`POST /log-sheets/{id}/attachments` already took that authority and now accepts an upload with no
`assetId`.

## And five more for a custom sheet's own questions (V10)

A template-raised round inherits its questions; a **custom** sheet owns them, so it has its own
five — `GET`/`POST:/log-sheets/{id}/fields` and the three `{fieldId}` ones, category
**`log-sheet`** rather than `master-data`, because a custom round is a supervisor's own one-off
work rather than plant master data.

All five are granted **derived from `POST:/log-sheets/custom`**, by the rule above: a code list
would leave a duplicated role able to raise a custom round and unable to put any questions on it,
which is a round that cannot be used. That grant reaches `SUPERVISOR` as well as `ADMIN` /
`HIGH_USER`, deliberately — putting the questions on a round is part of raising it.

**And the authority is not enough on its own**, the same two-layer shape as everywhere else
(§3, AGENTS.md #161). `CustomLogSheetService.assertCanEditOwnQuestions` refuses four further
cases, and the *unit* half of it is the same helper `createCustom` uses, so granting a unit cannot
mean one thing when the round is raised and another when its questions are written:

| Refused when | Because |
|---|---|
| the sheet came from a template | its questions have an owner already; a second editor would be two owners of one list |
| the round is terminal | the work has been delivered; re-wording what it asked is re-describing it |
| its questions are already frozen | the first answer froze them, so an edit here could not reach the round — refused honestly instead of accepted and ignored |
| the unit is outside the actor's supervisory branch | the permission reaches every supervisor; the unit narrows it to their own plant |

A unit-scoped supervisor holding all five therefore still cannot touch a round in somebody else's
unit — which is the case `LogSheetCustomQuestionsIntegrationTest` pins down with a `SUPERVISOR`
principal, since an `ADMIN` holds `SCOPE_PLANT_WIDE` and would pass the unit check vacuously.

# 3a. Seeing who belongs to which operational unit

The users list carries a «واحدهای عملیاتی» column — each person's units and their role in each.
It is a **read of data the same page already governs**: `unit_supervisors` and `unit_operators`
are edited from the operational-units page under its own permissions, and this column only
renders them next to the person instead of next to the unit. No new permission, and the page's
existing `GET:/users` authority is the gate.

Worth stating because the column makes an access-control fact legible for the first time: who
supervises what is what `SUPERVISE_ANY_UNIT`-less roles are narrowed by, and reading it off one
screen is how an administrator notices that somebody supervises a unit they should not.

# 3b. Deleting a person never deletes their work

**Every foreign key into `users` is `ON DELETE RESTRICT`** — every column that references a user
but two (thirty-five was the count once V23 added `lookup_downloads.user_id`; V28 added
`devices.wipe_requested_by`, and V29–V31 added the form authorship columns:
`form_definitions.created_by_user_id`, `form_submissions.reported_by_user_id` and
`approved_by_user_id`, `form_submission_action_log.actor_user_id`,
`form_submission_revisions.recorded_by_user_id` and `superseded_by_user_id`; V35 adds
`form_submissions.last_saved_by_user_id` to both the restrictive foreign keys and the activity check. An earlier
revision of this sentence said twenty-seven, which had stopped being true several migrations
before). Account metadata has different retention. The database removes API sessions and the
V37 per-reporter history counter when their user is deleted. The counter stores only the last
published position. `UserService.delete` clears role membership itself. The foreign keys on
filings, actions, answer revisions and attachments continue to protect the work.

That is asserted rather than assumed:
`UserDeleteReferenceIntegrityTest.everyForeignKeyToUsersRefusesTheDelete` reads `pg_constraint`
and fails if a migration ever adds a `CASCADE`, a `SET NULL` or a `SET DEFAULT` to that table.
There is no JPA cascade anywhere in the codebase either, so deletion is governed in exactly one
place — the schema.

**The service refuses first, so the database never has to.** `UserService.delete` checks, in
order: the last active administrator, membership of an operational unit, and then *any*
recorded activity (`UserRepository.isReferencedAnywhere`, one query over every referencing
column). Only then does it touch anything. The order matters more than it looks: the method goes
on to expire that user's web sessions through an in-memory registry that **no transaction rolls
back**, so a guard that let a working operator through would log them out for an action that was
then refused. That was a real defect — the guard covered nine of the columns — and AGENTS.md
#167 records it.

A user who has done anything is **deactivated**, not deleted, and the message says so.

# 3c. Device enrolment — which handheld may use the mobile app

*V13, off by default. The settings switch is `auth.device_enrollment.enabled`; the panel page is
`/devices`; the design and what was weighed is [roadmap §30](roadmap.md).*

## And four for log-sheet categories (V19)

`GET:/log-sheet-categories`, `POST:/log-sheet-categories`, `POST:/log-sheet-categories/{id}` and
`POST:/log-sheet-categories/{id}/delete` (category `master-data`), granted to every role holding
`POST:/log-sheet-templates` — whoever designs the rounds decides what kinds of round there are.
Reading a category needs nothing extra: the list filter and the detail page show the name frozen
onto each round, inside the round's own visibility.

## And eight for reference data (V22)

| Permission | Guards | Granted to |
|---|---|---|
| `GET:/lookup-lists` | the lists page, a list's page with its items, the Excel template and the export | every role holding `POST:/users` |
| `POST:/lookup-lists` | creating a list | same |
| `POST:/lookup-lists/{id}` | editing a list **and its columns** — adding, relabelling, flagging and removing a column change the list's shape | same |
| `POST:/lookup-lists/{id}/delete` | deleting an empty list | same |
| `POST:/lookup-lists/{id}/items` | adding an item | same |
| `POST:/lookup-lists/{id}/items/{itemId}` | editing an item, and deactivating or reactivating it | same |
| `POST:/lookup-lists/{id}/items/{itemId}/delete` | the two actions that **remove** data: a hard delete and anonymisation | same |
| `POST:/lookup-lists/{id}/import` | the whole Excel import: upload, preview (a `GET` under this authority, because previewing is part of importing), apply and discard | same |

**Why holders of `POST:/users`, which is `ADMIN` alone on the seeded roles.** A list may hold
personal data — a personnel list carries names, and may carry a national id or a phone number —
and whoever already manages user accounts is already trusted with that. Derived from the grant
rather than from a role code, so a role duplicated from `ADMIN` inherits it; an administrator who
wants a `HIGH_USER` to maintain, say, a list of violation types grants these through the roles
page. There is no per-list access in this phase: whoever reaches the page reaches every list.

**What reference data does not protect, and should be said to whoever owns privacy:**

- Every change to an item is field-audited like any other entity, so **`audit_log` holds the
  before-values** of every item ever edited — national ids included, if a list declares one.
  Anonymising an item does not reach that trail; its retention is the audit trail's own.
- The `offline` flag on a column is what lets a value reach a tablet: the device payload is built
  on the server from flagged columns only (`LookupDeviceService`), and a column never leaves the
  server unflagged. Below.
- The Excel export writes every non-anonymised item with every column, unflagged ones too: it is an
  administrator's working file, under `GET:/lookup-lists`.

## Where a list goes when a form uses it (roadmap §31 Phase 2, V23)

A `lookup` / `lookupmulti` field names a list. That opens **two** ways out of the server for its
items, and each takes three checks — an authority, the round, and the list:

| Route | Authority | The round | The list | What leaves |
|---|---|---|---|---|
| `GET /log-sheets/{id}/lookups/{listCode}?q=` — the panel's picker | `POST:/log-sheets/{id}/complete` (reused: searching a field's list is part of filling it, AGENTS.md #9) | visible (`requireVisibleLogSheet`) **and** fillable on the web by this user (`LogSheetWebCompletionAccess`) | this round's **frozen** definitions draw from it (`LookupGrantService.sheetUsesList`) — otherwise 404, so the endpoint cannot page through any list | at most 20 **active** items matching the term: code, label, and the `display` columns only. `no-store` |
| `GET /api/lookups/{code}/items?since=&afterId=&limit=` — the tablet | `GET:/api/lookups/{code}/items` (V23, granted *derived from* `POST:/api/log-sheets/batch`) | — | **an open round assigned to the caller draws from it, or an active form the caller may FILL draws from it** (`LookupGrantService.userMayHoldList`, V29). Refused 403 otherwise — **and for a list that does not exist**, so a refusal never says which codes are real | code, label, active, and the `offline` columns only; one page (≤ 1000) per request, half-open over `(updated_at, id)`; a `leaseUntil` and the server time. `no-store` |

**Why the device endpoint has a permission of its own.** A personnel list is personal data. A site
may want a role's tablets never to hold one and still deliver rounds — revoke this one permission
and that role's lookup fields work in code-only mode (the operator types a code; the server resolves
it at sync). A reuse of `batch` could not say that.

**The device lease** ([roadmap §31](roadmap.md), «the device lease»; [schema.md](schema.md) — V23):

| Rule | Where |
|---|---|
| A list is held while a round assigned to the user and still open needs it **or** an active form the user may FILL needs it (V29); when neither applies, the device drops it at the next sync (set difference), and the server refuses it from then on. Bootstrap carries `requiredFormLookups` for the form half | `LookupGrantService`, the PWA's `lookupSync` |
| Only `offline`-flagged columns, built on the server | `LookupDeviceService` |
| Kept for `device_lease_hours` after the **last successful sync** (not after login) — renewed at half its length, deleted on expiry, online or not | `lookup_lists.device_lease_hours`, the PWA's `lookupStore` |
| A device clock behind the last server time seen counts as expired (five minutes of drift allowed). This stops carelessness, **not an owner set on defeating it** — say so to the security unit | the PWA's `lookupStore.isUsable` |
| Cleared at sign-out — an exception to the shared-tablet rule that keeps other reference data | the PWA's `clearUserSessionContext` |
| Every page fetched is a row: list, user, API session `jti` (which names the device), revision, count, cursor, time (ISO/IEC 27001 A.8.15) | `lookup_downloads` |
| **At rest** the store is IndexedDB, not encrypted by the application. Protection is Android's storage encryption, the screen lock, device enrolment (§3c) and MDM; what actually limits exposure is the two rows above — flagged columns only, for one lease. A PIN-derived key is roadmap, for when the security unit asks for it | — |

**What a client can and cannot do to an answer.** It sends `{code, label}`; the server stamps it
(`LookupAnswerService`). A known code takes the **list's** label, so a client cannot record a forged
name against a real person's code. An unknown code keeps the client's label and is stamped
`UNKNOWN`; a retired one `INACTIVE` — both accepted and shown flagged, never refused, because an
operator cannot repair a refused offline record. Only a malformed value is refused.

**Deleting never loses an answer.** An item any stored answer names, or a device may hold, and a
list a field draws from or a device ever downloaded, are refused with «deactivate instead»
([schema.md](schema.md) §4b). The download log is RESTRICT to its list and to its user (it is in
`UserRepository.isReferencedAnywhere`), so neither can be deleted out from under it.

## Ad-hoc form submissions — FILL vs VIEW (V29 / V30 / V31)

Carrier 3 (`form_submissions`) uses **one filing unit** on write — the operator's operational
unit («واحد ثبت‌کننده»), same idea as a log sheet's `operational_unit_id`. Observation
*location*, when a form needs it, is an optional **lookup field on the form definition**, not a
second unit column. For V30 VIEW / review compatibility the server always stores
`subject_unit_id = reporter_unit_id` (no separate subject picker on panel or mobile).

| Axis | Column / table | Rule |
|---|---|---|
| FILL allow-list | `form_definition_units` | Filing unit must be linked to the form definition. |
| FILL filing unit | `reporter_unit_id` | Caller operates/supervises that unit (or plant-wide). When the caller has several accessible units, they pick among those that also appear on the form. |
| VIEW (list) | `subject_unit_id` (= reporter) | `subject_unit_id ∈ getAccessibleUnitIds` — **supervisors expand downward** (parent sees child filings); **operators stay direct-unit only**. Plant-wide sees all. |
| VIEW (object) | subject unit **or** author | Same as list, plus author own-work (`reported_by_user_id == me`). Own-work is **not** a list predicate. |
| Edit draft | DRAFT only | Author **or** subject-unit supervisor (`assertCanReview`) — so a reopen can be corrected on the web. |
| Late media | `SUBMITTED` or `APPROVED` | Author only, and only an attachment id already named in that filing's `form_data` on the same field (photo or signature). A new id, another field, another person, and `VOIDED` are refused with the draft-edit error. Deletes stay draft-only. Log-sheet upload is a separate method and is not this rule. |
| Approve / void / reopen | subject unit supervisor | `assertCanReview` (filing unit, because subject mirrors reporter). Reopen (V31): `SUBMITTED → DRAFT`; prior `form_data` snapshotted into `form_submission_revisions`. Approved must be unapproved first. Unapprove defaults to ADMIN (V8 shape). |

Sidebar section «فرم‌های عملیاتی» (before reports) lists each active form — **navigation only**;
each form menu reuses `GET:/form-submissions` and never grants access by itself. The unfiltered
page «همهٔ ارسال‌های فرم» (`GET /form-submissions` with no `formId`) is `GET:/form-submissions/all`,
granted by default to whoever may create users (ADMIN; a duplicated administrator inherits it).
Other roles keep their form menus and the unit-scoped list behind `GET:/form-submissions`.
On a form menu, «ثبت جدید» files only that form and the form filter is locked. On the
all-submissions page, «ثبت جدید» may pick any active form. Admin DEFINE stays
«فرم‌های میدانی» under داده‌های پایه (`GET:/forms`).

Panel fill of a `lookup` / `lookupmulti` field uses the same searchable picker as log-sheet
web completion (`lookup-field.js`). Before a draft exists the search is
`GET /forms/{id}/lookups/{listCode}` (FILL, and only a list the form draws from). On a draft
it is `GET /form-submissions/{id}/lookups/{listCode}`. Photo, audio and video fields use the
same `att-uploader` as a log sheet, aimed at `POST /form-submissions/{id}/attachments` once a
draft exists; a new filing with no id yet asks for «ذخیره پیش‌نویس» first. Deleting a file on
that draft is `POST /form-submissions/{id}/attachments/{attachmentId}/delete`, the same path
shape the log-sheet widget already posts.

Regression: `FormReviewIntegrationTest` (filing-unit list isolation, filing-unit supervisor may
approve); `FormReopenAndHierarchyIntegrationTest` (reopen + revision; parent supervisor VIEW;
parent operator does not see child); `FormSubmissionIntegrationTest` (subject forced to reporter);
`FormFillPageRenderIntegrationTest` (lookup picker loads items, draft media upload, form menu
vs all-submissions permission).

## Two questions, two layers, two lifetimes

Without this, a mobile session answers one question — **who are you?** — and any device that
knows a valid password gets one. Enrolment adds a second, independent question — **which
device?** — and a login needs both answers:

| Layer | Table | Lives | Ended by |
|---|---|---|---|
| The person | `api_sessions` (and `users.active`) | one JWT — 8 h by default | expiry, logout, a session revoke, deactivating the user, a login elsewhere |
| The device | `devices` | as long as the device is trusted | revoking the device — nothing else |

They do not touch each other. A session expiring or being revoked leaves the device approved;
revoking a device ends every session issued on it (`revoke_reason = DEVICE_REVOKED`). **A device
belongs to no user** — shared tablets are a supported shape, so any active user logs in on an
approved device with their own password. Revoking a session still means "log out now", not "may
not come back"; that was true before enrolment and still is. Each control answers one question:

| The question | The control |
|---|---|
| End this session now | revoke the session (`/api-sessions`) |
| This person may no longer work | deactivate the user |
| This device may no longer be used (lost, stolen, handed back) | revoke the device (`/devices`) |

## The flow

1. An unenrolled tablet's login is refused with `ENROLLMENT_REQUIRED` — **before the password is
   evaluated**, so trying passwords from a device that is not allowed in reveals nothing about any
   account and costs it no lockout strike.
2. The tablet creates an **ECDSA P-256 key with `extractable: false`** (WebCrypto), sends the
   public half, and shows a request number. The private half can sign but can never be read out —
   not by another page, not by the app's own JavaScript. A random id in storage would be a string
   anyone could copy; this cannot be.
3. An approver finds the number on `/devices` and issues an **eight-digit one-time code**: stored
   as a SHA-256, 10 minutes, five wrong attempts, shown once (flash-scoped — never in a URL).
4. The tablet sends the code **signed by its key** together with the request id. That signature is
   what makes a code read over the phone useless to anyone who overhears it: only the key that
   opened this request can produce it. The signature is checked before the code, so a stranger
   cannot even spend the request's attempts.
5. Every later login fetches a nonce from `/api/auth/challenge` and signs
   `mfdcs-login\n<nonce>\n<username>`. A server nonce rather than a timestamp, because a
   timestamp window would refuse every tablet whose clock is wrong.

The signature format is **IEEE P1363** (raw `r ‖ s`, 64 bytes), which is what WebCrypto emits;
the server verifies with `SHA256withECDSAinP1363Format`. `DeviceKeysTest` pins it against a vector
generated by WebCrypto itself — see AGENTS.md #173.

## Step by step — enrolling a tablet with a code

What each side does, in order, and what is stored where. Times and limits are the constants in
`DeviceEnrollmentService`.

| # | Who | Does | Stored |
|---|---|---|---|
| 1 | Operator, on the tablet | Types a username and password and presses «ورود». The tablet holds no device key, so it sends the plain login. | — |
| 2 | Server | Enrolment is on and the login carries no device proof: refused with **`ENROLLMENT_REQUIRED`** («این دستگاه برای ورود ثبت نشده است…») **before the password is looked at** — no lockout strike, nothing learnt about the account. | — |
| 3 | Tablet | Shows «ثبت این دستگاه» under the form (the form keeps what was typed). The operator may type a name for the tablet («تبلت ۷ واحد ۲») and presses «درخواست ثبت». | — |
| 4 | Tablet | Creates an **ECDSA P-256 key pair with `extractable: false`** (WebCrypto) and keeps it in its own IndexedDB database, `mfdcs-device-identity`. Sends the **public** half and the name: `POST /api/devices/enrollment-requests {publicKey, label}`. | tablet: the key pair |
| 5 | Server | Checks the key is a P-256 public key, computes its **fingerprint** (SHA-256 of the SPKI bytes, hex), and inserts a `PENDING` row: key, fingerprint, name, user agent, the requesting address, `request_expires_at` = **now + 30 minutes**. Refused with `TOO_MANY_PENDING` past **50** live requests in total or **5** from one address. The same key asking again gets **its own request back** (re-armed if it had run out) — never a second row; a key that was revoked is refused with `DEVICE_REVOKED`. Answers `{requestId, status: PENDING, keyFingerprint, expiresAt}`. | server: `devices` row, `PENDING` |
| 6 | Tablet | Shows the **request number** (`requestId`, also the future device id) and stores it with the fingerprint. «بررسی دوباره» asks again later — and is told *approved* if the answer to step 10 was lost. | tablet: request id, fingerprint, `pending` |
| 7 | Operator → approver | Reads the number to an approver — at the desk or over the phone. | — |
| 8 | Approver, in the panel | Opens **`/devices`** («دستگاه‌های موبایل»). «درخواست‌های در انتظار» lists the live requests, newest first: number, name, user agent, address, time. Finds the number and presses **«صدور کد»** (permission `POST:/devices/{id}/code`, ADMIN by default — the one to grant the night shift's supervisor). | — |
| 9 | Server | Draws an **eight-digit** code from `SecureRandom`, stores **only its SHA-256** with who issued it, the time, an expiry of **10 minutes** and a failed-attempt count of 0, and pushes the request's own expiry out to cover the code. Issuing again replaces the previous code. The code is shown **once**, as a flash message («2294 2661»), never in a URL; the code itself is masked in every log line and its hash in the audit trail. | server: code hash, issuer, expiry |
| 10 | Operator, on the tablet | Types the code — Persian or Arabic digits, spaces and dashes are all accepted and normalised the same way on both sides. The tablet **signs** `mfdcs-enroll\n<requestId>\n<code>` with the private key and sends `POST …/confirm {requestId, enrollmentCode, signature}`. | — |
| 11 | Server | Locks the row and checks, **in this order**: the **signature** against the request's public key (`SIGNATURE_INVALID` — and no attempt is spent, so a stranger cannot burn the request's tries); the status (an already-approved key is simply told *approved*; a revoked one `DEVICE_REVOKED`); the request still live (`REQUEST_EXPIRED`); a code issued (`CODE_NOT_ISSUED`) and not run out (`CODE_EXPIRED`, and it is destroyed); the code itself, compared in constant time against the hash. A wrong code counts an attempt (`CODE_INVALID`, with how many remain); the **fifth** destroys it (`CODE_LOCKED`) and a new one must be issued. A right code: the row becomes **`APPROVED`**, `approved_by` = whoever issued the code, the code is cleared. | server: `APPROVED` |
| 12 | Tablet | Records *approved*, says «این دستگاه ثبت شد. حالا «ورود» را بزنید.» | tablet: `approved` |
| 13 | Every login from now on | The tablet asks `POST /api/auth/challenge {deviceId, keyFingerprint}`; the server answers an approved device with a **nonce** (32 random bytes, **2 minutes**, at most 5 outstanding per device). The tablet signs `mfdcs-login\n<nonce>\n<username>` and sends the login with `deviceId`, `nonce`, `signature`. The server **consumes the nonce first** (one `DELETE` — a nonce works once), then checks the device is approved and the signature, and only then the password. The session is recorded with its `device_id` under a share lock, so a revoke racing it cannot be missed; the device's `last_seen_at` moves. | server: session with `device_id` |

**Why each piece is there.**

| Without it | What could happen |
|---|---|
| The signature on the code (step 10) | A code read over the phone could be typed into any other tablet by whoever overheard it. Signed with the requesting key, it works only on the tablet that asked. |
| `extractable: false` | The device's identity would be a string in storage — copyable to another phone by anyone with the tablet and a cable. |
| The signature checked before the code (step 11) | A stranger knowing a request number could spend its five attempts and lock the real tablet out. |
| Only the code's hash, shown once, never in a URL | The code would sit in the database, browser history and access logs for anyone who reads them in the next ten minutes. |
| A server nonce per login (step 13) | A captured login proof could be replayed; a signed timestamp instead would refuse every tablet whose clock is wrong. |
| The username inside the signed login message | A captured proof could be attached to a different account. |
| The nonce consumed before the device is looked at | Sequential device ids could be walked to learn which exist and in what state (issue B-12). |
| The caps on pending requests, and «رد همه» | Junk requests could hold the 50 slots full and keep every real tablet out (issue B-12). |

**What each refusal tells the operator** — the `message` the tablet shows, from `DeviceAuthError`:
`ENROLLMENT_REQUIRED` «این دستگاه برای ورود ثبت نشده است…», `REQUEST_EXPIRED` and `REQUEST_UNKNOWN`
«…دوباره درخواست ثبت بدهید», `CODE_NOT_ISSUED` «هنوز کدی برای این درخواست صادر نشده است…»,
`CODE_INVALID` «کد ثبت درست نیست.» (with the attempts left), `CODE_EXPIRED` / `CODE_LOCKED` «…کد تازه
بگیرید», `DEVICE_REVOKED` «مجوز این دستگاه لغو شده است…». A request that ran out is re-armed by
asking again with the same key; a revoked key is replaced by a new one («درخواست ثبت تازه») and needs a
new approval.

| Limit | Value |
|---|---|
| A pending request may wait | 30 minutes (extended to cover a code issued near its end) |
| A code lives | 10 minutes |
| Wrong codes before the code is destroyed | 5 |
| A login nonce lives | 2 minutes; at most 5 outstanding per device |
| Live pending requests | 50 in total, 5 from one address |
| Code length | 8 digits, random (`SecureRandom`) |

## Off means inert; on is enforced at login only

**Off** (the default, and the fallback for an unreadable value): the login path reads none of the
device fields, the challenge answers `{enforced: false}` and writes nothing, and the enrolment
endpoints refuse. The system is exactly what it was before V13. Verified live, including for a
device that had been revoked while the switch was on.

**One exception, V28: a wipe order outlives the switch.** With enrolment off, a challenge that
presents a device's id **and** its key fingerprint is still answered with that device's standing
wipe order (`{enforced: false, deviceStatus: "REVOKED", wipe: true, wipeOrderedAt}`) — and the
delivery is recorded. An administrator's order to empty a tablet must not be silently dropped by
somebody later turning the switch off. Without an order, or without both identifiers, the answer
is the plain `{enforced: false}` it always was. See «Wipe on revoke» below.

**On**: enforced **at login only**. A session already running is never cut off — it keeps
working until it expires or is revoked. Each tablet needs one enrolment at its next login, which
needs an approver; turn it on while one is reachable, and grant `POST:/devices/{id}/code` to the
shift supervisor's role if nights have no administrator.

### Turning the switch off and on again — what happens to tablets already enrolled

**Nothing is lost either way.** The approval lives in the `devices` row and the key on the tablet;
the switch changes neither. Verified against the code path by path:

| Change | A tablet already enrolled | A tablet never enrolled | A request still waiting |
|---|---|---|---|
| **On → off** | Keeps its key and its approval. At its next login it still asks the challenge, is told `{enforced: false}`, and logs in plainly — one extra request. A session already running carries on | logs in as it always did | cannot be completed while off (`ENROLLMENT_DISABLED`); it runs out after 30 minutes and the tablet re-arms it with the same key when the switch is on again |
| **Off → on** | Signs its next login automatically (step 13) — **no new code needed**, however long it was off. A session opened while off carries on until it expires or is revoked | refused at its next login (`ENROLLMENT_REQUIRED`) and must be enrolled — with an approver reachable | — |

**What the switch being off changes, and an administrator should know before relying on a revoke:**

1. **A revoke does not stop logins.** Off means the login reads no device field, so a revoked
   tablet logs in like any other until the switch is on again. (Verified live when V13 shipped.)
2. **Sessions opened while it is off are not tied to the device** (`api_sessions.device_id` is
   NULL — the device was never asked). Revoking the device therefore **does not end them**: a
   revoke ends only sessions opened while the switch was on. The tablet makes up for it: while a
   session is open it asks the challenge about its own device itself, at most every 30 minutes while
   online (the PWA's `useDeviceStatusWatch`). So a **wipe order** reaches it within half an hour of
   it being online whatever the switch says; and a **plain revoke** made once the switch is back on
   signs such a session out within the same half hour (while the switch is off a plain revoke
   changes nothing on the tablet, by point 1). Ending the session by hand on `/api-sessions` is still
   immediate.
3. **The devices page stops learning about it.** «آخرین ورود» and the reported «ارسال‌نشده» count
   come from sessions tied to the device, so for a tablet used only while the switch was off they
   stay as they were — the revoke page may say «گزارش نشده».

None of this affects a tablet's data or its enrolment; it is what «off means inert» costs, and why
a site that intends to revoke or wipe tablets should keep the switch on.

## Endpoints and permissions

Unauthenticated, all `POST`, all on `app.http.anonymous-json-paths` (the 64 KB pre-authentication
body ceiling — `AnonymousJsonPathsIntegrationTest` fails if one is left off):

| Path | What it does |
|---|---|
| `/api/auth/challenge` | a nonce for `{deviceId, keyFingerprint}`; answers about a device only to a caller presenting **both**, so ids cannot be walked to enumerate devices |
| `/api/devices/enrollment-requests` | a tablet asks to be enrolled; idempotent per key; capped at 50 live pending requests in total and 5 per address |
| `/api/devices/enrollment-requests/confirm` | the signed code; **the request id is in the body**, because the anonymous-path list is matched exactly and a path variable could not be listed |
| `/api/devices/enrollment-requests/wipe-confirm` | **V28** — a revoked tablet's signed «my data is gone»: `{deviceId, wipeOrderedAt, signature}` over `mfdcs-wipe\n<deviceId>\n<wipeOrderedAt>`. The signature is checked **before** anything is said about the order (an unknown device and a bad signature are the same `SIGNATURE_INVALID`); only a caller holding the key learns `WIPE_ORDER_UNKNOWN`. Idempotent — the first confirmation's time is kept. Under this prefix so the shipped nginx, which limits `^~ /api/devices/enrollment-requests`, limits it with no new image |

Panel, `admin` category, granted in V13 **derived from** `POST:/api-sessions/{id}/revoke` (so
ADMIN today, and a duplicated admin role too — not HIGH_USER, whose blanket grant excludes the
`admin` category):

| Permission | |
|---|---|
| `GET:/devices` | the page — pending requests and enrolled devices |
| `POST:/devices/{id}/code` | issue (or re-issue) a code for a pending request — **the one to delegate** |
| `POST:/devices/{id}/revoke` | revoke an approved device, or reject a pending request |
| `POST:/devices/pending/reject` | **V27** — «رد همه»: reject every request still waiting, each exactly as a single rejection (locked, `REJECTED`, attributed). Granted *derived from* `POST:/devices/{id}/revoke` |
| `POST:/devices/{id}/wipe` | **V28** — order a tablet to delete everything the app holds: on an already-revoked device (one that was once approved), or together with the revoke (`POST /devices/{id}/revoke` with `wipe=true`, which then needs **both** authorities — a caller without this one is refused entirely rather than revoked without the wipe). Granted *derived from* `POST:/devices/{id}/revoke` |
| `POST:/devices/{id}/wipe/cancel` | **V28** — withdraw an order the tablet has **not been handed yet**; refused once it has. Granted *derived from* `POST:/devices/{id}/revoke` |

`GET /devices/{id}/revoke` (V28) is the confirmation page for revoking and wiping, guarded by the
**revoke** authority itself rather than a permission of its own: showing the facts — how many rounds
the tablet last reported it has not delivered (V25's backlog, from any session on that device), when,
and who was signed in — is part of that action. The list's revoke button now links there instead of
a `confirm()` box, because that number is the decision.

**A flood of requests (issue B-12).** The request endpoint is public, and fifty live pending
requests in total is a ceiling a handful of addresses can hold full — five each, each needing only
a fresh P-256 key — which stops every real tablet enrolling for half an hour at a time. Two layers:
nginx rate-limits the enrolment and challenge endpoints (the shipped compose configuration), and
the approver clears whatever got through with one click («رد همه» on `/devices`). A tablet whose
request was swept up simply asks again.

**A login says nothing about a device to a caller without a live nonce (issue B-12).**
`authorizeLogin` used to answer `DEVICE_UNKNOWN`, `DEVICE_REVOKED` or `DEVICE_NOT_APPROVED` on the
device id alone — and ids are sequential, so they could be walked, though the challenge endpoint
had been built so they could not. It now spends the nonce **first**: one is only ever issued to a
caller presenting the key's fingerprint, so a live nonce for this id is itself that proof. Without
one, every id — approved, pending, revoked or nonexistent — is the same `CHALLENGE_INVALID`. No
wire change, and nothing changes for a real tablet: it learns its device's state from the
challenge, and a state that changed in the two minutes since is still reported, because its nonce is
live. (The issue proposed sending the fingerprint with the login; the nonce already proves it.)

## Wipe on revoke (V28)

**What it is.** Revoking a device stops it being used; it deletes nothing on it (V13). An
administrator who also needs the data gone — a tablet lost, stolen, sent for repair, handed back —
orders a **wipe** with the revoke, or afterwards on a revoked device. The tablet carries it out the
next time it reaches the server, and confirms with its own key. Roadmap §42 records why this, and
not a timer, is the answer for a tablet whose fate is known.

**How the order reaches the tablet.** Through `/api/auth/challenge`, the one call a tablet makes
with its device identity and without a session — and it makes it at exactly the right moments: the
revoke ended its sessions, so its next request answers 401 and lands it on the login page, which
asks the challenge as soon as the server is reachable; every sign-in asks it; the tablet asks it
before it would ever throw its key away; and **a signed-in tablet asks it on its own at most every 30
minutes while online** — for the session a revoke cannot end, one opened while enrolment was off.
Only a caller presenting the device's id **and** its key
fingerprint is told — the condition every other challenge answer needs — and the answer is given
with enrolment off too (above). The delivery is recorded (`wipe_signalled_at`), and from then on the
order cannot be withdrawn: [schema.md § `devices`](schema.md#devices-v13) has the conditional
`UPDATE` that makes a withdrawal and a delivery unable to both succeed.

**What the tablet does.** Records the order, reloads, and — before its database is opened or
anything renders — deletes the whole local database (rounds, readings, the archive of other users'
rounds, photos, audio, video, signatures, NFC fault reports, reference lists, the session) and both
storage areas, keeps only the server address, moves its key aside, and sends the signed
confirmation, retried until the server answers. The PWA's `docs/sync.md` («Wipe on revoke») is the
device half.

**What it costs, said where it is decided.** A revoked tablet cannot deliver anything, so a wipe
destroys every round it had not delivered — readings that exist nowhere else. That is the one place
this system deletes field data on purpose, and the confirmation page puts the tablet's last reported
count of such rounds beside the button, with the advice to let the tablet deliver first when it is
at hand.

**What it cannot do.** It reaches only a tablet that connects to the server again. A tablet stolen
and never opened on the plant network is protected by Android's storage encryption and the screen
lock, not by anything here. It needs the device to have been **enrolled** (V13): a tablet that only
ever signed in with enrolment off has no device row to order. And **with the switch off it is
slower**: a session opened while enrolment was off is not tied to the device, so the revoke does not
end it; the tablet's own check finds the order within 30 minutes of it being online instead of at its
next request. See «Turning the switch off and on again» above. The panel says this where it matters:
the revoke page, its flash message and the devices list each describe the switch-off case as it is
(«جلوی ورود دوباره با این تبلت را نمی‌گیرد»), rather than promising what a revoke does with it on.

## The races, and what closes them

- **Login vs revoke.** The login checks the device, then — in a later transaction — inserts its
  session. A revoke landing in between would find no session to revoke. `ApiSessionService.register`
  re-reads the device under a SHARE lock in the insert's own transaction; a revoke holds the row
  FOR UPDATE for its whole transaction. Whichever commits first, the other sees it.
- **Two logins with one nonce.** Consumed by a single conditional `DELETE`; the loser deletes
  nothing.
- **A refused step that must still count.** A wrong code's attempt, an exhausted code's
  destruction and a consumed nonce are committed although the call is refused
  (`@Transactional(noRollbackFor = DeviceAuthException.class)`).

## Hygiene

- The code is never stored, only its SHA-256, and that hash is **masked in the audit trail**
  (`AuditEntitySupport.MASKED_FIELDS`): an eight-digit code's hash falls to exhaustive search in
  seconds.
- `enrollmentCode` is masked in every log rendering by name (`LogSanitizer`), listed explicitly
  rather than by adding `code` to the masked words, which would mask `unitCode` and every other
  code this application records.
- A refusal is an `ExpectedRefusal`: one INFO line with its code, not an ERROR with a stack
  trace. With enrolment on, every login from an unenrolled tablet ends in one; logging each as an
  ERROR would bury real faults (AGENTS.md #174).
- Challenges are written by native queries, so the audit aspect does not put two rows in
  `audit_log` per login.

## What it does not protect against

- **Someone with a valid password and physical access to an approved tablet.** That is what
  revoking the device is for.
- **A rooted device's storage.** A non-extractable WebCrypto key is protected by the browser, not
  by hardware. The packaged app could hold it in the Android Keystore through a native plugin;
  the protocol would not change. Deferred — roadmap §30.
- **Loss of the key.** Clearing the app's data, reinstalling or a factory reset makes a new device
  that must be enrolled again — correctly, and the same act loses unsynced readings, which is the
  larger loss. The key is kept in its **own IndexedDB database**, never in `offline-pwa-db`, because
  `openDatabase()` deletes that one on a version error whenever it holds no unsynced work
  (AGENTS.md #175).

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

Three endpoints added with the per-asset band feature (V5) reuse rather than seed, for the same
reason — each is a variant of something the holder may already do:

| Endpoint | Reuses | Why that one |
|---|---|---|
| `GET:/asset-entries/{id}/parameters` | `GET:/asset-entries` | Reading an asset's own schema is reading the asset |
| `POST:/asset-entries/{id}/parameters` | `POST:/asset-entries/{id}` | Setting a band is editing the asset |
| `POST:/log-sheet-templates/{id}/convert-to-explicit` | `POST:/log-sheet-templates/{id}` | Freezing the list is editing the template |

The third also repeats `assertCanEditOrDelete` in the service and validates the posted asset ids
through the same `validateExplicitAssets` that create and update use, so a unit-scoped
supervisor cannot reach an asset through this door that the ordinary form would refuse them.
The first two do not, and that is F1 below rather than an oversight — they behave exactly as the
asset edit form beside them does.

**Forbidden:** creating a permission only in the Roles UI, only with ad-hoc SQL, or only in
Java bootstrap code. Other environments will miss it and `@PreAuthorize` will deny everyone.

---

# 5. Findings from the access review

State as reviewed. Re-run the queries in [§6](#6-how-to-re-audit) after any change.

## F1 — Master-data lists have no unit filter

**Status: fixed.** Lists, exports and every write path that takes an id.

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

**The per-asset parameter screen inherits this**, deliberately and without widening it.
`GET`/`POST:/asset-entries/{id}/parameters` carry the same two authorities as the registry list
and the asset edit form, and like them they do not consult `AssetAccessService`. A unit-scoped
custom role granted `POST:/asset-entries/{id}` could therefore re-band an asset outside its
units — but the same role could already rename, re-place or deactivate that asset through the
edit form, so this adds no reachable surface. It does raise the stakes of the remediation
below: a wrong band is a wrong decision about a machine, where a wrong name is only confusing.
Step 1 of the plan should cover `AssetEntryWebController` as a whole, the parameter handlers
included.

A scoped alternative already exists and is used by the reports:
`AssetAccessService.findVisibleAssets`.

### What was done

The filter now exists **before** anyone is granted the permission, which was the whole point:
the moment a unit-scoped role is given one of these, the alternative is handing over the plant.

| Surface | Now |
|---|---|
| `/asset-entries` list | `AssetAccessService.findVisibleFiltered(q, classId, pageable)` — the class filter and the search box both narrow *within* the scope |
| `/asset-entries/export` | the same method, so the file cannot differ from the screen |
| `/asset-entries/{id}` edit, delete | `assetAccessService.requireVisible(id)` |
| `/asset-entries/{id}/parameters` GET and POST | the same check — a band is the number a reading is judged against, so this matters more here than on the edit form |
| the edit dialog's prefill | `findVisible`, so another unit's asset cannot be rendered by typing its id into the query string |
| `/locations`, `/plant-systems`, `/main-functions`, `/sub-functions` lists | `MasterDataScopeService`, one branch point for all four |
| their four exports | the same service |

**A plant-wide caller runs exactly the query they ran before.** `visibleUnitIds()` returns
`null` for them and the null branch is the untouched statement — same plan, same measured cost
([performance.md](performance.md): 0.05 ms unchanged; the scoped path is 1.1 ms warm and is paid
by nobody today). The three-way branch — *null is unrestricted, empty is nothing, otherwise
filter* — is written once, in `MasterDataScopeService.scoped`, because getting `null` and empty
the wrong way round is gotcha #58 and shows up as an administrator staring at a blank page.

All four scoped queries select from `AssetUnitScopeSql.SCOPED_SUBFUNCTIONS_CTE`, which already
walks unit → location tree → systems → main-function trees → sub-functions and exposes each
level as its own set. A second hand-written walk would be a second answer to the same question.

Proved by `MasterDataUnitScopeIntegrationTest`: eleven cases, including that the class filter
and the search box cannot widen the scope, that a scoped user mapped to **no** unit sees nothing
rather than everything, and that a plant-wide caller still sees both.

### Two things to know before granting one of these

**The scope walk begins at `location_units`, and on the operational database that table has a
single row.** Until locations are mapped to units, a unit-scoped user granted
`GET:/asset-entries` sees an **empty** list, not a partial one. That is the safe direction — it
fails closed — but it will be reported as a bug, and the fix is data, not code.

**`asset_classes` is deliberately NOT filtered.** A class names a kind of equipment, carries no
location and belongs to no unit; filtering it would empty the class picker the asset form needs
without protecting anything. If that judgement ever changes, the rule would have to be "classes
with at least one asset in my units", which costs a query per page load.

### The write paths

The lists were the leak, but filtering them alone would have been a curtain in front of an open
door: every write handler takes an id from the URL. All twelve now ask whether that id is in the
caller's scope — `update`, `delete` and `delete-bulk` at each of the four levels — through
`MasterDataScopeService.requireVisible*`, which throws `AccessDeniedException` and lands on the
existing denial page.

**A bulk delete is all or nothing.** Silently dropping the out-of-scope ids and deleting the
rest is worse than refusing: the operator is told it worked and finds out later that half of it
did. One `count(*)` against the scope CTE compared with the number of distinct ids asked about
— a per-id loop would turn a three-hundred-row delete into three hundred recursive walks.

Proved by the write-path cases in `MasterDataUnitScopeIntegrationTest`, including that a bulk
delete spanning the boundary leaves **both** rows in place, and that a plant-wide caller may
still do all of it.

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

## F7 — A scope ceiling silently truncated the status-request queue

**Status: fixed.**

`/asset-status-requests` resolved its scope by materialising the caller's reportable asset ids
capped at **5000** and passing them as an `IN (…)` list. Past that, requests for the assets beyond
the cut were absent from the queue on every page — no error, nothing on screen to say a row was
missing. On a 200,000-asset installation it was firing for every unit-scoped supervisor.

Two things make this a security finding rather than only a defect. The list's contents **are** an
access decision, so a bug in how the scope is computed is a bug in access — here failing closed,
which hides work rather than exposing it, but no more correct for that. And the header's
`pendingCount` was **not scoped at all** (`countByStatus`), so a supervisor restricted to one unit
was shown the whole plant's backlog above a list that could never contain it: a number that
disclosed activity in units they cannot see, and could never agree with the rows beneath it.

Both now resolve the scope in SQL, through the same `REPORTABLE_ASSETS_CTE` the rest of the
reporting scope uses. `AssetStatusRequestScopeIntegrationTest` pins the access rules — own unit
yes, another unit no, admin everything, no-units nothing, and the log-sheet branch that makes
reporting scope deliberately wider than location ownership — and those cases pass against the old
implementation too, which is what says the fix changed the truncation and not who sees what.

See [performance.md §4d](performance.md) for why pagination was never going to help, and AGENTS.md
gotchas #124 and #125.

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

## Seeing a log sheet and changing it are two different permissions

`canView` is **unit-scoped**: an operator sees every sheet in their operational unit, which is
what the inbox, the pool and every list are built on. It was never the write rule for readings —
only the current assignee may complete a sheet, and a submission from anybody else is stored as
`SUPERSEDED` rather than applied.

**Even that storing now needs a claim on the sheet (V25, issue B-04 §1).** Before it, holding
`POST:/api/log-sheets/batch` was enough to name *any* `serverId`: a void row with an arbitrary
payload was written onto a sheet of another unit, in front of that unit's supervisor, and the
answers distinguished «not found» from «completed» from «expired» — an oracle for which sheet ids
exist. `voidSubmission` now first requires `canView(sheet)` **or** that the caller held the sheet at
some point (`LogSheetActionLogRepository.everHeldBy`: a `CLAIM`/`TAKEOVER` they made, or an
`ASSIGN`/`REASSIGN`/`TAKEOVER` naming them) — the second half so an operator moved to another unit
while offline can still deliver what they walked. Without a claim the answer is identical to a
missing sheet and nothing is written. Late progress kept after the deadline (B-02) is only ever
kept for the sheet's current assignee.

**And what a claim lets one caller leave is bounded (V26, B-04 §2–§4).** The same content again
updates the row it already has (`payload_hash`); past five *different* refused submissions per
person per sheet the next is answered `kept: false` and stored nowhere; a payload naming an asset
the round does not have is refused, not kept; and a void's evidence is held to the field's own
ceiling within that void (`AttachmentService.enforceEvidenceCount`, a `400`). The void used to
authorise any number of files through the ids it chose to name. `log-sheets.md` §6 has the table.

**Attachments used it as a write rule, and that was a real gap.** `AttachmentService.upload` and
`.delete` asked `requireVisibleLogSheet`, so one operator could add a photograph to, or delete one
from, a colleague's round in the same unit — on the tablet and in the browser. Evidence could be
removed from a round by somebody who had nothing to do with it, and nothing recorded that it had
been.

What made it easy to miss is that a test appeared to cover it.
`stopsAllowingItTheMomentTheSheetLeavesTheirHands` asserts an upload turns `403` the moment the
assignee is cleared — but the fixture removes the operator from every unit first, so `canView` had
nothing left but the assignee to fall back on. With unit membership intact, which is the ordinary
case, it never refused. **A test that arranges the scope away is not testing the scope.**

### The rule now

`LogSheetAccessService.requireWritableLogSheet` — the assignee, or
`CAP:LOGSHEET_COMPLETE_WEB_ANY`. It is deliberately **wider** than
`LogSheetWebCompletionAccess.canCompleteOnWeb`, and every case that one admits this one admits
too: the capability outright, and its other two branches both require the actor to be the
assignee. So the web fill page cannot lose a path it had, and the controller still applies its own
narrower rule on top.

| Operation | Rule | Why |
|---|---|---|
| `download` | **visible** | Reading is what unit scope is for. Narrowing it would break the detail page for everyone but the assignee |
| `upload` | **writable** | Including the idempotent re-send branch: it returns the stored row, so on a visibility check a colleague's id would answer `200` and turn upload into a lookup |
| `delete` | **writable** | Plus the `APPROVED` freeze, which is a separate and narrower rule again — and never a void's evidence (V15) |

**The one exception to `upload`: a void's evidence (V15).** When the sheet has moved on — taken
over, reassigned, released — the departed operator's late submit is kept as a void, and the
photographs its readings name arrive afterwards. `upload` then accepts a file the caller may not
write **only** if a void on that sheet was submitted by the caller **and** names that exact id for
that asset (or for the round, when there is no asset) and field. Anything else — a new file, the
right id on another field, a colleague's void — is still `403`. The row is marked
`void_submission_id` and kept out of the live round entirely (see `schema.md`), so the exception
can add evidence to a record of refused work and nothing to the round somebody else now owns. The
idempotent re-send branch answers **the file's own uploader** with their row whether or not they
may still write the sheet — they learn nothing they did not send — and everybody else only if they
may write it, as before. Without that, a file that did arrive, whose response was lost on a weak
link before the round moved to somebody else, was reported to the tablet as refused.

A sheet in the pool has no assignee, so only the capability opens it — matching what filling
already does, since the web form refuses an unassigned sheet without the capability and a tablet
claims before it captures.

### Two ids in one URL

`POST /log-sheets/{id}/attachments/{attachmentId}/delete` carries both, and until this was checked
they answered different questions: `{id}` decided whether the actor may act, `{attachmentId}`
decided what was acted on. `AttachmentService.delete` resolves the owning sheet from the
attachment itself, so it could never see the mismatch — somebody who may fill sheet A could delete
a file from sheet B by naming A in the path. The controller now checks the attachment is on the
sheet in the path, and answers the same way for "no such attachment" as for "belongs to another
sheet", so the endpoint cannot be used to discover that an id exists somewhere.

**Regression:** `AttachmentApiIntegrationTest` § *A colleague in the same unit* — four tests, three
refusing writes and one confirming reads still work — and
`WebFillEntryDialogIntegrationTest.anAttachmentFromAnotherSheetCannotBeDeletedThroughThisSheetsUrl`
with its counterweight. Reverting either check fails them; verified by mutation.

## Three things a re-audit does not need to re-derive

Each of these looks like a finding and is not. They were checked against the code, and the reason
they are safe is in each case a detail that is invisible from the call site — so without this note
they get re-investigated every time.

**`/api/auth/login` is not a password-flood vector.** It is `permitAll`, so it invites the
question. `AppAuthenticationProvider` extends `AbstractUserDetailsAuthenticationProvider`, **not**
`DaoAuthenticationProvider` — which matters because it is `DaoAuthenticationProvider` that runs a
dummy BCrypt comparison for an unknown username to level the timing. Here an unknown username
fails at user lookup and costs no hash at all. A *known* username costs one BCrypt per attempt,
and `LoginAttemptService` caps that at `max-attempts` (5) before a `lock-minutes` (15) lockout
that is checked *before* any password or LDAP verification. The counter map is in memory and keyed
by username, and only usernames that resolve to a real user ever reach it, so guessed names create
nothing. The one residual is timing: a known username takes a BCrypt longer to fail than an
unknown one, which is a username-enumeration oracle on a network where that matters.

**CSRF is disabled on exactly the two chains that should have it disabled.** `/integration/**`
(`@Order(0)`) and `/api/**` (`@Order(1)`) both authenticate from a header and hold no session, so
CSRF protection buys nothing there. The web chain (`@Order(2)`) has no `csrf` call at all and
therefore keeps Spring's default — enabled. Confirm by line number rather than by eye: two
`.csrf(AbstractHttpConfigurer::disable)` calls, and both sit above a `securityMatcher`.

**No query is assembled from user input.** The `@Query` annotations that concatenate
(`AssetUnitScopeSql.SCOPED_SUBFUNCTIONS_CTE + """…"""`) join compile-time constants; there is no
runtime string building, and no `createNativeQuery` with an interpolated argument anywhere in
`src/main/java`.

Four checks. All of them are cheap and should be repeated after touching permissions.

**Every handler is guarded** — should list only `/login`, `/api/auth/login`, `/api/health`, and the four device-enrolment endpoints (`/api/auth/challenge`, `/api/devices/enrollment-requests`, `/api/devices/enrollment-requests/confirm`, `/api/devices/enrollment-requests/wipe-confirm` — §3c):

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

## Dependencies — the part that cannot be audited from the plant

Reading versions out of `mvn dependency:tree` tells you what is installed, not what is vulnerable;
that answer lives in an advisory database, and the plant network cannot reach one. **So this check
belongs on a machine with internet, and its result has a shelf life** — a clean scan means clean
*that day*, and the same unchanged `pom.xml` can be vulnerable a month later.

```bash
mvn org.owasp:dependency-check-maven:check     # backend
npm audit --omit=dev                           # the PWA, in its own repo
```

Two standing notes so a scan's output is read correctly:

- **A CVE against a dependency is not automatically a CVE against this application.** Ask which
  code path reaches it before acting. The PWA's `react-router` advisories are the worked example —
  both are real, neither is reachable, and the offered remedy is a breaking major. The reasoning is
  in that repository's `AGENTS.md`.
- Record what you dismissed and why, in the same place. An advisory dismissed without a written
  reason gets re-investigated at every audit, and eventually somebody dismisses a real one by
  pattern-matching against the last time.

---

# 7. The fourth authentication surface — integration API keys

Everything above this section is about **people**. This one is not, and that difference is the
whole design rather than an implementation detail.

## Four chains, not three

| Order | Matches | Credential | Principal | Answers with |
|---|---|---|---|---|
| `@Order(0)` | `/integration/**` | `X-API-Key` header | `IntegrationClient` | English JSON + error code |
| `@Order(1)` | `/api/**` | `Bearer` JWT + live `api_sessions` row | `AppUserDetails` | Persian JSON |
| `@Order(2)` | everything else | session cookie (form login) | `AppUserDetails` | HTML / redirect |
| — | — | — | — | — |

The integration chain sits **ahead** of the API chain, so a request to `/integration/**` can only
ever be decided there: it cannot fall through to the JWT chain, and a browser session cannot
reach it. That is what "third-party endpoints must be separate from normal user authentication
APIs" means once written down as configuration rather than as a naming convention.

Four things are absent from that chain on purpose:

- **No CORS.** Server-to-server only. Enabling it invites somebody to put the key in browser
  JavaScript, where it is public.
- **No session.** `STATELESS`, so no `JSESSIONID` is ever minted and a key cannot be traded for
  a cookie that outlives its revocation.
- **No CSRF.** There is no ambient credential to ride on — that is the whole of what CSRF
  exploits — and every endpoint is a GET.
- **No redirecting entry point.** `IntegrationAuthenticationEntryPoint`, never
  `ApiAuthenticationEntryPoint`: the latter falls back to a login redirect for anything outside
  `/api/`, so a bad key would get a `302` to an HTML login page, which most HTTP clients follow.
  The integrator would then be staring at a 200 and a login form with no sign their key was
  refused.

## There is no permission row for these endpoints, and there must not be

Every other endpoint in this system is gated by a `METHOD:/path` authority that a role grants
([§4](#4-adding-an-endpoint-mandatory)). That model presumes a user. Here there is none, so
there is nothing for a role to hold: `IntegrationLogSheetController` carries no `@PreAuthorize`,
and the chain itself is the gate.

Seeding a permission for `GET:/integration/v1/log-sheets` would advertise that a role could be
given this access. It cannot be. `IntegrationKeyAdminIntegrationTest` asserts no such row exists.

The **admin page** that manages keys is gated normally, ADMIN only:

```
GET:/integration-keys            POST:/integration-keys
POST:/integration-keys/{id}/status    POST:/integration-keys/{id}/revoke
POST:/integration-keys/{id}/scope     (V20 — granted to whoever holds POST:/integration-keys)
```

`HIGH_USER` deliberately does not get them: issuing a credential that reads every completed round
in the plant is an administrative act.

## The principal is not an AppUserDetails

`IntegrationAuthenticationToken` holds an `IntegrationClient` (key id, client name) and exactly
one authority, `INTEGRATION_API`. Nothing in the application grants that authority, so no user —
administrator included — can ever hold it, and no integration client can ever hold a user's. The
separation is expressed as a type rather than as a convention.

Two consequences worth knowing, both of which fail in the safe direction:

- `SecurityUtils.currentUser()` returns **null** on this chain. Every helper that reads the
  principal already tolerates that.
- `SecurityUtils.isUnitScopedOnly()` answers **true** — restricted — because the capability is
  absent. A future caller that reaches a scoped query from here gets nothing rather than
  everything. This is [§3](#3-capabilities--access-that-is-not-about-an-endpoint)'s
  "phrase capabilities positively" rule paying off in a context it was not written for.

## What a key can reach, and what it cannot

**Finished log sheets only.** `SUBMITTED`, `VOIDED`, `EXPIRED`, `CANCELLED` — and the terminal
list is a *literal* inside `LogSheetRepository.findExposableToIntegration`, not only a bound
parameter validated by the caller. The duplication is deliberate: a filter that lives only in the
caller is one the next person to reuse the method will not know exists, and the thing being
filtered is "half-finished work must not leave the building". Same reasoning as
[§1](#1-the-three-layers) on not putting access rules in controllers alone.

A request for `PENDING`, `ASSIGNED` or `IN_PROGRESS` is a **400**, never a quietly empty page —
silently dropping it would answer 200 with only the submitted rows and let the caller conclude
that no round is ever in progress.

**A key may be limited to categories and statuses (V20).** Chosen when the key is issued and
changeable afterwards (`POST:/integration-keys/{id}/scope`); read on **every request** by
`ApiKeyScopeService`, never cached on the principal, so a narrowed scope applies to the very next
call. The scope **only ever narrows** what the caller asked for (`IntegrationKeyScope`): a status
outside it named in the request is a `403 forbidden` that lists what the key may read — the five
statuses are a public list — while a category outside it simply matches nothing, so a key cannot
probe which categories the plant has; a round outside it is a 404 on the detail, the same answer as
a round that does not exist. An empty narrowing is answered without a query and is never passed on
as «no filter» (null and empty are kept apart by construction: the type refuses an empty set).
A category a key is limited to cannot be deleted (RESTRICT), because a key whose last category
vanished would silently widen to every category. A status column whose names all stopped being
exposable fails closed (500) rather than widening. **Still no unit scope:** a key sees every unit
its category/status scope allows; a `scope_unit_ids` dimension would follow the same pattern.

**Deliberately not exposed**, whatever else is added to the underlying rows later:
`sync_status`, `synced_at`, `draft_saved_at`, `field_definitions_snapshot` on the summary,
`client_action_id`, internal user ids, `nfc_serial` (an anti-cloning control — one that has been
published is not one), `storage_key`, and every personal field on a user beyond username, full
name and personnel code. Responses are built from dedicated DTO records rather than from entities
or the mobile DTOs, so the default for anything new is that it is **not** published until somebody
adds a line to those records. Integration tests assert the absence by scanning the serialised JSON,
so a field added to a shared type fails the build rather than shipping.

**Attachments are announced, never served.** Metadata only — id, kind, mime type, size, duration.
No bytes, no URL, no download endpoint. Publishing the id keeps the file addressable later without
changing the response shape; publishing a link now would commit to serving plant photographs to an
external system, which is a decision nobody has taken.

## Every request is recorded, including the refusals

`api_key_usage` gets one row per request that reached the chain — client, endpoint, filters,
status, row count, duration, IP, timestamp. The refused ones matter most: a run of `INVALID_KEY`
from one address is the only evidence anybody will get that somebody is guessing keys.

**The caller is told less than the log records.** Every key failure — missing, malformed,
unknown, wrong secret, disabled, revoked, expired — answers the same `401 unauthorized`. Telling
somebody holding a key they should not have that it is "merely disabled" is telling them what to
try next. The real reason is in `outcome`, where an administrator can read it and the caller
cannot.

The key itself never reaches a log line, in any branch. It travels in a header, so it cannot
appear in `query_string`; `LogSanitizer` masks `apiKey`/`secretHash` in the aspect's output; and
verification lives in `security/ApiKeyAuthenticator` rather than under `service..*` precisely so
the raw credential never reaches `LoggingAspect`'s argument serialisation at all.

**Recording the refusals is what makes this chain floodable, and that is a trade rather than an
oversight.** A request carrying no header at all is refused without touching the database and
still costs one usage row and one WARN line. What bounds it today is the deployment — nginx
forwards `/api/` and not `/integration/`, so reaching this chain needs direct access to port 8081
— not the code. [roadmap.md § 2](roadmap.md#2-request-rate-limiting) carries the measurement, the
three bounds that currently apply, and the one-line nginx fix. **Read it before exposing
`/integration/**` through the proxy.**

## The trap this feature walked into

`IntegrationApiKeyFilter` is a `@Component` extending `Filter`, and **Boot auto-registers every
`Filter` bean against `/*`** on top of wherever the security configuration places it. For
`JwtAuthenticationFilter` that is harmless — it only tries to authenticate and then calls
`doFilter`. This one *terminates* the request with a 401 when there is no key, so auto-registered
it answered that 401 to **every URL in the application**: the login page, `/api/health`, the CSS.

All 1,356 tests passed. `MockMvcBuilders.webAppContextSetup(...).apply(springSecurity())` wires
the Spring Security chain and nothing else, so the auto-registered copy does not exist in a MockMvc
test. One live `curl http://localhost:8081/login` found it immediately.

The fix is a `FilterRegistrationBean` with `setEnabled(false)` in `WebSecurityConfig`.
`IntegrationFilterScopeIntegrationTest` starts a real servlet container and guards it — verified
to fail four ways when the registration is re-enabled. **Any future filter that can write a
response needs this, or must not be a `@Component`** (which is how `UserMdcFilter` solves it).
See gotcha #77 in [AGENTS.md](../AGENTS.md).

---

# 7a. Alarms (V17) — who sees them, who decides, and the first outbound call

The behaviour is [alarms.md](alarms.md). This section is the access model.

## Permissions

| Code | What | Granted (derived, like V16) to |
|---|---|---|
| `GET:/alarms` | the queue, one alarm's page (reused, as the log-sheet export reuses the list's), and the dashboard's three alarm counts (absent for anyone without it) | every role holding `GET:/asset-status-requests` |
| `POST:/alarms/{id}/acknowledge` | «دیدم — در حال پیگیری» | every role holding `POST:/asset-status-requests/{id}/decide` |
| `POST:/alarms/{id}/close` | close with a reason | same |
| `GET:/alarm-rules` | rules, webhook status, delivery log | every role holding `GET:/integration-keys` |
| `POST:/alarm-rules`, `POST:/alarm-rules/{id}`, `POST:/alarm-rules/{id}/delete` | write the rationalisation | same |
| `POST:/alarm-deliveries/{id}/retry` | resend a `FAILED` delivery | same |
| `GET:/alarm-webhooks` (V21) | the receivers page and the delivery log | every role holding `GET:/alarm-rules` |
| `POST:/alarm-webhooks`, `…/{id}`, `…/{id}/delete`, `…/{id}/secret`, `…/{id}/test` (V21) | register, edit, delete a receiver; regenerate its signing secret; send it a test request | every role holding `POST:/integration-keys` — pointing this server's outbound requests somewhere new is the same decision as issuing a credential to an outside system |

The alarm queue sits beside the asset-status-request queue because it is the same kind of question
— a reading on a finished round needs a supervisor — asked of the same people. Rules are
administration: they decide what this system tells people and other systems, which is what
`GET:/integration-keys` already means. Derived from grants rather than role codes, so a duplicated
role inherits both halves (§3).

## Scope: the log sheet's

An alarm belongs to the unit responsible for the round that raised it, and is visible to exactly
whoever could see that unit's rounds — `getAccessibleUnitIds`, so a parent unit's supervisor sees
their children's alarms — and to every plant-wide reader. **The same check decides who may
acknowledge or close** (`AlarmService.requireVisible`, behind the permission): seeing an alarm and
being entitled to decide it are one question here. An alarm whose round had no unit is visible
only to plant-wide readers; guessing a unit would show it to the wrong supervisor.

`returnTo` on the two POSTs is accepted only as a path inside the application
(`WebRedirectSupport.safeReturn`: it must start with one `/`, and may contain no `\` and no
`://`); anything else falls back to the alarm's own page, so the form cannot be turned into an
open redirect. **The same rule now covers every `returnTo` in the panel** (issue B-11): the status
requests' create and decide, and the NFC fault reports' review and delete, used to concatenate it
onto `redirect:`; and their flash messages go through `ErrorTranslator.toFa`, so driver text from
a constraint violation never reaches the page.

## The webhook is the first outbound HTTP call this server makes

Until V17 nothing in `src/main` opened a connection to anything (roadmap §11 recorded it). The
rules that keep that change safe:

- **Since V21 the receivers are data, entered in the panel — and the guard moved into code.** V17
  kept the URL in configuration because an admin-editable outbound URL is a way to point the
  server at an internal address and read the response (SSRF). `AlarmWebhookTargetPolicy` now
  refuses loopback, link-local (cloud metadata) and unspecified addresses **by what the name
  resolves to**, at save and again before every request; `app.alarm.webhook.allowed-hosts`, when
  set, is the complete list of hosts (production should set it — [deployment.md](deployment.md));
  and **a receiver's response body is never stored or shown** — only its status code — so the
  delivery log cannot be used to read another service. The list shows scheme, host and port; the
  full address only in the edit form, to those who may edit.
- **Two credentials per receiver, both stored as written** because the server must use them on
  every request: the API key (sent as `X-API-Key`) and a **generated** signing secret (never sent;
  the request carries `HMAC-SHA256(secret, timestamp.body)`). Neither is logged, put in a delivery
  row or an error message, or shown after it is saved (the secret once, at creation or
  regeneration); both are **masked in `audit_log`** (`AuditEntitySupport.MASKED_FIELDS`), whose
  value columns would otherwise hold a working credential.
- **Redirects are not followed**, and connect and request timeouts are always set.
- **No call inside a transaction**; the outbox is drained on its own thread.
- **What crosses the boundary is decided**: personnel code, username, full name — and phone
  number only for a receiver whose «شماره تلفن سرپرستان» is on (per receiver since V21), because
  the stated purpose is for the receiver to text people. No internal user id, no national code. Only **active**
  supervisors are listed: a deactivated account is somebody who no longer works here.
- Because the payload carries phone numbers, `alarm_webhook_deliveries` is excluded from
  `audit_log` (whose value columns would otherwise copy them) and its payload is never rendered in
  the panel.

On a plant LAN with no route outward, the receiver is an internal service; firewall the egress to
it alone ([deployment.md](deployment.md)).

## No foreign key to `users`

Every actor column in V17 (`acknowledged_by_user_id`, `closed_by_user_id`, `actor_user_id`,
`created_by_user_id`, …) has none — the `audit_log` decision (V3 §5): an alarm somebody
acknowledged must outlive their account, and a RESTRICT key would make «delete this user» depend on
every alarm they ever touched. `UserDeleteReferenceIntegrityTest` reads the foreign keys from the
catalogue and is unaffected.

---

# 8. When a change to a user takes effect

The two kinds of session answer this differently, and the difference is the whole section.

**A mobile request is always current.** The token says only who the holder is; every request
resolves that id to a user row, its roles and its permissions, through
`ApiTokenAuthenticator` → `AppUserDetailsService.loadById`. So a role change, a permission grant,
a revocation and a deactivation are all in force on the tablet's very next request, with the
session left open and nobody logged out.

**A browser session is not.** Spring Security stores the `AppUserDetails` built at form login in
the HTTP session, and the 60-minute timeout is an *idle* one, so an active browser holds the
identity it was given until the person logs out or is expired from `/web-sessions`.

| Change | Mobile (API) | Browser (panel) |
|---|---|---|
| **Deactivate** | session closed immediately, and the token would be refused anyway | session closed immediately |
| **Delete** | session closed immediately, and the token no longer resolves to a user | session closed immediately |
| **Role or permission change** | **in force on the next request**, session left open | old access until logout/timeout — the page warns |
| **Rename** | keeps working; resolution is by `uid`, not by the token's `sub` | keeps working |
| **Contact / org fields** | no effect on access | no effect on access |

## Why the mobile side both keeps the session and applies the change

These used to be alternatives, and the system chose the session. Roles are edited routinely —
adding a permission, correcting an assignment — and this is an offline-first fleet where
reconnecting is not always possible, so logging every affected operator out of their tablet
mid-round, in order to *widen* their access, would have been hostile to administer and would have
lost work at the worst moment. The cost of that choice was that the change then waited for the
token to expire, up to eight hours later, and an administrator who had just removed somebody's
access had no way to know.

Resolving authorities per request removes the choice rather than trading it off. Nothing about
the session needs to change for the permissions to change, because the session was never where
the permissions lived.

The deliberate non-behaviours are still deliberate: **a role change does not revoke the mobile
session**, and `UserDeactivationRevokesSessionsIntegrationTest` asserts both halves — that the
token keeps working, and that it carries the new role's access on the next call.

## A session that cannot reach the server (V28)

The other side of keeping the session: **offline, a mobile session never expires on the tablet** —
`isSessionValid` keeps it while the server is unreachable, so an operator in a dead zone is not
thrown out mid-round. A tablet taken away therefore opened, weeks later, still signed in, showing
every round on it. From V28 the plant can bound that: `device.offline_session_limit_enabled` /
`device.offline_session_limit_hours` (Settings, «قفل نشست آفلاین روی تبلت»; off by default, 8–720
hours), delivered on bootstrap as `offlineSession`. The tablet then locks a session the server has
not answered for that long — counted from the last authenticated answer, on the server's clock as
the tablet measured it — and also one whose clock reads earlier than that last answer (set back, or
reset by a flat battery). The lock is the ordinary sign-out: nothing local is deleted, unsent work
stays and is sent after the next sign-in, and signing in needs the network. It never interrupts an
open asset form; it lands at the next navigation or the next start. The server enforces nothing
here — it cannot see an offline tablet — and the PWA's `docs/sync.md` («The offline-session
ceiling») is the device half.

## What the warning on the users page is now about

`UserService.UserUpdateOutcome.needsSessionWarning()` is still true when roles changed on a user
who is still active, but it now says something narrower and true:

> نقش‌های این کاربر تغییر کرد. نشست‌های اپ موبایل از همان درخواست بعدی، دسترسی جدید را اعمال
> می‌کنند. اما اگر او مرورگری باز دارد، آن نشست تا خروج یا پایان مهلت با دسترسی قبلی ادامه
> می‌دهد؛ برای اعمال فوری، نشست وب او را از صفحه «نشست‌های وب» ببندید.

The lever moved with it: `/web-sessions`, not `/api-sessions`. Revoking a mobile session to apply
a role change is no longer something anybody needs to do — and the old message told them to.

## A leaked signing key, and what it is now worth

Worth stating plainly, because the answer changed and because the key ships in
`application.properties` as a working default that `ProductionReadinessRunner` warns about on
every boot.

Somebody holding the key can sign whatever they like. They **cannot**:

- **grant themselves anything.** Authorities come from the database. A `perms` claim listing
  every endpoint in the system is ignored, because nothing reads it.
- **become somebody else.** `isSessionActive` checks that the `jti` is a live session *belonging
  to the `uid` in the token*. Forging a `uid` needs a `jti` issued to that user, and the only
  `jti`s in existence belong to real logins. The one they can supply is their own.
- **outlive the session.** A forged `exp` of ten years is capped by `api_sessions.expires_at`,
  which is what the per-request check actually reads.

What remains: with the key **and** a valid login of their own, they can mint tokens for
themselves — which is what logging in already gave them. Replace the key anyway; this is
defence in depth, not a reason to ship the default.

`JwtForgeryIntegrationTest` signs real tokens with the real key and asserts each of the three,
over real requests. Its counterpart `ApiTokenAuthenticatorTest` covers each condition failing
alone.

## Sessions are found by user id, never by username

Both halves of "close this person's sessions" key off the **user id**:
`ApiSessionService.revokeAllForUser` always did, and `WebSessionService.expireByUserId` does now.

It used to match the username, and a username is the one field that changes. The registry holds
the principal captured *at login*, so renaming an account and deactivating it — in one edit or
two — searched under the new name, matched nothing, and left the browser live.

`AppUserDetails` identifies itself by id for the same reason. That also closed two bypasses that
had nothing to do with deactivation: renaming an account used to defeat `maximumSessions(1)`
(the registry saw a new principal and kept both browsers), and reusing a freed username made two
different people compare equal. See gotcha #82.

## The revoke reason is not decoration

Every rejected request looks identical to the device — the token simply stops working — so
`api_sessions.revoke_reason` is the only place that can distinguish a manual revoke (`ADMIN`)
from a second login (`SUPERSEDED`) from a deactivation (`USER_DEACTIVATED`). An administrator
fielding "my tablet logged itself out" needs that, and it is unrecoverable if never written.

Covered by `UserDeactivationRevokesSessionsIntegrationTest`, including the deliberate
non-behaviour: a role change must leave the token working, and that is asserted rather than left
to chance.

---

# 9. Other security surfaces

| Surface | State |
|---|---|
| **CSRF** | Enabled on the web chain; disabled **only** for `/api/**` (JWT, stateless). A `fetch()` POST without the token is silently swallowed — see gotcha #69 and use `AppCsrf.postJson`. |
| **Actuator** | `/actuator/health/liveness` and `/readiness` are public probes; everything else needs `GET:/actuator/**` (ADMIN). |
| **OpenAPI / Swagger** | Enabled in every environment but gated behind `GET:/v3/api-docs/**` (ADMIN). Never make it `permitAll()`. |
| **Mobile sessions** | JWTs are stateful — every token carries a `jti` backed by an `api_sessions` row, and the row must belong to the `uid` the token names. A valid signature alone does not authenticate. The token carries **no roles or permissions**: authorities are read from the database per request, so a change is in force on the next call. One device per user; a new login supersedes the others. See §8. |
| **Web sessions** | One browser per user (`maximumSessions(1)`). `/web-sessions` addresses rows by a SHA-256 digest, so raw `JSESSIONID`s never reach the page. |
| **Login timing** | A username nobody holds is still compared against a dummy hash before the refusal is returned, so the two answers cost the same. They are already identical in body and status; without this they differed by a BCrypt round, which is a username oracle — and it mattered more here than it usually does, because the throttle below locks **by username**, so a caller who can tell real usernames from invented ones can lock out a whole shift with five requests each. A deactivated account pays the same comparison, in `retrieveUser`, because Spring's `preAuthenticationChecks` refuses it before `additionalAuthenticationChecks` is reached and would otherwise have been the only fast answer left. The dummy hash is encoded through the configured `PasswordEncoder` at startup, so it carries whatever cost that encoder is set to rather than a literal that stops matching when the strength changes. A **locked** account still discloses itself, deliberately: it is told how long it has left to wait. Nothing bounds how often any of this may be asked — see [roadmap.md](roadmap.md) §2. |
| **Login throttle** | `LoginAttemptService` checks **before** password verification, including before the LDAP bind — this stops an attacker using this app to trip Active Directory's lockout against a real employee. It bounds failures per username; it does **not** bound request volume. Since `mfdcs-pwa:1.0.2` the PWA's Docker nginx does, per client address, for the four endpoints reachable without a session (sign-in, the device challenge, both enrolment calls) — nothing bounds the panel's `/login` or a client that reaches 8081 directly. See [roadmap.md](roadmap.md) §2 and §17. |
| **Client errors are 4xx** | `ApiExceptionHandler` maps binding failures to 400, wrong method 405, wrong content type 415, oversized upload 413. It used to answer **500** for all of them — the catch-all `@ExceptionHandler(Exception.class)` is consulted before Spring's own resolver — and the PWA retries 5xx while parking 4xx, so a permanently malformed request looped on every sync pass for the life of the tablet. No handler echoes the exception text: binding failures name parameters and types, and `/api/auth/login` is reachable unauthenticated. `ApiClientErrorStatusIntegrationTest` pins each status, with a counterweight that a well-formed request is not reported as malformed. **One of those statuses does not survive a live container: a wrong HTTP method answers 302, not 405** — measured on a running instance on 2026-10-17, and invisible to the suite because MockMvc does not perform the container's ERROR dispatch. Spring does produce the 405 (the response still carries `Allow: POST`), and then the error dispatch to `/error` re-enters the web chain, which guards it, and redirects to `/login`. Nothing the PWA sends is affected — it never uses a method an endpoint does not have — and the fix touches every error path, so it is recorded in [roadmap.md](roadmap.md) §18n rather than changed in passing. |
| **JSON body ceiling** | `JsonBodySizeLimitFilter` refuses a JSON request larger than `app.http.max-json-body-bytes` (64 MB — loose on purpose: the PWA pushes every pending sheet in one unchunked batch, and a 413 there would wedge a long-offline tablet into retrying the same body forever) with a 413, before anything parses it. Paths listed in `app.http.anonymous-json-paths` — `/api/auth/login` and the three device-enrolment endpoints (§3c), all `permitAll` — get `app.http.max-anonymous-json-body-bytes` (64 KB) instead, because `@RequestBody` means Jackson deserialises an untrusted body *before* authentication: a 5 MB anonymous body used to be accepted and parsed in full. Keyed on **content type**, so `multipart/form-data` is never examined and attachment limits are unchanged. **The path is matched after being reduced to the spelling Spring matches on** — each segment cut at its first `;`, empty segments dropped, each segment percent-decoded — because the raw `getRequestURI()` is not what decides anything else about a request: `POST /api/auth/login;x=1` was still `permitAll`, still reached the login handler, and still bought the 64 MB ceiling (finding 18a, fixed 2026-10-17; every reduction can only move a request to the *tighter* limit, and a malformed escape takes the tight one). The chunked-body counter now also covers `getReader()`, which `HttpServletRequestWrapper` otherwise delegates straight past it (18c). Registered by hand in `WebSecurityConfig`, not as a `@Component` — a filter that writes a response must not be auto-registered (see the note on `integrationApiKeyFilterRegistration`), which also means **MockMvc tests cannot see it**; its live behaviour is verified against a running instance. |
| **Active Directory down** | `LdapAuthenticationService.bind` never throws: it answers `AUTHENTICATED`, `REJECTED` (the directory said no — result code 49, whatever the `data` sub-code) or `UNAVAILABLE` (not configured, unreachable, timed out, TLS failed). A **HYBRID** account's local password is checked **first**, so a correct one never reaches the directory and keeps working through an outage — load-bearing, not an optimisation. When the directory does have to be asked and cannot be, the answer is `AuthenticationServiceException("Active Directory is unreachable")` — shown on the login page and returned by `/api/auth/login` as 401 with a Persian message — never `BadCredentials`. An **ACTIVE_DIRECTORY** account is not charged a failed attempt for it (no password was tested); a HYBRID account whose local password was wrong **is**, because a local hash was tested and that is what an attacker probing through the outage would be doing. `LdapOutageLoginIntegrationTest` runs all of this against the real chain with the directory pointed at a closed port. Connection shape — fail-over list, one timeout that bounds the bind, pinned truststore, hostname check — is in the README, "Active Directory (LDAP) authentication". |
| **Log files** | Not web-served. `LogSanitizer` masks any field name **containing** password/token/secret/credential, plus `apiKey`/`presentedKey`/`authorization`/`jwt`. `/api/**` request-boundary lines log types and status codes, never payloads. `key` is deliberately not a masked word — it would swallow `fieldKey` and every parameter's `key`, i.e. the readings. Note it does **not** mask `nationalCode` or `phoneNumber`. |
| **Nothing sensitive in a URL** | The PWA's nginx writes every request's full URL, **query string included**, into its access log and into its error log, where nothing masks it; headers and bodies it never writes (verified on a running container, 2026-11-20 — PWA `docker/DEPLOY.md` §7a «Nothing sensitive in a URL»). Those logs are kept for months and readable by whoever has the host, outside every rule in this file. So a token, a key, the enrolment code, or a name or personnel/national code someone typed must travel in a header or a `POST` body — **an endpoint must never take one as a path segment or a query parameter.** Today none does (tokens are `Authorization`, the enrolment code and request number are in the body). The way this is most likely to break is a server-side search of a reference list with personal data (§31): make it a `POST`, or mask the parameter in both nginx logs. |
| **Session revocation** | Deactivating or deleting a user closes their live sessions immediately — mobile (`api_sessions`, reason `USER_DEACTIVATED` / `USER_DELETED`) and browser. A **role change deliberately does not**; the users page warns instead. See §8. |
| **Bootstrap admin** | `admin` / `admin123`, created only when no ADMIN user exists. Change it before the system leaves your desk. |

---

## Related

- **[hierarchy.md](hierarchy.md)** — how unit scope is derived from the location tree
- **[log-sheets.md](log-sheets.md)** — the lifecycle these permissions gate
- **[schema.md](schema.md)** — `roles`, `permissions`, `role_permissions`, `user_roles`
- **[../AGENTS.md](../AGENTS.md)** — §2b and §5, plus the numbered traps
- **[../README.md](../README.md)** — the role descriptions in prose
- **[roadmap.md](roadmap.md) §15** — the threat model for the delivery path: a rooted tablet, a compromised nginx, why a proxy-issued rotating identifier was declined, and what CSP and file-integrity monitoring each do and do not cover. Stages 1–3 of its CSP plan are built (below); styles, Permissions-Policy and HSTS are not

---

## Browser headers on the panel

Since 2026-11-19, and **enforced** since stage 3 (roadmap §15), every response of the **panel's**
chain (`WebSecurityConfig.webSecurityFilterChain` — the Thymeleaf pages, `/login`, static files)
carries one `Content-Security-Policy`.

**Any change to a panel page is inside this header.** A control that needs script is a file under
`static/js/`, referenced with `th:src`. Confirmations and the other small behaviours are `data-*`
attributes bound by `panel-csp.js`. The browser refuses an inline handler, an executable
`<script>`, a `javascript:` URL, and any script, font, image or XHR whose origin is not this
server — and the response is still 200, so the failure looks like a dead button. `style=""` is
the one inline form still allowed. The check that runs in the build is `PanelInlineScriptTest`.
The PWA's header is a different file (`docker/nginx/snippets/security-headers.conf` in that
repo) and a panel change does not loosen it.

| Directive | Value |
|---|---|
| `default-src`, `script-src`, `connect-src`, `form-action`, `font-src` | `'self'` |
| `style-src` | `'self' 'unsafe-inline'` — templates still use `style="…"`; an injected style defaces a page, an injected script acts as the administrator, and the style tightening is the step not taken |
| `img-src` | `'self' data: blob:` |
| `media-src` | `'self' blob:` |
| `frame-ancestors`, `object-src` | `'none'` / `'none'` |
| `base-uri` | `'self'` |

`Referrer-Policy` is `same-origin`. `X-Content-Type-Options`, `X-Frame-Options: DENY` and
`Cache-Control` on pages stay Spring Security's defaults.

Inline handlers and inline `<script>` blocks are gone. Confirmations, auto-submit filters,
clipboard copy and "open this modal" are `data-*` attributes bound by `static/js/panel-csp.js`.
Page behaviour that used to be a `<script>` in the template is a file under `static/js/`. A value
Thymeleaf must print (the chart series, the user-picker list) sits in
`<script type="application/json">`, which the browser does not execute and `script-src` does not
govern. `PanelInlineScriptTest` fails the build if a template grows an handler or an executable
inline script again.

**Swagger UI** (`/swagger-ui/**`, `/v3/api-docs/**`) is the exception: its bundle is inline, so
that path alone gets `script-src 'self' 'unsafe-inline' 'unsafe-eval'`. It is not the panel's
policy, and loosening the panel to save Swagger is the wrong trade. The `/api/**` and
`/integration/**` chains return JSON and do not carry this header. The PWA's policy is its nginx,
untouched here.

---

## Two integrity rules that are enforced, not assumed

**An attachment id is a UUID or the upload is refused** (`AttachmentIds`). The id is client-minted
and becomes a filesystem path, and the path is global while only the database row is scoped to a
log sheet. Repairing a malformed id instead of rejecting it therefore let any authenticated
operator overwrite the bytes of a photograph on a sheet they had no access to: upload to their own
sheet with the victim's id plus one punctuation character, and the sanitiser collapsed the two onto
one file. The file is written before the row is inserted and with `REPLACE_EXISTING`, so the
victim's bytes were gone before the insert failed and rolled the transaction back without them.
`sha256` is recorded on every row but is not verified on read, so nothing would have noticed.

**An id already used for another file is not handed back (issue B-10).** The id reaches the
server inside `form_data` before its bytes do, and anyone who may read the round may read it — so a
colleague could upload their own file under it first. The idempotent branch returned the stored row
for a known id, after a writability check on *the stored row's* sheet: the owner was answered `403`,
which the tablet retries for ever, and the photograph never landed. The branch now compares the
stored row's `(log_sheet_id, asset_id, field_key)` with the request's. A different place is
answered **`409` with `code: ATTACHMENT_ID_TAKEN`** (`AttachmentIdTakenException`, the only error
body carrying a `code` besides device enrolment's) — but only to a caller who may add a file where
they asked, checked exactly as for a new upload (writable, or the evidence their void names);
anyone else gets the same refusal a new upload would, so the answer says nothing about the other
row. The tablet re-mints the id and rewrites its reference once (PWA issue P-06).

**One active mobile session per user is a database guarantee** (`ux_api_sessions_one_active`,
V3). It used to be only a sequence of statements inside `register()`, which two concurrent logins
could interleave — leaving an operator with two live tokens and a device a supervisor believed had
been signed out. The index states the invariant; a per-user advisory lock in `register()` is what
keeps a genuine race resolving as "last login wins" rather than as a failed login. See
[schema.md](schema.md) for why the index counts expired rows and why that shapes the supersede.


### Form final submission: effective filing-unit authorization (2026-11-24)

An existing draft must pass `FormAccessService.assertCanFill` on every final submission.
The effective unit is the request's reporter unit when present, otherwise the stored reporter
unit. Omitting the optional field cannot bypass form activation, unit links or caller scope.
A changed filing unit is applied only after full answer validation and stamping succeed;
a refused request must not dirty the managed entity's reporter/subject unit columns.
Regression coverage: `FormSubmissionServiceTest`. Database concurrency and durable replay
across reopen remain tracked separately as F-07/F-08 in `docs/issue.md`.


PWA filing-unit UI (2026-11-24): the observation-unit picker is removed. The single searchable
filing-unit picker uses cached definition links intersected with the caller's accessible
units (plant-wide skips only scope). A draft is created explicitly before displaying media
fields; draft unit corrections mirror subject to reporter. This is a client-side convenience;
`assertCanFill` remains authoritative on every draft/final write. The `subjectUnitId` wire
field remains compatible and is still ignored/mirrored by the server.


### Offline form schema identity (V34)

`GET /api/forms` and `/api/forms/{id}` now publish a server-owned `schemaVersionId` alongside
exactly the fields in that version. Draft/final requests may send it. New submissions copy
that stored version, never a schema supplied by the client. Versions are resolved by BOTH
version id and form id. An existing submission cannot switch its frozen snapshot; a supplied
version must equal it. Active-form and filing-unit gates still apply on every write.
An empty frozen snapshot remains empty (no fallback to newly added live fields).
Unknown answer keys are rejected before storage, not stripped. Persian messages explain refusal.

Compatibility: web and older mobile callers may omit the version and keep the historical
first-server-save snapshot behavior. Unknown keys are protected, but same-key semantic changes
on legacy unsynced drafts cannot be reconstructed automatically; F-02 remains partial for that
case. Deploy backend V34 before the new PWA; this feature does not make an old backend preserve
an unknown schemaVersionId. Published versions are retained to support long-offline clients.


### Form parent filtering and device recording time (2026-11-25)

Parent filtering narrows offered lookup choices; it does not replace lookup grants or filing-unit
scope and does not turn retired/unlisted answers into authorization failures. The device payload
adds only the parent code; it does not expose the parent's private attributes. Existing endpoints
and authorities are reused. Relative datetime limits on mobile form writes use the submitted
recording time so transport delay is harmless. This time is client-claimed, not an attestation;
it cannot confer unit access or replace server audit timestamps. Missing time falls back to the
server clock. Detailed contract and forged-filing-unit regression coverage: [forms.md](forms.md).

The form attachment removal route `DELETE /api/attachments/forms/{submissionId}/{id}?fieldKey=...`
reuses `DELETE:/api/attachments/{id}`. A matching upload requires the caller to be able to edit
the draft. A missing or mismatched form/field is an idempotent no-op. Draft/final request bodies
cannot assign the last saver. The server derives the actor from the authenticated write.

The mobile form changes feed derives the reporter from the authenticated principal and reuses
`GET:/api/form-submissions`. A cursor is a position within that reporter's history. It
grants no access to another reporter's data. The V36 final receipt verifies the immutable
submit actor and target before returning a duplicate acknowledgement. The current visibility
rule is checked again on replay. An old receipt cannot authorize a new final after reopen.
